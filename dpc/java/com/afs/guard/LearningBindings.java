package com.afs.guard;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 学习联动总绑定。
 *
 * 前半是学习来源，后半是联动目标。记忆、刷题各自仍保留原有目标设置；
 * 这里是总闸，关掉任一来源后，即使对应模块还保留每日目标，也不会再触发联动。
 *
 * 允许继续约束：
 * - 冻结时段：任务未完成时，冻结窗口中的应用在原本放行点之后继续冻结。
 * - 时间限额：未设冻结时间的限额应用也会参与联动。
 * - 断网：调整时段内也不放开断网名单。
 * - 奖励卡：今日学习联动未完成时，不能使用热爱券。
 *
 * 只进不出：勾选新的来源/目标随时可以；取消勾选会减少约束，只能在调整时段
 * 或首次安装宽限期内保存。
 */
final class LearningBindings {
    static final String SP = "afs_learning_link";

    private static final String K_SOURCE_MEMORY = "bind_source_memory";
    private static final String K_SOURCE_QUIZ = "bind_source_quiz";
    private static final String K_TARGET_GATE = "bind_target_gate";
    private static final String K_TARGET_QUOTA = "bind_target_quota";
    private static final String K_TARGET_NET = "bind_target_network";
    private static final String K_TARGET_REWARD = "bind_target_reward";

    static final boolean DEFAULT_SOURCE_MEMORY = true;
    static final boolean DEFAULT_SOURCE_QUIZ = true;
    static final boolean DEFAULT_TARGET_GATE = true;
    static final boolean DEFAULT_TARGET_QUOTA = true;
    /** 新目标默认关闭，避免升级后突然把断网调整时段变成强制断网。 */
    static final boolean DEFAULT_TARGET_NET = false;
    /** 1.91.56 已把奖励卡脱钩；这里默认关闭，需要用户显式重新绑定。 */
    static final boolean DEFAULT_TARGET_REWARD = false;

    static final class Binding {
        final boolean memory;
        final boolean quiz;
        final boolean gate;
        final boolean quota;
        final boolean network;
        final boolean reward;

        Binding(boolean memory, boolean quiz, boolean gate, boolean quota, boolean network, boolean reward) {
            this.memory = memory;
            this.quiz = quiz;
            this.gate = gate;
            this.quota = quota;
            this.network = network;
            this.reward = reward;
        }

        Binding source(boolean value) {
            return new Binding(value, value, gate, quota, network, reward);
        }

        boolean sameAs(Binding other) {
            return other != null
                    && memory == other.memory
                    && quiz == other.quiz
                    && gate == other.gate
                    && quota == other.quota
                    && network == other.network
                    && reward == other.reward;
        }

        boolean hasSource() {
            return memory || quiz;
        }

        boolean hasTarget() {
            return gate || quota || network || reward;
        }
    }

    private LearningBindings() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(SP, Context.MODE_PRIVATE);
    }

    static Binding read(Context c) {
        SharedPreferences p = sp(c);
        return new Binding(
                p.getBoolean(K_SOURCE_MEMORY, DEFAULT_SOURCE_MEMORY),
                p.getBoolean(K_SOURCE_QUIZ, DEFAULT_SOURCE_QUIZ),
                p.getBoolean(K_TARGET_GATE, DEFAULT_TARGET_GATE),
                p.getBoolean(K_TARGET_QUOTA, DEFAULT_TARGET_QUOTA),
                p.getBoolean(K_TARGET_NET, DEFAULT_TARGET_NET),
                p.getBoolean(K_TARGET_REWARD, DEFAULT_TARGET_REWARD));
    }

    static boolean sourceMemory(Context c) {
        return read(c).memory;
    }

    static boolean sourceQuiz(Context c) {
        return read(c).quiz;
    }

    static boolean targetGate(Context c) {
        return read(c).gate;
    }

    static boolean targetQuota(Context c) {
        return read(c).quota;
    }

    static boolean targetNetwork(Context c) {
        return read(c).network;
    }

    static boolean targetReward(Context c) {
        return read(c).reward;
    }

    static void save(Context c, Binding next) {
        if (next == null) throw new IllegalArgumentException(T.s("学习联动绑定无效"));
        Binding old = read(c);
        if (wouldRelax(old, next) && !TimeWindow.on(c) && !Config.inGrace(c)) {
            throw new IllegalStateException(T.f("取消学习联动绑定只能在调整时段 %s 内修改",
                    TimeWindow.label(c)));
        }
        boolean ok = sp(c).edit()
                .putBoolean(K_SOURCE_MEMORY, next.memory)
                .putBoolean(K_SOURCE_QUIZ, next.quiz)
                .putBoolean(K_TARGET_GATE, next.gate)
                .putBoolean(K_TARGET_QUOTA, next.quota)
                .putBoolean(K_TARGET_NET, next.network)
                .putBoolean(K_TARGET_REWARD, next.reward)
                .commit();
        if (!ok) throw new IllegalStateException(T.s("学习联动绑定未能保存"));
        StudyGate.invalidate(c);
        Guard.applyAsync(c);
        NetBlockService.reconcile(c);
    }

    static String summary(Context c) {
        Binding b = read(c);
        String sources = sourceText(b.memory, b.quiz);
        String targets = targetText(b.gate, b.quota, b.network, b.reward);
        if (sources.isEmpty() || targets.isEmpty()) return T.s("未绑定");
        return sources + " → " + targets;
    }

    /** 至少有一个勾选目标真的承接了当前应用/规则。 */
    static boolean targetsConfigured(Context c, Binding b) {
        if (c == null || b == null) return false;
        if (b.gate) {
            for (Rule rule : RuleStore.all(c).values()) if (rule.gate) return true;
        }
        if (b.reward) return true;
        if (b.quota && DailyQuotaStore.learningLinked(c)) return true;
        return b.network && NetBlockStore.enabled(c);
    }

    static String sourceText(boolean memory, boolean quiz) {
        StringBuilder out = new StringBuilder();
        if (memory) out.append(T.s("记忆"));
        if (quiz) {
            if (out.length() > 0) out.append(T.s("、"));
            out.append(T.s("刷题"));
        }
        return out.toString();
    }

    static String targetText(boolean gate, boolean quota, boolean network, boolean reward) {
        StringBuilder out = new StringBuilder();
        if (gate) out.append(T.s("冻结时段"));
        if (quota) {
            if (out.length() > 0) out.append(T.s("、"));
            out.append(T.s("时间限额"));
        }
        if (network) {
            if (out.length() > 0) out.append(T.s("、"));
            out.append(T.s("断网"));
        }
        if (reward) {
            if (out.length() > 0) out.append(T.s("、"));
            out.append(T.s("奖励卡"));
        }
        return out.toString();
    }

    /** 纯判定：任何已勾选项从 true 变成 false 都算放宽。 */
    static boolean wouldRelax(Binding before, Binding after) {
        if (before == null || after == null) return false;
        return (before.memory && !after.memory)
                || (before.quiz && !after.quiz)
                || (before.gate && !after.gate)
                || (before.quota && !after.quota)
                    || (before.network && !after.network)
                    || (before.reward && !after.reward);
    }
}
