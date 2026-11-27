package com.afs.guard;

import android.app.Activity;
import android.content.Intent;

import com.afs.guard.plugins.DockPages;

/** 二级页先回打卡主页；其他底栏页面先切到打卡主页，再由打卡页退出到桌面。 */
final class BackToHome {
    private BackToHome() { }

    /** 不是打卡页时切回默认主页；已经在打卡页则返回 false，交给系统退出。 */
    static boolean from(Activity activity, int currentPage) {
        if (activity == null || activity.isFinishing() || currentPage == DockPages.CHECKIN) return false;
        return open(activity);
    }

    static boolean open(Activity activity) {
        DockPages.activities();
        Class<?> page = DockPages.activities()[DockPages.CHECKIN];
        if (!Activity.class.isAssignableFrom(page)) return false;
        Intent home = new Intent(activity, page);
        home.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        home.putExtra(CheckinActivity.EXTRA_HOME, true);
        activity.startActivity(home);
        activity.overridePendingTransition(0, 0);
        return true;
    }
}
