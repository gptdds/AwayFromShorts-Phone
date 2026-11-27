package com.afs.guard.plugins;

import android.app.Activity;

import com.afs.guard.CheckinActivity;

/** Check-in and learning-card page plugin. */
public final class CheckinPlugin implements AppPlugin {
    public static final String ID = "checkin";

    @Override public String id() {
        return ID;
    }

    @Override public String label() {
        return "打卡";
    }

    @Override public Class<? extends Activity> activity() {
        return CheckinActivity.class;
    }
}
