package com.afs.guard.pluginqa;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.TextView;

import com.afs.guard.AppListActivity;
import com.afs.guard.CheckinActivity;
import com.afs.guard.FreezeBoardActivity;
import com.afs.guard.RuleStore;
import com.afs.guard.plugins.BuiltInPluginStore;
import com.afs.guard.plugins.CheckinPlugin;
import com.afs.guard.plugins.DockPages;
import com.afs.guard.plugins.FreezePlugin;
import com.afs.guard.plugins.MemoryPlugin;
import com.afs.guard.plugins.QuizPlugin;
import com.afs.host.plugins.PluginManagerActivity;

import java.util.Arrays;
import java.util.Map;

/**
 * Physical-device check for the independently disableable built-in freeze
 * page. The test changes only the plugin visibility preference and restores
 * that preference before it exits.
 */
public final class AppPluginUiDeviceTest extends Instrumentation {
    private int checks;
    private Context target;

    private void check(boolean value, String label) {
        checks++;
        if (!value) throw new AssertionError(label);
    }

    private static boolean contains(int[] values, int wanted) {
        for (int value : values) if (value == wanted) return true;
        return false;
    }

    private static TextView findText(View view, String... needles) {
        if (view instanceof TextView) {
            String text = ((TextView) view).getText().toString();
            for (String needle : needles) {
                if (text.contains(needle)) return (TextView) view;
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                TextView found = findText(group.getChildAt(i), needles);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static TextView findPluginLabel(View view, String... prefixes) {
        if (view instanceof TextView) {
            String text = ((TextView) view).getText().toString();
            for (String prefix : prefixes) {
                if (text.startsWith(prefix + " ")) return (TextView) view;
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                TextView found = findPluginLabel(group.getChildAt(i), prefixes);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static CheckBox findCheckBox(View view) {
        if (view instanceof CheckBox) return (CheckBox) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                CheckBox found = findCheckBox(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    private static View freezeRow(Activity manager) {
        TextView label = findPluginLabel(manager.getWindow().getDecorView(),
                "冻结面板", "Freeze Board");
        if (label == null) {
            throw new AssertionError("freeze switch is missing from plugin manager");
        }
        return (View) label.getParent().getParent();
    }

    private void restore(SharedPreferences prefs, Map<String, ?> values) {
        SharedPreferences.Editor editor = prefs.edit().clear();
        for (Map.Entry<String, ?> entry : values.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof Boolean) editor.putBoolean(entry.getKey(), (Boolean) value);
            else if (value instanceof Integer) editor.putInt(entry.getKey(), (Integer) value);
            else if (value instanceof Long) editor.putLong(entry.getKey(), (Long) value);
            else if (value instanceof Float) editor.putFloat(entry.getKey(), (Float) value);
            else if (value instanceof String) editor.putString(entry.getKey(), (String) value);
        }
        check(editor.commit(), "restore original built-in plugin preferences");
    }

    private Activity startAndWait(Class<? extends Activity> type) {
        Instrumentation.ActivityMonitor monitor =
                addMonitor(type.getName(), null, false);
        runOnMainSync(() -> target.startActivity(
                new Intent(target, type).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
        return monitor.waitForActivityWithTimeout(6000);
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
        SharedPreferences prefs =
                target.getSharedPreferences(BuiltInPluginStore.SP, Context.MODE_PRIVATE);
        Map<String, ?> originalPrefs = prefs.getAll();
        String originalRules = RuleStore.exportRules(target);
        String originalSlots = target.getSharedPreferences("afs_guard", Context.MODE_PRIVATE)
                .getString("freeze_slots", "[]");
        Activity manager = null;
        Activity checkin = null;
        Activity freeze = null;
        try {
            check(BuiltInPluginStore.setEnabled(target, FreezePlugin.ID, true),
                    "enable freeze plugin for deterministic UI test");
            check(BuiltInPluginStore.setEnabled(target, CheckinPlugin.ID, true),
                    "enable check-in plugin for deterministic UI test");
            check(BuiltInPluginStore.setEnabled(target, MemoryPlugin.ID, true),
                    "enable memory plugin for deterministic UI test");
            check(BuiltInPluginStore.setEnabled(target, QuizPlugin.ID, true),
                    "enable quiz plugin for deterministic UI test");
            manager = startAndWait(PluginManagerActivity.class);
            check(manager != null, "plugin manager opens");
            waitForIdleSync();

            View row = freezeRow(manager);
            CheckBox freezeToggle = findCheckBox(row);
            check(freezeToggle != null, "freeze panel has its own checkbox");
            check(freezeToggle.isChecked(), "freeze panel starts enabled");
            runOnMainSync(freezeToggle::performClick);
            waitForIdleSync();
            SystemClock.sleep(150);

            check(!BuiltInPluginStore.enabled(target, FreezePlugin.ID),
                    "tapping the switch disables the freeze panel");
            int[] disabledNav = DockPages.navigation(target);
            check(!contains(disabledNav, DockPages.FREEZE),
                    "disabled freeze page is removed from the dock");
            check(contains(disabledNav, DockPages.CHECKIN)
                            && contains(disabledNav, DockPages.MEMORY)
                            && contains(disabledNav, DockPages.QUIZ)
                            && contains(disabledNav, DockPages.GUARD),
                    "check-in and all other stable pages remain in the dock");

            checkin = startAndWait(CheckinActivity.class);
            check(checkin != null && !checkin.isFinishing(),
                    "check-in page still opens while the freeze panel is disabled");
            if (checkin != null) {
                Activity current = checkin;
                runOnMainSync(current::finish);
                checkin = null;
            }

            Instrumentation.ActivityMonitor oldEntryMonitor =
                    addMonitor(AppListActivity.class.getName(), null, false);
            Instrumentation.ActivityMonitor redirectMonitor =
                    addMonitor(CheckinActivity.class.getName(), null, false);
            runOnMainSync(() -> target.startActivity(new Intent(target, AppListActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
            Activity oldEntry = oldEntryMonitor.waitForActivityWithTimeout(6000);
            Activity redirected = redirectMonitor.waitForActivityWithTimeout(6000);
            check(oldEntry != null, "old freeze entry activity is reached");
            check(redirected != null, "old freeze entry redirects to check-in");
            if (redirected != null) {
                Activity current = redirected;
                runOnMainSync(current::finish);
            }
            if (oldEntry != null && !oldEntry.isFinishing()) {
                Activity current = oldEntry;
                runOnMainSync(current::finish);
            }

            check(originalRules.equals(RuleStore.exportRules(target)),
                    "disabling the panel leaves freeze rules untouched");
            check(originalSlots.equals(target.getSharedPreferences(
                            "afs_guard", Context.MODE_PRIVATE)
                            .getString("freeze_slots", "[]")),
                    "disabling the panel leaves freeze slots untouched");

            check(BuiltInPluginStore.setEnabled(target, FreezePlugin.ID, true),
                    "re-enable freeze plugin");
            int[] enabledNav = DockPages.navigation(target);
            check(Arrays.equals(enabledNav, DockPages.NAV_ORDER),
                    "re-enabling restores the full dock in stable order");
            freeze = startAndWait(FreezeBoardActivity.class);
            check(freeze != null && !freeze.isFinishing(),
                    "re-enabled freeze panel opens normally");

            result.putString("stream", "App plugin UI: " + checks + " checks passed\n");
            code = 0;
        } catch (Throwable error) {
            result.putString("stream", "FAILED " + android.util.Log.getStackTraceString(error));
        } finally {
            if (manager != null && !manager.isFinishing()) {
                Activity current = manager;
                runOnMainSync(current::finish);
            }
            if (freeze != null && !freeze.isFinishing()) {
                Activity current = freeze;
                runOnMainSync(current::finish);
            }
            if (checkin != null && !checkin.isFinishing()) {
                Activity current = checkin;
                runOnMainSync(current::finish);
            }
            restore(prefs, originalPrefs);
        }
        finish(code, result);
    }
}
