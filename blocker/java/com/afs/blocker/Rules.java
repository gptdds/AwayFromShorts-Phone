package com.afs.blocker;

import android.content.Context;
import android.content.SharedPreferences;

import com.afs.core.rules.RuleJson;
import com.afs.core.rules.WeeklyRule;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;

public final class Rules {
    static final String KEY = "app_rules_v1";
    private static String cachedRaw;
    private static Map<String, WeeklyRule> cached;

    private Rules() {}

    public static synchronized Map<String, WeeklyRule> all(Context context) {
        SharedPreferences prefs = Store.sp(context);
        String raw = prefs.getString(KEY, null);
        try {
            if (raw == null) {
                raw = RuleJson.migrate(null, Store.legacyTargets(context),
                        prefs.getInt("days", WeeklyRule.ALL_DAYS), Store.startMin(context), Store.endMin(context));
                if (!prefs.edit().putString(KEY, raw).putString("rules_before_migration", raw).commit())
                    throw new IllegalStateException("无法保存迁移结果，旧配置已保留");
            }
            if (!raw.equals(cachedRaw)) {
                cached = RuleJson.parse(raw);
                cachedRaw = raw;
            }
            return new LinkedHashMap<>(cached);
        } catch (Exception error) {
            throw new IllegalStateException("应用规则读取失败，未覆盖原配置：" + error.getMessage(), error);
        }
    }

    public static WeeklyRule defaultRule(Context context) {
        return WeeklyRule.allow(WeeklyRule.ALL_DAYS,
                new int[][]{{Store.startMin(context), Store.endMin(context)}});
    }

    public static boolean blocked(Context context, String pkg, long now) {
        try {
            WeeklyRule rule = all(context).get(pkg);
            return rule != null && Guard.isBlocked(rule, now, Store.forceUntil(context), TimeZone.getDefault());
        } catch (IllegalStateException error) {
            return Store.legacyTargets(context).contains(pkg);
        }
    }

    public static boolean locked(Context context) {
        if (Store.forceActive(context)) return true;
        if (!Store.isConfigured(context)) return false;
        long now = System.currentTimeMillis();
        try {
            for (WeeklyRule rule : all(context).values())
                if (rule.blocked(now, TimeZone.getDefault())) return true;
            return false;
        } catch (IllegalStateException error) {
            return true;
        }
    }

    static void requireEditable(Context context) {
        if (locked(context)) throw new IllegalStateException("锁定中，请在放行后修改规则");
    }

    public static synchronized void put(Context context, String pkg, WeeklyRule rule) {
        requireEditable(context);
        Map<String, WeeklyRule> rules = all(context);
        if (!rules.containsKey(pkg)) throw new IllegalArgumentException("请先选择应用");
        rules.put(pkg, rule);
        save(context, rules, true);
    }

    public static synchronized void setTargets(Context context, Set<String> targets) {
        requireEditable(context);
        Map<String, WeeklyRule> before = all(context);
        Map<String, WeeklyRule> after = new LinkedHashMap<>();
        for (String pkg : targets) after.put(pkg, before.containsKey(pkg) ? before.get(pkg) : defaultRule(context));
        save(context, after, true);
    }

    static synchronized void save(Context context, Map<String, WeeklyRule> rules, boolean configured) {
        save(context,rules,configured,null,null);
    }

    static synchronized void setWindow(Context context,int start,int end) {
        requireEditable(context);
        Map<String,WeeklyRule> rules = all(context);
        for (String pkg : rules.keySet()) rules.put(pkg,WeeklyRule.allow(127,new int[][]{{start,end}}));
        save(context,rules,true,start,end);
    }

    private static void save(Context context,Map<String,WeeklyRule> rules,boolean configured,Integer start,Integer end) {
        try {
            String raw = RuleJson.encode(rules);
            requireEditable(context);
            SharedPreferences.Editor edit = Store.sp(context).edit().putString(KEY, raw)
                    .putStringSet("targets", new java.util.LinkedHashSet<>(rules.keySet()));
            if (configured) edit.putBoolean("configured", true);
            if (start != null) edit.putInt("start_min",start).putInt("end_min",end);
            if (!edit.commit()) throw new IllegalStateException("无法写入规则");
        } catch (org.json.JSONException error) {
            throw new IllegalArgumentException("规则格式错误", error);
        }
        BlockerService.recheck();
    }
}
