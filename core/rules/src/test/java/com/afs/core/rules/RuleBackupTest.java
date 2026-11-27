package com.afs.core.rules;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import org.json.JSONObject;

public final class RuleBackupTest {
    private static int checks;
    private static void check(boolean value,String label) { checks++; if (!value) throw new AssertionError(label); }

    public static void main(String[] args) throws Exception {
        Map<String,WeeklyRule> rules = new LinkedHashMap<>();
        rules.put("com.test.first",WeeklyRule.freeze(31,new int[][]{{1320,60},{480,720}}));
        rules.put("com.test.second",WeeklyRule.allow(96,new int[][]{{0,0}}));
        RuleBackupDocument document = new RuleBackupDocument("device-owner",rules,Collections.singleton("com.test.second"),0,1260,1380);
        String encoded = document.encode();
        RuleBackupDocument copy = RuleBackupDocument.parse(encoded,"device-owner");
        check(copy.rules.get("com.test.first").signature().equals(rules.get("com.test.first").signature()),"mode, weekday and windows roundtrip");
        check(copy.keyApps.equals(document.keyApps),"learning exemptions retained");
        check(!encoded.contains("token") && !encoded.contains("keystore") && !encoded.contains("permission"),"only whitelisted fields exported");
        Map<String,WeeklyRule> current = new LinkedHashMap<>();
        current.put("com.test.third",WeeklyRule.allow(127,new int[0][]));
        check(copy.combined(current,false).size() == 3,"merge retains unrelated apps");
        check(copy.combined(current,true).size() == 2,"replace uses imported scope");
        check(current.size() == 1,"preview and merging do not mutate live state");
        check(copy.preview(current).contains("新增 2"),"preview counts added entries");
        try { RuleBackupDocument.parse(encoded,"accessibility"); throw new AssertionError("wrong variant accepted"); }
        catch (IllegalArgumentException expected) { checks++; }
        JSONObject future = new JSONObject(encoded).put("version",2);
        try { RuleBackupDocument.parse(future.toString(),"device-owner"); throw new AssertionError("future version accepted"); }
        catch (IllegalArgumentException expected) { checks++; }
        JSONObject invalid = new JSONObject(encoded).put("force_until",1.25);
        try { RuleBackupDocument.parse(invalid.toString(),"device-owner"); throw new AssertionError("fractional time accepted"); }
        catch (IllegalArgumentException expected) { checks++; }
        try { RuleBackupDocument.read(new java.io.ByteArrayInputStream(new byte[1024*1024+1])); throw new AssertionError("oversized input accepted"); }
        catch (java.io.IOException expected) { checks++; }
        String text = RuleBackupDocument.read(new java.io.ByteArrayInputStream(encoded.getBytes("UTF-8")));
        check(text.equals(encoded),"UTF-8 stream roundtrip");
        System.out.println("Rule backup: " + checks + " checks passed");
    }
}
