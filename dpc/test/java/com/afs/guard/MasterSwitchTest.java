package com.afs.guard;

/** 总开关与统一调整时段的边界。 */
public final class MasterSwitchTest {
    private static int checks;

    private static void ok(boolean value, String label) {
        checks++;
        if (!value) throw new AssertionError(label);
    }

    public static void main(String[] args) {
        ok(MasterSwitch.allowed(true, true, false, false), "重复开启是幂等的");
        ok(MasterSwitch.allowed(false, true, false, false), "关闭后随时可以从关打到开");
        ok(!MasterSwitch.allowed(true, false, false, false), "时段外不能关");
        ok(MasterSwitch.allowed(true, false, true, false), "调整时段内可以关");
        ok(MasterSwitch.allowed(true, false, false, true), "首次安装宽限期可以关");
        ok(MasterSwitch.allowed(false, true, false, true), "首次安装宽限期可以开");
        ok(MasterSwitch.allowed(false, false, false, false), "重复关闭是幂等的");
        ok(MasterSwitch.allowed(true, true, true, false), "调整时段内重复开启是幂等的");
        ok(MasterSwitch.allowed(false, true, true, false), "调整时段内可以从关打到开");
        System.out.println("Master switch: " + checks + " checks");
    }
}
