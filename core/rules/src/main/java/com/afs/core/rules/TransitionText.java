package com.afs.core.rules;

import java.util.Locale;

public final class TransitionText {
    private TransitionText() {}

    public static String remaining(long now, long transition) {
        long seconds = Math.max(0L, (transition - now + 999L) / 1000L);
        return String.format(Locale.ROOT, "%02d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60);
    }
}
