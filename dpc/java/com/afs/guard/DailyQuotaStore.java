package com.afs.guard;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.*;
import org.json.*;

/** The same edit window is enforced at every write, including an already-open dialog. */
final class DailyQuotaStore {
    static final String SP="afs_daily_quota";
    private static long refreshed;
    private static String signature="";
    private static volatile boolean writeFailed;
    private static SharedPreferences prefs(Context c){return c.getSharedPreferences(SP,0);}
    private static DailyQuotaData load(Context c,long now){SharedPreferences p=prefs(c);return new DailyQuotaData(p.getString("config","{}"),p.getString("usage",p.contains("config")?"{missing":"{}"),now,DayBoundary.hour(c),DayBoundary.minute(c));}
    /** 只读快照的记忆：见 {@link #snapshot(Context)}。 */
    private static final Object SNAPSHOT_LOCK=new Object();
    private static String cachedConfigRaw,cachedUsageRaw;
    private static DailyQuotaData cachedSnapshot;
    private static int cachedResetHour=-1;
    private static int cachedResetMinute=-1;
    /**
     * 只读快照（返回的对象禁止修改；写路径走 {@link #load}）。
     *
     * 构造要校验整份配置（含分组正则），冻结页每秒给几十个应用各取一次快照，
     * 不记忆就是每秒几十次整份校验 —— 实测是 tick 里仅次于规则解析的开销。
     * 原始串没变就直接复用，变了自然重建。
     */
    static DailyQuotaData snapshot(Context c){
        // getAll supplies one coherent config/ledger pair without waiting for slow
        // UsageStats IPC under the writer monitor. Published models stay read-only.
        Map<String,?> values=prefs(c).getAll();
        String config=(String)(values.containsKey("config")?values.get("config"):"{}");
        String usage=(String)(values.containsKey("usage")?values.get("usage"):values.containsKey("config")?"{missing":"{}");
        int resetHour=DayBoundary.hour(c);
        int resetMinute=DayBoundary.minute(c);
        synchronized(SNAPSHOT_LOCK){
            if(cachedSnapshot==null||cachedResetHour!=resetHour||cachedResetMinute!=resetMinute||!config.equals(cachedConfigRaw)||!usage.equals(cachedUsageRaw)){
                DailyQuotaData next=new DailyQuotaData(config,usage,GuardClock.now(c).getTimeInMillis(),resetHour,resetMinute);
                cachedResetMinute=resetMinute;
                cachedResetHour=resetHour;cachedSnapshot=next;cachedConfigRaw=config;cachedUsageRaw=usage;
            }
            return cachedSnapshot;
        }
    }
    static synchronized void refresh(Context c){refresh(c,false);}
    private static synchronized void refresh(Context c,boolean force){long now=GuardClock.now(c).getTimeInMillis();if(!force&&now>=refreshed&&now-refreshed<4500)return;DailyQuotaData d=load(c,now);if(d.groups().length()==0||!d.enabled()&&!force)return;
        recountTodayOnce(c,d,now);
        boolean reliable=AppUsageQuota.permission(c);List<AppUsageQuota.Span> spans=Collections.emptyList();if(d.enabled()&&reliable)try{spans=AppUsageQuota.spans(c,now,true);
            // 挂后台也计时：勾选的应用把「前台服务在跑」的区间并进用量（重叠合并成一段，不双倍）；没勾的原样只计前台。
            if(!d.backgroundPackages().isEmpty())spans=AppUsageQuota.effective(spans,AppUsageQuota.fgs(c,now),d.backgroundPackages());
        }catch(RuntimeException error){reliable=false;}
        // 全天推进：调整时段只管「能不能改设置」，不参与统计。
        d.advance(now,spans,reliable,RuleStore.all(c),DayBoundary.hour(c),DayBoundary.minute(c));if(!prefs(c).edit().putString("usage",d.usage.toString()).commit()){writeFailed=true;throw new IllegalStateException(T.s("每日限额用量未能保存"));}writeFailed=false;refreshed=now;
    }

    private static final String K_RECOUNT="recount_full_day_v1";

    /**
     * 一次性口径迁移。
     *
     * 1.79.3 之前额度只在调整时段（22:00–23:00）里累计，白天用掉的几个小时全被丢掉，
     * 「今日已用」整天都是 0。升级后第一次跑额度节拍时，把今天的账本清零、水位线拨回零点，
     * 让全天用量重算一遍 —— 只做一次，且必须已授权使用情况，没权限算不出来。
     */
    private static void recountTodayOnce(Context c,DailyQuotaData d,long now){
        SharedPreferences p=prefs(c);
        if(p.getBoolean(K_RECOUNT,false)||!AppUsageQuota.permission(c))return;
        if(!DayBoundary.key(c,now).equals(d.usage.optString("date")))return;   // 账本还没切到今天，下次再说
        try{
            DailyQuotaData.put(d.usage,"targets",new JSONObject());
            DailyQuotaData.put(d.usage,"lastAt",DayBoundary.startMs(c,now));
            DailyQuotaData.put(d.usage,"since",DayBoundary.startMs(c,now));
            d.usage.remove("error");
            d.usage.remove("unavailable");
        }catch(RuntimeException ignored){return;}
        // 先落盘再写标记：万一第二步失败，下一轮会拿着已清零的账本再算一遍，结果一样，不会算重。
        if(!p.edit().putString("usage",d.usage.toString()).commit())return;
        p.edit().putBoolean(K_RECOUNT,true).apply();
    }
    static synchronized void save(Context c,JSONObject candidate){Calendar now=GuardClock.now(c);DailyQuotaData.normalizeOrGroups(candidate);DailyQuotaData.validate(candidate);if(new DailyQuotaData(candidate.toString(),"{}",now.getTimeInMillis()).packages().contains(c.getPackageName()))throw new IllegalArgumentException(T.s("不能限制守卫自身"));
        refresh(c,true);DailyQuotaData current=load(c,now.getTimeInMillis());
        // 调整时段外只放「只加不减」的改动：补额度随时可以，把自己放出来得等时段
        if(!DailyQuotaData.editable(now,TimeWindow.start(c),TimeWindow.end(c))&&!DailyQuotaData.additive(current.config,candidate))throw new IllegalStateException(T.f("这个改动要减少额度，只在调整时段 %s 内开放",TimeWindow.label(c)));
        current.checkpoint(candidate,GuardClock.now(c).getTimeInMillis());
        if(!prefs(c).edit().putString("config",candidate.toString()).putString("usage",current.usage.toString()).commit())throw new IllegalStateException(T.s("每日限额未能保存"));refreshed=0;signature="";StudyGate.invalidate(c);
    }
    static synchronized void beforeScheduleChange(Context c){if(active(c))refresh(c,true);}
    /**
     * 跨设备格子的分钟数改成电脑那一份。
     *
     * 池子的预算只有电脑端一个权威（{@code game-quota-config.json} 里那条 shared 规则），
     * 手机本地那份 minutes 只是给进度条和文案用的副本。以前两边各存一个数、谁也不管谁，
     * 电脑改成 90 之后手机还顶着 120 显示，非等调整时段还改不动 —— 于是只能看到两个数。
     *
     * 这里刻意绕开 {@link #save}：那条路会拦「减少额度」，而同步是状态对齐，不是用户的
     * 编辑动作。写盘失败（进程被打断之类）就静默跳过，下一轮轮询会再同步一次。
     */
    static synchronized void syncSharedMinutes(Context c,String groupId,int minutes){
        if(groupId==null||groupId.isEmpty()||minutes<1||minutes>1440)return;
        try{
            SharedPreferences p=prefs(c);
            if(!p.contains("config"))return;
            JSONObject config=new JSONObject(p.getString("config","{}"));
            JSONArray groups=config.optJSONArray("groups");
            if(groups==null)return;
            boolean changed=false;
            for(int i=0;i<groups.length();i++){
                JSONObject group=groups.optJSONObject(i);
                if(group==null||!groupId.equals(group.optString("id"))||group.optInt("minutes")==minutes)continue;
                group.put("minutes",minutes);
                changed=true;
            }
            if(!changed)return;
            DailyQuotaData.validate(config);
            if(!p.edit().putString("config",config.toString()).commit())throw new IllegalStateException(T.s("每日限额未能保存"));
            refreshed=0;signature="";
        }catch(Exception error){
            android.util.Log.w("AFS","sync shared quota minutes failed: "+error);
        }
    }

    /**
     * 禁忌卡认领或手动按钮的限额惩罚：直接把已用时间记进当天账本。
     *
     * 不走 {@link #save}：那条路会按「减少额度」拦截，而这里惩罚的是已用时间，
     * 不是用户改配置。每个额度格子只写第一个成员一次，避免共享格子重复扣。
     */
    static synchronized int addTabooQuotaPenalty(Context c,int minutes){
        if(minutes<=0)return 0;
        long now=GuardClock.now(c).getTimeInMillis();
        DailyQuotaData data=load(c,now);
        String today=DayBoundary.key(c,now);
        if(!today.equals(data.usage.optString("date"))){
            // 跨额度日后先让正常刷新把当天账本建好，避免惩罚写进昨天的 usage。
            refresh(c,true);
            data=load(c,now);
            if(!today.equals(data.usage.optString("date"))){
                if(data.groups().length()==0)return 0;
                throw new IllegalStateException(T.s("额度日切换尚未完成，请稍后再试"));
            }
        }
        if(data.usage.optBoolean("unavailable")||!data.usage.optString("error").isEmpty())
            throw new IllegalStateException(T.s("每日限额用量暂不可读，稍后再试"));
        int changed=DailyQuotaData.addUsedPenalty(data.config,data.usage,minutes);
        if(changed==0)return 0;
        if(!prefs(c).edit().putString("usage",data.usage.toString()).commit()){
            writeFailed=true;
            throw new IllegalStateException(T.s("每日限额用量未能保存"));
        }
        writeFailed=false;refreshed=0;signature="";StudyGate.invalidate(c);
        return changed;
    }

    /**
     * 把旧「全天限时」（{@link AppUsageQuota}，一只应用一份、天生不能共享）并进每日限额格子：
     * 每只应用各成一格（minutes 照搬），今天已经用掉的时间写进账本，然后清空旧表。
     *
     * 只搬一次：旧表清空后本方法立刻返回 0。没授权使用情况时先不搬 —— 拿不到今天的用量，
     * 搬过去等于把已用时间清零，所以等下次授权后再搬。迁移前的原始数据留在
     * `afs_usage_quota/limits_before_migration` 与 `afs_daily_quota/*_before_migration`，便于回滚。
     */
    static synchronized int migrateLegacy(Context c){
        JSONObject limits;
        try{limits=AppUsageQuota.config(c);}catch(RuntimeException error){return 0;}
        if(limits.length()==0)return 0;
        if(!AppUsageQuota.permission(c))return 0;
        long now=GuardClock.now(c).getTimeInMillis();
        DailyQuotaData data=load(c,now);
        JSONObject config=MemoryData.copy(data.config),usage=MemoryData.copy(data.usage);
        Set<String> migrated=new LinkedHashSet<>();
        JSONArray groups=config.optJSONArray("groups");
        JSONObject targets=usage.optJSONObject("targets");
        if(groups==null||targets==null)return 0;
        Set<String> known=new LinkedHashSet<>(data.packages());
        int moved=0;
        for(Iterator<String> keys=limits.keys();keys.hasNext();){
            String pkg=keys.next();int minutes=limits.optInt(pkg);
            if(minutes<=0||known.contains(pkg))continue;
            try{
                long used=AppUsageQuota.used(c,pkg,RuleStore.all(c).getOrDefault(pkg,FreezeSlotStore.withoutSchedule()),now);
                groups.put(new JSONObject().put("id",UUID.randomUUID().toString()).put("name",Guard.labelOf(c.getPackageManager(),pkg)).put("minutes",minutes).put("apps",new JSONArray().put(pkg)));
                targets.put(pkg,Math.max(0,used));
                known.add(pkg);migrated.add(pkg);moved++;
            }catch(RuntimeException|JSONException error){/* 这一只搬不动就留着，下次再试 */}
        }
        if(moved==0)return 0;
        DailyQuotaData.put(config,"enabled",true);
        DailyQuotaData.validate(config);
        SharedPreferences legacy=c.getSharedPreferences(AppUsageQuota.SP,0);
        if(!prefs(c).edit().putString("config_before_migration",data.config.toString()).putString("usage_before_migration",data.usage.toString()).commit())throw new IllegalStateException(T.s("额度迁移未能保存备份"));
        if(!prefs(c).edit().putString("config",config.toString()).putString("usage",usage.toString()).commit())throw new IllegalStateException(T.s("额度迁移未能保存"));
        JSONObject remaining=DailyQuotaData.remainingLegacy(limits,migrated);
        if(!legacy.edit().putString("limits_before_migration",limits.toString()).putString("limits",remaining.toString()).commit())throw new IllegalStateException(T.s("旧额度清理未能保存"));
        refreshed=0;signature="";
        return moved;
    }
    static boolean editable(Context c){return DailyQuotaData.editable(GuardClock.now(c),TimeWindow.start(c),TimeWindow.end(c));}
    static Set<String> packages(Context c){return snapshot(c).packages();}
    static boolean active(Context c){DailyQuotaData d=snapshot(c);return d.enabled()&&d.groups().length()>0;}
    static boolean learningLinked(Context c){
        try{DailyQuotaData d=snapshot(c);return LearningBindings.targetQuota(c)&&d.enabled()&&d.groups().length()>0&&d.learningLinked();}
        catch(RuntimeException error){return false;}
    }
    static boolean hasAllowance(Context c,String pkg){DailyQuotaData d=snapshot(c);return d.enabled()&&d.group(pkg)!=null;}
    /** 先放开权限：没授权也能配额度，但判不出用量就不冻结；授权后立刻恢复计时与冻结。 */
    /** 超限判定：只看额度本身和应用自身冻结时段，与全局调整时段无关；额度用完当场就冻。 */
    static boolean exceeded(Context c,String pkg){
        if(!AppUsageQuota.permission(c))return false;
        DailyQuotaData d=snapshot(c);Rule rule=RuleStore.all(c).get(pkg);JSONObject group=d.group(pkg);
        if(group==null||!d.enabled())return false;
        Calendar clock=GuardClock.now(c);long now=clock.getTimeInMillis();
        boolean quotaPaused=FreezeWeekdayPolicy.pausesQuota(c,clock);
        boolean orPaused=FreezeWeekdayPolicy.pausesOrSet(c,clock);
        // 「共享格子」＝这一格有多个成员（一个格子放多个应用就是它们共用一份额度）。
        // 注意：本机的多应用格子也算共享，不能只看跨设备那条——之前只认 LanQuota.isShared，
        // 导致「Chrome 这种本机多应用格子」仍被成员自己的放行时段压制（nextplayer 的案例）。
        boolean shared=DailyQuotaData.sharedCell(group);
        // 共享格子选中的星期几：组内应用整天冻结，不看额度用没用完；其余天按原额度逻辑。
        boolean freezeToday=shared&&DailyQuotaData.freezeToday(group,clock);
        boolean lanShared=LanQuota.isShared(c,group.optString("id"));
        // 本机账本已经写不出来 / 损坏 → 保护性冻结（与时段无关）。
        boolean broken=writeFailed||d.usage.optBoolean("unavailable");
        boolean localExceeded=broken||freezeToday||d.groupExhausted(group);
        // 共享格子优先于自身冻结时段：只要共享额度（含本机这一份）满了就冻，
        // 不看应用此刻是不是在自己的放行时段里；跨设备格子再把电脑那半边的合计算进来。
        boolean lanExhausted=lanShared&&LanQuota.exhausted(c,group.optString("id"));
        boolean inScope=DailyQuotaData.inScope(rule,now);
        boolean quotaExceeded=DailyQuotaData.exceededBy(shared,localExceeded,inScope,lanExhausted);
        boolean orExceeded=DailyQuotaData.hasOrGroup(group)&&!d.groupExhausted(group)
                &&d.orFrozenByOther(group,now);
        return DailyQuotaData.enforced(quotaExceeded,orExceeded,quotaPaused,orPaused);
    }
    /** Only the mutual-exclusion cause; normal quota exhaustion keeps its existing exit behavior. */
    static boolean orFrozenByOther(Context c,String pkg,long now){
        if(pkg==null||!AppUsageQuota.permission(c))return false;
        Calendar clock=GuardClock.now(c);clock.setTimeInMillis(now);
        if(FreezeWeekdayPolicy.pausesOrSet(c,clock))return false;
        DailyQuotaData d=snapshot(c);JSONObject group=d.group(pkg);
        return d.enabled()&&group!=null&&DailyQuotaData.hasOrGroup(group)
                &&!d.groupExhausted(group)&&d.orFrozenByOther(group,now);
    }
    static boolean orReminder(Context c,String pkg,long now){
        Calendar clock=GuardClock.now(c);clock.setTimeInMillis(now);
        if(FreezeWeekdayPolicy.pausesOrSet(c,clock))return false;
        DailyQuotaData d=snapshot(c);JSONObject group=d.group(pkg);
        return d.enabled()&&group!=null&&DailyQuotaData.hasOrGroup(group)
                &&!d.groupExhausted(group)&&d.orReminderDue(group,now);
    }
    static boolean orForceExit(Context c,String pkg,long now){
        return orReminder(c,pkg,now);
    }
    /** 原子地占用当天唯一一次提醒名额；返回真才允许弹出提醒。 */
    static synchronized boolean claimOrReminder(Context c,String pkg,long now){
        if(pkg==null||!AppUsageQuota.permission(c))return false;
        Calendar clock=GuardClock.now(c);clock.setTimeInMillis(now);
        if(FreezeWeekdayPolicy.pausesOrSet(c,clock))return false;
        DailyQuotaData d=load(c,now);JSONObject group=d.group(pkg);
        if(!d.enabled()||group==null||!DailyQuotaData.hasOrGroup(group)
                ||d.groupExhausted(group)||!d.orReminderDue(group,now))return false;
        d.markOrReminded(group.optString("orGroup",""),now);
        if(!prefs(c).edit().putString("usage",d.usage.toString()).commit())
            throw new IllegalStateException(T.s("每日限额用量未能保存"));
        refreshed=0;signature="";
        return true;
    }
    static synchronized boolean claimOrForceExit(Context c,String pkg,long now){
        return claimOrReminder(c,pkg,now);
    }
    static boolean enforcementChanged(Context c){try{refresh(c);}catch(IllegalStateException error){if(!writeFailed)throw error;}StringBuilder out=new StringBuilder();for(String pkg:packages(c))if(exceeded(c,pkg))out.append(pkg).append(';');synchronized(DailyQuotaStore.class){String next=out.toString();boolean changed=!next.equals(signature);signature=next;return changed;}}
    static String status(Context c,String pkg){DailyQuotaData d=snapshot(c);JSONObject g=d.group(pkg);Rule rule=RuleStore.all(c).get(pkg);if(g==null)return "";return (DailyQuotaData.allDay(g,rule)?T.s("全天限额 · "):T.s("所绑时段限额 · "))+description(c,d,g);}
    static boolean readable(Context c,DailyQuotaData d){return !writeFailed&&AppUsageQuota.permission(c)&&!d.usage.optBoolean("unavailable")&&d.usage.optString("error").isEmpty();}
    static String description(Context c,DailyQuotaData d,JSONObject g){if(!d.enabled())return T.s("每日限额已暂停");if(FreezeWeekdayPolicy.pausesQuota(c,GuardClock.now(c)))return T.s("今天按星期暂停 · 用量继续记录");if(!AppUsageQuota.permission(c))return T.s("未授予使用情况权限，暂不计时");if(writeFailed||d.usage.optBoolean("unavailable")||!d.usage.optString("error").isEmpty())return T.s("用量暂不可读，保护性冻结"); 
        if(DailyQuotaData.freezeToday(g,GuardClock.now(c)))return T.s("星期冻结 · 今天整天冻结");
        if(DailyQuotaData.hasOrGroup(g)&&!d.groupExhausted(g)&&d.orFrozenByOther(g,GuardClock.now(c).getTimeInMillis()))
            return d.orRemindedAt(g.optString("orGroup",""))>0L
                    ?T.s("或集互斥 · 今天已提醒一次，超过 5 分钟后其他格冻结")
                    :T.s("或集互斥 · 超过 5 分钟，另一格已冻结");
        String groupId=g.optString("id");
        if(LanQuota.isShared(c,groupId)){
            // 共享组不能只报本机用量 —— 用户看到的“剩 30 分钟”必须和两台设备都能用的一致。
            if(!LanQuota.fresh(c,groupId))return T.s("跨设备额度 · 未连上电脑，暂按本机计");
            long total=LanQuota.total(c,groupId),limit=LanQuota.minutes(c,groupId)*60000L;
            // 局域网断了、数字来自 Gist 时说清楚：这是分钟级的旧数据，偏乐观。
            String head="gist".equals(LanQuota.source(c,groupId))?T.s("离线估算 · "):T.s("跨设备 · ");
            return head+String.format(Locale.CHINA,T.s("已用 %.1f / %d 分钟（电脑 %.1f + 手机 %.1f）"),total/60000.0,LanQuota.minutes(c,groupId),LanQuota.pcUsed(c,groupId)/60000.0,LanQuota.phoneUsed(c,groupId)/60000.0);
        }
        long used=d.used(g),limit=g.optInt("minutes")*60000L;return String.format(Locale.CHINA,T.s("已用 %.1f / %d 分钟 · 剩余 %.1f 分钟"),used/60000.0,g.optInt("minutes"),Math.max(0,limit-used)/60000.0);}
}
