package com.afs.guard;

import android.os.PowerManager;
import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 冻结前弹窗提醒（可选，默认关，提前量可调）。
 *
 * <p>为什么靠 GuardService 的节拍而不是闹钟：规则可能随时被改，闹钟得跟着重排；
 * 守护节拍本来就 30 秒走一次，顺手问一句「有没有快要翻转的」即可。
 * 弹窗本身走 {@link AlertPopupActivity}：能直接弹就弹，后台启动受系统限制时
 * 发高优先级通知，回到 App 前台再补一次；多个应用同一边界合成一条提醒。</p>
 *
 * <p>只提醒「放行窗口即将结束」这一种翻转 —— 也就是马上要开始冻结的那一刻；
 * 冻结结束（即将放行）不打扰，那是好消息。</p>
 */
public final class FreezeWarn {
    private FreezeWarn() {
    }

    static final int DEFAULT_MINUTES = 15;
    static final int MIN_MINUTES = 1;
    static final int MAX_MINUTES = 180;
    private static final long MINUTE_MS = 60 * 1000L;
    private static final String KEY_MINUTES = "freeze_warn_minutes";
    /** ±45 秒容差：节拍 5–30 秒一跳，宽松一点也不会一天弹多次（有去重兜底）。 */
    private static final long TOLERANCE_MS = 45 * 1000L;
    private static final String KEY_WARNED_DAY = "alert_warn_day";
    private static final String KEY_WARNED = "alert_warned";

    static boolean enabled(Context c) {
        return prefs(c).getBoolean("freeze_warn_on", false);
    }

    static void setEnabled(Context c, boolean on) {
        prefs(c).edit().putBoolean("freeze_warn_on", on).apply();
    }

    static int minutes(Context c) {
        return normalizeMinutes(prefs(c).getInt(KEY_MINUTES, DEFAULT_MINUTES));
    }

    static void setMinutes(Context c, int value) {
        prefs(c).edit().putInt(KEY_MINUTES, normalizeMinutes(value)).apply();
    }

    /** 旧设置或异常输入回到默认值；正常范围按整数分钟原样保留。 */
    static int normalizeMinutes(int value) {
        return value >= MIN_MINUTES && value <= MAX_MINUTES ? value : DEFAULT_MINUTES;
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("afs_alert", Context.MODE_PRIVATE);
    }

    static void tick(Context context) {
        if (!enabled(context)) {
            return;
        }
        long now = System.currentTimeMillis();
        PowerManager power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        if (power != null && !power.isInteractive()) {
            return;
        }
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(now);
        String today = DayBoundary.key(now, 0);
        SharedPreferences sp = prefs(context);
        pruneWarnings(sp, today);
        int minutes = minutes(context);
        long warnBeforeMs = minutes * MINUTE_MS;
        Set<Warned> due = new LinkedHashSet<>();
        for (Map.Entry<String, Rule> entry : RuleStore.all(context).entrySet()) {
            Rule rule = entry.getValue();
            if (rule == null || rule.windowCount() == 0) {
                continue;
            }
            long flip = rule.nextFlip(cal);
            long gap = flip - now;
            if (gap < warnBeforeMs - TOLERANCE_MS || gap > warnBeforeMs + TOLERANCE_MS) {
                continue;
            }
            // 翻转后 inWindow=false 才是「放行窗口要结束、马上冻结」
            Calendar probe = Calendar.getInstance();
            probe.setTimeInMillis(flip);
            if (rule.inWindow(probe)) {
                continue;
            }
            Warned item = new Warned(entry.getKey(), flip);
            if (!sp.getStringSet(KEY_WARNED, java.util.Collections.<String>emptySet()).contains(item.key())) {
                due.add(item);
            }
        }
        if (due.isEmpty()) {
            return;
        }
        List<String> names = new ArrayList<>();
        for (Warned item : due) names.add(appLabel(context, item.pkg));
        StringBuilder body = new StringBuilder(T.f(
                "%s 的放行时间还剩 %d 分钟，到点就会冻结。", joinNames(names), minutes));
        Set<String> marked = new LinkedHashSet<>(sp.getStringSet(KEY_WARNED, java.util.Collections.<String>emptySet()));
        StringBuilder token = new StringBuilder(today);
        for (Warned item : due) {
            marked.add(item.key());
            token.append('|').append(item.key());
        }
        sp.edit().putString(KEY_WARNED_DAY, today).putStringSet(KEY_WARNED, marked).commit();
        AlertPopupActivity.show(context, "freeze:" + token, T.s("即将冻结"), body.toString());
    }

    /** 旧版按包名存了一堆 warned_* 布尔值；这里按自然日只保留一天的提醒去重键。 */
    private static void pruneWarnings(SharedPreferences sp, String today) {
        SharedPreferences.Editor edit = sp.edit();
        if (!today.equals(sp.getString(KEY_WARNED_DAY, ""))) {
            edit.remove(KEY_WARNED);
        }
        boolean changed = false;
        for (String key : sp.getAll().keySet()) {
            if (key.startsWith("warned_") && !key.equals(KEY_WARNED_DAY)) {
                edit.remove(key);
                changed = true;
            }
        }
        if (changed || !today.equals(sp.getString(KEY_WARNED_DAY, ""))) {
            edit.putString(KEY_WARNED_DAY, today).apply();
        }
    }

    private static String joinNames(List<String> names) {
        StringBuilder out = new StringBuilder();
        for (String name : names) {
            if (out.length() > 0) out.append(T.s("、"));
            out.append(name);
        }
        return out.toString();
    }

    private static final class Warned {
        final String pkg;
        final long flip;

        Warned(String pkg, long flip) {
            this.pkg = pkg;
            this.flip = flip;
        }

        String key() {
            return pkg + "@" + flip;
        }

        @Override public boolean equals(Object other) {
            return other instanceof Warned && key().equals(((Warned) other).key());
        }

        @Override public int hashCode() {
            return key().hashCode();
        }
    }

    private static String appLabel(Context context, String pkg) {
        try {
            android.content.pm.PackageManager pm = context.getPackageManager();
            android.content.pm.ApplicationInfo info = pm.getApplicationInfo(pkg, 0);
            CharSequence label = pm.getApplicationLabel(info);
            if (label != null) {
                return label.toString();
            }
        } catch (Throwable ignored) {
        }
        return pkg;
    }
}
