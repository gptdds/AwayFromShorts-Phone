package com.afs.guard;

/** Learning link sources/targets and the one-way relaxation guard. */
public final class LearningBindingsTest {
    private static int checks;

    private static void ok(boolean value, String label) {
        checks++;
        if (!value) throw new AssertionError(label);
    }

    private static void eq(Object expected, Object actual, String label) {
        checks++;
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError(label + ": " + actual + " != " + expected);
        }
    }

    public static void main(String[] args) {
        ok(LearningBindings.DEFAULT_SOURCE_MEMORY, "memory source on by default");
        ok(LearningBindings.DEFAULT_SOURCE_QUIZ, "quiz source on by default");
        ok(LearningBindings.DEFAULT_TARGET_GATE, "gate target on by default");
        ok(LearningBindings.DEFAULT_TARGET_QUOTA, "quota target on by default");
        ok(!LearningBindings.DEFAULT_TARGET_NET, "network target is opt-in");
        ok(!LearningBindings.DEFAULT_TARGET_REWARD, "reward target is opt-in");

        LearningBindings.Binding base =
                new LearningBindings.Binding(true, false, true, true, false, false);
        ok(base.hasSource() && base.hasTarget(), "base has source and target");
        ok(!LearningBindings.wouldRelax(base,
                        new LearningBindings.Binding(true, true, true, true, true, false)),
                "adding bindings is always a restriction");
        ok(LearningBindings.wouldRelax(base,
                        new LearningBindings.Binding(false, true, true, true, true, false)),
                "disabling an existing source is relaxation");
        ok(LearningBindings.wouldRelax(base,
                        new LearningBindings.Binding(true, false, false, true, false, false)),
                "disabling an existing target is relaxation");
        ok(!LearningBindings.wouldRelax(base,
                        new LearningBindings.Binding(true, false, true, true, false, true)),
                "enabling the reward target is not a relaxation");
        LearningBindings.Binding boundReward =
                new LearningBindings.Binding(true, false, true, true, false, true);
        ok(LearningBindings.wouldRelax(boundReward,
                        new LearningBindings.Binding(true, false, true, true, false, false)),
                "unbinding rewards is relaxation");
        ok(!LearningBindings.wouldRelax(base,
                        new LearningBindings.Binding(true, false, true, true, false, false)),
                "unchanged binding is not relaxation");

        LearningBindings.Binding none =
                new LearningBindings.Binding(false, false, false, false, false, false);
        ok(!none.hasSource() && !none.hasTarget(), "all-off binding");
        ok(none.source(true).hasSource(), "source helper updates both selectors");

        eq("记忆、刷题", LearningBindings.sourceText(true, true), "source copy");
        eq("冻结时段、时间限额、断网",
                LearningBindings.targetText(true, true, true, false), "target copy");
        eq("冻结时段、时间限额、断网、奖励卡",
                LearningBindings.targetText(true, true, true, true), "reward target copy");
        eq("", LearningBindings.sourceText(false, false), "empty source copy");

        System.out.println("Learning bindings: " + checks + " checks passed");
    }
}
