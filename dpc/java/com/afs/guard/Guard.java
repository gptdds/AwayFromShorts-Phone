package com.afs.guard;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.UserManager;
import android.util.Log;

import java.util.Calendar;
import java.util.List;
import java.util.Map;

/**
 * 全部判定与落地逻辑都在这里，UI / 定时器 / 开机广播共用一套。
 *
 * 核心：Device Owner 的 setPackagesSuspended() 会把目标 App 直接冻结，
 * 图标变灰、点不开、后台被杀，而且没有「忽略限制」「延长 15 分钟」这种口子。
 *
 * 每个应用有自己的 {@link Rule}（放行星期 + 放行时段），判定一律按包名走。
 * 另有 {@link StudyGate}：今天学习目标没打完 → 直接延长冻结（不看时段，打完即解）。
 */
public final class Guard {

    /* ==================== 可调参数 ==================== */

    /** logcat 标签：adb logcat -s AFS 就能看到闹钟排点/触发 */
    public static final String TAG = "AFS";

    /** 上次打印过的闹钟时刻（守护服务每 30s 重排，避免日志刷屏） */
    private static long lastLoggedAt = -1L;

    /** 默认管哪些包 —— 用户没在「添加应用」里改过就用这个 */
    public static final String[] DEFAULT_TARGETS = new String[] {
            "com.ss.android.ugc.aweme",       // 抖音
            "com.ss.android.ugc.aweme.lite",  // 抖音极速版
            "com.ss.android.ugc.live"         // 抖音火山版
    };

    /** 加固开关。想松一点就改 false 重新编译。 */
    public static final boolean BLOCK_FACTORY_RESET = true;  // 禁止恢复出厂（真正的锁死）
    public static final boolean BLOCK_SAFE_BOOT     = true;  // 禁止安全模式
    /** 被管理应用必须随时可以卸载；冻结和限额名单按包名保留，改名单要等调整时段。 */
    public static final boolean BLOCK_APPS_CONTROL  = false;
    public static final boolean BLOCK_TIME_CHANGE   = true;  // 禁止改系统时间（否则改个钟就绕过去了）
    public static final boolean BLOCK_UNINSTALL_ALL = false; // 禁止卸载任何应用（比较狠，默认关）

    /** 只要有应用处于冻结，就把浏览器一起冻结（堵短视频网页版）。默认关。 */
    public static final boolean ALSO_SUSPEND_BROWSERS = false;

    private static final String[] BROWSERS = new String[] {
            "com.android.chrome",
            "com.coloros.browser",
            "com.heytap.browser",
            "com.android.browser",
            "com.quark.browser",
            "com.UCMobile"
    };

    /* ==================== 常量 ==================== */

    private static final int ALARM_REQ = 1001;
    private static final int JOB_ID    = 2001;
    private static final long FALLBACK_MS = 15L * 60L * 1000L;
    /** 保活闹钟间隔：进程被划掉后，最长空窗这么久。 */
    private static final long KEEPALIVE_MS = 5L * 60L * 1000L;

    /** 排一个精确唤醒（不弹闹钟图标）。排不上就退回允闲时唤醒。 */
    private static void armExact(Context ctx, AlarmManager am, int request, long at) {
        PendingIntent pending = alarms(ctx, request);
        try {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending);
        } catch (Throwable first) {
            try {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending);
            } catch (Throwable second) {
                Log.w(TAG, "aux alarm " + request + " failed: " + second.getClass().getSimpleName());
            }
        }
    }

    private Guard() {
    }

    /* ==================== 基础设施 ==================== */

    public static ComponentName admin(Context ctx) {
        return new ComponentName(ctx.getPackageName(), AfsAdmin.class.getName());
    }

    public static DevicePolicyManager dpm(Context ctx) {
        return (DevicePolicyManager) ctx.getSystemService(Context.DEVICE_POLICY_SERVICE);
    }

    public static boolean isOwner(Context ctx) {
        try {
            DevicePolicyManager d = dpm(ctx);
            return d != null && d.isDeviceOwnerApp(ctx.getPackageName());
        } catch (Throwable t) {
            return false;
        }
    }

    /* ==================== 判定（按应用） ==================== */

    /**
     * 纯判定：现在是否该冻结 = 不在自己的放行窗口里。
     * 不碰 Context —— 离线回归直接调它。
     * 学习闸门不在这里：它依赖 Context（拉数据），走 blockedNow / apply。
     */
    public static boolean blocked(Rule r, Calendar c) {
        return !r.inWindow(c);
    }

    /**
     * 多个应用里最早的下一次翻转。
     * 闹钟只能排一个时刻，排最早的；响了之后 apply() 会把所有应用重判一遍。
     */
    public static long nextFlipOf(java.util.Collection<Rule> rules, Calendar now) {
        long best = Long.MAX_VALUE;
        for (Rule r : rules) {
            long t = r.nextFlip(now);
            if (t < best) {
                best = t;
            }
        }
        return best == Long.MAX_VALUE ? now.getTimeInMillis() + 3600000L : best;
    }

    /**
     * 这个应用现在是否该冻结。
     * 两条路：① 不在自己的放行窗口里；② 它参与学习闸门、且闸门正在生效。
     */
    public static boolean blockedNow(Context ctx, String pkg) {
        return blockReasonNow(ctx, pkg) != 0;
    }

    /** 0=可使用，1=自身时段，2=学习联动，3=禁忌惩罚。与实际冻结使用同一判定。 */
    public static int blockReasonNow(Context ctx, String pkg) {
        if (!MasterSwitch.enabled(ctx)) return 0;
        Calendar c = GuardClock.now(ctx);
        Rule r = enforcedRules(ctx).get(pkg);if(r==null)return 0;
        boolean holding = StudyGate.holding(ctx, c);
        boolean taboo = StudyGate.tabooHolding(c.getTimeInMillis(), StudyGate.tabooUntil(ctx));
        boolean reward = CardStore.rewardUntil(ctx,c.getTimeInMillis())>0;
        boolean pardon = TemporaryPardon.active(ctx,pkg,c.getTimeInMillis());
        return effectiveReason(ctx,pkg,r,RuleStore.keyApps(ctx).contains(pkg),holding,taboo,c,reward,pardon);
    }

    static int blockReason(Rule rule, boolean keyApp, boolean holding, boolean taboo, Calendar now) {
        return blockReason(rule,keyApp,holding,taboo,false,now,false);
    }
    /**
     * 最终判定 = 时段/额度/禁忌这些基础规则，再叠一层钥匙闸门。
     *
     * 拆成两层是故意的：基础规则是纯函数（离线回归 207 项断言全压在上面），
     * 钥匙闸门要读使用记录，只能在带 Context 的这一层追加。reason 6 只在这里出现。
     */
    private static int effectiveReason(Context c,String pkg,Rule rule,boolean key,boolean hold,boolean taboo,Calendar now,boolean reward,boolean pardon){
        int base=effectiveBase(c,pkg,rule,key,hold,taboo,now,reward);
        // 热爱券和临时赦免都抵扣应用时段、使用额度和每日限额；学习联动、断网、罚时和钥匙门槛仍按原规则。
        base=rewardAdjustedReason(base,reward||pardon);
        if(base!=0)return base;
        return KeyGate.blocks(c,pkg,now.getTimeInMillis())?6:0;
    }
    /** Pure reward overlay rule: schedules, app quotas and daily limits are waived together. */
    static int rewardAdjustedReason(int reason,boolean reward){
        return reward&&(reason==1||reason==4||reason==5)?0:reason;
    }
    /** Temporary pardon has the same schedule/quota-overlay priority as a love reward. */
    static int pardonAdjustedReason(int reason,boolean pardon){
        return rewardAdjustedReason(reason,pardon);
    }
    private static int effectiveBase(Context c,String pkg,Rule rule,boolean key,boolean hold,boolean taboo,Calendar now,boolean reward){
        boolean quotaPaused=FreezeWeekdayPolicy.pausesQuota(c,now);
        boolean studyPaused=FreezeWeekdayPolicy.pausesStudy(c,now);
        boolean penaltyPaused=FreezeWeekdayPolicy.pausesPenalty(c,now);
        if(DailyQuotaStore.exceeded(c,pkg))return 5;
        key=key||!rule.gate;
        // 使用限额默认参与学习联动：即使应用只设了额度、没有冻结时段，
        // 今天只要有学习任务没完成，就立即进入 reason 2。关键应用仍由下面的 key 豁免。
        boolean linkedQuota=!studyPaused&&DailyQuotaStore.learningLinked(c)&&DailyQuotaStore.hasAllowance(c,pkg);
        boolean gateHold=!studyPaused&&hold&&StudyGate.learningFor(c,rule,now);
        boolean quotaHold=linkedQuota&&StudyGate.quotaLearningHold(c,now);
        hold=gateHold||quotaHold;
        taboo=!penaltyPaused&&PenaltyStore.holding(c,pkg,now.getTimeInMillis());
        boolean checkin=!penaltyPaused&&PenaltyStore.checkinHolding(c,pkg,now.getTimeInMillis());
        boolean scheduleDisabled=FreezeWeekdayPolicy.pausesSchedule(c,now);
        if(AppUsageQuota.minutes(c,pkg)==0&&!DailyQuotaStore.hasAllowance(c,pkg))return blockReason(rule,key,hold,taboo,checkin,now,reward,scheduleDisabled);
        int base=blockReason(FreezeSlotStore.withoutSchedule(),key,hold,taboo,checkin,now,false);
        if(base!=0)return base;
        return !quotaPaused&&AppUsageQuota.exceeded(c,pkg,rule,now.getTimeInMillis())?4:0;
    }

    static int blockReason(Rule rule, boolean keyApp, boolean holding, boolean taboo, Calendar now, boolean reward) {
        return blockReason(rule,keyApp,holding,taboo,false,now,reward);
    }

    static int blockReason(Rule rule, boolean keyApp, boolean holding, boolean taboo,
                           boolean checkin, Calendar now, boolean reward) {
        return blockReason(rule, keyApp, holding, taboo, checkin, now, reward, false);
    }
    /**
     * 带「打卡罚时」的判定。
     *
     * 打卡没完成的罚时对**所有被冻结的应用**生效：它排在 keyApp 豁免之前，
     * 否则非联动应用会被直接放行，罚时条目等于白记；热爱奖励也不抵扣。
     */
    static int blockReason(Rule rule, boolean keyApp, boolean holding, boolean taboo,
                           boolean checkin, Calendar now, boolean reward, boolean scheduleDisabled) {
        if (!scheduleDisabled && blocked(rule, now) && !reward) return 1;
        if (checkin) return 3;
        if (keyApp) return 0;
        if (taboo) return 3;
        return holding ? 2 : 0;
    }

    public static String blockReasonText(int reason) {
        return reason == 1 ? T.s("时段冻结") : reason == 2 ? T.s("学习联动冻结") : reason == 3 ? T.s("延长冻结") : reason==4?T.s("使用额度冻结"):reason==5?T.s("每日限额用尽"):reason==6?T.s("钥匙应用未达标"):T.s("可使用");
    }

    /** 最近一次「额度用完请出去」的对象与时刻：同一次使用只拉一次提示页。 */
    private static long lastQuotaKickAt;
    private static String lastQuotaKickPkg;
    private static long lastOrKickAt;
    private static String lastOrKickPkg;

    /**
     * 额度用尽时把用户请出正在用的那只应用。
     *
     * setPackagesSuspended 只挡「下一次启动」，已经在前台的实例照样能刷，
     * 所以额度一到点就主动把提示页拉到最前，当场结束这次使用。
     * 设备所有者不受后台启动 Activity 的限制，这条路不依赖无障碍服务。
     */
    static void kickQuotaExceeded(Context ctx) {
        try {
            if (!MasterSwitch.enabled(ctx)) return;
            android.os.PowerManager power = (android.os.PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
            if (power != null && !power.isInteractive()) return;   // 灭屏时不必抢前台
            long now = GuardClock.now(ctx).getTimeInMillis();
            String pkg = AppUsageQuota.foreground(ctx, now);
            if (pkg == null || pkg.equals(ctx.getPackageName())) return;
            int reason = blockReasonNow(ctx, pkg);
            if (reason != 4 && reason != 5) return;
            // Mutual exclusion freezes through the package policy. Its first boundary is a
            // reminder, not a foreground eviction, so it must not fall through to this page.
            try {
                if (DailyQuotaStore.orFrozenByOther(ctx, pkg, now)) return;
            } catch (RuntimeException ignored) {
            }
            if (pkg.equals(lastQuotaKickPkg) && now - lastQuotaKickAt < 8000L) return;
            lastQuotaKickPkg = pkg; lastQuotaKickAt = now;
            Phase3Telemetry.blockedAttempt(ctx, pkg, reason, "device_owner");
            showQuotaExit(ctx, pkg);
        } catch (RuntimeException error) {
            Log.w(TAG, "quota kick failed: " + error.getClass().getSimpleName());
        }
    }
    /**
     * At the group's first-reminder boundary, sample the foreground every five seconds and
     * show the once-per-day popup reminder. The current app stays in front; normal
     * mutual-exclusion freezing still starts only after more than five minutes.
     */
    static void remindOrFirstConflict(Context ctx) {
        try {
            if (!MasterSwitch.enabled(ctx)) return;
            android.os.PowerManager power = (android.os.PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
            if (power != null && !power.isInteractive()) return;
            long now = GuardClock.now(ctx).getTimeInMillis();
            String pkg = AppUsageQuota.foregroundCached(ctx, now);
            if (pkg == null || pkg.equals(ctx.getPackageName())) return;
            if (pkg.equals(lastOrKickPkg) && now - lastOrKickAt < 10000L) return;
            if (!DailyQuotaStore.claimOrReminder(ctx, pkg, now)) return;
            lastOrKickPkg = pkg; lastOrKickAt = now;
            DailyQuotaData data = DailyQuotaStore.snapshot(ctx);
            org.json.JSONObject group = data.group(pkg);
            String orGroup = group == null ? "" : group.optString("orGroup", "");
            int minutes = group == null
                    ? DailyQuotaData.DEFAULT_OR_FORCE_EXIT_MINUTES
                    : DailyQuotaData.orForceExitMinutes(group);
            AlertPopupActivity.show(
                    ctx,
                    "or-reminder:" + DayBoundary.key(ctx, now) + ":" + orGroup,
                    T.s("互斥格子首次提醒"),
                    T.f("「%s」所在的或集今天已由另一格开始使用，%d 分钟首次提醒时间已到。本次不会强制退出；超过 5 分钟后其他格子冻结。",
                            labelOf(ctx.getPackageManager(), pkg), minutes)
            );
        } catch (RuntimeException error) {
            Log.w(TAG, "or-set reminder failed: " + error.getClass().getSimpleName());
        }
    }
    private static void showQuotaExit(Context ctx, String pkg) {
        ctx.startActivity(new Intent(ctx, QuotaBlockedActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(QuotaBlockedActivity.EXTRA_PACKAGE, pkg));
    }
    static Map<String,Rule> enforcedRules(Context c){Map<String,Rule> rules=RuleStore.all(c);for(String pkg:DailyQuotaStore.packages(c))if(!rules.containsKey(pkg))rules.put(pkg,FreezeSlotStore.withoutSchedule());return rules;}

    /**
     * 带预计算全局状态的判定：一屏几十个应用只算一次学习闸门/禁忌，
     * 别每个应用都重跑一遍 {@link StudyGate#holding}（那里要读联动配置和罚时）。
     *
     * 冻结页每秒刷新全部瓦片，旧的逐应用重算实测一次 tick 470ms。
     */
    public static int blockReasonNow(Context ctx, String pkg, Calendar now, Map<String, Rule> enforced,
                                     java.util.Set<String> keys, boolean holding, boolean taboo, boolean reward) {
        if (!MasterSwitch.enabled(ctx)) return 0;
        Rule r = enforced.get(pkg);
        if (r == null) return 0;
        return effectiveReason(ctx, pkg, r, keys.contains(pkg), holding, taboo, now, reward,
                TemporaryPardon.active(ctx, pkg, now.getTimeInMillis()));
    }

    /** 这个应用现在是否在自己的放行窗口里（不看学习闸门）。 */
    public static boolean inWindowNow(Context ctx, String pkg) {
        return RuleStore.get(ctx, pkg).inWindow(GuardClock.now(ctx));
    }

    /**
     * 这个应用现在能不能改设置。
     *
     * ★ 只看它自己的窗口，不看学习闸门 —— 否则闸门一生效就永远"锁定"，
     * 连放行时段也改不了，自己把自己锁死。
     */
    public static boolean lockedNow(Context ctx, String pkg) {
        if (Config.inGrace(ctx)) {
            return false;
        }
        // 刚加进来的 10 分钟里允许改时段，否则上午加应用、当场锁死到 21 点
        if (RuleStore.isFresh(ctx, pkg)) {
            return false;
        }
        if(DailyQuotaStore.hasAllowance(ctx,pkg))return DailyQuotaStore.exceeded(ctx,pkg)||AppUsageQuota.exceeded(ctx,pkg,RuleStore.get(ctx,pkg),GuardClock.now(ctx).getTimeInMillis());
        if(AppUsageQuota.minutes(ctx,pkg)>0)return AppUsageQuota.exceeded(ctx,pkg,RuleStore.get(ctx,pkg),GuardClock.now(ctx).getTimeInMillis());
        return !inWindowNow(ctx, pkg);
    }

    /**
     * 能不能把它移出黑名单。
     *
     * 名单只进不出：应用可以随时卸载，但冻结时段和限额名单会按包名保留；
     * 只有在调整时段内（或首次安装的宽限期）才能把应用从名单里移出。
     */
    public static boolean canRemove(Context ctx, String pkg) {
        return Config.inGrace(ctx) || TimeWindow.on(ctx);
    }

    /** 有没有应用正处在放行窗口（决定强制模式能不能关）。 */
    public static boolean anyUnlocked(Context ctx) {
        if (!MasterSwitch.enabled(ctx)) return true;
        Calendar now = GuardClock.now(ctx);
        boolean scheduleDisabled=FreezeWeekdayPolicy.pausesSchedule(ctx,now);
        for (Map.Entry<String,Rule> entry : enforcedRules(ctx).entrySet()) {
            if(DailyQuotaStore.exceeded(ctx,entry.getKey()))continue;
            Rule r=entry.getValue();
            if ((DailyQuotaStore.hasAllowance(ctx,entry.getKey())||scheduleDisabled||r.inWindow(now)||AppUsageQuota.minutes(ctx,entry.getKey())>0)&&!AppUsageQuota.exceeded(ctx,entry.getKey(),r,now.getTimeInMillis())) {
                return true;
            }
        }
        return false;
    }

    /** 全部应用都处于屏蔽 → 整页切深色。 */
    public static boolean allBlocked(Context ctx) {
        return !anyUnlocked(ctx);
    }

    public static int blockedCount(Context ctx) {
        if (!MasterSwitch.enabled(ctx)) return 0;
        Calendar now = GuardClock.now(ctx);
        // 必须把学习闸门算进去，否则状态卡显示"5 个冻结"、下面结果却是"12 个冻结"
        boolean gate = StudyGate.holding(ctx, now);
        java.util.Set<String> keys = RuleStore.keyApps(ctx);
        PackageManager pm = ctx.getPackageManager();
        int n = 0;
        for (Map.Entry<String, Rule> e : enforcedRules(ctx).entrySet()) {
            // 与 apply() 的统计口径一致：没装的应用不算"冻结中"
            if (!installed(pm, e.getKey())) {
                continue;
            }
            Rule r = e.getValue();
            if (blockReasonNow(ctx,e.getKey())!=0) {
                n++;
            }
        }
        return n;
    }

    /**
     * 下一次有应用要翻转状态的时刻 —— 取所有应用里最早的那个。
     *
     * 每个应用各有各的窗口，闹钟只能排最早的一个边界；响了之后
     * apply() 会把所有应用重新判一遍，并重新排下一次。
     *
     * 学习联动还需要在 20:00 检查，并在次日零点清除前一天的延长冻结。
     */
    public static int suspendedCount(Context context) {
        if (!MasterSwitch.enabled(context)) return 0;
        if (!isOwner(context)) throw new IllegalStateException("设备管理未启用");
        DevicePolicyManager policy = dpm(context);
        int count = 0;
        for (String pkg : enforcedRules(context).keySet()) {
            if (!installed(context.getPackageManager(), pkg)) continue;
            try {
                if (policy.isPackageSuspended(admin(context), pkg)
                        || policy.isApplicationHidden(admin(context), pkg)) count++;
            } catch (PackageManager.NameNotFoundException ignored) {}
        }
        return count;
    }

    private static String transitionKey;
    private static long transitionAt, transitionCalculated;

    public static synchronized long nextScheduledChange(Context context) {
        if (!MasterSwitch.enabled(context)) return 0L;
        Calendar now = GuardClock.now(context);
        int weekdayDisabled = FreezeWeekdayPolicy.disabledDays(context);
        String key = RuleStore.exportRules(context) + "|" + FreezeWeekdayPolicy.revision(context)
                + "|" + now.getTimeZone().getID();
        long current = now.getTimeInMillis();
        if (key.equals(transitionKey) && current >= transitionCalculated && (transitionAt == 0L || current < transitionAt))
            return transitionAt;
        long next = 0L;
        for (Rule rule : RuleStore.all(context).values()) {
            com.afs.core.rules.WeeklyRule schedule = rule.freeze
                    ? com.afs.core.rules.WeeklyRule.freeze(rule.days, rule.windows())
                    : com.afs.core.rules.WeeklyRule.allow(rule.days, rule.windows());
            long candidate = schedule.nextTransition(now.getTimeInMillis(), now.getTimeZone());
            if (candidate > now.getTimeInMillis() && (next == 0L || candidate < next)) next = candidate;
        }
        if(weekdayDisabled!=0){
            long midnight=FreezeWeekdayPolicy.nextMidnight(now);
            if(next==0L||midnight<next)next=midnight;
        }
        transitionKey = key; transitionAt = next; transitionCalculated = current;
        return next;
    }

    public static long nextFlipMillis(Context ctx) {
        if (!MasterSwitch.enabled(ctx)) return 0L;
        Calendar now = GuardClock.now(ctx);
        long next=Math.min(Math.min(nextFlipOf(RuleStore.all(ctx).values(), now),StudyGate.nextCheckBoundaryMillis(now)),PenaltyStore.boundary(ctx,now.getTimeInMillis()));
        if(FreezeWeekdayPolicy.disabledDays(ctx)!=0)next=Math.min(next,FreezeWeekdayPolicy.nextMidnight(now));
        if(ScreenOnLimit.enabled(ctx))next=Math.min(next,DayBoundary.nextResetMs(now.getTimeInMillis(),DayBoundary.hour(ctx),DayBoundary.minute(ctx)));
        long reward=CardStore.rewardUntil(ctx,now.getTimeInMillis());
        long pardon=TemporaryPardon.nextBoundary(ctx,now.getTimeInMillis());
        if(reward>0)next=Math.min(next,reward);
        if(pardon<Long.MAX_VALUE)next=Math.min(next,pardon);
        return next;
    }

    /* ==================== 落地 ==================== */

    /**
     * 后台执行 {@link #apply}：默认给「守护节拍、闹钟、开机」这些系统回调用的。
     *
     * 为什么要异步：apply 里要查使用记录（`UsageStatsManager.queryEvents`），实测单次
     * 186ms～538ms，全在主线程上就是每 30 秒卡一下 —— 用户在任何页面滑动都可能撞上。
     * 这里用单线程串行 + 合并请求：正在跑时再来请求只记一个 pending，跑完补一次。
     */
    private static final java.util.concurrent.ExecutorService POLICY =
            java.util.concurrent.Executors.newSingleThreadExecutor(runnable -> new Thread(runnable, "afs-guard-policy"));
    private static final Object REQUEST_LOCK=new Object(), APPLY_LOCK=new Object();
    private static boolean applying;
    private static boolean pending;

    public static void applyAsync(final Context context) {
        final Context app;
        try {
            app = context.getApplicationContext() == null ? context : context.getApplicationContext();
        } catch (RuntimeException error) {
            return;
        }
        synchronized(REQUEST_LOCK){
            pending=true;if(applying)return;applying=true;
        }
        POLICY.execute(()->{
            for(;;){
                synchronized(REQUEST_LOCK){if(!pending){applying=false;return;}pending=false;}
                try{apply(app);}catch(Throwable error){app.getSharedPreferences("afs_status",Context.MODE_PRIVATE).edit().putString("last_error",error.getClass().getSimpleName()).apply();Log.e(TAG,"async apply failed",error);}
            }
        });
    }

    /**
     * 按当前时间把每个目标 App 单独冻结/解冻，并补齐各项限制。
     * 幂等：反复调用没有副作用。
     */
    public static String apply(Context ctx) {
        synchronized(APPLY_LOCK){return applySerial(ctx);}
    }
    private static String applySerial(Context ctx) {
        // 被管理应用可以随时卸载。冻结规则和限额名单按包名保留，只有调整时段里
        // 才允许从名单移除；不要按“未安装”自动清格子，否则重装后设置会丢失。
        try{DailyQuotaStore.refresh(ctx);}catch(IllegalStateException error){Log.e(TAG,"daily quota state unavailable",error);}
        DailyCardCompletion.capture(ctx);
        DevicePolicyManager d = dpm(ctx);
        if (d == null) {
            ctx.getSharedPreferences("afs_status",Context.MODE_PRIVATE).edit().putBoolean("last_apply_ok",false).putString("last_error","设备管理服务不可用").apply();
            return T.s("拿不到 DevicePolicyManager");
        }
        if (!isOwner(ctx)) {
            ctx.getSharedPreferences("afs_status",Context.MODE_PRIVATE).edit().putBoolean("last_apply_ok",false).putString("last_error","未取得设备所有者权限").apply();
            return T.s("尚未成为设备所有者，什么都没做。\n请先按 README 用 adb 执行 set-device-owner。");
        }

        boolean masterOn = MasterSwitch.enabled(ctx);
        // 顺手触发学习闸门的数据刷新（内部节流 10 分钟 + 后台线程，绝不阻塞）
        if (masterOn) StudyGate.refreshIfNeeded(ctx);

        ComponentName a = admin(ctx);
        applyRestrictions(ctx, d, a, masterOn);

        java.util.Set<String> scheduled=RuleStore.packages(ctx);
        Map<String, Rule> rules = masterOn
                ? enforcedRules(ctx)
                : java.util.Collections.<String,Rule>emptyMap();
        Calendar now = GuardClock.now(ctx);
        // 学习闸门：整轮判一次。true = 今天目标没打完且此刻在延长区间里
        boolean gateHold = masterOn && StudyGate.holding(ctx, now);
        java.util.Set<String> keys = RuleStore.keyApps(ctx);
        boolean taboo=masterOn && StudyGate.tabooHolding(now.getTimeInMillis(),StudyGate.tabooUntil(ctx));
        PackageManager pm=ctx.getPackageManager();
        // 热爱券：手动用掉一张后开一段「时段与使用限额豁免」窗口，窗口内 reward=true。
        // 学习联动/罚时仍由 effectiveReason 拦。
        boolean reward=false;
        try{reward=CardStore.rewardUntil(ctx,now.getTimeInMillis())>0;}
        catch(RuntimeException error){Log.w(TAG,"reward accounting: "+error.getClass().getSimpleName());}
        boolean pardonActive=false;
        boolean anyBlocked = false;
        for (Map.Entry<String, Rule> entry : rules.entrySet()) {
            boolean pardon=TemporaryPardon.active(ctx,entry.getKey(),now.getTimeInMillis());
            pardonActive=pardonActive||pardon;
            if (effectiveReason(ctx,entry.getKey(),entry.getValue(),keys.contains(entry.getKey()),gateHold,taboo,now,reward,pardon)!=0) {
                anyBlocked = true;
                break;
            }
        }

        // 学习闸门开启且今日未完成时，系统级灰度覆盖桌面和所有应用。
        // 打卡完成即恢复彩色。需要 WRITE_SECURE_SETTINGS 权限。
        int st = masterOn ? StudyGate.cardState(ctx, now) : StudyGate.ST_NONE;
        // 亮屏上限到点后同样整机灰度，和「今天没学完」共用同一条系统开关；
        // 每日亮屏上限启用时，GrayMode 独立按当日额度决定色度；学习冻结保持原判定。
        boolean screenLimit = false;
        try { screenLimit = ScreenOnLimit.exceeded(ctx); }
        catch (RuntimeException error) { Log.w(TAG, "screen limit: " + error.getClass().getSimpleName()); }
        GrayMode.apply(ctx, screenLimit || (masterOn && !MainActivity.isHomeVisible()
                && (st == StudyGate.ST_ACTIVE || st == StudyGate.ST_PENDING)));

        StringBuilder sb = new StringBuilder();
        int frozen = 0;
        String failure = "";

        // 关闭总开关时不能只释放「本轮规则账本」里的包：旧版本留下的 suspended
        // 集合会跨升级保留。Gemini 的入口会转交 Google 搜索，后者只要还在集合里，
        // 系统就会弹出「已被工作政策禁用」。这里直接按设备策略反查并全量释放。
        if (!masterOn) releaseAllManaged(ctx, d, a);

        // 被移出列表的必须先解冻，否则它会永远停在被冻结的状态
        for (String p : RuleStore.applied(ctx)) {
            if (!rules.containsKey(p)) {
                try {
                    String[] failed = setSuspended(d, a, new String[] { p }, false);
                    hide(d, a, p, false);   // 用隐藏顶过冻结的，移出列表时必须恢复显示
                    setUninstall(d, a, p, false);
                    if (failed != null && failed.length > 0 || d.isApplicationHidden(a,p)) failure = p + " · 移出后未完整解冻";
                } catch (Throwable error) {
                    failure = p + " · 移出解冻失败 · " + error.getClass().getSimpleName();
                }
            }
        }

        for (Map.Entry<String, Rule> e : rules.entrySet()) {
            String pkg = e.getKey();
            Rule r = e.getValue();
            if (!installed(pm, pkg)) {
                sb.append("- ").append(pkg).append(T.s("：未安装\n"));
                continue;
            }
            // 只有关键应用不受学习闸门牵连。
            boolean gateBlock = gateHold && !keys.contains(pkg);
            boolean pardon=TemporaryPardon.active(ctx,pkg,now.getTimeInMillis());
            pardonActive=pardonActive||pardon;
            boolean block = effectiveReason(ctx,pkg,r,keys.contains(pkg),gateHold,taboo,now,reward,pardon)!=0;
            // 清掉旧版本可能留下的禁止卸载策略；冻结不等于禁止用户卸载。
            try { setUninstall(d, a, pkg, false); } catch (Throwable ignored) { }
            String state;
            boolean beforeBlocked = false;
            boolean afterBlocked = false;
            String enforcementMethod = "device_owner";
            // ★ setPackagesSuspended 不抛异常不代表成功：系统会把拒绝的包名放进返回数组。
            //   原来完全不看这个返回值，拒绝时界面照样显示「已冻结」—— 用户只看到「锁不住」，
            //   却以为已经冻上了（明明应用还能点开）。现在把拒绝明写出来。
            boolean refused = false;
            try {
                boolean suspended = d.isPackageSuspended(a,pkg), hidden = d.isApplicationHidden(a,pkg);
                beforeBlocked = suspended || hidden;
                String[] failed = suspended == block || block && hidden ? new String[0]
                        : setSuspended(d, a, new String[] { pkg }, block);
                boolean suspendRefused = failed != null && failed.length > 0;
                if (suspendRefused) {
                    // ★ 有些 ROM 不让挂起系统应用 —— OPPO 真机上 Google Play 商店就是：
                    //   额度已经超额，setPackagesSuspended 却没有冻上（返回在失败名单里），
                    //   用户看到的就是「锁不住」。退一步用隐藏：图标从桌面消失、点不开，
                    //   对「额度用完」这个场景和冻结是一回事；放行时恢复显示。
                    if (hide(d, a, pkg, block)) {
                        state = block ? T.s("已冻结（隐藏）") : T.s("放行中");
                        refused = false;
                    } else {
                        state = block ? T.s("冻结被系统拒绝") : T.s("解冻被系统拒绝");
                        refused = true;
                    }
                } else {
                    // 曾经用隐藏顶过冻结的，放行时要恢复显示（幂等，没隐藏过也不会报错）。
                    if (!block && hidden) hide(d, a, pkg, false);
                    state = block
                            ? (gateBlock && !blocked(r, now) ? T.s("冻结（学习闸门）") : T.s("已冻结"))
                            : T.s("放行中");
                }
                boolean actual = d.isPackageSuspended(a,pkg) || d.isApplicationHidden(a,pkg);
                afterBlocked = actual;
                enforcementMethod = d.isApplicationHidden(a,pkg) ? "hidden" : "device_owner";
                if (actual != block) { refused = true; state = block ? T.s("冻结被系统拒绝") : T.s("解冻被系统拒绝"); }
            } catch (Throwable t) {
                refused = true;
                state = T.s("冻结失败：") + t.getClass().getSimpleName();
            }
            if (beforeBlocked != afterBlocked) {
                Phase3Telemetry.enforcementTransition(ctx, pkg, beforeBlocked, afterBlocked,
                        enforcementMethod, refused ? "apply_refused" : "apply");
            }
            if (afterBlocked != block) {
                Phase3Telemetry.enforcementFailure(ctx, pkg, block, enforcementMethod, state);
            }
            if (block && !refused) {
                frozen++;
            }
            if (refused) failure = pkg + " · " + state;
            // 应用名给人看，包名方便核对是哪个版本，后面带它自己的放行时段
            sb.append("- ").append(labelOf(pm, pkg)).append(T.s("（")).append(pkg).append(T.s("）："))
                    .append(state).append(" · ").append(r.freezeSummary())
                    .append(keys.contains(pkg) ? T.s(" · 关键应用") : scheduled.contains(pkg)?T.s(" · 参与学习闸门"):T.s(" · 每日限额 · 参与学习闸门")).append('\n');
        }
        RuleStore.setApplied(ctx, rules.keySet());

        if (ALSO_SUSPEND_BROWSERS) {
            for (int i = 0; i < BROWSERS.length; i++) {
                if (!installed(pm, BROWSERS[i])) {
                    continue;
                }
                try {
                    setSuspended(d, a, new String[] { BROWSERS[i] }, anyBlocked);
                    if (anyBlocked) {
                        sb.append("- ").append(BROWSERS[i]).append(T.s("：已冻结（堵网页版）\n"));
                    }
                } catch (Throwable ignored) {
                }
            }
        }

        // ★ 每次 apply 都重排一次定时。package 覆盖安装会清掉所有 alarm/job，
        // 而 schedule() 原本只在开机/闹钟/Job 回调里调用 —— 装完就再也排不上了。
        if (masterOn) schedule(ctx);
        else cancelSchedules(ctx);

        String head = (masterOn
                ? T.s("结果：") + rules.size() + T.s(" 个应用，") + frozen + T.s(" 个冻结")
                : T.s("总开关已关闭 · 已解除冻结和临时限制"))
                + (gateHold ? T.s("（学习闸门生效中）") : "")
                + (reward?T.s(" · 热爱奖励减免中"):"")
                + (pardonActive?T.s(" · 临时赦免中"):"") + "\n";
        String result = head + sb.toString();
        android.content.SharedPreferences.Editor status = ctx.getSharedPreferences("afs_status", Context.MODE_PRIVATE).edit()
                .putString("last_result", result)
                .putLong("last_at", System.currentTimeMillis());
        status.putBoolean("last_apply_ok",failure.isEmpty());
        if (failure.isEmpty()) status.putLong("last_success_at",System.currentTimeMillis());
        else status.putString("last_error",failure).putLong("last_error_at",System.currentTimeMillis());
        status.apply();
        return result;
    }

    static final Object POLICY_LOCK = new Object();
    static String[] setSuspended(DevicePolicyManager d, ComponentName a, String[] packages, boolean value) {
        synchronized (POLICY_LOCK) { return d.setPackagesSuspended(a,packages,value); }
    }

    /**
     * 释放所有仍由 AFS 挂起或隐藏的包。
     *
     * 只遍历 RuleStore.applied 不够：没有 applied 账本的旧版本会把 suspended
     * 集合一直留在设备策略里，升级后新代码也找不到它们。公开 SDK 没有
     * getSuspendedPackages()，只能逐个包向本管理员查询，才能清掉历史残留。
     */
    static void releaseAllManaged(Context ctx, DevicePolicyManager d, ComponentName a) {
        java.util.LinkedHashSet<String> packages = new java.util.LinkedHashSet<String>();
        packages.addAll(RuleStore.applied(ctx));
        packages.addAll(RuleStore.packages(ctx));
        try {
            packages.addAll(DailyQuotaStore.packages(ctx));
        } catch (RuntimeException ignored) {
        }
        try {
            java.util.List<PackageInfo> installed = ctx.getPackageManager().getInstalledPackages(0);
            for (PackageInfo info : installed) {
                if (info == null || packages.contains(info.packageName)) continue;
                try {
                    if (d.isPackageSuspended(a, info.packageName)) packages.add(info.packageName);
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable error) {
            Log.w(TAG, "scan suspended packages failed: " + error.getClass().getSimpleName());
        }
        for (String pkg : packages) {
            try {
                setSuspended(d, a, new String[] { pkg }, false);
                hide(d, a, pkg, false);
            } catch (Throwable error) {
                Log.w(TAG, "release " + pkg + " failed: " + error.getClass().getSimpleName());
            }
        }
    }

    /**
     * 挂起被 ROM 拒绝时的退路：把应用隐藏起来。
     *
     * 隐藏后图标从桌面消失、点不开，效果和冻结一样；数据保留，放行时恢复显示即可。
     * 返回 false 表示这条路也被拒了（那时候才真的没办法，结果页会明说）。
     */
    static boolean hide(DevicePolicyManager d, ComponentName a, String pkg, boolean hidden) {
        try {
            synchronized (POLICY_LOCK) { return d.setApplicationHidden(a, pkg, hidden); }
        } catch (Throwable error) {
            Log.w(TAG, "hide " + pkg + " failed: " + error.getClass().getSimpleName());
            return false;
        }
    }
    /**
     * 这个应用现在是不是靠「隐藏」顶替冻结（ROM 不让挂起系统应用时的退路）。
     *
     * 冻结页要能把这类应用和真正 suspended 的区分开：Google Play 这类系统应用在 OPPO 上
     * 挂不起，只能隐藏——图标不见了、点不开，但 dumpsys 里 suspended=false、FLAG_SUSPENDED=0，
     * 只看那个标志会把它误判成「可使用」。这里直接问设备管理员策略。
     */
    static boolean hiddenNow(Context ctx, String pkg) {
        try {
            DevicePolicyManager d = dpm(ctx);
            if (d == null || !isOwner(ctx)) return false;
            return d.isApplicationHidden(admin(ctx), pkg);
        } catch (Throwable error) {
            return false;
        }
    }
    private static void setUninstall(DevicePolicyManager d, ComponentName a, String pkg, boolean value) {
        synchronized (POLICY_LOCK) { d.setUninstallBlocked(a,pkg,value); }
    }
    private static void applyRestrictions(Context ctx, DevicePolicyManager d, ComponentName a,
                                          boolean masterOn) {
        synchronized (POLICY_LOCK) { applyRestrictionsUnlocked(ctx,d,a,masterOn); }
    }
    private static void applyRestrictionsUnlocked(Context ctx, DevicePolicyManager d,
                                                  ComponentName a, boolean masterOn) {
        if (!masterOn) {
            clear(d, a, UserManager.DISALLOW_FACTORY_RESET);
            clear(d, a, UserManager.DISALLOW_SAFE_BOOT);
            clear(d, a, UserManager.DISALLOW_APPS_CONTROL);
            if (Build.VERSION.SDK_INT >= 28) {
                clear(d, a, UserManager.DISALLOW_CONFIG_DATE_TIME);
            } else {
                try { d.setAutoTimeRequired(a, false); }
                catch (Throwable ignored) { }
            }
            clear(d, a, UserManager.DISALLOW_UNINSTALL_APPS);
            try { d.setUninstallBlocked(a, ctx.getPackageName(), false); }
            catch (Throwable ignored) { }
            return;
        }
        add(d, a, BLOCK_FACTORY_RESET ? UserManager.DISALLOW_FACTORY_RESET : null);
        add(d, a, BLOCK_SAFE_BOOT     ? UserManager.DISALLOW_SAFE_BOOT     : null);
        if (BLOCK_APPS_CONTROL) {
            add(d, a, UserManager.DISALLOW_APPS_CONTROL);
        } else {
            // 旧版升级后要主动撤销这个全局限制，否则以前装过的手机仍不能卸载被管理应用。
            try { d.clearUserRestriction(a, UserManager.DISALLOW_APPS_CONTROL); }
            catch (Throwable ignored) { }
        }
        if (BLOCK_TIME_CHANGE) {
            if (Build.VERSION.SDK_INT >= 28) {
                add(d, a, UserManager.DISALLOW_CONFIG_DATE_TIME);
            } else {
                // DISALLOW_CONFIG_DATE_TIME was added in API 28. On Android 7/8
                // require network time and pin the schedule timezone in GuardClock.
                try { d.setAutoTimeRequired(a, true); }
                catch (RuntimeException e) { Log.e(TAG, "Cannot enforce automatic time", e); }
            }
        }
        add(d, a, BLOCK_UNINSTALL_ALL ? UserManager.DISALLOW_UNINSTALL_APPS : null);
        try {
            d.setUninstallBlocked(a, ctx.getPackageName(), true);
        } catch (Throwable ignored) {
        }
        // 设备所有者可以直接给自己发精确闹钟权限，边界时刻才准
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                d.setPermissionGrantState(a, ctx.getPackageName(),
                        "android.permission.SCHEDULE_EXACT_ALARM",
                        DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED);
            } catch (Throwable ignored) {
            }
        }
        // ★ Android 13+ 的通知是运行时权限，没授就 importance=NONE —— 常驻通知
        //   （也就是"今天目标没完成"的唯一提醒渠道）根本不会显示。
        //   DO 可以代授，用户不用去设置里点。
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                d.setPermissionGrantState(a, ctx.getPackageName(),
                        "android.permission.POST_NOTIFICATIONS",
                        DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED);
            } catch (Throwable ignored) {
            }
        }
    }

    private static void clear(DevicePolicyManager d, ComponentName a, String restriction) {
        try { d.clearUserRestriction(a, restriction); }
        catch (Throwable ignored) { }
    }

    private static void add(DevicePolicyManager d, ComponentName a, String restriction) {
        if (restriction == null) {
            return;
        }
        try {
            d.addUserRestriction(a, restriction);
        } catch (Throwable ignored) {
        }
    }

    /** 应用名；取不到就退回包名。 */
    static String labelOf(PackageManager pm, String pkg) {
        try {
            return String.valueOf(pm.getApplicationInfo(pkg, 0).loadLabel(pm));
        } catch (Throwable hidden) {
            // 被隐藏兜底冻上的应用在普通查询里查不到，结果页会只剩包名。
            // 再带 MATCH_UNINSTALLED_PACKAGES 查一次，名字照样拿得到。
            try {
                return String.valueOf(pm.getApplicationInfo(pkg, PackageManager.MATCH_UNINSTALLED_PACKAGES).loadLabel(pm));
            } catch (Throwable t) {
                return pkg;
            }
        }
    }

    /**
     * 规则的展示文案。
     *
     * ★ 必须写明"放行"两个字 —— 光写 "每天 22:00-23:00"，谁都会读成
     *   "每天 22:00-23:00 冻结"，而实际语义正好相反：这段是唯一能用的时间，
     *   其余 23 小时全冻结。这是最容易误读的地方。
     */
    static String allowText(Rule r) {
        return r.freezeSummary() + (r.gate ? "" : T.s(" · 不受学习闸门影响"));
    }

    /**
     * 应用是否还装着。
     *
     * ★ 不能只看 getPackageInfo(pkg, 0)：被 setApplicationHidden 隐藏的应用在普通查询里
     *   和「已卸载」一模一样查不到，但它明明还在。用 MATCH_UNINSTALLED_PACKAGES 再核对
     *   FLAG_INSTALLED，隐藏和卸载就分得开了。
     *   （踩过：隐藏兜底一上线，被隐藏的应用被额度清理当成已卸载删掉，配置直接丢。）
     */
    static boolean installed(PackageManager pm, String pkg) {
        try {
            PackageInfo info = pm.getPackageInfo(pkg, PackageManager.MATCH_UNINSTALLED_PACKAGES);
            return info != null && info.applicationInfo != null
                    && (info.applicationInfo.flags & ApplicationInfo.FLAG_INSTALLED) != 0;
        } catch (Throwable t) {
            return false;
        }
    }

    /* ==================== 定时 ==================== */

    /**
     * 保证常驻守护在跑。
     *
     * ColorOS 的待机分组会把 AlarmManager 的闹钟整体往后推（实测被推 2 天 23 小时），
     * 光靠闹钟"到点自动生效"在这台机器上不成立 —— 前台服务是唯一稳定的节拍源。
     */
    public static void ensureService(Context ctx) {
        if (!MasterSwitch.enabled(ctx)) return;
        try {
            Intent i = new Intent(ctx, GuardService.class);
            if (Build.VERSION.SDK_INT >= 26) {
                ctx.startForegroundService(i);
            } else {
                ctx.startService(i);
            }
            Log.i(TAG, "ensureService ok");
        } catch (Throwable t) {
            Log.e(TAG, "ensureService failed: " + t);
        }
    }

    /**
     * 排闹钟 + 15 分钟 JobScheduler 兜底。
     *
     * ★ 双路排点，两个 PendingIntent 都指向 AlarmReceiver（apply 是幂等的）：
     *   1) setAlarmClock —— 系统闹钟级，ColorOS 的 OplusAlarmAdjustment 一般不压
     *   2) setExactAndAllowWhileIdle —— Doze 里也允许唤醒（每分钟最多一次）
     * 只排一路不够：实测闹钟被子系统的待机策略整体推迟（adjustment 加了 2 天 23 小时），
     * 前台服务的 30 秒 tick 在休眠时也不跑（Doze 冻结主线程定时器）。两路 + Job 一起上。
     */
    public static void schedule(Context ctx) {
        if (!MasterSwitch.enabled(ctx)) {
            cancelSchedules(ctx);
            return;
        }
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am != null) {
            long at = nextFlipMillis(ctx);
            PendingIntent piClock = alarms(ctx, ALARM_REQ);
            PendingIntent piIdle = alarms(ctx, ALARM_REQ + 1);
            // Android 12+ 精确闹钟受 appop 控制：没授权时 setAlarmClock 直接抛 SecurityException，
            // 以前这里 catch 掉就静默失败 —— 结果闹钟永远不存在，只有手动打开 App 才冻得上。
            boolean exact = true;
            if (Build.VERSION.SDK_INT >= 31) {
                try {
                    exact = am.canScheduleExactAlarms();
                } catch (Throwable ignored) {
                }
            }
            boolean okClock = false;
            boolean okIdle = false;
            try {
                if (exact) {
                    am.setAlarmClock(new AlarmManager.AlarmClockInfo(at, piClock), piClock);
                    okClock = true;
                } else {
                    am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, piClock);
                    okClock = true;
                }
            } catch (Throwable t) {
                Log.e(TAG, "setAlarmClock failed: " + t);
            }
            try {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, piIdle);
                okIdle = true;
            } catch (Throwable t) {
                Log.e(TAG, "setExactAndAllowWhileIdle failed: " + t);
            }
            if (!okClock && !okIdle) {
                try {
                    am.set(AlarmManager.RTC_WAKEUP, at, piClock);
                    okClock = true;
                } catch (Throwable ignored) {
                }
            }
            ctx.getSharedPreferences("afs_health",Context.MODE_PRIVATE).edit()
                    .putLong("alarm_request_at",okClock || okIdle ? System.currentTimeMillis() : 0L)
                    .putLong("alarm_due",at).putString("alarm_error",okClock || okIdle ? "" : "系统拒绝定时请求").apply();
            if (at != lastLoggedAt) {   // 守护服务每 30s 会重排一次，别刷屏
                lastLoggedAt = at;
                Log.i(TAG, "alarm armed at " + at + " (clock=" + okClock + " idle=" + okIdle + ")");
            }

            // 保活闹钟：从「最近任务」把 App 划掉会连进程一起被杀（ColorOS 实测），
            // 无障碍拦截和定时提醒都住在进程里 —— 没人把进程叫起来，它们就一直不工作。
            // 这个闹钟每 5 分钟兜一次底：闹钟由系统投递，不依赖进程活着。
            armExact(ctx, am, ALARM_REQ + 2, System.currentTimeMillis() + KEEPALIVE_MS);
            // 定时提醒（休息提醒）到点：让系统在那一刻把进程叫起来，别等下一个节拍。
            long reminderAt = RestAlert.nextDueAt(ctx);
            if (reminderAt > 0L) armExact(ctx, am, ALARM_REQ + 3, reminderAt);
        }

        JobScheduler js = (JobScheduler) ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (js == null) {
            return;
        }
        try {
            List<JobInfo> pending = js.getAllPendingJobs();
            for (int i = 0; i < pending.size(); i++) {
                if (pending.get(i).getId() == JOB_ID) {
                    return;
                }
            }
            JobInfo ji = new JobInfo.Builder(JOB_ID, new ComponentName(ctx, GuardJob.class))
                    .setPeriodic(FALLBACK_MS)
                    .setPersisted(true)
                    .build();
            js.schedule(ji);
        } catch (Throwable ignored) {
        }
    }

    private static PendingIntent alarms(Context ctx, int req) {
        Intent i = new Intent(ctx, AlarmReceiver.class);
        return PendingIntent.getBroadcast(
                ctx, req, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** 总开关关闭后撤销守护闹钟与兜底 Job；规则和名单保持不动。 */
    public static void cancelSchedules(Context ctx) {
        try {
            AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
            if (am != null) {
                for (int req = ALARM_REQ; req <= ALARM_REQ + 3; req++) {
                    am.cancel(alarms(ctx, req));
                }
            }
        } catch (Throwable error) {
            Log.w(TAG, "cancel alarms failed: " + error.getClass().getSimpleName());
        }
        try {
            JobScheduler js = (JobScheduler) ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (js != null) js.cancel(JOB_ID);
        } catch (Throwable error) {
            Log.w(TAG, "cancel keepalive job failed: " + error.getClass().getSimpleName());
        }
    }

    /* ==================== 展示 ==================== */

    public static String describe(Context ctx) {
        Calendar now = GuardClock.now(ctx);
        int total = RuleStore.count(ctx);
        int frozen = blockedCount(ctx);

        StringBuilder sb = new StringBuilder();
        sb.append(T.s("设备所有者　")).append(isOwner(ctx) ? T.s("是（已锁死）") : T.s("否 —— 冻结不会生效")).append('\n');
        sb.append(T.s("总开关　　　")).append(MasterSwitch.enabled(ctx) ? T.s("已开启") : T.s("已关闭（规则与名单已保留）")).append('\n');
        sb.append(T.s("当前时间　")).append(stamp(now.getTimeInMillis())).append('\n');
        sb.append(T.s("应用　　　")).append(total).append(T.s(" 个，")).append(frozen).append(T.s(" 个冻结中")).append('\n');
        // 关键应用名单：用户自己列的，学习目标没完成时只按自己的时段走。
        // 不显示这一行的话，用户没法确认"我列的那几个到底生效了没有"。
        int keys = RuleStore.keyApps(ctx).size();
        sb.append(T.s("关键应用　")).append(keys).append(T.s(" 个（学习目标未完成也不延长冻结）")).append('\n');
        // 学习联动不在这里 —— 它现在并进首页的「打卡」按钮了（styleCheckin：
        // 后缀带「冻结中 / ⚠ 联动过期 / 等记账」），埋在这个同色同字号的
        // 文本块里没人看得见。
        sb.append(T.s("下次切换　")).append(total == 0 ? T.s("—（没添加应用）") : stamp(nextFlipMillis(ctx))).append('\n');
        sb.append('\n');
        sb.append(T.s("加固项　")).append(restrictionSummary()).append('\n');
        return sb.toString();
    }

    private static String restrictionSummary() {
        java.util.List<String> items = new java.util.ArrayList<String>();
        if (BLOCK_FACTORY_RESET) items.add(T.s("禁恢复出厂"));
        if (BLOCK_SAFE_BOOT)     items.add(T.s("禁安全模式"));
        if (BLOCK_APPS_CONTROL)  items.add(T.s("禁强停/清数据"));
        if (BLOCK_TIME_CHANGE)   items.add(T.s("禁改时间"));
        if (BLOCK_UNINSTALL_ALL) items.add(T.s("禁卸载"));
        if (items.isEmpty()) {
            return T.s("无");
        }
        // 每两项一行。(char)10 = 换行, (char)0x3000 = 全角空格
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) {
                sb.append(i % 2 == 0 ? (char) 10 : (char) 0x3000);
            }
            sb.append(items.get(i));
        }
        return sb.toString();
    }

    public static String stamp(long millis) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(millis);
        int[] wd = { 0, 7, 1, 2, 3, 4, 5, 6 };
        String[] names = { "", T.s("周一"), T.s("周二"), T.s("周三"), T.s("周四"), T.s("周五"), T.s("周六"), T.s("周日") };
        StringBuilder sb = new StringBuilder();
        sb.append(c.get(Calendar.MONTH) + 1).append('/')
          .append(c.get(Calendar.DAY_OF_MONTH)).append(' ')
          .append(pad2(c.get(Calendar.HOUR_OF_DAY))).append(':')
          .append(pad2(c.get(Calendar.MINUTE))).append(':')
          .append(pad2(c.get(Calendar.SECOND))).append('（')
          .append(names[wd[c.get(Calendar.DAY_OF_WEEK)]]).append('）');
        return sb.toString();
    }

    private static String pad2(int v) {
        return v < 10 ? "0" + v : String.valueOf(v);
    }
}
