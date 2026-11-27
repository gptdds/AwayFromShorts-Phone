package com.afs.guard.plugins;

import android.app.Activity;

import com.afs.guard.MemoryActivity;

/** Memory-card page plugin. */
public final class MemoryPlugin implements AppPlugin {
    public static final String ID = "memory";

    @Override public String id() {
        return ID;
    }

    @Override public String label() {
        return "记忆";
    }

    @Override public Class<? extends Activity> activity() {
        return MemoryActivity.class;
    }
}
