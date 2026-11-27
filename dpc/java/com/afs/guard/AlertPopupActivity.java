package com.afs.guard;

import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * 通用应用内提醒弹窗：冻结提醒使用全屏半透明页面；待办提醒使用不遮挡
 * 整个界面的小窗卡片。
 *
 * 冻结前提醒和待办定时提醒共用；不进最近任务、按返回也关。
 */
public final class AlertPopupActivity extends ThemedActivity {
    static final String EXTRA_TITLE = "alert_title";
    static final String EXTRA_BODY = "alert_body";
    static final String EXTRA_TOKEN = "alert_token";
    static final String EXTRA_COMPACT = "alert_compact";
    private static final String SP = "afs_alert";
    private static final String KEY_PENDING_TITLE = "pending_title";
    private static final String KEY_PENDING_BODY = "pending_body";
    private static final String KEY_PENDING_TOKEN = "pending_token";
    private static final String KEY_PENDING_AT = "pending_at";
    private static final String KEY_PENDING_COMPACT = "pending_compact";
    private static final long PENDING_TTL_MS = 4L * 60L * 60L * 1000L;

    /**
     * 后台拉起一次提醒。优先直接启动页面；Android 10+ 不允许后台启动 Activity 时挂起，
     * 并发一条 HIGH 的全屏意图通知，回到 App 前台还会补弹一次。
     */
    static void show(Context c, String token, String title, String body) {
        show(c, token, title, body, true, false);
    }

    /** 待办已有自己的通知通道时关掉通用通知，避免同一次到点收到两遍。 */
    static void show(Context c, String token, String title, String body, boolean notify) {
        show(c, token, title, body, notify, false);
    }

    /** 待办提醒使用紧凑窗口，保留原页面可见。 */
    static void showCompact(Context c, String token, String title, String body, boolean notify) {
        show(c, token, title, body, notify, true);
    }

    private static void show(Context c, String token, String title, String body,
                             boolean notify, boolean compact) {
        android.content.SharedPreferences prefs = prefs(c);
        if (token == null || token.isEmpty()) {
            token = "once:" + System.currentTimeMillis();
        }
        if (token.equals(prefs.getString(KEY_PENDING_TOKEN, "")) || showing()) {
            return;
        }
        String last = prefs.getString("last_token", "");
        long lastAt = prefs.getLong("last_at", 0L);
        if (token.equals(last) && System.currentTimeMillis() - lastAt < 60_000L) {
            return;
        }
        prefs.edit().putString("last_token", token).putLong("last_at", System.currentTimeMillis()).commit();
        Intent popup = intent(c, title, body)
                .putExtra(EXTRA_TOKEN, token)
                .putExtra(EXTRA_COMPACT, compact);
        boolean launched = false;
        if (RestReminderActivity.backgroundAllowed(c)) {
            try {
                c.startActivity(popup);
                launched = true;
            } catch (RuntimeException ignored) {
            }
        }
        boolean delivered = notify && postFullScreen(c, token, title, body);
        android.app.NotificationManager manager =
                (android.app.NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null && notify && !launched && !delivered) {
            manager.cancel("alert", Math.abs(token.hashCode()));
        }
        if (!token.equals(prefs.getString(KEY_PENDING_TOKEN, ""))) {
            prefs.edit()
                    .putString(KEY_PENDING_TITLE, title == null ? "" : title)
                    .putString(KEY_PENDING_BODY, body == null ? "" : body)
                    .putString(KEY_PENDING_TOKEN, token)
                    .putLong(KEY_PENDING_AT, System.currentTimeMillis())
                    .putBoolean(KEY_PENDING_COMPACT, compact)
                    .commit();
            if (notify && !launched && !delivered) {
                android.util.Log.w(Guard.TAG, "alert popup deferred until foreground");
            }
        }
    }

    private static final java.lang.ref.WeakReference<AlertPopupActivity> NONE =
            new java.lang.ref.WeakReference<>(null);
    private static java.lang.ref.WeakReference<AlertPopupActivity> current = NONE;

    private static boolean showing() {
        AlertPopupActivity page = current.get();
        return page != null && !page.isFinishing() && !page.isDestroyed();
    }

    /** 从打卡页或首页回到前台时补弹：一条提醒最多补一次，超过 4 小时丢弃。 */
    static void deliverPending(Context c) {
        android.content.SharedPreferences prefs = prefs(c);
        long at = prefs.getLong(KEY_PENDING_AT, 0L);
        if (at <= 0L) {
            return;
        }
        String title = prefs.getString(KEY_PENDING_TITLE, "");
        String body = prefs.getString(KEY_PENDING_BODY, "");
        String token = prefs.getString(KEY_PENDING_TOKEN, "");
        boolean compact = prefs.getBoolean(KEY_PENDING_COMPACT, false);
        prefs.edit().remove(KEY_PENDING_TITLE).remove(KEY_PENDING_BODY)
                .remove(KEY_PENDING_TOKEN).remove(KEY_PENDING_AT)
                .remove(KEY_PENDING_COMPACT).apply();
        if (System.currentTimeMillis() - at > PENDING_TTL_MS) {
            return;
        }
        try {
            c.startActivity(foregroundIntent(c, title, body)
                    .putExtra(EXTRA_TOKEN, token)
                    .putExtra(EXTRA_COMPACT, compact));
        } catch (RuntimeException ignored) {
        }
    }

    private static android.content.SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(SP, Context.MODE_PRIVATE);
    }

    static Intent intent(Context c, String title, String body) {
        return foregroundIntent(c, title, body)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
    }

    private static Intent foregroundIntent(Context c, String title, String body) {
        return new Intent(c, AlertPopupActivity.class)
                .putExtra(EXTRA_TITLE, title == null ? "" : title)
                .putExtra(EXTRA_BODY, body == null ? "" : body);
    }

    private static boolean postFullScreen(Context c, String token, String title, String body) {
        if (Build.VERSION.SDK_INT >= 33
                && c.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        android.app.NotificationManager manager =
                (android.app.NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null || !manager.areNotificationsEnabled()) {
            return false;
        }
        String channel = "afs_alerts_v1";
        if (Build.VERSION.SDK_INT >= 26) {
            android.app.NotificationChannel nc = manager.getNotificationChannel(channel);
            if (nc == null) {
                nc = new android.app.NotificationChannel(channel, T.s("提醒弹窗"),
                        android.app.NotificationManager.IMPORTANCE_HIGH);
                nc.setDescription(T.s("待办和冻结前的到点提醒"));
                nc.setLockscreenVisibility(android.app.Notification.VISIBILITY_PUBLIC);
                manager.createNotificationChannel(nc);
            }
            if (nc.getImportance() == android.app.NotificationManager.IMPORTANCE_NONE) {
                return false;
            }
        }
        Intent popup = foregroundIntent(c, title, body).putExtra(EXTRA_TOKEN, token)
                .setAction("afs-alert:" + (token == null ? "once" : token));
        android.app.PendingIntent full = android.app.PendingIntent.getActivity(c, 0, popup,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE);
        android.app.PendingIntent dismissed = android.app.PendingIntent.getBroadcast(c, 1,
                new Intent(c, AlertDismissReceiver.class).setAction(AlertDismissReceiver.ACTION)
                        .putExtra(EXTRA_TOKEN, token),
                android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE);
        android.app.Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new android.app.Notification.Builder(c, channel)
                : new android.app.Notification.Builder(c);
        builder.setSmallIcon(android.R.drawable.ic_popup_reminder)
                .setContentTitle(title == null ? T.s("提醒") : title)
                .setContentText(body == null ? "" : body)
                .setContentIntent(full)
                .setDeleteIntent(dismissed)
                .setCategory(android.app.Notification.CATEGORY_REMINDER)
                .setPriority(android.app.Notification.PRIORITY_MAX)
                .setAutoCancel(true)
                .setVisibility(android.app.Notification.VISIBILITY_PUBLIC);
        if (Build.VERSION.SDK_INT < 34) {
            builder.setFullScreenIntent(full, true);
        }
        try {
            manager.notify("alert", token == null ? 0 : Math.abs(token.hashCode()), builder.build());
            return true;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        current = new java.lang.ref.WeakReference<>(this);
        onNewIntent(getIntent());
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
            KeyguardManager keys = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
            if (keys != null) keys.requestDismissKeyguard(this, null);
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                    | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setFinishOnTouchOutside(false);
        String title = getIntent().getStringExtra(EXTRA_TITLE);
        String body = getIntent().getStringExtra(EXTRA_BODY);
        String token = getIntent().getStringExtra(EXTRA_TOKEN);
        boolean compact = getIntent().getBooleanExtra(EXTRA_COMPACT, false);
        android.content.SharedPreferences prefs = prefs(this);
        if (token != null && token.equals(prefs.getString(KEY_PENDING_TOKEN, ""))) {
            prefs.edit().remove(KEY_PENDING_TITLE).remove(KEY_PENDING_BODY)
                    .remove(KEY_PENDING_TOKEN).remove(KEY_PENDING_AT)
                    .remove(KEY_PENDING_COMPACT).commit();
        }
        if (token != null && !token.isEmpty()) {
            android.app.NotificationManager manager =
                    (android.app.NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null) manager.cancel("alert", Math.abs(token.hashCode()));
        }
        Ui ui = new Ui(this);
        if (compact) {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN
                    | WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            getWindow().setDimAmount(0f);
            getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            getWindow().setLayout(WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.WRAP_CONTENT);
            getWindow().setGravity(Gravity.BOTTOM);
            setFinishOnTouchOutside(true);
        }
        LinearLayout root = vertical();
        root.setGravity(compact ? Gravity.BOTTOM : Gravity.CENTER);
        root.setPadding(ui.dp(compact ? 14 : 28), compact ? ui.dp(10) : 0,
                ui.dp(compact ? 14 : 28), compact ? ui.dp(14) : 0);
        if (!compact) {
            root.setBackgroundColor(0x99000000);
        }
        root.setOnClickListener(v -> finish());
        LinearLayout card = ui.card(22f, 20f);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setClickable(true);
        TextView heading = text(title == null ? T.s("提醒") : title, 20, ui.text());
        heading.setTypeface(Typeface.DEFAULT_BOLD);
        card.addView(heading);
        if (body != null && !body.isEmpty()) {
            TextView detail = text(body, 14, ui.text2());
            detail.setLineSpacing(ui.dp(4), 1f);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = ui.dp(10);
            card.addView(detail, lp);
        }
        TextView ok = text(T.s(compact ? "去打卡页处理" : "知道了"), 15, ui.onAccent());
        ok.setGravity(Gravity.CENTER);
        ok.setBackground(ui.round(ui.accent(), 14));
        LinearLayout.LayoutParams okLp = new LinearLayout.LayoutParams(-1, ui.dp(46));
        okLp.topMargin = ui.dp(18);
        ok.setOnClickListener(v -> {
            if (compact) {
                startActivity(new Intent(this, CheckinActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
            }
            finish();
        });
        card.addView(ok, okLp);
        if (compact) {
            root.addView(card, new LinearLayout.LayoutParams(-1, -2));
        } else {
            ScrollView wrap = new ScrollView(this);
            wrap.addView(card);
            root.addView(wrap, new LinearLayout.LayoutParams(-1, 0, 1f));
        }
        setContentView(root);
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
    }

    @Override protected void onDestroy() {
        if (current.get() == this) current.clear();
        super.onDestroy();
    }

    private LinearLayout vertical() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        return box;
    }

    private TextView text(String value, float size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }
}
