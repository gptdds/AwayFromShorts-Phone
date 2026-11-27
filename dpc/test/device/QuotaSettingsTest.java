package com.afs.guard;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import java.util.*;

/** Emulator-only checks for the reset-time entry, freeze settings header and taboo isolation. */
public final class QuotaSettingsTest extends Instrumentation {
    private int checks;

    private void check(boolean ok, String label) {
        checks++;
        if (!ok) throw new AssertionError(label);
    }

    private boolean hasText(View root, String text) {
        if (root == null) return false;
        if (root instanceof TextView) {
            CharSequence got = ((TextView) root).getText();
            if (got != null && text.contentEquals(got)) return true;
        }
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                if (hasText(group.getChildAt(i), text)) return true;
            }
        }
        return false;
    }

    private View byDescription(View root, String description) {
        if (root == null) return null;
        if (description.contentEquals(root.getContentDescription() == null
                ? "" : root.getContentDescription())) return root;
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = byDescription(group.getChildAt(i), description);
                if (found != null) return found;
            }
        }
        return null;
    }

    private View byText(View root, String text) {
        if (root == null) return null;
        if (root instanceof TextView) {
            CharSequence got = ((TextView) root).getText();
            if (got != null && text.contentEquals(got)) return root;
        }
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = byText(group.getChildAt(i), text);
                if (found != null) return found;
            }
        }
        return null;
    }

    private View clickable(View view) {
        for (View at = view; at != null; ) {
            if (at.isClickable()) return at;
            at = at.getParent() instanceof View ? (View) at.getParent() : null;
        }
        return view;
    }

    private void runMain(Runnable action) {
        runOnMainSync(action);
    }

    private Activity open(Class<?> activity, long waitMs) throws Exception {
        Instrumentation.ActivityMonitor monitor = addMonitor(activity.getName(), null, false);
        Context c = getTargetContext();
        runMain(() -> c.startActivity(new Intent(c, activity)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK)));
        Activity screen = waitForMonitorWithTimeout(monitor, 5000);
        removeMonitor(monitor);
        check(screen != null, activity.getSimpleName() + " opens");
        Thread.sleep(waitMs);
        return screen;
    }

    private void capture(String name) {
        java.io.File out = new java.io.File(getTargetContext().getFilesDir(), name + ".png");
        try (android.os.ParcelFileDescriptor descriptor = getUiAutomation().executeShellCommand("screencap -p");
             java.io.InputStream in = new android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor);
             java.io.FileOutputStream stream = new java.io.FileOutputStream(out)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) > 0) stream.write(buffer, 0, read);
        } catch (Exception ignored) { }
    }

    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }

    @Override public void onStart() {
        android.app.UiAutomation automation = getUiAutomation();
        Context c = getTargetContext();
        SharedPreferences boundary = c.getSharedPreferences(DayBoundary.SP, 0);
        Map<String, ?> boundaryBackup = new HashMap<>(boundary.getAll());
        SharedPreferences taboo = c.getSharedPreferences(StudyGate.TABOO_SP, 0);
        Map<String, ?> tabooBackup = new HashMap<>(taboo.getAll());
        SharedPreferences link = c.getSharedPreferences(LearningBindings.SP, 0);
        Map<String, ?> linkBackup = new HashMap<>(link.getAll());
        try {
            check(android.os.Build.FINGERPRINT.contains("generic")
                    || android.os.Build.MODEL.contains("sdk_gphone"), "emulator required");

            /* 重置时间：分钟粒度 + 延迟到下一个原重置点生效 */
            boundary.edit().clear().putBoolean(DayBoundary.KEY_UNIFIED_0, true).commit();
            check(DayBoundary.hour(c) == 0, "default reset is midnight");
            check(DayBoundary.minute(c) == 0, "default reset has zero minute");
            check(DayBoundary.saveTime(c, 6, 30), "save custom reset time");
            check(DayBoundary.selectedHour(c) == 6 && DayBoundary.selectedMinute(c) == 30,
                    "custom hour and minute persisted");
            int active = DayBoundary.hour(c);
            check(active == DayBoundary.DEFAULT_HOUR, "reset change does not apply immediately");
            long pendingAt = boundary.getLong("pending_at", 0);
            check(pendingAt > System.currentTimeMillis(),
                    "pending change waits for a boundary");
            check(DayBoundary.label(6, 30).contains("06:30"), "minute label renders");
            check(DayBoundary.summary(c).contains("生效"), "summary announces pending activation");

            /* 自律一级菜单里出现重置时间入口 */
            Activity settings = open(SettingsActivity.class, 700);
            check(hasText(settings.getWindow().getDecorView(),
                    T.s("每日时间限额重置时间")), "self-control menu exposes reset time");
            runMain(settings::finish);
            Thread.sleep(300);

            /* 冻结页不再有底部设置卡，但右上角设置菜单里有断网设置 */
            Activity board = open(FreezeBoardActivity.class, 1200);
            check(!hasText(board.getWindow().getDecorView(), T.s("断网应用设置")),
                    "footer settings card removed from freeze page");
            check(hasText(board.getWindow().getDecorView(), T.s("冻结时段"))
                    && hasText(board.getWindow().getDecorView(), T.s("时间限额")),
                    "overview shows abstract freeze and quota entries");
            check(!hasText(board.getWindow().getDecorView(), T.s("使用限额")),
                    "overview keeps concrete quota details out of the first screen");
            Thread.sleep(500);
            capture("freeze-overview");
            boolean menuHasNet = false;
            boolean menuHasSiblings = false;
            final View gear = byDescription(board.getWindow().getDecorView(),
                    T.s("冻结设置"));
            check(gear != null, "freeze page keeps the top-right settings gear");
            runMain(gear::performClick);
            for (int i = 0; i < 30 && !menuHasNet; i++) {
                android.view.accessibility.AccessibilityNodeInfo root = automation.getRootInActiveWindow();
                menuHasNet = root != null && !root.findAccessibilityNodeInfosByText(
                        T.s("断网应用设置")).isEmpty();
                menuHasSiblings = root != null
                        && !root.findAccessibilityNodeInfosByText(T.s("冻结时段应用设置")).isEmpty()
                        && !root.findAccessibilityNodeInfosByText(T.s("冻结时段设置")).isEmpty();
                if (!menuHasNet) Thread.sleep(100);
            }
            check(menuHasNet, "top-right settings menu contains network settings");
            check(menuHasSiblings, "freeze app and freeze window settings are sibling entries");
            automation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK);
            Thread.sleep(300);
            final View quotaEntry = byText(board.getWindow().getDecorView(), T.s("时间限额"));
            check(quotaEntry != null, "overview quota entry is clickable");
            runMain(() -> clickable(quotaEntry).performClick());
            Thread.sleep(900);
            capture("freeze-quota-detail");
            check(hasText(board.getWindow().getDecorView(), T.s("使用限额")),
                    "quota detail page shows concrete quotas in the second level");
            check(byDescription(board.getWindow().getDecorView(), T.s("返回冻结总览")) == null,
                    "quota detail page has no top-left back button");
            runMain(board::onBackPressed);
            Thread.sleep(700);
            check(hasText(board.getWindow().getDecorView(), T.s("冻结时段")),
                    "back returns to the overview");
            runMain(board::finish);
            Thread.sleep(300);

            /* 亮屏上限：达到自定义时长就判超；跨额度日归零 */
            SharedPreferences screen = c.getSharedPreferences(ScreenOnLimit.SP, 0);
            Map<String, ?> screenBackup = new HashMap<>(screen.getAll());
            try {
                long nowAt = System.currentTimeMillis();
                String day = DayBoundary.key(c, nowAt);
                screen.edit().putBoolean("enabled", true).putInt("minutes", 60)
                        .putString("day", day).putLong("used_ms", 3600_000L)
                        .putLong("last_sample", nowAt).commit();
                check(ScreenOnLimit.exceeded(c), "screen-on budget triggers at the custom time");
                screen.edit().putLong("used_ms", 3599_000L).commit();
                check(!ScreenOnLimit.exceeded(c), "one minute below the budget stays colorful");
                screen.edit().putString("day", "1970-01-01").putLong("used_ms", 7200_000L).commit();
                check(!ScreenOnLimit.exceeded(c), "new quota day resets the screen-on total");
                screen.edit().putString("day", day).putLong("used_ms", 3600_000L).commit();
                check(ScreenOnLimit.summary(c).contains("灰度"), "summary reports the reached state");
            } finally {
                restore(screen, screenBackup);
            }

            /* 禁忌卡没认领：只算待办，不挂罚时、不进限额判定 */
            link.edit().putBoolean("bind_source_memory", true)
                    .putBoolean("bind_source_quiz", true)
                    .putBoolean("bind_target_quota", true).commit();
            taboo.edit().remove("plan").remove("hold_until").remove("claimed")
                    .putString("items", "[\"QA 禁忌\"]").commit();
            StudyGate.invalidate(c);
            long now = System.currentTimeMillis();
            check(StudyGate.tabooPendingToday(c) > 0, "unfinished taboo stays pending");
            check(!PenaltyStore.any(c, now), "pending taboo adds no penalty");
            check(PenaltyStore.status(c, "com.afs.quota.one", now).isEmpty(),
                    "pending taboo has no per-app hold");
            check(!StudyGate.quotaLearningHold(false, LearningBindings.targetQuota(c)),
                    "taboo pending does not hold the quota link");
            check(StudyGate.quotaLearningHold(true, LearningBindings.targetQuota(c)),
                    "real unfinished learning still holds the quota link");
        } catch (Exception error) {
            throw new RuntimeException(error);
        } finally {
            restore(boundary, boundaryBackup);
            restore(taboo, tabooBackup);
            restore(link, linkBackup);
            StudyGate.invalidate(c);
            try {
                getUiAutomation().executeShellCommand(
                        "am broadcast --include-stopped-packages -a com.afs.guard.WAKE").close();
            } catch (java.io.IOException ignored) { }
        }
        Bundle result = new Bundle();
        result.putString("result", "Quota settings device: " + checks + " checks passed");
        System.out.println("Quota settings device: " + checks + " checks passed");
        finish(Activity.RESULT_OK, result);
    }

    private void restore(SharedPreferences prefs, Map<String, ?> backup) {
        SharedPreferences.Editor edit = prefs.edit().clear();
        for (Map.Entry<String, ?> e : backup.entrySet()) {
            Object value = e.getValue();
            if (value instanceof Boolean) edit.putBoolean(e.getKey(), (Boolean) value);
            else if (value instanceof Integer) edit.putInt(e.getKey(), (Integer) value);
            else if (value instanceof Long) edit.putLong(e.getKey(), (Long) value);
            else if (value instanceof Float) edit.putFloat(e.getKey(), (Float) value);
            else edit.putString(e.getKey(), String.valueOf(value));
        }
        edit.commit();
    }
}
