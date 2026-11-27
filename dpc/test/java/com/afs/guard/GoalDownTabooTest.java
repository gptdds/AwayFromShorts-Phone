package com.afs.guard;

import org.json.JSONObject;

/**
 * 目标卡「下滑按禁忌卡认领」的离线回归。
 *
 * 覆盖两件事：
 *  1. 开关字段的兼容与往返：旧目标没有 downTaboo 字段时必须按关闭读取。
 *  2. 「今天已处理」只按目标 id + 日期窗口判定，不影响打卡记录本身。
 */
final class GoalDownTabooTest {

    private static int checks;

    private static void ok(boolean condition, String what) {
        checks++;
        if (!condition) {
            throw new AssertionError(what);
        }
    }

    private static void eq(Object want, Object got, String what) {
        checks++;
        if (want == null ? got != null : !want.equals(got)) {
            throw new AssertionError(what + ": want " + want + " got " + got);
        }
    }

    public static void main(String[] args) throws Exception {
        /* 1. 旧目标没有字段 → 默认关闭 */
        JSONObject legacy = new JSONObject()
                .put("id", "goal-1")
                .put("name", "读论文")
                .put("total", 10);
        StudyStore.G old = StudyStore.goalFrom(legacy);
        ok(!old.downTaboo, "旧目标默认不开启下滑禁忌");
        eq("goal-1", old.id, "id 保留");

        /* 2. 开启后写盘再读回仍是开启，其它字段不丢 */
        StudyStore.G goal = new StudyStore.G();
        goal.id = "g2";
        goal.name = "背单词";
        goal.unit = "词";
        goal.total = 100;
        goal.due = "2026-12-31";
        goal.downTaboo = true;
        JSONObject written = StudyStore.goalJson(goal);
        ok(written.optBoolean("downTaboo", false), "downTaboo 写入 JSON");
        StudyStore.G back = StudyStore.goalFrom(written);
        ok(back.downTaboo, "downTaboo 往返保持开启");
        eq("背单词", back.name, "名字往返");
        eq(100.0, back.total, "总量往返");
        eq("2026-12-31", back.due, "截止日往返");

        /* 3. 显式关闭也要写进 JSON，不能退化成"没这个字段" */
        goal.downTaboo = false;
        ok(!StudyStore.goalFrom(StudyStore.goalJson(goal)).downTaboo, "关闭状态往返");

        /* 4. 当天已处理判定：只认本目标、本时间窗 */
        long now = 1_800_000_000_000L;
        String handled = new JSONObject().put("g2", now).toString();
        ok(StudyStore.downHandledOn(handled, "g2", now - 1000, now + 1000), "窗口内算已处理");
        ok(!StudyStore.downHandledOn(handled, "g2", now + 1000, now + 2000), "窗口外不算");
        ok(!StudyStore.downHandledOn(handled, "other", now - 1000, now + 1000), "别的目标不算");
        ok(!StudyStore.downHandledOn("{}", "g2", now - 1000, now + 1000), "空表不算");
        ok(!StudyStore.downHandledOn(handled, "", now - 1000, now + 1000), "空 id 不算");
        ok(!StudyStore.downHandledOn("not json", "g2", 0, now), "坏 JSON 不崩");

        /* 5. 已处理不等于打卡：JSON 里不该出现任何打卡记录语义 */
        ok(StudyStore.downHandledOn(handled, "g2", now - 1000, now + 1000)
                && !written.has("recs"), "下滑处理与打卡记录分离");

        System.out.println("Goal down-taboo: " + checks + " checks passed");
    }
}
