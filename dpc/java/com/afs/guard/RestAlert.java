package com.afs.guard;

import android.app.NotificationManager;
import android.content.Context;
import android.os.PowerManager;
import android.util.Log;

/**
 * 定时提醒的执行者：跟 {@link GuardService} 的节拍走，到点只弹可关闭的提醒页面，不发通知。
 *
 * 不发的情况见 {@link RestReminder#shouldFire}：灭屏、锁机中、放行期里都不打扰。
 *
 * 进程被杀会把弹着的页面一起带走 —— live 记号还在的话，下一个节拍立刻补弹
 * （见 {@link RestReminder#revive}），杀进程不能当关提醒的捷径。
 *
 * 发完立刻把「上次提醒」写到当前时刻 —— 弹页面失败也要写，
 * 否则下一次 tick 会重试，节拍一密集就变成连环弹。
 */
public final class RestAlert {

    /** 旧版本用过的提醒通知 id，只用于把历史遗留的那条清干净。 */
    private static final int LEGACY_NOTIFICATION_ID = 51;

    private RestAlert() {
    }

    public static void tick(Context c) {
        try {
            if (!MasterSwitch.enabled(c)) return;
            if (!ReminderStore.on(c)) return;
            long now = GuardClock.now(c).getTimeInMillis();
            long dayStart = LockStore.dayStartMs(GuardClock.now(c));
            boolean inWindow = LockPlan.inWindow(LockPlan.minuteOfDay(now, dayStart),
                    ReminderStore.from(c), ReminderStore.to(c));
            boolean screenOn = true;
            PowerManager power = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
            if (power != null) screenOn = power.isInteractive();
            boolean locked = LockEnforcer.showing || LockStore.appLockUntil(c) > now;
            boolean pass = UnlockStore.passActive(c, now);

            // 亮屏计时：从亮屏开始统计，息屏清零。采样放在所有提前返回之前，
            // 否则灭屏那段会被下一次亮屏误算进去。
            long accum = ReminderStore.accum(c);
            long lastTick = ReminderStore.lastTick(c);
            boolean counting = inWindow && !locked && !pass && !RestReminderActivity.showing();
            long next = RestReminder.accumulate(accum, lastTick, now, screenOn, counting, ScreenLock.MAX_GAP);
            if (!screenOn) {
                ReminderStore.resetCounting(c);       // 息屏：累计清零，标记下次亮屏重新起算
            } else {
                ReminderStore.saveCounting(c, next, now);
            }

            // 补弹：页面被进程死亡带走后，条件一恢复就重新弹（杀进程不再等于关提醒）。
            // 走正当渠道关掉的会把 live 记号撤掉，走不到这里。
            if (RestReminder.revive(ReminderStore.live(c), RestReminderActivity.showing(),
                    inWindow, screenOn, locked, pass)) {
                ReminderStore.setLastFired(c, now);   // 这算重新呈现一次
                ReminderStore.resetCounting(c);
                show(c, ReminderStore.every(c));
                return;
            }
            if (!inWindow) ReminderStore.setLive(c, false);   // 时段都过了，过期的挂起不再追
            if (!RestReminder.shouldFireAccum(true, inWindow, screenOn, locked, pass,
                    next, ReminderStore.every(c))) {
                return;
            }
            ReminderStore.setLastFired(c, now);
            ReminderStore.resetCounting(c);           // 发完这一轮，亮屏计时重新起算
            show(c, ReminderStore.every(c));
        } catch (RuntimeException error) {
            Log.w(Guard.TAG, "rest reminder failed: " + error.getClass().getSimpleName());
        }
    }

    /**
     * 只弹页面。
     *
     * 不再发通知：页面本身就是提醒，通知栏那条既会被用户划掉、又会在页面关掉后
     * 还留在栏里，等于同一次提醒打扰两遍。没有了通知兜底，弹窗权限就是唯一通路，
     * 设置页里「后台提醒弹窗」必须允许，否则系统会直接拦掉这次 launch。
     *
     * 先记 live 再 launch：万一进程恰好在 launch 和页面渲染之间被杀，
     * 下一个节拍也能凭这个记号把弹窗补回来；launch 失败则把记号撤掉。
     */
    static void show(Context c, int every) {
        if (!RestReminderActivity.backgroundAllowed(c)) {
            // 没给弹窗权限：后台 launch 会被系统静默拦下。先挂起来，回 App 时补弹。
            ReminderStore.setPending(c, System.currentTimeMillis());
            return;
        }
        ReminderStore.setLive(c, true);
        try { c.startActivity(RestReminderActivity.intent(c, every)); }
        catch (RuntimeException error) {
            ReminderStore.setLive(c, false);
            Log.w(Guard.TAG, "rest popup launch failed", error);
        }
    }

    /**
     * 补弹被拦下的提醒（由 MainActivity 在首帧后调用）。
     *
     * 超过 4 小时的旧提醒直接丢掉 —— 早上开机弹一条「已经连续 45 分钟没休息」只会莫名其妙。
     */
    static void deliverPending(Context c) {
        long pending = ReminderStore.pending(c);
        if (pending <= 0L) return;
        ReminderStore.setPending(c, 0L);
        if (!ReminderStore.on(c)) return;
        if (System.currentTimeMillis() - pending > 4L * 60L * 60L * 1000L) return;
        ReminderStore.setLive(c, true);
        try { c.startActivity(RestReminderActivity.intent(c, ReminderStore.every(c))); }
        catch (RuntimeException error) {
            ReminderStore.setLive(c, false);
            Log.w(Guard.TAG, "pending rest popup failed", error);
        }
    }

    /** 关掉提醒时顺手撤掉旧版本还挂在通知栏的那条；live 记号也一并撤掉。 */
    public static void clear(Context c) {
        RestReminderActivity.dismissCurrent();
        ReminderStore.setLive(c, false);
        ReminderStore.setPending(c, 0L);
        NotificationManager manager = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) manager.cancel(LEGACY_NOTIFICATION_ID);
    }

    /**
     * 下一次该提醒的时刻（给守护闹钟用）；没开返回 0。
     *
     * 为什么要算这个：进程被「最近任务」划掉后没人跑 tick，提醒会整个错过。
     * 把到点时刻排给 AlarmManager，系统会在那一刻把进程叫醒，tick 一跑就补发。
     * 时段外则排到下一次开门，避免每分钟空转。
     */
    static long nextDueAt(Context c) {
        if (!ReminderStore.on(c)) return 0L;
        long now = System.currentTimeMillis();
        java.util.Calendar calendar = GuardClock.now(c);
        int minute = LockPlan.minuteOfDay(now, LockStore.dayStartMs(calendar));
        int from = ReminderStore.from(c);
        int to = ReminderStore.to(c);
        if (!LockPlan.inWindow(minute, from, to)) {
            long dayStart = LockStore.dayStartMs(calendar);
            long open = dayStart + from * LockPlan.MINUTE;
            while (open <= now) open += 24L * 60L * LockPlan.MINUTE;
            return open;
        }
        // 累计制按亮屏时长走，墙上时刻没法精确预测：按「还差多少亮屏」估一个上限，
        // 由 tick 用累计值判该不该弹；另有 5 分钟保活闹钟兜底。
        long full = ReminderStore.every(c) * LockPlan.MINUTE;
        long left = full - ReminderStore.accum(c);
        return now + Math.max(60_000L, Math.min(left, full));
    }
}
