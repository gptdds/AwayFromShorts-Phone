package com.afs.guard.plugins;

import android.app.Activity;

import com.afs.guard.FreezeBoardActivity;
import com.afs.guard.T;

/** The freeze board can be disabled independently from the check-in tools. */
public final class FreezePlugin implements AppPlugin {
    public static final String ID = "freeze";

    @Override public String id() {
        return ID;
    }

    @Override public String label() {
        return T.s("冻结面板");
    }

    @Override public Class<? extends Activity> activity() {
        return FreezeBoardActivity.class;
    }
}
