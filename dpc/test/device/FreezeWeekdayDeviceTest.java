package com.afs.guard;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.Calendar;

/** Physical-device check for the global weekday pause in the freeze board. */
public final class FreezeWeekdayDeviceTest extends Instrumentation {
    private int checks;
    private Context target;
    private UiAutomation automation;

    private void check(boolean value, String label) {
        checks++;
        if (!value) throw new AssertionError(label);
    }

    private static String nodeText(AccessibilityNodeInfo node) {
        CharSequence text = node.getText();
        if (text == null) text = node.getContentDescription();
        return text == null ? "" : text.toString();
    }

    private static void dump(AccessibilityNodeInfo node, int depth, StringBuilder out) {
        if (node == null) return;
        for (int i = 0; i < depth; i++) out.append("  ");
        android.graphics.Rect bounds = new android.graphics.Rect();
        node.getBoundsInScreen(bounds);
        out.append(node.getClassName()).append(" text=").append(nodeText(node))
                .append(" click=").append(node.isClickable())
                .append(" check=").append(node.isCheckable())
                .append(" bounds=").append(bounds).append('\n');
        for (int i = 0; i < node.getChildCount(); i++) dump(node.getChild(i), depth + 1, out);
    }

    private AccessibilityNodeInfo find(AccessibilityNodeInfo root, String[] labels,
                                       boolean exact, boolean clickable) {
        if (root == null) return null;
        String value = nodeText(root);
        boolean matches = false;
        for (String label : labels) {
            matches |= exact ? value.equals(label) : value.contains(label);
        }
        if (matches) {
            AccessibilityNodeInfo targetNode = root;
            if (clickable) {
                while (targetNode != null && !targetNode.isClickable()) {
                    targetNode = targetNode.getParent();
                }
            }
            if (targetNode != null && (!clickable || targetNode.isClickable())) return targetNode;
        }
        for (int i = 0; i < root.getChildCount(); i++) {
            AccessibilityNodeInfo found = find(root.getChild(i), labels, exact, clickable);
            if (found != null) return found;
        }
        return null;
    }

    private AccessibilityNodeInfo waitNode(String[] labels, boolean exact, boolean clickable) {
        long deadline = SystemClock.uptimeMillis() + 6000L;
        do {
            AccessibilityNodeInfo root = automation.getRootInActiveWindow();
            AccessibilityNodeInfo found = find(root, labels, exact, clickable);
            if (found != null) return found;
            SystemClock.sleep(120);
        } while (SystemClock.uptimeMillis() < deadline);
        return null;
    }

    private AccessibilityNodeInfo findScrollable(AccessibilityNodeInfo root) {
        if (root == null) return null;
        if (root.isScrollable()) return root;
        for (int i = 0; i < root.getChildCount(); i++) {
            AccessibilityNodeInfo found = findScrollable(root.getChild(i));
            if (found != null) return found;
        }
        return null;
    }

    private AccessibilityNodeInfo waitClickableWithScroll(String label, boolean exact) {
        long deadline = SystemClock.uptimeMillis() + 6000L;
        do {
            AccessibilityNodeInfo root = automation.getRootInActiveWindow();
            AccessibilityNodeInfo found = find(root, new String[]{label}, exact, true);
            if (found != null) return found;
            AccessibilityNodeInfo scroll = findScrollable(root);
            if (scroll != null) scroll.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
            SystemClock.sleep(220);
        } while (SystemClock.uptimeMillis() < deadline);
        return null;
    }

    private void click(String label) {
        AccessibilityNodeInfo node = waitClickableWithScroll(label, false);
        check(node != null, label + " is visible and clickable");
        check(node.performAction(AccessibilityNodeInfo.ACTION_CLICK),
                label + " accepts an accessibility tap");
        SystemClock.sleep(350);
        waitForIdleSync();
    }

    private void clickExact(String label) {
        AccessibilityNodeInfo node = waitClickableWithScroll(label, true);
        check(node != null, label + " is visible and clickable");
        check(node.performAction(AccessibilityNodeInfo.ACTION_CLICK),
                label + " accepts an accessibility tap");
        SystemClock.sleep(300);
        waitForIdleSync();
    }

    private void writePolicy(int days, int scopes) {
        check(target.getSharedPreferences("afs_guard", Context.MODE_PRIVATE).edit()
                        .putInt("freeze_weekday_disabled_mask", days)
                        .putInt("freeze_weekday_pause_scopes", scopes)
                        .commit(),
                "test policy fixture saved");
    }

    private void writeAdjustmentWindow(int start, int end) {
        check(target.getSharedPreferences("afs_time_window", Context.MODE_PRIVATE).edit()
                        .putInt("start", start)
                        .putInt("end", end)
                        .putBoolean("cleared", false)
                        .putBoolean("default_22_23", true)
                        .commit(),
                "test adjustment-window fixture saved");
    }

    private static void restoreInt(Context context, String file, String key,
                                   boolean existed, int value) {
        android.content.SharedPreferences prefs = context.getSharedPreferences(file, Context.MODE_PRIVATE);
        android.content.SharedPreferences.Editor edit = prefs.edit();
        if (existed) edit.putInt(key, value); else edit.remove(key);
        edit.commit();
    }

    private static void restoreBoolean(Context context, String file, String key,
                                       boolean existed, boolean value) {
        android.content.SharedPreferences prefs = context.getSharedPreferences(file, Context.MODE_PRIVATE);
        android.content.SharedPreferences.Editor edit = prefs.edit();
        if (existed) edit.putBoolean(key, value); else edit.remove(key);
        edit.commit();
    }

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        start();
    }

    @Override
    public void onStart() {
        Bundle result = new Bundle();
        int code = 1;
        target = getTargetContext();
        automation = getUiAutomation();
        AccessibilityServiceInfo info = automation.getServiceInfo();
        info.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
        automation.setServiceInfo(info);

        android.content.SharedPreferences guardPrefs =
                target.getSharedPreferences("afs_guard", Context.MODE_PRIVATE);
        android.content.SharedPreferences timePrefs =
                target.getSharedPreferences("afs_time_window", Context.MODE_PRIVATE);
        int originalPolicy = FreezeWeekdayPolicy.disabledDays(target);
        int originalScopes = FreezeWeekdayPolicy.pauseScopes(target);
        boolean scopesExisted = guardPrefs.contains("freeze_weekday_pause_scopes");
        boolean startExisted = timePrefs.contains("start");
        boolean endExisted = timePrefs.contains("end");
        boolean defaultExisted = timePrefs.contains("default_22_23");
        int originalStart = TimeWindow.start(target);
        int originalEnd = TimeWindow.end(target);
        boolean originalDefault = timePrefs.getBoolean("default_22_23", false);
        String originalRules = RuleStore.exportRules(target);
        String originalSlots = guardPrefs.getString("freeze_slots", "[]");
        Activity activity = null;
        try {
            writeAdjustmentWindow(0, 0);
            writePolicy(0, FreezeWeekdayPolicy.ALL_SCOPES);
            Guard.applyAsync(target);

            ActivityMonitor monitor = addMonitor(FreezeBoardActivity.class.getName(), null, false);
            runOnMainSync(() -> target.startActivity(new Intent(target, FreezeBoardActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
            activity = monitor.waitForActivityWithTimeout(7000);
            check(activity != null, "freeze board opens on the physical device");
            waitForIdleSync();
            SystemClock.sleep(700);

            check(waitNode(new String[]{"冻结时段", "Freeze windows"}, false, false) != null,
                    "freeze windows card is visible");
            check(waitNode(new String[]{"全局按星期停用", "Pause freeze windows by weekday"},
                    false, false) != null, "weekday pause entry is visible");
            click("全局按星期停用");
            check(waitNode(new String[]{"全局按星期停用", "Pause all restrictions by weekday"},
                    true, false) != null,
                    "weekday picker dialog opens");
            clickExact("周六");
            clickExact("周日");
            clickExact("保存");
            SystemClock.sleep(900);
            waitForIdleSync();

            check(FreezeWeekdayPolicy.disabledDays(target) == 0x60,
                    "Saturday and Sunday persist as a Monday-first mask");
            check(FreezeWeekdayPolicy.pauseScopes(target) == FreezeWeekdayPolicy.ALL_SCOPES,
                    "global mode persists all five pause scopes");
            Calendar saturday = Calendar.getInstance();
            saturday.clear();
            saturday.set(2026, Calendar.OCTOBER, 24, 2, 0);
            check(FreezeWeekdayPolicy.pausedOn(0x60, FreezeWeekdayPolicy.ALL_SCOPES,
                            FreezeWeekdayPolicy.SCOPE_SCHEDULE, saturday)
                            && FreezeWeekdayPolicy.pausedOn(0x60, FreezeWeekdayPolicy.ALL_SCOPES,
                            FreezeWeekdayPolicy.SCOPE_QUOTA, saturday)
                            && FreezeWeekdayPolicy.pausedOn(0x60, FreezeWeekdayPolicy.ALL_SCOPES,
                            FreezeWeekdayPolicy.SCOPE_OR_SET, saturday)
                            && FreezeWeekdayPolicy.pausedOn(0x60, FreezeWeekdayPolicy.ALL_SCOPES,
                            FreezeWeekdayPolicy.SCOPE_STUDY, saturday)
                            && FreezeWeekdayPolicy.pausedOn(0x60, FreezeWeekdayPolicy.ALL_SCOPES,
                            FreezeWeekdayPolicy.SCOPE_PENALTY, saturday),
                    "global weekday mode releases schedule, quota, OR-set, study and penalties");
            Rule night = new Rule(127, new int[][]{{0, 360}}, true, true);
            check(Guard.blockReason(night, false, false, false, false, saturday, false, true) == 0,
                    "weekday pause releases the app's own freeze window");
            check(originalRules.equals(RuleStore.exportRules(target)),
                    "weekday pause does not rewrite app rules");
            check(originalSlots.equals(guardPrefs.getString("freeze_slots", "[]")),
                    "weekday pause does not rewrite freeze slots");
            check(waitNode(new String[]{"全局暂停", "Global pause"}, false, false) != null,
                    "freeze board reports the global pause scope");

            click("全局按星期停用");
            check(waitNode(new String[]{"全局按星期停用", "Pause all restrictions by weekday"},
                    true, false) != null,
                    "weekday picker reopens with saved selections");
            clickExact("自定义范围");
            clickExact("冻结时段");
            clickExact("学习联动");
            clickExact("罚时");
            clickExact("周日");
            clickExact("保存");
            SystemClock.sleep(900);
            check(FreezeWeekdayPolicy.disabledDays(target) == 0x20,
                    "custom mode persists the selected Saturday only");
            check(FreezeWeekdayPolicy.pauseScopes(target)
                            == (FreezeWeekdayPolicy.SCOPE_QUOTA | FreezeWeekdayPolicy.SCOPE_OR_SET),
                    "custom mode persists only quota and OR-set scopes");
            check(FreezeWeekdayPolicy.pausedOn(0x20,
                            FreezeWeekdayPolicy.SCOPE_QUOTA | FreezeWeekdayPolicy.SCOPE_OR_SET,
                            FreezeWeekdayPolicy.SCOPE_QUOTA, saturday)
                            && FreezeWeekdayPolicy.pausedOn(0x20,
                            FreezeWeekdayPolicy.SCOPE_QUOTA | FreezeWeekdayPolicy.SCOPE_OR_SET,
                            FreezeWeekdayPolicy.SCOPE_OR_SET, saturday)
                            && !FreezeWeekdayPolicy.pausedOn(0x20,
                            FreezeWeekdayPolicy.SCOPE_QUOTA | FreezeWeekdayPolicy.SCOPE_OR_SET,
                            FreezeWeekdayPolicy.SCOPE_STUDY, saturday)
                            && !FreezeWeekdayPolicy.pausedOn(0x20,
                            FreezeWeekdayPolicy.SCOPE_QUOTA | FreezeWeekdayPolicy.SCOPE_OR_SET,
                            FreezeWeekdayPolicy.SCOPE_PENALTY, saturday),
                    "custom weekday mode releases only its selected families");
            check(waitNode(new String[]{"自定义：时间限额、或集", "Custom: Quota, OR sets"},
                    false, false) != null, "freeze board reports the custom scope list");

            Calendar now = GuardClock.now(target);
            int minute = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);
            writeAdjustmentWindow((minute + 10) % 1440, (minute + 20) % 1440);
            boolean rejectedOutside = false;
            try {
                FreezeWeekdayPolicy.setPolicy(target, 0x7f, FreezeWeekdayPolicy.ALL_SCOPES);
            } catch (IllegalStateException expected) {
                rejectedOutside = true;
            }
            check(rejectedOutside
                            && FreezeWeekdayPolicy.disabledDays(target) == 0x20
                            && FreezeWeekdayPolicy.pauseScopes(target)
                            == (FreezeWeekdayPolicy.SCOPE_QUOTA | FreezeWeekdayPolicy.SCOPE_OR_SET),
                    "policy writes are rejected outside the adjustment window");
            click("全局按星期停用");
            check(waitNode(new String[]{"当前只能查看", "View only"},
                    false, false) != null,
                    "weekday policy opens read-only outside the adjustment window");
            check(find(automation.getRootInActiveWindow(), new String[]{"保存"}, true, true) == null,
                    "read-only weekday policy exposes no save action");
            clickExact("关闭");

            writeAdjustmentWindow(0, 0);
            click("全局按星期停用");
            clickExact("周六");
            clickExact("保存");
            SystemClock.sleep(900);
            check(FreezeWeekdayPolicy.disabledDays(target) == 0,
                    "clearing every weekday restores the normal freeze schedule");
            check(originalRules.equals(RuleStore.exportRules(target)),
                    "clearing weekday pause leaves app rules unchanged");

            result.putString("stream", "Freeze weekday device: " + checks + " checks passed\n");
            code = 0;
        } catch (Throwable error) {
            StringBuilder tree = new StringBuilder();
            dump(automation.getRootInActiveWindow(), 0, tree);
            result.putString("stream", "FAILED " + android.util.Log.getStackTraceString(error)
                    + "\nUI TREE\n" + tree);
        } finally {
            restoreInt(target, "afs_guard", "freeze_weekday_disabled_mask", true, originalPolicy);
            restoreInt(target, "afs_guard", "freeze_weekday_pause_scopes", scopesExisted, originalScopes);
            restoreInt(target, "afs_time_window", "start", startExisted, originalStart);
            restoreInt(target, "afs_time_window", "end", endExisted, originalEnd);
            restoreBoolean(target, "afs_time_window", "default_22_23",
                    defaultExisted, originalDefault);
            Guard.applyAsync(target);
            if (activity != null && !activity.isFinishing()) {
                Activity closing = activity;
                runOnMainSync(closing::finish);
            }
        }
        finish(code, result);
    }
}
