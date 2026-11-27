package com.afs.guard;

import java.util.Arrays;
import java.util.LinkedHashSet;

/** Strict migration parsing must reject damaged data instead of treating it as empty. */
public final class LegacySnapshotParserTest {
    private static int checks;

    private static void ok(boolean value, String label) {
        checks++;
        if (!value) throw new AssertionError(label);
    }

    private static void eq(Object expected, Object actual, String label) {
        checks++;
        if (!expected.equals(actual)) {
            throw new AssertionError(label + ": " + actual + " != " + expected);
        }
    }

    private static void fails(Runnable work, String label) {
        checks++;
        try {
            work.run();
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError(label + " should have failed");
    }

    public static void main(String[] args) {
        LegacySnapshotParser.RulesSnapshot empty = LegacySnapshotParser.parseRules("");
        ok(empty.rules.isEmpty(), "blank rules are a valid first install");

        LegacySnapshotParser.RulesSnapshot v1 = LegacySnapshotParser.parseRules(
                "{\"com.example.v1\":{\"d\":31,\"s\":1320,\"e\":120,"
                        + "\"g\":false,\"freeze\":true,\"at\":123456}}");
        Rule old = v1.rules.get("com.example.v1");
        eq(31, old.days, "v1 days");
        eq(1320, old.startAt(0), "v1 overnight start");
        eq(120, old.endAt(0), "v1 overnight end");
        ok(old.freeze, "v1 freeze mode");
        ok(!old.gate, "v1 gate");
        eq(123456L, v1.addedAt.get("com.example.v1"), "v1 added_at");

        LegacySnapshotParser.RulesSnapshot v2 = LegacySnapshotParser.parseRules(
                "{\"com.example.v2\":{\"d\":127,\"g\":true,\"freeze\":false,"
                        + "\"w\":[[480,720],[1320,60]],\"at\":7}}");
        Rule multi = v2.rules.get("com.example.v2");
        eq(2, multi.windowCount(), "v2 multi-window count");
        ok(multi.crossMidnightAt(1), "v2 cross-midnight preserved");
        ok(!multi.freeze, "v2 legacy allow mode");
        ok(multi.gate, "v2 gate preserved");

        LegacySnapshotParser.RulesSnapshot cleared = LegacySnapshotParser.parseRules(
                "{\"com.example.clear\":{\"d\":127,\"w\":[]}}");
        eq(0, cleared.rules.get("com.example.clear").windowCount(),
                "explicit empty windows are preserved");
        ok(!cleared.rules.get("com.example.clear").freeze,
                "rules default to legacy allow mode");

        LegacySnapshotParser.SlotsSnapshot slots = LegacySnapshotParser.parseSlots(
                "[{\"id\":\"night\",\"days\":127,\"freeze\":true,"
                        + "\"windows\":[[0,360]]}]");
        ok(slots.slots.get("night").freeze, "slot defaults and mode preserved");
        eq(127, slots.slots.get("night").days, "slot long-form days preserved");
        eq(1, slots.slots.get("night").windowCount(), "slot long-form window count");
        eq(0, slots.slots.get("night").startAt(0), "slot long-form start");
        eq(360, slots.slots.get("night").endAt(0), "slot long-form end");

        LegacySnapshotParser.RulesSnapshot globals = LegacySnapshotParser.legacyGlobal(
                31, 1260, 1380, new LinkedHashSet<>(Arrays.asList("com.a", "com.b")));
        eq(2, globals.rules.size(), "legacy target set");
        eq(1260, globals.rules.get("com.a").startAt(0), "legacy global start");

        fails(() -> LegacySnapshotParser.parseRules("{broken"), "malformed root");
        fails(() -> LegacySnapshotParser.parseRules(
                "{\"com.a\":{\"d\":128}}"), "day mask out of range");
        fails(() -> LegacySnapshotParser.parseRules(
                "{\"com.a\":{\"d\":127,\"w\":[[0,\"60\"]]}}"), "window minute is not numeric");
        fails(() -> LegacySnapshotParser.parseRules(
                "{\"com.a\":{\"d\":127,\"w\":[[0,1440]]}}"), "window minute is out of range");
        fails(() -> LegacySnapshotParser.parseRules(
                "{\"com.a\":{\"d\":127,\"g\":\"true\"}}"), "gate type is invalid");
        fails(() -> LegacySnapshotParser.parseRules(
                "{\"com.a\":{\"d\":127,\"w\":[[0,60.5]]}}"), "fractional minute rejected");
        fails(() -> LegacySnapshotParser.parseSlots(
                "[{\"id\":\"a\",\"w\":[[0,60]]},{\"id\":\"a\",\"w\":[[0,120]]}]"),
                "duplicate slot id");
        fails(() -> LegacySnapshotParser.parseSlots(
                "[{\"id\":\"a\",\"w\":[[0,60]]},{\"id\":\"b\",\"w\":[[0,60]]}]"),
                "duplicate schedule key");
        fails(() -> LegacySnapshotParser.parseSlots(
                "[{\"id\":\"a\",\"days\":127,\"windows\":\"broken\"}]"),
                "slot windows type is invalid");

        System.out.println("Legacy snapshot parser: " + checks + " checks passed");
    }
}
