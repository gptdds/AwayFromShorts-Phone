package com.afs.guard;

import android.app.Activity;
import android.app.ActivityManager;
import android.util.Log;
import android.app.admin.DevicePolicyManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.ImageView;
import android.graphics.BitmapFactory;
import java.io.InputStream;
import java.lang.ref.WeakReference;

/** Reminder in its own task; device-owner installs prevent app switching until dismissal. */
public final class RestReminderActivity extends Activity {
    private static WeakReference<RestReminderActivity> current = new WeakReference<>(null);
    private ImageView artwork;
    private int slide;
    private final Handler timer = new Handler(Looper.getMainLooper());
    /** 到点自己退场：这条是轻提醒，不该一直压在屏幕上等人点。 */
    private final Runnable autoClose = () -> { if (!isFinishing()) RestAlert.clear(this); };
    /** 这一次弹出来的时刻：15 秒内一律不给关（返回键、点外面都不理）。 */
    private long openedAt;
    private boolean ownsLockTask;
    private boolean closing;
    private long unlockDeadline;

    @Override protected void onResume() {
        super.onResume();
        if (!isFinishing() && !closing) enterReminderLock();
    }

    private void enterReminderLock() {
        if (ownsLockTask) return;
        DevicePolicyManager policy = (DevicePolicyManager)getSystemService(Context.DEVICE_POLICY_SERVICE);
        ActivityManager activities = (ActivityManager)getSystemService(Context.ACTIVITY_SERVICE);
        if (policy == null || activities == null || !policy.isDeviceOwnerApp(getPackageName())) return;
        // Another page (for example the focus timer) owns an existing lock. Leave it intact.
        if (activities.getLockTaskModeState() != ActivityManager.LOCK_TASK_MODE_NONE) return;
        try {
            if (!policy.isLockTaskPermitted(getPackageName())) {
                java.util.Set<String> packages = new java.util.LinkedHashSet<>(
                        java.util.Arrays.asList(policy.getLockTaskPackages(Guard.admin(this))));
                packages.add(getPackageName());
                policy.setLockTaskPackages(Guard.admin(this), packages.toArray(new String[0]));
            }
            // Only DO-whitelisted apps enter here: never show the escapable screen-pinning prompt.
            startLockTask();
            // The system applies the mode asynchronously. Track our accepted request so
            // dismissal still stops it even before ActivityManager reports LOCKED.
            ownsLockTask = true;
        } catch (RuntimeException error) {
            Log.w(Guard.TAG, "rest reminder lock failed", error);
        }
    }

    private void leaveReminderLock() {
        if (ownsLockTask) {
            try {
                stopLockTask();
                ownsLockTask = false;
            } catch (RuntimeException error) {
                // Keep ownership so a later dismissal can retry our own request.
                Log.w(Guard.TAG, "rest reminder unlock failed", error);
            }
        }
    }

    @Override public void finish() {
        if (closing) return;
        closing = true;
        boolean releasing = ownsLockTask;
        leaveReminderLock();
        // Unlock is processed asynchronously by the system. Finishing the root task in
        // the same turn can be rejected while that task is still marked as locked.
        if (releasing) {
            unlockDeadline = android.os.SystemClock.uptimeMillis() + 2000L;
            timer.post(this::finishAfterUnlock);
        } else super.finish();
    }

    private void finishAfterUnlock() {
        ActivityManager activities = (ActivityManager)getSystemService(Context.ACTIVITY_SERVICE);
        if (isDestroyed() || isFinishing()) return;
        // Global mode may belong to a different task by now. Never wait indefinitely
        // for NONE and never stop someone else's lock to make our page finish.
        if (activities != null && activities.getLockTaskModeState() != ActivityManager.LOCK_TASK_MODE_NONE
                && android.os.SystemClock.uptimeMillis() < unlockDeadline) {
            timer.postDelayed(this::finishAfterUnlock, 50L);
            return;
        }
        super.finish();
        if (!isFinishing()) {
            Log.w(Guard.TAG, "rest reminder finish rejected after unlock wait");
            closing = false; // Permit a subsequent explicit dismissal to retry.
        }
    }

    static Intent intent(Context c, int minutes) {
        return new Intent(c, RestReminderActivity.class).putExtra("minutes", minutes)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
    }

    static boolean backgroundAllowed(Context c) {
        DevicePolicyManager policy = (DevicePolicyManager)c.getSystemService(Context.DEVICE_POLICY_SERVICE);
        return (policy != null && policy.isDeviceOwnerApp(c.getPackageName())) || Settings.canDrawOverlays(c);
    }

    static void dismissCurrent() {
        new Handler(Looper.getMainLooper()).post(() -> {
            RestReminderActivity activity = current.get();
            // This task is already excluded from recents. finish() also works on API 24,
            // whose window manager can crash when removing a reused reminder task.
            if (activity != null && !activity.isFinishing()) activity.finish();
        });
    }

    /**
     * 提醒页面此刻在不在屏幕上。
     *
     * 进程刚被拉起来时这里必然是 false —— 页面随旧进程一起没了，
     * 正是 {@link RestAlert#tick} 判断「要不要补弹」的信号。
     */
    static boolean showing() {
        RestReminderActivity activity = current.get();
        return activity != null && !activity.isFinishing();
    }

    @Override protected void attachBaseContext(Context base) {
        T.init(base);
        super.attachBaseContext(ThemeStore.context(base));
    }

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        current = new WeakReference<>(this);
        slide = saved == null ? ReminderStore.takeNextCard(this) : saved.getInt("rest_card", 0);
        ReminderStore.setPending(this, 0L);   // 页面真出来了，待补的那笔就算消化掉
        // 点卡片外面不能关：这 15 秒是要人真的停一下的
        setFinishOnTouchOutside(false);
        getWindow().setWindowAnimations(0);
        openedAt = System.currentTimeMillis();
        render();
        armAutoClose();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        slide = ReminderStore.takeNextCard(this);
        openedAt = System.currentTimeMillis();
        render();
        armAutoClose();   // 换了一张卡，倒计时重新开始
    }

    /** 15 秒的强制停留过完了没有。没到点就什么关闭路径都不生效。 */
    private boolean dismissible() {
        return System.currentTimeMillis() - openedAt >= ReminderStore.AUTO_DISMISS_MS;
    }

    /**
     * 排上「15 秒后自己消失」。
     *
     * 选了「点一下才消失」就不排，卡片留在屏幕上等人确认（老行为）。
     * 但两种模式都躲不开那 15 秒的强制停留 —— 见 {@link #dismissible()}。
     * 每次都先清掉旧的，避免换卡时残留一个计到上一张卡上的回调。
     */
    private void armAutoClose() {
        timer.removeCallbacks(autoClose);
        if (!ReminderStore.confirm(this)) timer.postDelayed(autoClose, ReminderStore.AUTO_DISMISS_MS);
    }

    private void render() {
        android.graphics.Bitmap bitmap;
        try (InputStream input = getAssets().open("rest_cards/card_" + (slide + 1) + ".png")) {
            bitmap = BitmapFactory.decodeStream(input);
        } catch (java.io.IOException error) {
            finish();
            return;
        }
        if (bitmap == null) { finish(); return; }
        Ui ui = new Ui(this);
        int minutes = Math.max(1, Math.min(RestReminder.MAX_EVERY,
                getIntent().getIntExtra("minutes", RestReminder.DEFAULT_EVERY)));
        RestReminderCard card = new RestReminderCard(this, bitmap, slide,
                T.f("已经连续 %d 分钟没休息，站起来走走，看看远处。", minutes),
                ReminderStore.confirm(this),
                () -> { if (dismissible()) RestAlert.clear(this); });
        artwork = card.artwork;
        setContentView(card);
        getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
        getWindow().getDecorView().setPadding(0, 0, 0, 0);
        android.util.DisplayMetrics metrics = getResources().getDisplayMetrics();
        float ratio = bitmap.getWidth() / (float) bitmap.getHeight();
        int width = Math.min(metrics.widthPixels - ui.dp(32), ui.dp(560));
        width = Math.min(width, (int)((metrics.heightPixels - ui.dp(96)) * ratio));
        getWindow().setLayout(width, Math.round(width / ratio));
    }

    /** 15 秒内按返回不关：想走就得把这 15 秒走完。 */
    @Override public void onBackPressed() { if (dismissible()) RestAlert.clear(this); }

    @Override protected void onSaveInstanceState(Bundle out) {
        out.putInt("rest_card", slide);
        super.onSaveInstanceState(out);
    }

    @Override protected void onDestroy() {
        leaveReminderLock();
        timer.removeCallbacksAndMessages(null);
        if (artwork != null) artwork.setImageDrawable(null);
        if(current.get()==this) current.clear();
        super.onDestroy();
    }
}

