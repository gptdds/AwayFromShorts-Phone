package com.afs.guard;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 待办定时到点弹通知；开机/换时区后把没发过的提醒重新排上。 */
public final class TodoReminderReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) {
            return;
        }
        String action = intent.getAction();
        if (TodoReminder.ACTION.equals(action)) {
            String id = intent.getStringExtra("id");
            if (id != null) {
                TodoReminder.fire(context, id, intent.getLongExtra("at", 0L));
            }
        } else if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)
                || Intent.ACTION_TIME_CHANGED.equals(action)
                || Intent.ACTION_TIMEZONE_CHANGED.equals(action)) {
            TodoReminder.reschedule(context);
        }
    }
}
