package com.afs.guard;

import android.Manifest;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.AssetFileDescriptor;
import android.media.AudioAttributes;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.Settings;

import java.io.Closeable;
import java.security.MessageDigest;
import java.util.Locale;

/**
 * 待办的本地定时：到点弹一条通知，并把待办推进打卡页。
 *
 * <p>整条链路不碰 Guard、冻结时段或学习数据 —— 通知只是叫人回来看一眼，
 * 待办真正怎么处理由打卡页的上滑/下滑决定。</p>
 */
public final class TodoReminder {
    private TodoReminder() {
    }

    static final String ACTION = "com.afs.guard.TODO_REMINDER";
    /** 旧版默认通道，仅保留 id 兼容；新提醒使用按铃声区分的 v2 通道。 */
    static final String CHANNEL = "todo_reminders_v1";
    private static final String CHANNEL_PREFIX = "todo_reminders_v2_";
    private static final String DEFAULT_CHANNEL = CHANNEL_PREFIX + "default";
    private static final String SILENT_CHANNEL = CHANNEL_PREFIX + "silent";
    private static final int NOTIFICATION_ID = 43;
    private static final long PREVIEW_LIMIT_MS = 12000L;

    private static Ringtone previewRingtone;
    private static Runnable previewStop;

    private static PendingIntent alarm(Context context, String id, long time) {
        Intent intent = new Intent(context, TodoReminderReceiver.class)
                .setAction(ACTION)
                .setData(Uri.parse("afs-todo:reminder/" + Uri.encode(id)))
                .putExtra("id", id)
                .putExtra("at", time);
        return PendingIntent.getBroadcast(context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /**
     * Android 8+ 的声音属于通知通道，且创建后不能再单独改。每种待办声音使用稳定
     * 通道 id：默认铃声、静音、每个自定义 URI 各一条，后台和锁屏也能按该卡播放。
     */
    static String ensureChannel(Context context, String requestedSound) {
        String sound = TodoStore.normalizeSoundUri(requestedSound);
        if (!sound.isEmpty() && !"silent".equals(sound) && !readable(context, Uri.parse(sound))) {
            sound = "";
        }
        String channelId = channelId(sound);
        if (Build.VERSION.SDK_INT < 26) {
            return channelId;
        }
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null || manager.getNotificationChannel(channelId) != null) {
            return channelId;
        }
        String name = "silent".equals(sound)
                ? T.s("待办提醒 · 静音")
                : sound.isEmpty() ? T.s("待办提醒") : T.s("待办提醒 · 自定义铃声");
        NotificationChannel channel = new NotificationChannel(channelId, name,
                NotificationManager.IMPORTANCE_HIGH);
        channel.setDescription(T.s("待办设定的时间到了，每张卡可使用自己的提醒铃声"));
        if ("silent".equals(sound)) {
            channel.setSound(null, null);
            channel.enableVibration(false);
        } else {
            Uri soundUri = sound.isEmpty() ? Settings.System.DEFAULT_NOTIFICATION_URI : Uri.parse(sound);
            channel.setSound(soundUri, new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build());
            channel.enableVibration(true);
        }
        channel.setShowBadge(true);
        channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        manager.createNotificationChannel(channel);
        return channelId;
    }

    private static String channelId(String sound) {
        if ("silent".equals(sound)) {
            return SILENT_CHANNEL;
        }
        if (sound == null || sound.isEmpty()) {
            return DEFAULT_CHANNEL;
        }
        return CHANNEL_PREFIX + digest(sound);
    }

    private static String digest(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes("UTF-8"));
            StringBuilder hex = new StringBuilder();
            for (int index = 0; index < bytes.length && hex.length() < 16; index++) {
                hex.append(String.format(Locale.US, "%02x", bytes[index] & 0xff));
            }
            return hex.toString();
        } catch (Exception ignored) {
            return Integer.toHexString(value.hashCode());
        }
    }

    public static boolean allowed(Context context) {
        if (Build.VERSION.SDK_INT >= 33
                && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null || !manager.areNotificationsEnabled()) {
            return false;
        }
        return true;
    }

    public static void schedule(Context context, TodoStore.Todo todo) {
        if (todo == null) {
            return;
        }
        ensureChannel(context, todo.soundUri);
        // 只换闹钟，不碰已经弹出的通知；守护进程每 10 分钟会重排一次。
        cancelAlarm(context, todo.id);
        if (!todo.pending() || todo.remindAt <= 0L || todo.remindAt <= System.currentTimeMillis()
                || todo.remindedAt == todo.remindAt) {
            return;
        }
        AlarmManager manager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (manager == null) {
            return;
        }
        PendingIntent pending = alarm(context, todo.id, todo.remindAt);
        try {
            // ColorOS 会把普通 setAndAllowWhileIdle 推后很久，能排精确闹钟就用 setAlarmClock。
            boolean exact = true;
            if (Build.VERSION.SDK_INT >= 31) {
                try {
                    exact = manager.canScheduleExactAlarms();
                } catch (Throwable ignored) {
                }
            }
            if (exact) {
                manager.setAlarmClock(new AlarmManager.AlarmClockInfo(todo.remindAt, pending), pending);
                return;
            }
        } catch (RuntimeException ignored) {
        }
        try {
            manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, todo.remindAt, pending);
        } catch (RuntimeException error) {
            try {
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, todo.remindAt, pending);
            } catch (RuntimeException unavailable) {
                android.util.Log.w("afs.todo", "Todo reminder schedule unavailable", unavailable);
            }
        }
    }

    private static void cancelAlarm(Context context, String id) {
        if (id == null) {
            return;
        }
        AlarmManager manager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (manager != null) {
            manager.cancel(alarm(context, id, 0L));
        }
    }

    public static void cancel(Context context, String id) {
        cancelAlarm(context, id);
        if (id == null) {
            return;
        }
        NotificationManager notifications = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (notifications != null) {
            notifications.cancel(id, NOTIFICATION_ID);
        }
    }

    /**
     * 把待办的闹钟和状态对齐：未来项重排；到点但没通知过的（闹钟被系统清掉、
     * 或通知权限刚打开）补发一次。守护节拍和首页回到前台都会走这里。
     */
    public static void reconcile(Context context) {
        long now = System.currentTimeMillis();
        for (TodoStore.Todo todo : TodoStore.all(context)) {
            if (todo == null || !todo.pending()) {
                continue;
            }
            if (todo.remindAt > 0L && todo.remindAt <= now && todo.remindedAt != todo.remindAt) {
                fire(context, todo.id, todo.remindAt);
            } else {
                schedule(context, todo);
            }
        }
    }

    public static void reschedule(Context context) {
        reconcile(context);
    }

    /** 闹钟被系统清掉（覆盖安装、重启、省电策略）时靠守护节拍补排；10 分钟节流。 */
    private static long lastRescheduleAt;

    public static void rescheduleThrottled(Context context) {
        long now = System.currentTimeMillis();
        if (lastRescheduleAt != 0L && now >= lastRescheduleAt && now - lastRescheduleAt < 10 * 60 * 1000L) {
            return;
        }
        lastRescheduleAt = now;
        try {
            reschedule(context);
        } catch (RuntimeException ignored) {
        }
    }

    public static void fire(Context context, String id, long expected) {
        TodoStore.Todo todo = TodoStore.claimReminder(context, id, expected, System.currentTimeMillis());
        if (todo == null) {
            return;
        }
        String channel = ensureChannel(context, todo.soundUri);
        // 到点先弹应用内弹窗；后台拉不起时还有全屏意图通知和回前台补弹。
        String todoTitle = TodoStore.displayTitle(todo);
        String todoDetail = TodoStore.oneLine(todo.detail);
        String notice = todoDetail.isEmpty() ? T.s("上滑完成，下滑延时提醒") : todoDetail;
        AlertPopupActivity.showCompact(context, "todo:" + todo.id + ":" + todo.remindAt,
                T.s("待办到时间了 · ") + todoTitle, notice, false);
        if (!allowed(context)) {
            return;
        }
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) {
            return;
        }
        Intent target = new Intent(context, CheckinActivity.class)
                .setData(Uri.parse("afs-todo:open/" + Uri.encode(id)))
                .putExtra("todo_id", id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent open = PendingIntent.getActivity(context, 0, target,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = builder(context, channel, todo.soundUri);
        String title = TodoStore.displayTitle(todo);
        String detail = TodoStore.oneLine(todo.detail);
        if (detail.length() > 60) {
            detail = detail.substring(0, 60) + "…";
        }
        builder.setSmallIcon(android.R.drawable.ic_popup_reminder)
                .setContentTitle(T.s("待办到时间了 · ") + title)
                .setContentText(detail.isEmpty() ? T.s("上滑完成，下滑延时提醒") : detail)
                .setSubText(T.s("待办卡"))
                .setContentIntent(open)
                .setWhen(todo.remindAt > 0L ? todo.remindAt : System.currentTimeMillis())
                .setCategory(Notification.CATEGORY_REMINDER)
                .setPriority(Notification.PRIORITY_MAX)
                .setAutoCancel(true)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setColor(0xFF3D7BF5)
                .setGroup("todo_reminders");
        try {
            manager.notify(id, NOTIFICATION_ID, builder.build());
        } catch (SecurityException ignored) {
        }
    }

    /** 待办页的「发送测试通知」：不碰任何待办数据，只验证系统横幅/权限。 */
    public static boolean postTest(Context context) {
        return postTest(context, "");
    }

    public static boolean postTest(Context context, String soundUri) {
        String channel = ensureChannel(context, soundUri);
        if (!allowed(context)) {
            return false;
        }
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) {
            return false;
        }
        Intent target = new Intent(context, TodoActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent open = PendingIntent.getActivity(context, 0, target,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = builder(context, channel, soundUri);
        builder.setSmallIcon(android.R.drawable.ic_popup_reminder)
                .setContentTitle(T.s("待办提醒测试"))
                .setContentText(T.s("如果你看到这条通知，到点提醒可以正常工作。"))
                .setSubText(T.s("待办卡"))
                .setContentIntent(open)
                .setCategory(Notification.CATEGORY_REMINDER)
                .setPriority(Notification.PRIORITY_MAX)
                .setAutoCancel(true)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setColor(0xFF3D7BF5)
                .setGroup("todo_reminders");
        try {
            manager.notify("todo-test", NOTIFICATION_ID, builder.build());
            return true;
        } catch (SecurityException ignored) {
            return false;
        }
    }

    private static Notification.Builder builder(Context context, String channel, String soundUri) {
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(context, channel) : new Notification.Builder(context);
        if (Build.VERSION.SDK_INT < 26) {
            String sound = TodoStore.normalizeSoundUri(soundUri);
            if ("silent".equals(sound)) {
                builder.setSound(null);
                builder.setVibrate(null);
            } else {
                Uri uri = sound.isEmpty() ? Settings.System.DEFAULT_NOTIFICATION_URI : Uri.parse(sound);
                if (!sound.isEmpty() && !readable(context, uri)) {
                    uri = Settings.System.DEFAULT_NOTIFICATION_URI;
                }
                builder.setSound(uri)
                        .setVibrate(new long[]{0L, 250L, 150L, 250L});
            }
        }
        return builder;
    }

    private static boolean readable(Context context, Uri uri) {
        if (uri == null) {
            return false;
        }
        try {
            AssetFileDescriptor descriptor = context.getContentResolver().openAssetFileDescriptor(uri, "r");
            if (descriptor != null) {
                close(descriptor);
                return true;
            }
        } catch (SecurityException ignored) {
            return false;
        } catch (Exception ignored) {
        }
        try {
            ParcelFileDescriptor descriptor = context.getContentResolver().openFileDescriptor(uri, "r");
            if (descriptor != null) {
                close(descriptor);
            }
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static void close(Closeable closeable) {
        try {
            closeable.close();
        } catch (Exception ignored) {
        }
    }

    static String soundName(Context context, String soundUri) {
        String sound = TodoStore.normalizeSoundUri(soundUri);
        if (sound.isEmpty()) {
            return T.s("系统默认铃声");
        }
        if ("silent".equals(sound)) {
            return T.s("静音");
        }
        try {
            Ringtone ringtone = RingtoneManager.getRingtone(context, Uri.parse(sound));
            String title = ringtone == null ? "" : ringtone.getTitle(context);
            return title == null || title.trim().isEmpty() ? T.s("自定义铃声") : title.trim();
        } catch (RuntimeException ignored) {
            return T.s("自定义铃声");
        }
    }

    public static boolean playPreview(Context context, String soundUri) {
        stopPreview();
        String sound = TodoStore.normalizeSoundUri(soundUri);
        if ("silent".equals(sound)) {
            return true;
        }
        Uri uri = sound.isEmpty() ? Settings.System.DEFAULT_NOTIFICATION_URI : Uri.parse(sound);
        if (!sound.isEmpty() && !readable(context, uri)) {
            uri = Settings.System.DEFAULT_NOTIFICATION_URI;
        }
        try {
            Ringtone ringtone = RingtoneManager.getRingtone(context.getApplicationContext(), uri);
            if (ringtone == null) {
                return false;
            }
            previewRingtone = ringtone;
            ringtone.play();
            previewStop = TodoReminder::stopPreview;
            new Handler(Looper.getMainLooper()).postDelayed(previewStop, PREVIEW_LIMIT_MS);
            return true;
        } catch (RuntimeException ignored) {
            stopPreview();
            return false;
        }
    }

    public static void stopPreview() {
        if (previewRingtone != null) {
            try {
                if (previewRingtone.isPlaying()) {
                    previewRingtone.stop();
                }
            } catch (RuntimeException ignored) {
            }
            previewRingtone = null;
        }
        if (previewStop != null) {
            new Handler(Looper.getMainLooper()).removeCallbacks(previewStop);
            previewStop = null;
        }
    }
}
