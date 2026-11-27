package com.afs.guard;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Calendar;

/**
 * An app-specific, one-off exemption from schedules and usage quotas.
 *
 * <p>The saved range is a concrete start/end timestamp, not a weekday or daily
 * repeat. It can only be created, extended or cleared from the adjustment
 * window. Study linkage, network blocking, punishments and key gates keep
 * working.</p>
 */
public final class TemporaryPardon {
    static final String SP = "afs_temporary_pardon";
    private static final String K_PACKAGE = "package";
    private static final String K_START_AT = "start_at";
    private static final String K_END_AT = "end_at";

    // 1.92.9 stored one concrete start/end timestamp.
    private static final String LEGACY_START = "start";
    private static final String LEGACY_END = "end";

    // 1.92.10 briefly stored a daily clock range.
    private static final String DAILY_START = "start_minute";
    private static final String DAILY_END = "end_minute";
    private static final String DAILY_DAYS = "days";

    private static final long MINUTE = 60_000L;
    private static final long DAY = 24 * 60 * MINUTE;

    public static final class Window {
        public final String pkg;
        public final long start;
        public final long end;

        Window(String pkg, long start, long end) {
            this.pkg = pkg;
            this.start = start;
            this.end = end;
        }
    }

    private TemporaryPardon() {
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(SP, Context.MODE_PRIVATE);
    }

    public static Window window(Context c) {
        if (c == null) return null;
        SharedPreferences p = prefs(c);
        String pkg = p.getString(K_PACKAGE, "");
        long start = p.getLong(K_START_AT, 0L);
        long end = p.getLong(K_END_AT, 0L);
        if (valid(pkg, start, end)) return new Window(pkg, start, end);

        start = p.getLong(LEGACY_START, 0L);
        end = p.getLong(LEGACY_END, 0L);
        if (valid(pkg, start, end)) {
            Window out = new Window(pkg, start, end);
            save(p, out);
            return out;
        }
        return migrateDaily(p, pkg);
    }

    /**
     * Convert a daily clock range from 1.92.10 into the next concrete
     * occurrence, so it does not keep repeating after the upgrade.
     */
    private static Window migrateDaily(SharedPreferences p, String pkg) {
        int start = p.getInt(DAILY_START, -1);
        int end = p.getInt(DAILY_END, -1);
        if (pkg == null || pkg.isEmpty()
                || start < 0 || start >= 1440 || end < 0 || end >= 1440 || start == end) {
            return null;
        }
        Window out = fromDaily(pkg, start, end, System.currentTimeMillis());
        save(p, out);
        return out;
    }

    static Window fromDaily(String pkg, int startMinute, int endMinute, long now) {
        Calendar day = Calendar.getInstance();
        day.setTimeInMillis(now);
        day.set(Calendar.HOUR_OF_DAY, 0);
        day.set(Calendar.MINUTE, 0);
        day.set(Calendar.SECOND, 0);
        day.set(Calendar.MILLISECOND, 0);
        long base = day.getTimeInMillis();
        long start = base + startMinute * MINUTE;
        long end = base + endMinute * MINUTE;
        if (end <= start) end += DAY;
        if (now >= end) {
            start += DAY;
            end += DAY;
        } else if (now < start && end > DAY) {
            // The 00:00–end half of yesterday's cross-midnight range.
            long previousStart = start - DAY;
            long previousEnd = end - DAY;
            if (now >= previousStart && now < previousEnd) {
                start = previousStart;
                end = previousEnd;
            }
        }
        return new Window(pkg, start, end);
    }

    public static synchronized void set(Context c, String pkg, long startAt, long endAt) {
        if (c == null) throw new IllegalArgumentException(T.s("临时赦免不可用"));
        if (!TimeWindow.canEdit(c)) {
            throw new IllegalStateException(
                    T.f("临时赦免只能在调整时段 %s 内修改", TimeWindow.label(c)));
        }
        if (pkg == null || pkg.trim().isEmpty()) {
            throw new IllegalArgumentException(T.s("先选择要赦免的应用"));
        }
        if (endAt <= startAt) {
            throw new IllegalArgumentException(T.s("结束时间必须晚于开始时间"));
        }
        if (endAt <= GuardClock.now(c).getTimeInMillis()) {
            throw new IllegalArgumentException(T.s("结束时间必须晚于现在"));
        }
        if (!save(prefs(c), new Window(pkg, startAt, endAt))) {
            throw new IllegalStateException(T.s("临时赦免未能保存"));
        }
    }

    private static boolean valid(String pkg, long start, long end) {
        return pkg != null && !pkg.isEmpty() && start > 0L && end > start;
    }

    private static boolean save(SharedPreferences p, Window w) {
        return p.edit().putString(K_PACKAGE, w.pkg)
                .putLong(K_START_AT, w.start).putLong(K_END_AT, w.end)
                .remove(LEGACY_START).remove(LEGACY_END)
                .remove(DAILY_START).remove(DAILY_END).remove(DAILY_DAYS).commit();
    }

    public static void clear(Context c) {
        if (c == null) return;
        if (!TimeWindow.canEdit(c)) {
            throw new IllegalStateException(
                    T.f("临时赦免只能在调整时段 %s 内修改", TimeWindow.label(c)));
        }
        prefs(c).edit().remove(K_PACKAGE).remove(K_START_AT).remove(K_END_AT)
                .remove(LEGACY_START).remove(LEGACY_END)
                .remove(DAILY_START).remove(DAILY_END).remove(DAILY_DAYS).apply();
    }

    /** True only for the selected package while the one-off range is active. */
    public static boolean active(Context c, String pkg, long now) {
        Window w = window(c);
        return w != null && pkg != null && pkg.equals(w.pkg) && active(w, now);
    }

    static boolean active(Window w, long now) {
        return w != null && now >= w.start && now < w.end;
    }

    /** The next start/end boundary that needs a guard re-evaluation. */
    public static long nextBoundary(Context c, long now) {
        return nextBoundaryOf(window(c), now);
    }

    static long nextBoundaryOf(Window w, long now) {
        if (w == null) return Long.MAX_VALUE;
        if (now < w.start) return w.start;
        if (now < w.end) return w.end;
        return Long.MAX_VALUE;
    }

    public static String signature(Context c, long now) {
        Window w = window(c);
        if (w == null) return "0";
        return w.pkg + "|" + w.start + "|" + w.end;
    }

    public static String summary(Context c) {
        Window w = window(c);
        if (w == null) return T.s("未开启 · 选择应用和时间段");
        long now = GuardClock.now(c).getTimeInMillis();
        String state = now < w.start ? T.s("等待生效")
                : now < w.end ? T.s("生效中") : T.s("已结束");
        return T.f("%s · %s · %s", state,
                Guard.labelOf(c.getPackageManager(), w.pkg), rangeText(w.start, w.end));
    }

    static String rangeText(long startAt, long endAt) {
        Calendar start = Calendar.getInstance();
        start.setTimeInMillis(startAt);
        Calendar end = Calendar.getInstance();
        end.setTimeInMillis(endAt);
        String left = dateTime(start);
        int days = dayDiff(start, end);
        String right;
        if (days == 0) {
            right = clock(end);
        } else if (days == 1) {
            right = T.s("次日 ") + clock(end);
        } else {
            right = dateTime(end);
        }
        return left + "–" + right;
    }

    private static String dateTime(Calendar c) {
        return T.f("%d月%d日 %s", c.get(Calendar.MONTH) + 1,
                c.get(Calendar.DAY_OF_MONTH), clock(c));
    }

    static String dateTimeText(long at) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(at);
        return dateTime(c);
    }

    private static String clock(Calendar c) {
        return Rule.hm(c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE));
    }

    private static int dayDiff(Calendar from, Calendar to) {
        Calendar a = (Calendar) from.clone();
        Calendar b = (Calendar) to.clone();
        a.set(Calendar.HOUR_OF_DAY, 0);
        a.set(Calendar.MINUTE, 0);
        a.set(Calendar.SECOND, 0);
        a.set(Calendar.MILLISECOND, 0);
        b.set(Calendar.HOUR_OF_DAY, 0);
        b.set(Calendar.MINUTE, 0);
        b.set(Calendar.SECOND, 0);
        b.set(Calendar.MILLISECOND, 0);
        return (int) Math.round((b.getTimeInMillis() - a.getTimeInMillis()) / (double) DAY);
    }
}
