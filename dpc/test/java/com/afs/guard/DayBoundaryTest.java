package com.afs.guard;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/** 额度日界：默认零点与旧行为逐毫秒一致，改成别的整点只移动边界，不丢账本。 */
public final class DayBoundaryTest {
    private static int checks;

    private static void eq(Object expected, Object actual, String label) {
        checks++;
        if (!expected.equals(actual)) throw new AssertionError(label + ": " + actual + " != " + expected);
    }

    private static long at(int year, int month, int day, int hour, int minute) {
        Calendar c = Calendar.getInstance();
        c.set(year, month, day, hour, minute, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    private static String day(long at) {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date(at));
    }

    public static void main(String[] args) {
        // 默认零点：与旧的 Calendar.set(HOUR_OF_DAY,0) 完全一致
        long morning = at(2026, 9, 31, 9, 30);
        eq(at(2026, 9, 31, 0, 0), DayBoundary.startMs(morning, 0), "midnight boundary");
        eq("2026-10-31", DayBoundary.key(morning, 0), "midnight key");

        // 4 点日界：凌晨 2 点还属于「昨天」那一档
        long earlyTwo = at(2026, 9, 31, 2, 0);
        eq(at(2026, 9, 30, 4, 0), DayBoundary.startMs(earlyTwo, 4), "4am boundary before reset");
        eq("2026-10-30", DayBoundary.key(earlyTwo, 4), "4am key stays on previous day");

        // 5 点已过重置
        long five = at(2026, 9, 31, 5, 0);
        eq(at(2026, 9, 31, 4, 0), DayBoundary.startMs(five, 4), "4am boundary after reset");
        eq("2026-10-31", DayBoundary.key(five, 4), "4am key rolls over");

        // 正好在重置那一分钟：算新的一天
        long exactly = at(2026, 9, 31, 4, 0);
        eq(at(2026, 9, 31, 4, 0), DayBoundary.startMs(exactly, 4), "exact reset instant belongs to new day");
        eq("2026-10-31", DayBoundary.key(exactly, 4), "exact reset key");

        // 23 点日界跨月
        long firstHour = at(2026, 9, 1, 0, 30);
        eq(at(2026, 8, 30, 23, 0), DayBoundary.startMs(firstHour, 23), "23h boundary crosses month");

        // nextReset 永远是下一个整点
        eq(at(2026, 9, 31, 4, 0), DayBoundary.nextResetMs(earlyTwo, 4), "next reset after boundary");
        eq(at(2026, 10, 1, 0, 0), DayBoundary.nextResetMs(morning, 0), "next reset at midnight");

        // 非法小时按默认零点兜底
        eq(0, DayBoundary.DEFAULT_HOUR, "default hour is midnight");
        eq(at(2026, 9, 31, 0, 0), DayBoundary.startMs(morning, -3), "negative hour falls back");
        eq(at(2026, 9, 31, 0, 0), DayBoundary.startMs(morning, 99), "too large hour falls back");

        // 文案
        eq("每日零点", DayBoundary.label(0), "midnight label");
        eq("每日 4:00", DayBoundary.label(4), "four label");
        eq("每日零点", DayBoundary.label(42), "invalid label falls back");
        eq("每日零点", DayBoundary.label(DayBoundary.DEFAULT_HOUR), "default label is midnight");

        // 8 点日界：凌晨 7:30 还算「昨天」，8:00 整属于新的一天（通用逻辑，与默认无关）
        // 注意 at() 的月份是 Calendar 的 0-based，9 就是十月
        long beforeEight = at(2026, 9, 31, 7, 30);
        eq(at(2026, 9, 30, 8, 0), DayBoundary.startMs(beforeEight, 8), "7:30 still previous quota day");
        eq("2026-10-30", DayBoundary.key(beforeEight, 8), "7:30 key stays on previous day");
        eq("2026-10-30", DayBoundary.key(at(2026, 9, 31, 7, 59), 8), "7:59 still previous day");
        eq("2026-10-31", DayBoundary.key(at(2026, 9, 31, 8, 0), 8), "8:00 rolls over");

        // 参数顺序一致性：startMs(now,hour) 与 key(now,hour) 用同一个边界
        eq(day(DayBoundary.startMs(earlyTwo, 4)), DayBoundary.key(earlyTwo, 4), "key derives from same boundary");

        long pending=DayBoundary.changeAt(morning,0,0);
        eq(DayBoundary.nextResetMs(morning,0),pending,"change starts at next old boundary");
        eq(pending,DayBoundary.changeAt(morning+60000,0,pending),"editing pending time again never postpones activation");
        eq(DayBoundary.nextResetMs(morning,4),DayBoundary.changeAt(morning,4,morning-1),"expired pending marker schedules next valid boundary");
        long beforeMinute=at(2026,9,31,11,29);
        long exactMinute=at(2026,9,31,11,30);
        eq(at(2026,9,30,11,30),DayBoundary.startMs(beforeMinute,11,30),"minute boundary retains previous day");
        eq(exactMinute,DayBoundary.startMs(exactMinute,11,30),"minute boundary resets exactly");
        eq("2026-10-30",DayBoundary.key(beforeMinute,11,30),"minute ledger key before reset");
        eq("2026-10-31",DayBoundary.key(exactMinute,11,30),"minute ledger key after reset");
        eq(exactMinute,DayBoundary.nextResetMs(beforeMinute,11,30),"next minute reset");
        eq(at(2026,10,1,11,30),DayBoundary.nextResetMs(exactMinute,11,30),"next reset across month");
        eq("每日 11:30",DayBoundary.label(11,30),"minute reset label");
        System.out.println("Day boundary: " + checks + " checks passed");
    }
}
