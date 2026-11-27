package com.afs.core.rules;

import java.util.Calendar;
import java.util.TimeZone;

/**
 * 一周规则：星期位图 + 若干时段 + 模式。
 *
 * 这是 DO 守卫和无障碍版共用的唯一判定核心。两个 APK 都有自己的执行器
 * （DO 用系统暂停、无障碍版用悬浮窗拦截），但"此刻该不该拦"必须算得一模一样，
 * 否则同一个用户装两个包会得到两套结果。
 *
 * 纯 Java，不 import 任何 Android 类 —— 两个 build.sh 和两套离线回归都直接编译它。
 *
 * 三条语义，别当成细节：
 * 1. 时段左闭右开：[start, end) —— 22:00 生效、22:59 还在、23:00 已经结束。
 * 2. start > end 表示跨午夜，"选中的星期"指这段的**起始日**：22:00–01:00
 *    周五开始，那它覆盖周五 22:00–24:00 和周六 00:00–01:00。
 * 3. start == end 当成全天，不是空窗口 —— 这是唯一能表达"整天"的写法。
 *
 * 模式只有两种，且互为补集，不允许多第三种：
 * ALLOW  = 时段内放行、时段外拦截（无障碍版的"放行时段"就是这个）
 * FREEZE = 时段内拦截、时段外放行（DO 守卫的"冻结时段"就是这个）
 *
 * days == 0 或没有任何时段时，ALLOW 模式全天拦截，FREEZE 模式全天放行 ——
 * 跟两个 APK 的历史行为一致，迁移时不能改。
 */
public final class WeeklyRule {

    /** 位图：bit 0 = 周一 …… bit 6 = 周日。 */
    public static final int ALL_DAYS = 0x7F;
    public static final int MINUTES_PER_DAY = 24 * 60;
    public static final int WORKDAYS = 0x1F;
    public static final int WEEKEND = 0x60;

    public enum Mode {
        /** 时段内放行，时段外拦截。 */
        ALLOW,
        /** 时段内拦截，时段外放行。 */
        FREEZE
    }

    private final int days;
    private final Mode mode;
    private final int[][] windows;

    private WeeklyRule(int days, int[][] windows, Mode mode) {
        this.days = days & ALL_DAYS;
        this.windows = normalize(windows);
        this.mode = mode == null ? Mode.ALLOW : mode;
    }

    public static WeeklyRule allow(int days, int[][] windows) {
        return new WeeklyRule(days, windows, Mode.ALLOW);
    }

    public static WeeklyRule freeze(int days, int[][] windows) {
        return new WeeklyRule(days, windows, Mode.FREEZE);
    }

    /* ==================== 取值 ==================== */

    public int days() {
        return days;
    }

    public Mode mode() {
        return mode;
    }

    public boolean freezeMode() {
        return mode == Mode.FREEZE;
    }

    public int windowCount() {
        return windows.length;
    }

    public int startAt(int i) {
        return windows[i][0];
    }

    public int endAt(int i) {
        return windows[i][1];
    }

    public boolean crossMidnightAt(int i) {
        return windows[i][0] > windows[i][1];
    }

    /** 深拷贝，调用方改数组不会穿回规则本身。 */
    public int[][] windows() {
        int[][] out = new int[windows.length][2];
        for (int i = 0; i < windows.length; i++) {
            out[i][0] = windows[i][0];
            out[i][1] = windows[i][1];
        }
        return out;
    }

    public WeeklyRule withDays(int mask) {
        return new WeeklyRule(mask, windows, mode);
    }

    public WeeklyRule withMode(Mode next) {
        return new WeeklyRule(days, windows, next);
    }

    /* ==================== 判定 ==================== */

    /**
     * 现在是不是落在时段里（不考虑模式，只看时间）。
     * 跨午夜的凌晨那半段，看的是**昨天**的星期位。
     */
    public boolean matches(int monIndex, int minuteOfDay) {
        if (windows.length == 0) {
            return false;
        }
        int minute = norm(minuteOfDay);
        int today = ((monIndex % 7) + 7) % 7;
        int yesterday = (today + 6) % 7;
        for (int i = 0; i < windows.length; i++) {
            int s = windows[i][0];
            int e = windows[i][1];
            if (s == e) {
                // 起止相同 = 选中的星期全天
                if (dayOn(days, today)) {
                    return true;
                }
            } else if (s < e) {
                if (dayOn(days, today) && minute >= s && minute < e) {
                    return true;
                }
            } else {
                boolean evening = dayOn(days, today) && minute >= s;
                boolean morning = dayOn(days, yesterday) && minute < e;
                if (evening || morning) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 此刻要不要拦 —— 两个执行器都只读这一个结论。 */
    public boolean blocked(int monIndex, int minuteOfDay) {
        boolean hit = matches(monIndex, minuteOfDay);
        return mode == Mode.FREEZE ? hit : !hit;
    }

    /**
     * 下一次拦截状态翻转的时刻（毫秒）。
     *
     * 逐分钟扫，最多 8 天。多段 + 跨午夜 + 星期位组合下，解析式"跳到下一边界"
     * 容易漏算，而 11520 次比较是微秒级的，没必要为它引入更难验的算法。
     * 找不到翻转时返回 now + 1 小时，调用方不会因为拿不到时刻就不排下一次检查。
     */
    public long nextFlip(long nowMillis) {
        return nextFlip(nowMillis, TimeZone.getDefault());
    }

    public long nextFlip(long nowMillis, TimeZone zone) {
        long next = nextTransition(nowMillis, zone);
        return next == 0L ? nowMillis + 3600000L : next;
    }

    public boolean blocked(long nowMillis, TimeZone zone) {
        Calendar now = Calendar.getInstance(zone == null ? TimeZone.getDefault() : zone);
        now.setTimeInMillis(nowMillis);
        return blocked(monIndex(now), now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE));
    }

    public long nextTransition(long nowMillis, TimeZone zone) {
        Calendar t = Calendar.getInstance(zone == null ? TimeZone.getDefault() : zone);
        t.setTimeInMillis(nowMillis);
        long cursor = Math.floorDiv(nowMillis, 60000L) * 60000L;
        boolean current = blocked(monIndex(t), t.get(Calendar.HOUR_OF_DAY) * 60 + t.get(Calendar.MINUTE));
        for (int i = 0; i < 8 * MINUTES_PER_DAY + 2; i++) {
            cursor += 60000L;
            t.setTimeInMillis(cursor);
            if (blocked(monIndex(t), t.get(Calendar.HOUR_OF_DAY) * 60 + t.get(Calendar.MINUTE)) != current) {
                return t.getTimeInMillis();
            }
        }
        return 0L;
    }

    /**
     * 所有时段加起来一天有多少分钟（不看星期）。
     * DO 用它判断"每天只给几小时"从而自动把应用归入学习闸门，
     * 迁移后必须保持一致。
     */
    public int totalWindowMinutes() {
        int total = 0;
        for (int i = 0; i < windows.length; i++) {
            int s = windows[i][0];
            int e = windows[i][1];
            if (s == e) {
                total += MINUTES_PER_DAY;
            } else if (s < e) {
                total += e - s;
            } else {
                total += (MINUTES_PER_DAY - s) + e;
            }
        }
        return total;
    }

    /** 规则完全一致时返回同一串，给分组卡片和去重用。 */
    public String signature() {
        StringBuilder sb = new StringBuilder();
        sb.append(days).append('|').append(mode == Mode.FREEZE ? 'F' : 'A');
        for (int i = 0; i < windows.length; i++) {
            sb.append('|').append(windows[i][0]).append('-').append(windows[i][1]);
        }
        return sb.toString();
    }

    /* ==================== 纯函数 ==================== */

    public static int norm(int minute) {
        return ((minute % MINUTES_PER_DAY) + MINUTES_PER_DAY) % MINUTES_PER_DAY;
    }

    public static boolean dayOn(int mask, int monBasedIndex) {
        return (mask & (1 << monBasedIndex)) != 0;
    }

    /** Calendar.DAY_OF_WEEK → 0 = 周一 …… 6 = 周日。 */
    public static int monIndex(Calendar c) {
        return (c.get(Calendar.DAY_OF_WEEK) + 5) % 7;
    }

    /**
     * 丢掉非法行、规范化分钟数、按起点升序。
     * 排序是为了让展示和 signature 稳定，不影响判定结果。
     */
    public static int[][] normalize(int[][] in) {
        if (in == null || in.length == 0) {
            return new int[0][2];
        }
        int[][] tmp = new int[in.length][2];
        int n = 0;
        for (int i = 0; i < in.length; i++) {
            if (in[i] == null || in[i].length < 2) {
                continue;
            }
            tmp[n][0] = norm(in[i][0]);
            tmp[n][1] = norm(in[i][1]);
            n++;
        }
        int[][] out = new int[n][2];
        System.arraycopy(tmp, 0, out, 0, n);
        for (int i = 1; i < out.length; i++) {
            int[] cur = out[i];
            int j = i - 1;
            while (j >= 0 && out[j][0] > cur[0]) {
                out[j + 1] = out[j];
                j--;
            }
            out[j + 1] = cur;
        }
        return out;
    }
}
