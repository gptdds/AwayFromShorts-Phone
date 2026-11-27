package com.afs.guard;

/**
 * 定时提醒的判定。
 *
 * 钉五件事：间隔到没到、首次不弹、四种不该打扰的情况、改间隔后重新起算、
 * 以及弹窗被进程死亡带走后的补弹判定。
 * 时间全部用毫秒直算，不依赖任何 Android 类。
 */
public final class RestReminderTest {

    private static int checks;

    private static void ok(boolean value, String label) {
        checks++;
        if (!value) throw new AssertionError(label);
    }

    private static long minutes(long value) {
        return value * 60000L;
    }

    public static void main(String[] args) {
        // ---- due：间隔判断 ----
        long start = 1000L * 1000L;
        ok(RestReminder.due(start + minutes(45), start, 45), "exactly 45 min is due");
        ok(RestReminder.due(start + minutes(46), start, 45), "46 min is due");
        ok(!RestReminder.due(start + minutes(44), start, 45), "44 min is not due");
        ok(!RestReminder.due(start, start, 45), "same instant is not due");
        ok(!RestReminder.due(start + minutes(1), 0L, 45), "never fired before: no pop on first tick");
        ok(!RestReminder.due(start + minutes(999), start, 0), "zero interval never fires");
        ok(!RestReminder.due(start + minutes(999), start, -5), "negative interval never fires");

        // 跨零点：只要距上次够久就提醒，不需要「今天第几个点」
        long beforeMidnight = 23L * 60L * 60000L;
        ok(RestReminder.due(beforeMidnight + minutes(70), beforeMidnight, 45), "cross midnight is due");

        // 改小时数后
        ok(RestReminder.due(start + minutes(16), start, 15), "switched to 15 min fires at 16");
        ok(!RestReminder.due(start + minutes(9), start, 15), "switched to 15 min waits");

        // ---- shouldFire：四种不打扰 ----
        long now = start + minutes(60);
        ok(RestReminder.shouldFire(true, true, true, false, false, now, start, 45),
                "normal case fires");
        ok(!RestReminder.shouldFire(false, true, true, false, false, now, start, 45),
                "off means off");
        ok(!RestReminder.shouldFire(true, false, true, false, false, now, start, 45),
                "outside window stays quiet");
        ok(!RestReminder.shouldFire(true, true, false, false, false, now, start, 45),
                "screen off stays quiet");
        ok(!RestReminder.shouldFire(true, true, true, true, false, now, start, 45),
                "while locked stays quiet");
        ok(!RestReminder.shouldFire(true, true, true, false, true, now, start, 45),
                "during unlock pass stays quiet");
        ok(!RestReminder.shouldFire(true, true, true, false, false, now, now, 45),
                "just fired, waits again");

        // 五个条件全满足才发
        int fired = 0;
        for (int mask = 0; mask < 32; mask++) {
            boolean on = (mask & 1) != 0;
            boolean win = (mask & 2) != 0;
            boolean screen = (mask & 4) != 0;
            boolean locked = (mask & 8) != 0;
            boolean pass = (mask & 16) != 0;
            boolean got = RestReminder.shouldFire(on, win, screen, locked, pass, now, start, 45);
            boolean want = on && win && screen && !locked && !pass;
            ok(got == want, "truth table mask=" + mask);
            if (got) fired++;
        }
        ok(fired == 1, "exactly one combination fires: " + fired);

        // ---- revive：弹窗被进程死亡带走后的补弹 ----
        // 场景：提醒弹着，用户把守卫从最近任务划掉（或一键清理），页面随进程一起没了。
        // 走正当渠道关掉的会把 live 记号撤掉；没撤就说明这条还没算数，要补弹。
        ok(!RestReminder.revive(false, false, true, true, false, false),
                "no live mark, nothing to revive");
        ok(!RestReminder.revive(true, true, true, true, false, false),
                "popup already on screen, no revive");
        ok(!RestReminder.revive(true, false, false, true, false, false),
                "outside window: stale mark, not revived");
        ok(!RestReminder.revive(true, false, true, false, false, false),
                "screen off stays quiet");
        ok(!RestReminder.revive(true, false, true, true, true, false),
                "while locked stays quiet");
        ok(!RestReminder.revive(true, false, true, true, false, true),
                "during unlock pass stays quiet");
        ok(RestReminder.revive(true, false, true, true, false, false),
                "killed popup comes back once conditions return");

        // 六个条件全满足才补弹
        int revived = 0;
        for (int mask = 0; mask < 64; mask++) {
            boolean live = (mask & 1) != 0;
            boolean showing = (mask & 2) != 0;
            boolean win = (mask & 4) != 0;
            boolean screen = (mask & 8) != 0;
            boolean locked = (mask & 16) != 0;
            boolean pass = (mask & 32) != 0;
            boolean got = RestReminder.revive(live, showing, win, screen, locked, pass);
            boolean want = live && !showing && win && screen && !locked && !pass;
            ok(got == want, "revive truth table mask=" + mask);
            if (got) revived++;
        }
        ok(revived == 1, "exactly one combination revives: " + revived);

        // ---- 亮屏累计：从亮屏起算，息屏清零 ----
        long gap = ScreenLock.MAX_GAP;
        long t0 = 2_000_000L;
        ok(RestReminder.accumulate(0L, t0, t0 + 10_000L, true, true, gap) == 10_000L,
                "screen on accumulates one tick");
        ok(RestReminder.accumulate(10_000L, t0 + 10_000L, t0 + 20_000L, true, true, gap) == 20_000L,
                "consecutive screen-on ticks add up");
        ok(RestReminder.accumulate(200_000L, t0, t0 + 10_000L, false, true, gap) == 0L,
                "screen off clears the whole round");
        ok(RestReminder.accumulate(200_000L, t0, t0 + 10_000L, true, false, gap) == 200_000L,
                "not counting pauses without clearing");
        ok(RestReminder.accumulate(0L, 0L, t0, true, true, gap) == 0L,
                "first tick after screen on only marks the start");
        ok(RestReminder.accumulate(0L, t0, t0 + 10L * gap, true, true, gap) == gap,
                "long gap is clamped instead of charging screen-off time");
        ok(RestReminder.accumulate(0L, t0, t0 - 5_000L, true, true, gap) == 0L,
                "clock moved backwards adds nothing");
        ok(RestReminder.dueAccum(minutes(45), 45), "exactly 45 min of screen time is due");
        ok(!RestReminder.dueAccum(minutes(44), 45), "44 min is not due");
        ok(!RestReminder.dueAccum(minutes(60), 0), "zero interval never due");
        ok(RestReminder.shouldFireAccum(true, true, true, false, false, minutes(45), 45),
                "due screen time fires");
        ok(!RestReminder.shouldFireAccum(true, true, false, false, false, minutes(45), 45),
                "screen off never fires");
        ok(!RestReminder.shouldFireAccum(true, true, true, false, false, 0L, 45),
                "fresh screen-on does not fire immediately");

        // ---- minutes 文案 ----
        ok(RestReminder.minutes(45).contains("45"), "minutes text carries the number");

        System.out.println("Rest reminder: " + checks + " checks passed");
    }
}
