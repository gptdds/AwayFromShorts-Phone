package com.afs.guard;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Collection;
import java.util.Map;
import java.util.Set;

/**
 * Strict reader for the SharedPreferences payloads that phase three migrates.
 *
 * Unlike RuleStore.parseAll(), malformed input is rejected instead of being
 * treated as an empty rule set.  This class has no Android dependencies so the
 * parser can be exercised by the offline regression suite.
 */
public final class LegacySnapshotParser {
    private LegacySnapshotParser() {
    }

    public static final class RulesSnapshot {
        public final Map<String, Rule> rules;
        public final Map<String, Long> addedAt;

        RulesSnapshot(Map<String, Rule> rules, Map<String, Long> addedAt) {
            this.rules = rules;
            this.addedAt = addedAt;
        }
    }

    public static final class SlotsSnapshot {
        public final Map<String, Rule> slots;

        SlotsSnapshot(Map<String, Rule> slots) {
            this.slots = slots;
        }
    }

    public static RulesSnapshot parseRules(String raw) {
        Map<String, Rule> rules = new LinkedHashMap<>();
        Map<String, Long> addedAt = new LinkedHashMap<>();
        if (raw == null || raw.trim().isEmpty()) {
            return new RulesSnapshot(rules, addedAt);
        }
        JSONObject root = object(raw, "rules");
        Iterator<String> keys = root.keys();
        while (keys.hasNext()) {
            String pkg = keys.next();
            if (pkg.trim().isEmpty()) {
                throw invalid("rules contains an empty package name");
            }
            JSONObject object = root.optJSONObject(pkg);
            if (object == null) {
                throw invalid("rules." + pkg + " must be an object");
            }
            Rule rule = parseRule(object, "rules." + pkg);
            rules.put(pkg, rule);
            addedAt.put(pkg, optionalLong(object, "at", 0L, "rules." + pkg + ".at"));
        }
        return new RulesSnapshot(rules, addedAt);
    }

    public static RulesSnapshot legacyGlobal(int days, int start, int end,
                                             Collection<String> targets) {
        if (days < 0 || days > Rule.ALL_DAYS) {
            throw invalid("legacy days must be between 0 and " + Rule.ALL_DAYS);
        }
        validateMinute(start, "legacy start_min");
        validateMinute(end, "legacy end_min");
        Rule rule = new Rule(days, start, end);
        Map<String, Rule> rules = new LinkedHashMap<>();
        Map<String, Long> addedAt = new LinkedHashMap<>();
        if (targets != null) {
            for (String pkg : targets) {
                if (pkg == null || pkg.trim().isEmpty()) {
                    throw invalid("legacy targets contains an empty package name");
                }
                rules.put(pkg, rule);
                addedAt.put(pkg, 0L);
            }
        }
        return new RulesSnapshot(rules, addedAt);
    }

    public static SlotsSnapshot parseSlots(String raw) {
        Map<String, Rule> slots = new LinkedHashMap<>();
        if (raw == null || raw.trim().isEmpty()) {
            return new SlotsSnapshot(slots);
        }
        JSONArray root;
        try {
            root = new JSONArray(raw);
        } catch (Exception error) {
            throw invalid("freeze_slots must be a JSON array", error);
        }
        Set<String> scheduleKeys = new LinkedHashSet<>();
        for (int i = 0; i < root.length(); i++) {
            JSONObject object = root.optJSONObject(i);
            if (object == null) {
                throw invalid("freeze_slots[" + i + "] must be an object");
            }
            String id = requiredString(object, "id", "freeze_slots[" + i + "].id");
            if (id.trim().isEmpty()) {
                throw invalid("freeze_slots[" + i + "].id must not be empty");
            }
            if (slots.containsKey(id)) {
                throw invalid("freeze_slots contains duplicate id: " + id);
            }
            Rule rule = parseRule(object, "freeze_slots[" + i + "]", true);
            String scheduleKey = FreezeSlotStore.scheduleKey(rule);
            if (!scheduleKeys.add(scheduleKey)) {
                throw invalid("freeze_slots contains a duplicate schedule: " + id);
            }
            slots.put(id, rule);
        }
        return new SlotsSnapshot(slots);
    }

    private static Rule parseRule(JSONObject object, String path) {
        return parseRule(object, path, false);
    }

    private static Rule parseRule(JSONObject object, String path, boolean slot) {
        String daysKey = slot && object.has("days") ? "days" : "d";
        int days = optionalInt(object, daysKey, Rule.ALL_DAYS, path + "." + daysKey);
        if (days < 0 || days > Rule.ALL_DAYS) {
            throw invalid(path + "." + daysKey + " must be between 0 and " + Rule.ALL_DAYS);
        }
        boolean freeze = optionalBoolean(object, "freeze", slot, path + ".freeze");
        boolean gate = slot || optionalBoolean(object, "g", true, path + ".g");

        String windowsKey = slot && object.has("windows") ? "windows" : "w";
        JSONArray array = object.optJSONArray(windowsKey);
        if (array == null && object.has(windowsKey)) {
            throw invalid(path + "." + windowsKey + " must be an array");
        }
        if (array != null) {
            int[][] windows = new int[array.length()][2];
            for (int i = 0; i < array.length(); i++) {
                JSONArray pair = array.optJSONArray(i);
                if (pair == null || pair.length() < 2) {
                    throw invalid(path + "." + windowsKey + "[" + i
                            + "] must contain start and end");
                }
                windows[i][0] = minute(pair, 0, path + "." + windowsKey + "[" + i + "][0]");
                windows[i][1] = minute(pair, 1, path + "." + windowsKey + "[" + i + "][1]");
            }
            return new Rule(days, windows, gate, freeze);
        }

        int start = optionalInt(object, "s", RuleStore.FALLBACK_START, path + ".s");
        int end = optionalInt(object, "e", RuleStore.FALLBACK_END, path + ".e");
        validateMinute(start, path + ".s");
        validateMinute(end, path + ".e");
        return new Rule(days, new int[][]{{start, end}}, gate, freeze);
    }

    private static JSONObject object(String raw, String path) {
        try {
            return new JSONObject(raw);
        } catch (Exception error) {
            throw invalid(path + " must be valid JSON", error);
        }
    }

    private static int minute(JSONArray pair, int index, String path) {
        Object value = pair.opt(index);
        if (!(value instanceof Number)) {
            throw invalid(path + " must be a number");
        }
        int minute = integral((Number) value, path);
        validateMinute(minute, path);
        return minute;
    }

    private static void validateMinute(int minute, String path) {
        if (minute < 0 || minute >= 24 * 60) {
            throw invalid(path + " must be between 0 and 1439");
        }
    }

    private static int optionalInt(JSONObject object, String key, int fallback, String path) {
        if (!object.has(key)) return fallback;
        Object value = object.opt(key);
        if (!(value instanceof Number)) throw invalid(path + " must be a number");
        return integral((Number) value, path);
    }

    private static long optionalLong(JSONObject object, String key, long fallback, String path) {
        if (!object.has(key)) return fallback;
        Object value = object.opt(key);
        if (!(value instanceof Number)) throw invalid(path + " must be a number");
        double number = ((Number) value).doubleValue();
        if (number != Math.rint(number)) throw invalid(path + " must be an integer");
        long result = ((Number) value).longValue();
        if (result < 0L) throw invalid(path + " must not be negative");
        return result;
    }

    private static boolean optionalBoolean(JSONObject object, String key, boolean fallback,
                                           String path) {
        if (!object.has(key)) return fallback;
        Object value = object.opt(key);
        if (!(value instanceof Boolean)) throw invalid(path + " must be a boolean");
        return (Boolean) value;
    }

    private static String requiredString(JSONObject object, String key, String path) {
        Object value = object.opt(key);
        if (!(value instanceof String)) throw invalid(path + " must be a string");
        return (String) value;
    }

    private static int integral(Number value, String path) {
        double number = value.doubleValue();
        if (number != Math.rint(number) || number < Integer.MIN_VALUE
                || number > Integer.MAX_VALUE) {
            throw invalid(path + " must be an integer");
        }
        return value.intValue();
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException("Invalid legacy snapshot: " + message);
    }

    private static IllegalArgumentException invalid(String message, Throwable cause) {
        return new IllegalArgumentException("Invalid legacy snapshot: " + message, cause);
    }
}
