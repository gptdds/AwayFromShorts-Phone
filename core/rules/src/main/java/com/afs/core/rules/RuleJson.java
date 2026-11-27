package com.afs.core.rules;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public final class RuleJson {
    public static final int VERSION = 1;

    private RuleJson() {}

    public static boolean packageName(String name) {
        return name != null && name.length() <= 255
                && name.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+");
    }

    private static int integer(Object value, int minimum, int maximum) {
        if (!(value instanceof Number)) throw new IllegalArgumentException("规则必须使用整数");
        double number = ((Number) value).doubleValue();
        if (number != Math.floor(number) || number < minimum || number > maximum)
            throw new IllegalArgumentException("规则数值超出范围");
        return (int) number;
    }

    public static WeeklyRule read(JSONObject value) throws JSONException {
        if (value == null) throw new IllegalArgumentException("缺少应用规则");
        int days = integer(value.get("d"), 0, WeeklyRule.ALL_DAYS);
        Object freeze = value.get("freeze");
        if (!(freeze instanceof Boolean)) throw new IllegalArgumentException("规则模式无效");
        JSONArray segments = value.getJSONArray("w");
        if (segments.length() > 64) throw new IllegalArgumentException("时间段过多");
        int[][] windows = new int[segments.length()][2];
        for (int index = 0; index < windows.length; index++) {
            JSONArray segment = segments.getJSONArray(index);
            if (segment.length() != 2) throw new IllegalArgumentException("每个时段需要起止时间");
            windows[index][0] = integer(segment.get(0), 0, 1439);
            windows[index][1] = integer(segment.get(1), 0, 1439);
        }
        return (Boolean) freeze ? WeeklyRule.freeze(days, windows) : WeeklyRule.allow(days, windows);
    }

    public static JSONObject write(WeeklyRule rule) throws JSONException {
        JSONArray windows = new JSONArray();
        for (int index = 0; index < rule.windowCount(); index++)
            windows.put(new JSONArray().put(rule.startAt(index)).put(rule.endAt(index)));
        return new JSONObject().put("d", rule.days()).put("freeze", rule.freezeMode()).put("w", windows);
    }

    public static Map<String, WeeklyRule> parse(String raw) throws JSONException {
        JSONObject document = new JSONObject(raw);
        if (integer(document.get("version"), 1, Integer.MAX_VALUE) != VERSION)
            throw new IllegalArgumentException("不支持的规则版本");
        JSONObject entries = document.getJSONObject("rules");
        if (entries.length() > 2000) throw new IllegalArgumentException("应用数量过多");
        Map<String, WeeklyRule> rules = new LinkedHashMap<>();
        Iterator<String> names = entries.keys();
        while (names.hasNext()) {
            String name = names.next();
            if (!packageName(name)) throw new IllegalArgumentException("应用包名无效");
            rules.put(name, read(entries.getJSONObject(name)));
        }
        return rules;
    }

    public static String encode(Map<String, WeeklyRule> rules) throws JSONException {
        JSONObject entries = new JSONObject();
        for (Map.Entry<String, WeeklyRule> entry : rules.entrySet())
            entries.put(entry.getKey(), write(entry.getValue()));
        String raw = new JSONObject().put("version", VERSION).put("rules", entries).toString();
        parse(raw);
        return raw;
    }

    public static String migrate(String existing, Set<String> targets, int days, int start, int end)
            throws JSONException {
        if (existing != null) {
            parse(existing);
            return existing;
        }
        Map<String, WeeklyRule> rules = new LinkedHashMap<>();
        WeeklyRule legacy = WeeklyRule.allow(days, new int[][]{{start, end}});
        for (String target : targets) rules.put(target, legacy);
        return encode(rules);
    }
}
