package com.afs.guard;

/** Daily screen-color policy is independent from learning's freeze decisions. */
final class GrayPolicy {
    private GrayPolicy(){}
    static boolean resolve(boolean screenLimitEnabled,boolean screenExceeded,boolean learningGray){
        // With a daily screen budget, a new day restores color even if study is pending.
        // Preserve the legacy study-color behavior when that budget is disabled.
        return screenLimitEnabled?screenExceeded:learningGray;
    }
}
