package com.afs.guard;

import android.content.Context;
import android.util.Log;

/**
 * Detects a real external change and applies the taboo-card consequence exactly once.
 *
 * <p>Callers snapshot before importing/pulling a configuration, then call
 * {@link #recordIfChanged(Context, String, String)} after the write. A no-op sync does not
 * increase the counter and does not add a penalty.</p>
 */
final class ExternalAdjustment {
    private ExternalAdjustment() {
    }

    static String snapshot(Context c) {
        try {
            return RuleStore.exportRules(c) + "\n--quota--\n"
                    + DailyQuotaStore.snapshot(c).config.toString();
        } catch (RuntimeException error) {
            return "";
        }
    }

    /** Returns true when the configuration actually changed. */
    static boolean recordIfChanged(Context c, String before, String source) {
        String after = snapshot(c);
        if (after.equals(before)) return false;
        long now = GuardClock.now(c).getTimeInMillis();
        try {
            AiAdjustmentStore.record(c, source);
        } catch (RuntimeException error) {
            Log.w(Guard.TAG, "AI adjustment count failed: " + error.getClass().getSimpleName());
        }
        try {
            TabooPenalty.Result result = TabooPenalty.apply(c, now);
            Log.i(Guard.TAG, "external adjustment: " + source + " " + result.freezeText()
                    + result.quotaText() + " → until=" + result.until);
        } catch (RuntimeException error) {
            Log.w(Guard.TAG, "external adjustment penalty failed: "
                    + error.getClass().getSimpleName());
        }
        return true;
    }
}
