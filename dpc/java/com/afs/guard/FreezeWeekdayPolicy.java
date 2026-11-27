package com.afs.guard;

import android.content.Context;

import java.util.Calendar;

/**
 * Whole-day pause policy for GameGuard's automatic restriction rails.
 *
 * <p>The weekday mask uses the same Monday-first layout as {@link Rule}: bit 0 is
 * Monday and bit 6 is Sunday. A second mask selects which restriction families
 * are paused. Older installations have no scope key; a non-empty weekday mask is
 * interpreted as the original "all restrictions" behavior.</p>
 */
public final class FreezeWeekdayPolicy {
    private static final String FILE = "afs_guard";
    private static final String KEY_DAYS = "freeze_weekday_disabled_mask";
    private static final String KEY_SCOPES = "freeze_weekday_pause_scopes";

    public static final int SCOPE_SCHEDULE = 1;
    public static final int SCOPE_QUOTA = 1 << 1;
    public static final int SCOPE_OR_SET = 1 << 2;
    public static final int SCOPE_STUDY = 1 << 3;
    public static final int SCOPE_PENALTY = 1 << 4;
    public static final int ALL_SCOPES = SCOPE_SCHEDULE | SCOPE_QUOTA | SCOPE_OR_SET
            | SCOPE_STUDY | SCOPE_PENALTY;

    private FreezeWeekdayPolicy() {
    }

    public static int disabledDays(Context context) {
        if (context == null) return 0;
        return context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
                .getInt(KEY_DAYS, 0) & Rule.ALL_DAYS;
    }

    public static int pauseScopes(Context context) {
        if (context == null) return ALL_SCOPES;
        android.content.SharedPreferences prefs =
                context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
        if (!prefs.contains(KEY_SCOPES)) return ALL_SCOPES;
        return prefs.getInt(KEY_SCOPES, ALL_SCOPES) & ALL_SCOPES;
    }

    public static String summary(Context context) {
        int mask = disabledDays(context);
        if (mask == 0) return T.s("未开启 · 调整时段内可设置全局或自定义暂停");
        String days = Rule.daysText(mask);
        String scopes = scopesText(pauseScopes(context));
        return disabledToday(context, GuardClock.now(context))
                ? T.f("今日暂停 · %s · %s", days, scopes)
                : T.f("%s · %s", days, scopes);
    }

    /**
     * Policy edits share the same adjustment-window gate as schedules and quota
     * membership. This protects callers other than the freeze-board dialog too.
     */
    public static boolean editable(Context context) {
        return context != null && TimeWindow.canEdit(context);
    }

    public static void setPolicy(Context context, int days, int scopes) {
        if (context == null) return;
        if (!editable(context)) {
            throw new IllegalStateException(T.f(
                    "全局按星期停用只能在调整时段 %s 内修改", TimeWindow.label(context)));
        }
        if (!context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
                .putInt(KEY_DAYS, days & Rule.ALL_DAYS)
                .putInt(KEY_SCOPES, scopes & ALL_SCOPES)
                .commit()) {
            throw new IllegalStateException(T.s("全局按星期停用未能保存"));
        }
    }

    /** Backward-compatible entry point: selecting weekdays pauses all five rails. */
    public static void setDisabledDays(Context context, int mask) {
        setPolicy(context, mask, ALL_SCOPES);
    }

    public static boolean disabledToday(Context context, Calendar now) {
        return paused(context, ALL_SCOPES, now);
    }

    public static boolean pausesSchedule(Context context, Calendar now) {
        return paused(context, SCOPE_SCHEDULE, now);
    }

    public static boolean pausesQuota(Context context, Calendar now) {
        return paused(context, SCOPE_QUOTA, now);
    }

    public static boolean pausesOrSet(Context context, Calendar now) {
        return paused(context, SCOPE_OR_SET, now);
    }

    public static boolean pausesStudy(Context context, Calendar now) {
        return paused(context, SCOPE_STUDY, now);
    }

    public static boolean pausesPenalty(Context context, Calendar now) {
        return paused(context, SCOPE_PENALTY, now);
    }

    private static boolean paused(Context context, int scope, Calendar now) {
        return pausedOn(disabledDays(context), pauseScopes(context), scope, now);
    }

    static boolean disabledOn(int mask, Calendar now) {
        return now != null && (mask & (1 << Rule.monIndex(now))) != 0;
    }

    static boolean pausedOn(int days, int scopes, int scope, Calendar now) {
        return (scopes & scope) != 0 && disabledOn(days, now);
    }

    static String scopesText(int scopes) {
        scopes &= ALL_SCOPES;
        if (scopes == ALL_SCOPES) return T.s("全局暂停");
        StringBuilder out = new StringBuilder(T.s("自定义："));
        boolean first = true;
        int[] values = {SCOPE_SCHEDULE, SCOPE_QUOTA, SCOPE_OR_SET, SCOPE_STUDY, SCOPE_PENALTY};
        String[] labels = {
            T.s("冻结时段"), T.s("时间限额"), T.s("或集"), T.s("学习联动"), T.s("罚时")
        };
        for (int i = 0; i < values.length; i++) {
            if ((scopes & values[i]) == 0) continue;
            if (!first) out.append(T.s("、"));
            out.append(labels[i]);
            first = false;
        }
        if (first) out.append(T.s("未选择"));
        return out.toString();
    }

    static String revision(Context context) {
        return disabledDays(context) + ":" + pauseScopes(context);
    }

    /** The weekday policy is re-evaluated at each local midnight. */
    static long nextMidnight(Calendar now) {
        if (now == null) return Long.MAX_VALUE;
        Calendar next = (Calendar) now.clone();
        next.add(Calendar.DAY_OF_MONTH, 1);
        next.set(Calendar.HOUR_OF_DAY, 0);
        next.set(Calendar.MINUTE, 0);
        next.set(Calendar.SECOND, 0);
        next.set(Calendar.MILLISECOND, 0);
        return next.getTimeInMillis();
    }
}
