package com.afs.guard;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.PowerManager;

/**
 * 每日亮屏上限：今天累计亮屏达到自定义时长后，整机自动进入灰度。
 *
 * 和 LockEnforcer 的「累计亮屏」不同：那个是锁机用的单轮累计，锁完清零；
 * 这里是按额度日累计到第二天重置时刻（与时间限额同一个日界）。
 * 采样挂在常驻节拍上，只累加「屏幕亮着」的区间，灭屏不计。
 */
public final class ScreenOnLimit {

    static final String SP = "afs_screen_on_limit";
    private static final String K_ENABLED = "enabled";
    private static final String K_MINUTES = "minutes";
    private static final String K_USED = "used_ms";
    private static final String K_DAY = "day";
    private static final String K_LAST = "last_sample";

    /** 最长按 24 小时算，超出直接截断，避免异常数据把累计撑爆。 */
    private static final long MAX_USED_MS = 24 * 3600_000L;
    /** 间隔超过 2 分钟按「刚亮屏」起算，不把灭屏那段补进来。 */
    private static final long MAX_GAP_MS = 120_000L;

    private ScreenOnLimit() {
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(SP, Context.MODE_PRIVATE);
    }

    public static boolean enabled(Context c) {
        return prefs(c).getBoolean(K_ENABLED, false);
    }

    public static int minutes(Context c) {
        int value = prefs(c).getInt(K_MINUTES, 180);
        return value < 5 || value > 24 * 60 ? 180 : value;
    }

    static void configure(Context c, boolean enabled, int minutes) {
        if (minutes < 5 || minutes > 24 * 60) {
            throw new IllegalArgumentException(T.s("亮屏上限只能是 5–1440 分钟"));
        }
        prefs(c).edit().putBoolean(K_ENABLED, enabled).putInt(K_MINUTES, minutes).commit();
        Guard.applyAsync(c);
    }

    /** 今天已累计的亮屏毫秒；跨额度日自动归零。 */
    public static long usedMs(Context c) {
        SharedPreferences p = prefs(c);
        rollover(c, p, System.currentTimeMillis());
        return Math.min(MAX_USED_MS, Math.max(0, p.getLong(K_USED, 0)));
    }

    public static boolean exceeded(Context c) {
        return MasterSwitch.enabled(c) && enabled(c) && usedMs(c) >= minutes(c) * 60_000L;
    }

    public static String summary(Context c) {
        if (!MasterSwitch.enabled(c)) return T.s("总开关关闭 · 亮屏灰度已暂停");
        if (!enabled(c)) return T.s("已关闭");
        long used = usedMs(c);
        String limit = T.f("每天 %d 分钟", minutes(c)) + T.s(" · ")
                + DayBoundary.label(c) + T.s("重置");
        if (used >= minutes(c) * 60_000L) {
            return limit + T.s(" · 已达上限，正在灰度");
        }
        long left = Math.max(0, minutes(c) * 60_000L - used);
        return limit + T.s(" · 剩余 ") + (left + 59_999L) / 60_000L + T.s(" 分钟");
    }

    /**
     * 常驻节拍采样。亮屏才累加；灭屏、进程重启、跨日均从当前时刻重新起算。
     */
    public static synchronized void tick(Context c) {
        if (!MasterSwitch.enabled(c)) return;
        long now = System.currentTimeMillis();
        SharedPreferences p = prefs(c);
        rollover(c, p, now);
        long last = p.getLong(K_LAST, 0);
        if (last <= 0 || last > now || now - last > MAX_GAP_MS) {
            p.edit().putLong(K_LAST, now).commit();
            return;
        }
        if (!interactive(c)) {
            p.edit().putLong(K_LAST, now).commit();
            return;
        }
        long used = Math.min(MAX_USED_MS, Math.max(0, p.getLong(K_USED, 0)) + (now - last));
        if (!p.edit().putLong(K_USED, used).putLong(K_LAST, now).commit()) {
            throw new IllegalStateException(T.s("亮屏时长未能保存"));
        }
    }

    /** 跨额度日时清空累计，并把采样点对齐到当前时刻。 */
    private static void rollover(Context c, SharedPreferences p, long now) {
        String key = DayBoundary.key(c, now);
        if (key.equals(p.getString(K_DAY, ""))) return;
        p.edit().putString(K_DAY, key).putLong(K_USED, 0).putLong(K_LAST, now).commit();
    }

    private static boolean interactive(Context c) {
        try {
            PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
            return pm == null || pm.isInteractive();
        } catch (Throwable ignored) {
            return true;
        }
    }
}
