package com.afs.guard.style;

/** Durations and springs in dp/ms that each renderer may scale within MotionPolicy budgets. */
public final class MotionTokens {
    public final long tapMs,pageMs,panelMs;
    public final float springStiffness=230f,springDamping=1f;
    public final float elasticDamping=.8f;
    public MotionTokens(long tapMs,long pageMs,long panelMs){this.tapMs=tapMs;this.pageMs=pageMs;this.panelMs=panelMs;}
}
