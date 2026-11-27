package com.afs.guard;

import android.content.Context;
import android.content.SharedPreferences;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Counts successful AI/ops/import edits to the blocking configuration.
 *
 * <p>A count is recorded only after the new configuration is actually different from the
 * previous one. Ordinary app-internal edits do not come through this store.</p>
 */
final class AiAdjustmentStore {
    static final String SP = "afs_ai_adjustment";
    private static final String K_TOTAL = "total";
    private static final String K_TODAY = "today";
    private static final String K_DAY = "day";
    private static final String K_LAST_AT = "last_at";
    private static final String K_LAST_SOURCE = "last_source";

    private AiAdjustmentStore() {
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(SP, Context.MODE_PRIVATE);
    }

    static synchronized int record(Context c, String source) {
        if (c == null) return 0;
        SharedPreferences p = prefs(c);
        long now = GuardClock.now(c).getTimeInMillis();
        String today = dayOf(c, now);
        int total = p.getInt(K_TOTAL, 0) + 1;
        int daily = today.equals(p.getString(K_DAY, "")) ? p.getInt(K_TODAY, 0) + 1 : 1;
        if (!p.edit().putInt(K_TOTAL, total).putInt(K_TODAY, daily)
                .putString(K_DAY, today).putLong(K_LAST_AT, now)
                .putString(K_LAST_SOURCE, source == null ? "" : source).commit()) {
            throw new IllegalStateException(T.s("AI 调整次数未能保存"));
        }
        return total;
    }

    static int total(Context c) {
        return c == null ? 0 : prefs(c).getInt(K_TOTAL, 0);
    }

    static int today(Context c) {
        return today(c, GuardClock.now(c).getTimeInMillis());
    }

    static int today(Context c, long now) {
        if (c == null) return 0;
        SharedPreferences p = prefs(c);
        String day = dayOf(c, now);
        return day.equals(p.getString(K_DAY, "")) ? p.getInt(K_TODAY, 0) : 0;
    }

    static long lastAt(Context c) {
        return c == null ? 0L : prefs(c).getLong(K_LAST_AT, 0L);
    }

    static String summary(Context c) {
        return T.f("今日 %d 次 · 累计 %d 次", today(c), total(c));
    }

    static String detail(Context c) {
        long at = lastAt(c);
        String source = prefs(c).getString(K_LAST_SOURCE, "");
        String last = at <= 0L ? T.s("还没有记录")
                : new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(new Date(at));
        return T.f("今天通过 AI/外部通道调整了 %d 次；累计 %d 次。\n每次实际改变屏蔽设置，都会按当前禁忌卡惩罚设置延长冻结并扣减时间限额。\n最近一次：%s%s",
                today(c), total(c), last,
                source == null || source.isEmpty() ? "" : T.f("（%s）", source));
    }

    private static String dayOf(Context c, long now) {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)
                .format(new Date(now));
    }
}
