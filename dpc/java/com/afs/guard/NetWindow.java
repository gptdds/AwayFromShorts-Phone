package com.afs.guard;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;

/**
 * 断网「调整时段」：一天里唯一能改断网设置的一段时间，也是断网唯一放开的时间。
 *
 * 默认跟随 {@link TimeWindow}（限额／冻结的调整时段），也可以在设置里改成独立时段。
 * 断网方向仍是「默认常开、只在调整时段放开」；两套时段只共享边界，不共享收紧方向。
 *
 * 语义只有三条：
 * 1. 左闭右开、跨午夜、start==end 全天，判断全走 {@link TimeWindow#contains(long, int, int)}。
 * 2. 时段内：隧道断开（放开）、开关/名单/时段本身都能改；时段外：按名单断网、三者一律锁住。
 * 3. 时段本身也上锁 —— 否则把时段改成「现在」就等于给自己开了后门。
 */
public final class NetWindow {

    static final String SP = "afs_net_window";
    private static final String K_START = "start";
    private static final String K_END = "end";
    private static final String K_FOLLOW_FREEZE_WINDOW = "follow_freeze_window";

    private static final int MINUTES = 24 * 60;

    /** 解绑后使用的自定义默认值：每晚 10 点到 11 点。 */
    public static final int DEFAULT_START = 22 * 60;
    public static final int DEFAULT_END = 23 * 60;

    private NetWindow() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(SP, Context.MODE_PRIVATE);
    }

    public static int start(Context c) {
        if (c == null) return DEFAULT_START;
        return resolvedStart(followsFreezeWindow(c), TimeWindow.start(c),
                sane(sp(c).getInt(K_START, DEFAULT_START), DEFAULT_START));
    }

    public static int end(Context c) {
        if (c == null) return DEFAULT_END;
        return resolvedEnd(followsFreezeWindow(c), TimeWindow.end(c),
                sane(sp(c).getInt(K_END, DEFAULT_END), DEFAULT_END));
    }

    /** 断网调整时段默认跟随冻结/限额的调整时段，也可切成自定义。 */
    public static boolean followsFreezeWindow(Context c) {
        if (c == null) return true;
        return sp(c).getBoolean(K_FOLLOW_FREEZE_WINDOW, true);
    }

    static int resolvedStart(boolean follows, int freezeStart, int customStart) {
        return follows ? freezeStart : customStart;
    }

    static int resolvedEnd(boolean follows, int freezeEnd, int customEnd) {
        return follows ? freezeEnd : customEnd;
    }

    private static int sane(int value, int fallback) {
        return value < 0 || value >= MINUTES ? fallback : value;
    }

    public static void save(Context c, int start, int end) {
        if (start < 0 || start >= MINUTES || end < 0 || end >= MINUTES) {
            throw new IllegalArgumentException(T.s("时刻只能是 0:00–23:59"));
        }
        if (!inside(c) && !Config.inGrace(c)) {
            throw new IllegalStateException(T.f("断网调整时段只能在 %s 内修改", label(c)));
        }
        if (!sp(c).edit()
                .putInt(K_START, start)
                .putInt(K_END, end)
                .putBoolean(K_FOLLOW_FREEZE_WINDOW, false)
                .commit()) {
            throw new IllegalStateException(T.s("断网调整时段未能保存"));
        }
        NetBlockService.reconcile(c);
    }

    public static void followFreezeWindow(Context c, boolean follow) {
        if (!inside(c) && !Config.inGrace(c)) {
            throw new IllegalStateException(T.f("断网调整时段只能在 %s 内修改", label(c)));
        }
        if (!sp(c).edit().putBoolean(K_FOLLOW_FREEZE_WINDOW, follow).commit()) {
            throw new IllegalStateException(T.s("断网调整时段绑定未能保存"));
        }
        NetBlockService.reconcile(c);
    }

    public static String label(Context c) {
        return TimeWindow.clock(start(c)) + "–" + TimeWindow.clock(end(c));
    }

    public static String summary(Context c) {
        return followsFreezeWindow(c)
                ? T.f("跟随主时段 · %s", label(c))
                : T.f("自定义 · %s", label(c));
    }

    /** 现在是不是在调整时段里 —— 是的话设置可改、断网放开。 */
    public static boolean inside(Context c) {
        if (c == null) return false;
        if (!MasterSwitch.enabled(c)) return false;
        return contains(GuardClock.now(c).getTimeInMillis(), start(c), end(c));
    }

    /**
     * 设置能不能改。不能改就顺手把「什么时候能改」说出来，别让人对着灰按钮猜。
     *
     * 首次安装的 10 分钟宽限（{@link Config#inGrace}）里也放行：刚装完正好不在
     * 调整时段的话，连断网名单都选不了，等于把这个功能锁死在装完的第一天。
     * 宽限只影响「能不能改」，不影响断网本身什么时候生效。
     */
    public static boolean canEdit(Activity host) {
        if (host == null) return false;
        if (!MasterSwitch.enabled(host)) {
            TimeWindow.toast(host, T.s("总开关关闭时断网设置暂停"));
            return false;
        }
        if (inside(host) || Config.inGrace(host)) return true;
        TimeWindow.toast(host, T.f("断网设置只能在调整时段 %s 内修改", label(host)));
        return false;
    }

    /**
     * 先选绑定方式：跟随主调整时段，或自定义。
     * 自定义先走完两段式选择再原子落盘，取消不会意外把原绑定切掉。
     */
    public static void pick(final Activity host, final Runnable after) {
        if (host == null || host.isFinishing()) return;
        final boolean follows = followsFreezeWindow(host);
        String[] labels = {
                T.f("跟随冻结与限额调整时段 · %s", TimeWindow.label(host)),
                T.f("自定义断网调整时段 · %s", customLabel(host))
        };
        ThemeStore.dialog(host)
                .setTitle(T.s("断网调整时段绑定"))
                .setSingleChoiceItems(labels, follows ? 0 : 1, (dialog, which) -> {
                    dialog.dismiss();
                    if (which == 0) {
                        try {
                            followFreezeWindow(host, true);
                        } catch (RuntimeException error) {
                            TimeWindow.toast(host, error.getMessage());
                            return;
                        }
                        if (after != null) after.run();
                        return;
                    }
                    pickCustom(host, after);
                })
                .setNegativeButton(T.s("取消"), null)
                .show();
    }

    private static void pickCustom(final Activity host, final Runnable after) {
        int customStart = sane(sp(host).getInt(K_START, DEFAULT_START), DEFAULT_START);
        int customEnd = sane(sp(host).getInt(K_END, DEFAULT_END), DEFAULT_END);
        TimeWindow.pickSlots(host, customStart, customEnd, new TimeWindow.Saver() {
            @Override
            public void save(int start, int end) {
                NetWindow.save(host, start, end);
            }
        }, after);
    }

    private static String customLabel(Context c) {
        int customStart = sane(sp(c).getInt(K_START, DEFAULT_START), DEFAULT_START);
        int customEnd = sane(sp(c).getInt(K_END, DEFAULT_END), DEFAULT_END);
        return TimeWindow.clock(customStart) + "–" + TimeWindow.clock(customEnd);
    }

    /* ==================== 纯逻辑（离线可测） ==================== */

    static boolean contains(long now, int start, int end) {
        return TimeWindow.contains(now, start, end);
    }

    /**
     * 隧道该不该在跑：开了断网、选了应用，而且不在调整时段里。
     *
     * 这一行就是整个功能的方向 —— 以前是「在时段内才断」，现在是「除了时段内都断」。
     */
    static boolean shouldCut(boolean enabled, boolean inside) {
        return shouldCut(enabled, inside, false);
    }

    /**
     * @param learningHold 学习联动目标命中。命中时调整时段也不再是放行例外。
     */
    static boolean shouldCut(boolean enabled, boolean inside, boolean learningHold) {
        return enabled && (!inside || learningHold);
    }
}
