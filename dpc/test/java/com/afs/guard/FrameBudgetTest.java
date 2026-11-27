package com.afs.guard;
import com.afs.guard.style.FrameBudget;
public final class FrameBudgetTest {
    private static int checks;
    private static void equal(long expected,long actual){checks++;if(expected!=actual)throw new AssertionError(expected+" != "+actual);}
    public static void main(String[] args){
        equal(20,FrameBudget.normalizedMillis(20_000_000,FrameBudget.intervalNanos(60)));
        equal(30,FrameBudget.normalizedMillis(20_000_000,FrameBudget.intervalNanos(90)));
        equal(40,FrameBudget.normalizedMillis(20_000_000,FrameBudget.intervalNanos(120)));
        equal(20,FrameBudget.normalizedMillis(10_000_000,FrameBudget.intervalNanos(120)));
        for(float rate:new float[]{0,-1,Float.NaN,Float.POSITIVE_INFINITY,2000})
            equal(FrameBudget.intervalNanos(60),FrameBudget.intervalNanos(rate));
        equal(20,FrameBudget.normalizedMillis(20_000_000,0));
        equal(1000,FrameBudget.normalizedMillis(1_000_000_000L,FrameBudget.intervalNanos(120)));
        equal(0,FrameBudget.normalizedMillis(0,1));
        equal(0,FrameBudget.normalizedMillis(Long.MAX_VALUE,1));
        equal(0,FrameBudget.normalizedMillis(-1,1));
        System.out.println("Frame budgets: "+checks+" checks passed");
    }
}
