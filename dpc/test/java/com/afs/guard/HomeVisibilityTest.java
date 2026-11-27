package com.afs.guard;

/** 打卡页与首页切换时，学习台可见性不能因 onPause/onResume 顺序闪断。 */
public final class HomeVisibilityTest {
    private static int checks;

    private static void check(boolean value, String label) {
        checks++;
        if (!value) throw new AssertionError(label);
    }

    public static void main(String[] args) {
        Object checkin = new Object(), home = new Object();
        check(!MainActivity.isHomeVisible(), "no home owner initially");
        MainActivity.enterHome(checkin);
        check(MainActivity.isHomeVisible(), "check-in page owns the foreground");
        MainActivity.enterHome(home);
        MainActivity.leaveHome(checkin);
        check(MainActivity.isHomeVisible(), "overlapping pause/resume stays visible");
        MainActivity.leaveHome(home);
        check(!MainActivity.isHomeVisible(), "both pages paused means no visible home");
        System.out.println("Home visibility: " + checks + " checks passed");
    }
}
