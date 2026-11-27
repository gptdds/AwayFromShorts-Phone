package com.afs.blocker;

import com.afs.core.rules.WeeklyRule;

/**
 * 共享规则核心与无障碍版 Guard 的对拍。
 *
 * 无障碍版现在只有一条全局时段，判别方向跟 DO 的"冻结时段"相反：
 * 这里的时段是**放行**时段，时段外才拦。共享核心用 Mode.ALLOW 表达这一侧，
 * 本测试逐分钟证明两者的拦截结论完全一致，挡位（force）的优先级也没变。
 */
public final class GuardParityTest {

    private static int checks;

    private static void ok(boolean value, String label) {
        checks++;
        if (!value) throw new AssertionError(label);
    }

    public static void main(String[] args) {
        int[][] pairs = {
                {0, 0},
                {20 * 60, 23 * 60},
                {22 * 60, 60},
                {1439, 1},
                {8 * 60, 18 * 60},
                {23 * 60, 24 * 60 - 1}
        };
        for (int p = 0; p < pairs.length; p++) {
            int start = pairs[p][0];
            int end = pairs[p][1];
            WeeklyRule shared = WeeklyRule.allow(WeeklyRule.ALL_DAYS, new int[][]{{start, end}});
            for (int minute = 0; minute < 1440; minute++) {
                boolean guardAllowed = Guard.inWindow(minute, start, end);
                boolean sharedAllowed = !shared.blocked(0, minute);
                checks++;
                if (guardAllowed != sharedAllowed) {
                    throw new AssertionError("窗口 " + start + "–" + end + " 第 " + minute
                            + " 分钟不一致：Guard=" + guardAllowed + " WeeklyRule=" + sharedAllowed);
                }
            }
            // 没有档位时，拦截就是放行的取反
            ok(!Guard.isBlocked(12 * 60, 0L, 0L, start, end) == Guard.inWindow(12 * 60, start, end),
                    "无档位时拦截与放行互补 " + start + "–" + end);
        }

        // 档位（force）优先级高于时段：这在共享核心里不属于规则本身，
        // 由执行器叠加，所以这里钉住的是"叠加顺序"——档位期内一律拦，
        // 哪怕共享规则自己认为此刻在放行时段内。
        long now = 1700000000000L;
        long future = now + 30 * 60 * 1000L;
        WeeklyRule allowWindow = WeeklyRule.allow(WeeklyRule.ALL_DAYS, new int[][]{{20 * 60, 23 * 60}});
        for (int minute = 0; minute < 1440; minute += 5) {
            checks++;
            if (!Guard.isBlocked(minute, now, future, 20 * 60, 23 * 60)) {
                throw new AssertionError("档位中第 " + minute + " 分钟应一律拦截");
            }
        }
        // 放行时段内部：规则本身放行，但档位压过它
        ok(!allowWindow.blocked(0, 22 * 60), "共享规则在放行时段内不拦");
        ok(Guard.isBlocked(22 * 60, now, future, 20 * 60, 23 * 60), "档位压过放行时段");
        ok(Guard.isLocked(22 * 60, now, future, 20 * 60, 23 * 60), "档位中不可改设置");
        ok(Guard.isLocked(2 * 60, now, 0L, 20 * 60, 23 * 60), "时段外不可改设置");

        // 档位过期后交还给窗口判断
        ok(!Guard.isBlocked(22 * 60, now, now - 1000L, 20 * 60, 23 * 60), "档位过期回到放行");
        ok(Guard.isBlocked(19 * 60, now, now - 1000L, 20 * 60, 23 * 60), "档位过期时段外仍拦");

        System.out.println("Guard parity: " + checks + " checks passed");
    }
}
