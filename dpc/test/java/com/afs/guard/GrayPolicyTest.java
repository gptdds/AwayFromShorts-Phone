package com.afs.guard;
public final class GrayPolicyTest {
    private static int count;
    private static void check(boolean condition,String label){if(!condition)throw new AssertionError(label);count++;}
    public static void main(String[] args){
        check(!GrayPolicy.resolve(true,false,true),"midnight reset restores color while learning remains active");
        check(!GrayPolicy.resolve(true,false,false),"new budget stays colorful at home too");
        check(GrayPolicy.resolve(true,true,false),"exceeded budget stays gray even on the home page");
        check(GrayPolicy.resolve(true,true,true),"both causes cannot suppress screen limit");
        check(GrayPolicy.resolve(false,false,true),"disabled daily budget preserves prior learning color behavior");
        check(!GrayPolicy.resolve(false,false,false),"no cause means color");
        check(!GrayPolicy.resolve(true,0>=240*60000L,true),"a zeroed next-day total is colorful");
        check(GrayPolicy.resolve(true,240*60000L>=240*60000L,false),"exact limit enables grayscale");
        System.out.println("Gray policy: "+count+" checks passed");
    }
}
