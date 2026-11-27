package com.afs.guard;

import com.afs.core.rules.WeeklyRule;

import java.util.Calendar;

/**
 * 共享规则核心（com.afs.core.rules.WeeklyRule）与 DO 现有 Rule 的对拍。
 *
 * 这不是"再测一遍同样的逻辑"，而是证明**换实现之后行为没变**：
 * 同一个用户在两个 APK 上看到的状态必须一致，所以逐分钟对比一周 7×1440 个点，
 * 外加下一切换时刻、每天总时长、分组签名的逐项比对。
 *
 * 任何一条对不上，说明共享核心与历史语义出现漂移 —— 迁移就必须停。
 */
public final class RuleParityTest {

    private static int checks;

    private static void ok(boolean value, String label) {
        checks++;
        if (!value) throw new AssertionError(label);
    }

    private static void eq(Object expected, Object actual, String label) {
        checks++;
        if (!expected.equals(actual)) {
            throw new AssertionError(label + ": " + actual + " != " + expected);
        }
    }

    /** 2026-11-16 是周一，从这天 00:00 起扫一整周。 */
    private static long mondayBase() {
        Calendar c = Calendar.getInstance();
        c.set(2026, Calendar.NOVEMBER, 16, 0, 0, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    private static Calendar at(long base, int offsetMinutes) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(base + offsetMinutes * 60000L);
        return c;
    }

    private static Rule rule(int days, int[][] windows, boolean freeze) {
        return new Rule(days, windows, Rule.autoGate(windows), freeze);
    }

    /** 一周逐分钟对比 Rule.inWindow 与 WeeklyRule.blocked 的补集。 */
    private static void compareWeek(Rule legacy, WeeklyRule shared, String label) {
        long base = mondayBase();
        WeeklyRule.Mode mode = shared.mode();
        // 模式与 freeze 标志必须一一对应，否则后面比的是两件事
        ok(legacy.freeze == (mode == WeeklyRule.Mode.FREEZE), label + " 模式对应");
        for (int offset = 0; offset < 7 * 1440; offset++) {
            Calendar c = at(base, offset);
            int monIndex = WeeklyRule.monIndex(c);
            int minute = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
            boolean legacyAllowed = legacy.inWindow(c);
            boolean sharedAllowed = !shared.blocked(monIndex, minute);
            checks++;
            if (legacyAllowed != sharedAllowed) {
                throw new AssertionError(label + " 第 " + offset + " 分钟不一致：Rule="
                        + legacyAllowed + " WeeklyRule=" + sharedAllowed);
            }
        }
    }

    private static void compareNextFlip(Rule legacy, WeeklyRule shared, String label) {
        long base = mondayBase();
        // 一周里均匀取 14 个点，含跨午夜边界前后
        int[] offsets = {0, 60, 480, 600, 700, 720, 1080, 1200, 1260, 1320, 1321, 1380, 1439, 3 * 1440 + 30};
        for (int i = 0; i < offsets.length; i++) {
            Calendar c = at(base, offsets[i]);
            eq(legacy.nextFlip(c), shared.nextFlip(c.getTimeInMillis()), label + " nextFlip@" + offsets[i]);
        }
    }

    private static void compareDailyTotal(int[][] windows, String label) {
        WeeklyRule shared = WeeklyRule.allow(WeeklyRule.ALL_DAYS, windows);
        boolean legacyGate = Rule.autoGate(windows);
        boolean sharedGate = shared.totalWindowMinutes() > 0 && shared.totalWindowMinutes() <= Rule.GATE_MAX_MINUTES;
        eq(legacyGate, sharedGate, label + " 闸门自动判定");
    }

    public static void main(String[] args) {
        int[][] onlyEvening = {{20 * 60, 23 * 60}};
        int[][] crossMidnight = {{22 * 60, 60}};
        int[][] workdaysTwo = {{8 * 60, 18 * 60}, {22 * 60, 60}};
        int[][] wholeDay = {{0, 0}};
        int[][] empty = {};

        // 1. 无障碍版默认（全天放行时段 20:00–23:00）
        Rule allowEvening = rule(WeeklyRule.ALL_DAYS, onlyEvening, false);
        compareWeek(allowEvening, WeeklyRule.allow(WeeklyRule.ALL_DAYS, onlyEvening), "放行 20–23");
        compareNextFlip(allowEvening, WeeklyRule.allow(WeeklyRule.ALL_DAYS, onlyEvening), "放行 20–23");

        // 2. 冻结模式（DO 默认形态）
        Rule freezeEvening = rule(WeeklyRule.ALL_DAYS, onlyEvening, true);
        compareWeek(freezeEvening, WeeklyRule.freeze(WeeklyRule.ALL_DAYS, onlyEvening), "冻结 20–23");
        compareNextFlip(freezeEvening, WeeklyRule.freeze(WeeklyRule.ALL_DAYS, onlyEvening), "冻结 20–23");

        // 3. 跨午夜 + 工作日多段
        Rule workdays = rule(WeeklyRule.WORKDAYS, workdaysTwo, true);
        compareWeek(workdays, WeeklyRule.freeze(WeeklyRule.WORKDAYS, workdaysTwo), "工作日两段含跨午夜");
        compareNextFlip(workdays, WeeklyRule.freeze(WeeklyRule.WORKDAYS, workdaysTwo), "工作日两段含跨午夜");

        // 4. 跨午夜纯放行
        Rule allowCross = rule(WeeklyRule.ALL_DAYS, crossMidnight, false);
        compareWeek(allowCross, WeeklyRule.allow(WeeklyRule.ALL_DAYS, crossMidnight), "放行跨午夜 22–01");
        compareNextFlip(allowCross, WeeklyRule.allow(WeeklyRule.ALL_DAYS, crossMidnight), "放行跨午夜 22–01");

        // 5. 只选周末全天（start == end = 全天）
        Rule weekendAll = rule(WeeklyRule.WEEKEND, wholeDay, true);
        compareWeek(weekendAll, WeeklyRule.freeze(WeeklyRule.WEEKEND, wholeDay), "周末全天");
        compareNextFlip(weekendAll, WeeklyRule.freeze(WeeklyRule.WEEKEND, wholeDay), "周末全天");

        // 6. 一天都没选 = 没有生效日
        Rule noDays = rule(0, onlyEvening, true);
        compareWeek(noDays, WeeklyRule.freeze(0, onlyEvening), "没有生效日");

        // 7. 没有任何时段
        Rule noWindows = rule(WeeklyRule.ALL_DAYS, empty, false);
        compareWeek(noWindows, WeeklyRule.allow(WeeklyRule.ALL_DAYS, empty), "没有时段");
        compareNextFlip(noWindows, WeeklyRule.allow(WeeklyRule.ALL_DAYS, empty), "没有时段");

        // 8. 贴边一分钟窗口
        Rule minute = rule(WeeklyRule.ALL_DAYS, new int[][]{{1439, 1}}, true);
        compareWeek(minute, WeeklyRule.freeze(WeeklyRule.ALL_DAYS, new int[][]{{1439, 1}}), "1 分钟跨午夜");

        // 9. 每天总时长：闸门阈值 6 小时两侧都要判对
        compareDailyTotal(new int[][]{{23 * 60, 24 * 60 - 1}}, "5 小时 59 分");
        compareDailyTotal(new int[][]{{23 * 60, 24 * 60}}, "整 1 小时");
        compareDailyTotal(new int[][]{{18 * 60, 24 * 60}}, "6 小时整（不该进闸门）");
        compareDailyTotal(new int[][]{{8 * 60, 18 * 60}, {22 * 60, 60}}, "10 小时 + 跨午夜");
        compareDailyTotal(wholeDay, "全天");
        compareDailyTotal(empty, "没有时段");

        // 10. 同一份规则重复构造必须给出同一串签名（分组卡片靠它）
        WeeklyRule a = WeeklyRule.freeze(WeeklyRule.WORKDAYS, workdaysTwo);
        WeeklyRule b = WeeklyRule.freeze(WeeklyRule.WORKDAYS, workdaysTwo);
        eq(a.signature(), b.signature(), "签名稳定");
        ok(!a.signature().equals(WeeklyRule.allow(WeeklyRule.WORKDAYS, workdaysTwo).signature()), "模式进入签名");

        // 11. 两种模式互为补集：同一时段不可能同时"该拦"和"该放"
        WeeklyRule allowMode = WeeklyRule.allow(WeeklyRule.ALL_DAYS, crossMidnight);
        WeeklyRule freezeMode = WeeklyRule.freeze(WeeklyRule.ALL_DAYS, crossMidnight);
        for (int m = 0; m < 1440; m += 7) {
            ok(allowMode.blocked(0, m) != freezeMode.blocked(0, m), "模式互补 @" + m);
        }

        // 12. 规范化：乱序输入按起点排好，非法行丢掉，分钟数折回一天
        WeeklyRule messy = WeeklyRule.allow(WeeklyRule.ALL_DAYS, new int[][]{{1320, 60}, null, {480, 1080}});
        eq(2, messy.windowCount(), "非法行被丢掉");
        eq(480, messy.startAt(0), "按起点升序");
        eq(1320, messy.startAt(1), "第二段起点");
        eq(60, WeeklyRule.norm(-1380), "负数折回");
        eq(1439, WeeklyRule.norm(1439), "正常值不动");

        // 13. 星期位判断与 Calendar 映射
        ok(WeeklyRule.dayOn(WeeklyRule.WORKDAYS, 0), "工作日含周一");
        ok(!WeeklyRule.dayOn(WeeklyRule.WORKDAYS, 5), "工作日不含周六");
        Calendar sunday = at(mondayBase(), 6 * 1440);
        eq(6, WeeklyRule.monIndex(sunday), "周日映射为 6");
        eq(0, WeeklyRule.monIndex(at(mondayBase(), 0)), "周一映射为 0");

        System.out.println("Weekly rule parity: " + checks + " checks passed");
    }
}
