package com.afs.guard;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 用户划掉提醒通知时，撤掉「回前台补弹」那一份，避免关了又冒出来。 */
public final class AlertDismissReceiver extends BroadcastReceiver {
    static final String ACTION = "com.afs.guard.ALERT_DISMISSED";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION.equals(intent.getAction())) {
            return;
        }
        String token = intent.getStringExtra(AlertPopupActivity.EXTRA_TOKEN);
        android.content.SharedPreferences prefs =
                context.getSharedPreferences("afs_alert", Context.MODE_PRIVATE);
        if (token == null || token.equals(prefs.getString("pending_token", ""))) {
            prefs.edit().remove("pending_title").remove("pending_body")
                    .remove("pending_token").remove("pending_at").apply();
        }
    }
}
