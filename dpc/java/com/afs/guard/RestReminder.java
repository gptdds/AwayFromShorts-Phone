package com.afs.guard;

/**
 * 定时提醒：隔一段时间提醒一次「起来歇会儿」「喝口水」。
 *
 * 和定时锁机的区别：锁机是硬拦（LockTask 真锁屏），提醒弹出可关闭的页面，不强制锁机。
 * 两种可以同时开 —— 早上用提醒，晚上用锁机。
 *
 * 计时口径是「累计亮屏时长」：从亮屏开始统计，息屏则把本轮计时清零。
 * 不再按墙上时间「距上次提醒满 N 分钟」算 —— 那样灭屏两小时后一亮屏就会
 * 立刻弹一条过期提醒。也不按「时段起点整点起算」，避免刚进时段就弹。
 *
 * 纯函数，不碰 Android，离线可测。
 */
public final class RestReminder {

    public static final int DEFAULT_EVERY = 60;
    public static final int MAX_EVERY = 240;

    private RestReminder() {
    }

    /** 时段内且距上次提醒够久，就该提醒。 */
    public static boolean due(long nowMs, long lastFiredMs, int everyMinutes) {
        if (everyMinutes <= 0) return false;
        if (lastFiredMs <= 0L) return false;      // 还没起算，第一下不弹
        return nowMs - lastFiredMs >= everyMinutes * LockPlan.MINUTE;
    }

    /**
     * 亮屏累计一拍：
     *   · 息屏 → 直接清零（用户口径：息屏则计时清零），下次亮屏重新起算；
     *   · 刚亮屏／刚启用（lastTick<=0）→ 只记起点，不累加；
     *   · 亮着但不该提醒（时段外／锁机中／放行期／弹窗正显示）→ 暂停，保留累计；
     *   · 正常亮屏 → 累加这一拍，单次上限 maxGapMs 防止进程被杀后把灭屏时间补算进来。
     */
    public static long accumulate(long prevMs, long lastTickMs, long nowMs,
                                  boolean screenOn, boolean counting, long maxGapMs) {
        if (!screenOn) return 0L;
        long base = prevMs < 0 ? 0L : prevMs;
        if (lastTickMs <= 0L || !counting) return base;
        long delta = nowMs - lastTickMs;
        if (delta <= 0L) return base;
        if (maxGapMs > 0L && delta > maxGapMs) delta = maxGapMs;
        return base + delta;
    }

    /** 累计亮屏够久了没有。 */
    public static boolean dueAccum(long accumMs, int everyMinutes) {
        return everyMinutes > 0 && accumMs >= everyMinutes * LockPlan.MINUTE;
    }

    /** 跟 shouldFire 一样的四种不打扰，判据换成累计亮屏时长。 */
    public static boolean shouldFireAccum(boolean on, boolean inWindow, boolean screenOn,
                                          boolean locked, boolean passActive,
                                          long accumMs, int everyMinutes) {
        if (!on || !inWindow || !screenOn || locked || passActive) return false;
        return dueAccum(accumMs, everyMinutes);
    }

    /**
     * 此刻该不该真的发提醒。
     *
     * 不该发的四种情况，都是为了"别在不该打扰的时候打扰"：
     *   · 没开
     *   · 不在时段里（深夜、上班时间之外）
     *   · 灭屏（人不在看手机，弹了也是白弹，醒来还留着一条过期的）
     *   · 正被锁着 / 处于解锁码放行期（那会儿人在休息或有事，不需要再催）
     */
    public static boolean shouldFire(boolean on, boolean inWindow, boolean screenOn,
                                     boolean locked, boolean passActive,
                                     long nowMs, long lastFiredMs, int everyMinutes) {
        if (!on || !inWindow || !screenOn || locked || passActive) return false;
        return due(nowMs, lastFiredMs, everyMinutes);
    }

    /**
     * 补弹判定：有一条已经弹出去、但还没被打发走的提醒（live），而它的页面此刻不在屏幕上。
     *
     * 为什么需要它：提醒页面是活页，住在守卫进程里 —— 进程被杀（划掉最近任务、一键清理）
     * 会把页面一起带走，杀进程就成了「关掉提醒」的捷径。走正当渠道关掉的（点掉/自动退场）
     * 会把 live 记号撤掉；没撤就说明这条还没算数，条件一恢复就立刻补弹。
     *
     * 补弹沿用 shouldFire 的四种不打扰：不在时段、灭屏、锁机中、放行期里都先不弹，
     * 等条件回来再补 —— 弹窗记号还在，不会丢。
     */
    public static boolean revive(boolean live, boolean showing, boolean inWindow,
                                 boolean screenOn, boolean locked, boolean passActive) {
        if (!live || showing) return false;
        return inWindow && screenOn && !locked && !passActive;
    }

    /** 「45 分钟」这种时长文案（拆成数字与单位，英文好换序）。 */
    public static String minutes(int value) {
        return T.f("%d 分钟", value);
    }
}
