package com.afs.guard.style;

/** Pure measurement conversion, shared by every visual renderer. */
public final class FrameBudget {
    private FrameBudget(){}
    public static long intervalNanos(float refreshRate){
        if(Float.isNaN(refreshRate)||Float.isInfinite(refreshRate)||refreshRate<10||refreshRate>1000)refreshRate=60;
        return Math.round(1_000_000_000d/refreshRate);
    }
    /** Equivalent cost on a 60Hz display; preserves the existing degradation policy. */
    public static long normalizedMillis(long durationNanos,long intervalNanos){
        if(durationNanos<=0||durationNanos>1_000_000_000L)return 0;
        if(intervalNanos<1_000_000L||intervalNanos>100_000_000L)intervalNanos=intervalNanos(60);
        return Math.min(1000,Math.max(1,Math.round(durationNanos*1000d/(60d*intervalNanos))));
    }
}
