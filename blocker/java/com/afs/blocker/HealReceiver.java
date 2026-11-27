package com.afs.blocker;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 定时自愈的落地点。开机也顺便把巡检链重新挂上。 */
public class HealReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(final Context ctx, Intent intent) {
        android.content.SharedPreferences.Editor status = ctx.getSharedPreferences("afs_runtime",Context.MODE_PRIVATE).edit();
        status.putLong("heal_fired",System.currentTimeMillis());
        if (intent != null && Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) status.putLong("boot_at",System.currentTimeMillis());
        status.apply();
        Heal.schedule(ctx);   // 不管什么原因被叫醒，先把下一次挂上
        final PendingResult pr = goAsync();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Heal.healOnce(ctx);
                } catch (Throwable ignored) {
                } finally {
                    pr.finish();
                }
            }
        }, "afs-heal").start();
    }
}
