package com.afs.blocker;

import android.content.Context;
import android.os.SystemClock;

final class RuntimeStatus {
    static volatile boolean connected;
    static volatile long tickAt;

    private RuntimeStatus() {}

    static boolean healthy() { return connected && tickAt > 0 && SystemClock.elapsedRealtime() - tickAt < 12000L; }

    static void result(Context context, boolean success, String detail) {
        android.content.SharedPreferences.Editor edit = context.getSharedPreferences("afs_runtime",Context.MODE_PRIVATE).edit();
        if (success) edit.putLong("last_success",System.currentTimeMillis()).putString("last_action",detail);
        else edit.putLong("last_failure_at",System.currentTimeMillis()).putString("last_failure",detail);
        edit.apply();
    }
}
