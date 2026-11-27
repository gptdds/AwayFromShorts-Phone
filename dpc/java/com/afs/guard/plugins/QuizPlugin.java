package com.afs.guard.plugins;

import android.app.Activity;

import com.afs.guard.QuizActivity;

/** Question-bank page plugin. */
public final class QuizPlugin implements AppPlugin {
    public static final String ID = "quiz";

    @Override public String id() {
        return ID;
    }

    @Override public String label() {
        return "刷题";
    }

    @Override public Class<? extends Activity> activity() {
        return QuizActivity.class;
    }
}
