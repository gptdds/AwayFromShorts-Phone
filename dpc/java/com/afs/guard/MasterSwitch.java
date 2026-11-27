package com.afs.guard;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

/**
 * 手机端总开关。
 *
 * 关闭后暂时解除应用冻结、使用时限额、学习联动、断网、页面拦截、锁机和整机灰度；
 * 原有规则与名单全部保留。关闭必须在统一的「调整时段」内（或首次安装宽限期）执行；
 * 关闭后调整时段清零，重新开启不受时段限制。
 */
public final class MasterSwitch {

    static final String SP = "afs_master_switch";
    private static final String K_ENABLED = "enabled";
    private static final String K_DEFAULT_OFF_APPLIED = "default_off_applied";

    private MasterSwitch() {
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(SP, Context.MODE_PRIVATE);
    }

    /**
     * 默认关闭。新安装直接是关；已安装用户第一次读取时也一次性落为关，
     * 之后仍可在设置页手动开启或关闭。
     */
    public static boolean enabled(Context c) {
        if (c == null) return false;
        SharedPreferences prefs = prefs(c);
        if (!prefs.getBoolean(K_DEFAULT_OFF_APPLIED, false)) {
            prefs.edit().putBoolean(K_ENABLED, false)
                    .putBoolean(K_DEFAULT_OFF_APPLIED, true).commit();
        }
        return prefs.getBoolean(K_ENABLED, false);
    }

    /** 当前能否修改总开关；时段外仍允许查看状态。 */
    public static boolean canEdit(Context c) {
        if (c == null) return false;
        // 关闭时调整时段已清零；此时允许随时重新开启，否则会把自己永久锁在外面。
        return !enabled(c) || TimeWindow.canEdit(c);
    }

    /** Backward-compatible name for callers compiled against the old gate. */
    public static boolean canDisable(Context c) {
        return canEdit(c);
    }

    /**
     * 修改总开关并立即落地。返回 false 表示当前时段不允许修改。
     * 这里再做一次闸门，避免以后新增入口绕过设置页的限制。
     */
    public static boolean setEnabled(Context c, boolean enabled) {
        if (c == null) return false;
        boolean current = enabled(c);
        if (!allowed(current, enabled, TimeWindow.on(c), Config.inGrace(c))) return false;
        boolean turningOn = !current && enabled;
        boolean turningOff = current && !enabled;
        if (!prefs(c).edit().putBoolean(K_ENABLED, enabled).commit()) {
            throw new IllegalStateException(T.s("总开关状态未能保存"));
        }

        if (enabled) {
            if (turningOn) TimeWindow.restoreDefaultForMasterOn(c);
            Guard.ensureService(c);
            Guard.applyAsync(c);
            Guard.schedule(c);
            NetBlockService.reconcile(c);
        } else {
            if (turningOff) TimeWindow.clearForMasterOff(c);
            // 先排队解冻并撤销限制；停服务不会终止 Guard 的策略线程。
            Guard.applyAsync(c);
            NetBlockService.reconcile(c);
            Guard.cancelSchedules(c);
            c.stopService(new Intent(c, GuardService.class));
        }
        return true;
    }

    /**
     * 总开关与调整时段绑定的纯逻辑：关闭只允许在调整时段或安装宽限期内；
     * 从关打到开随时允许，因为关闭时调整时段已经清零。
     * 不改变状态时也允许，方便幂等调用。
     */
    static boolean allowed(boolean current, boolean requested, boolean inWindow, boolean grace) {
        return current == requested || !current || inWindow || grace;
    }
}
