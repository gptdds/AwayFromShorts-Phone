package com.afs.guard;

public final class FreezeWarnTest {
    private static int checks;

    private static void eq(int expected, int actual, String label) {
        checks++;
        if (expected != actual) {
            throw new AssertionError(label + ": " + actual + " != " + expected);
        }
    }

    public static void main(String[] args) {
        eq(15, FreezeWarn.DEFAULT_MINUTES, "default lead time");
        eq(1, FreezeWarn.normalizeMinutes(1), "minimum lead time");
        eq(15, FreezeWarn.normalizeMinutes(15), "ordinary lead time");
        eq(180, FreezeWarn.normalizeMinutes(180), "maximum lead time");
        eq(15, FreezeWarn.normalizeMinutes(0), "zero falls back to default");
        eq(15, FreezeWarn.normalizeMinutes(-1), "negative falls back to default");
        eq(15, FreezeWarn.normalizeMinutes(181), "above range falls back to default");
        System.out.println("Freeze warning: " + checks + " checks passed");
    }
}
