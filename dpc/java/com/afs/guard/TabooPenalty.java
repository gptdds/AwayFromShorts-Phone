package com.afs.guard;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * The single implementation of a taboo-card penalty.
 *
 * <p>Taboo claims and AI/external configuration changes both enter here, so the two paths
 * cannot drift apart: the same minute setting, the same freeze/quota switches and the same
 * error handling are used.</p>
 */
final class TabooPenalty {
    private TabooPenalty() {
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(StudyGate.TABOO_SP, Context.MODE_PRIVATE);
    }

    static int minutes(Context c) {
        return prefs(c).getInt("penalty_min", PenaltyStore.DEFAULT_TABOO_MIN);
    }

    static boolean freezeEnabled(Context c) {
        return prefs(c).getBoolean("penalty_freeze", true);
    }

    static boolean quotaEnabled(Context c) {
        return prefs(c).getBoolean("penalty_quota", true);
    }

    static String summary(Context c) {
        int min = minutes(c);
        boolean freeze = freezeEnabled(c);
        boolean quota = quotaEnabled(c);
        if (freeze && quota) return T.f("冻结 +%d 分钟 · 限额 -%d 分钟", min, min);
        if (freeze) return T.f("只延长冻结 +%d 分钟", min);
        if (quota) return T.f("只减少时间限额 %d 分钟", min);
        return T.s("不追加冻结或限额");
    }

    /**
     * Apply one penalty and return enough detail for a confirmation toast.
     * The caller is responsible for recording the taboo/AI event itself.
     */
    static Result apply(Context c, long now) {
        int min = minutes(c);
        boolean freeze = freezeEnabled(c);
        boolean quota = quotaEnabled(c);
        if (freeze) StudyGate.extendTaboo(c, min * 60_000L, now);
        int cells = 0;
        String quotaError = null;
        if (quota) {
            try {
                cells = DailyQuotaStore.addTabooQuotaPenalty(c, min);
            } catch (RuntimeException error) {
                String message = error.getMessage();
                quotaError = message == null || message.trim().isEmpty() ? T.s("未知错误") : message;
            }
        }
        return new Result(min, freeze, quota, cells, quotaError, StudyGate.tabooUntil(c));
    }

    static final class Result {
        final int minutes;
        final boolean freeze;
        final boolean quota;
        final int quotaCells;
        final String quotaError;
        final long until;

        Result(int minutes, boolean freeze, boolean quota, int quotaCells,
               String quotaError, long until) {
            this.minutes = minutes;
            this.freeze = freeze;
            this.quota = quota;
            this.quotaCells = quotaCells;
            this.quotaError = quotaError;
            this.until = until;
        }

        String freezeText() {
            return freeze ? T.f("冻结 +%d 分钟", minutes) : T.s("不延长冻结");
        }

        String quotaText() {
            if (!quota) return T.s(" · 不扣限额");
            if (quotaError != null) return T.f(" · 时间限额扣减失败：%s", quotaError);
            if (quotaCells > 0) return T.f(" · 时间限额 -%d 分钟", minutes);
            return T.s(" · 时间限额未配置");
        }
    }
}
