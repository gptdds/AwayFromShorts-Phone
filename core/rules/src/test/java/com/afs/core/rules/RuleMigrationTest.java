package com.afs.core.rules;

import org.json.JSONObject;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;

public final class RuleMigrationTest {
    private static int checks;

    private static void check(boolean value, String label) {
        checks++;
        if (!value) throw new AssertionError(label);
    }

    private static void reject(String raw) throws Exception {
        try {
            RuleJson.parse(raw);
        } catch (IllegalArgumentException | org.json.JSONException expected) {
            checks++;
            return;
        }
        throw new AssertionError("accepted invalid rules: " + raw);
    }

    public static void main(String[] args) throws Exception {
        String migrated = RuleJson.migrate(null, new LinkedHashSet<>(Arrays.asList("com.test.first", "com.test.second")),
                31, 1320, 60);
        Map<String, WeeklyRule> entries = RuleJson.parse(migrated);
        check(entries.size() == 2, "each old selected app gets a rule");
        WeeklyRule expected = WeeklyRule.allow(31, new int[][]{{1320, 60}});
        for (int day = 0; day < 7; day++) for (int minute = 0; minute < 1440; minute++)
            check(entries.get("com.test.first").blocked(day, minute) == expected.blocked(day, minute),
                    "migration preserves original minute " + day + "/" + minute);
        check(RuleJson.migrate(migrated, Collections.singleton("com.test.third"), 127, 0, 0).equals(migrated),
                "repeated migration preserves edited rules byte for byte");
        check(RuleJson.parse(RuleJson.migrate(null, Collections.emptySet(), 127, 1200, 1380)).isEmpty(),
                "explicit empty target set stays empty in new schema");
        entries.put("com.test.first", WeeklyRule.freeze(96, new int[][]{{0, 0}}));
        String roundtrip = RuleJson.encode(entries);
        check(RuleJson.parse(roundtrip).get("com.test.first").freezeMode(), "mode retained");
        reject("broken");
        reject("{\"version\":2,\"rules\":{}}");
        JSONObject invalid = new JSONObject(roundtrip);
        invalid.getJSONObject("rules").getJSONObject("com.test.first").put("d", 128);
        reject(invalid.toString());
        invalid.getJSONObject("rules").getJSONObject("com.test.first").put("d", 1.5);
        reject(invalid.toString());
        invalid = new JSONObject(roundtrip);
        invalid.getJSONObject("rules").put("com.test.app;reboot", RuleJson.write(expected));
        reject(invalid.toString());
        invalid = new JSONObject(roundtrip);
        invalid.getJSONObject("rules").getJSONObject("com.test.first").getJSONArray("w").getJSONArray(0).put(1, 1440);
        reject(invalid.toString());
        try {
            RuleJson.migrate("corrupt", Collections.singleton("com.test.new"), 127, 0, 0);
            throw new AssertionError("corrupt existing data must not be replaced");
        } catch (org.json.JSONException expectedError) { checks++; }
        System.out.println("Rule migration: " + checks + " checks passed");
    }
}
