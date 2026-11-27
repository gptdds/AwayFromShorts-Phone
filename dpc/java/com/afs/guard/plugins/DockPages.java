package com.afs.guard.plugins;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;

import com.afs.guard.FreezeBoardActivity;
import com.afs.guard.MainActivity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Host dock catalog. Plugin IDs and page indexes are stable across UI and
 * future process boundaries; the visual order remains separate from the
 * persisted page index.
 */
public final class DockPages {
    public static final int MEMORY = 0;
    public static final int QUIZ = 1;
    public static final int FREEZE = 2;
    public static final int CHECKIN = 3;
    public static final int GUARD = 4;

    /** Visual order: Check-in, Memory, Guard, Quiz, Freeze. */
    public static final int[] NAV_ORDER = {CHECKIN, MEMORY, GUARD, QUIZ, FREEZE};

    private static final AppPlugin MEMORY_PLUGIN = new MemoryPlugin();
    private static final AppPlugin QUIZ_PLUGIN = new QuizPlugin();
    private static final AppPlugin CHECKIN_PLUGIN = new CheckinPlugin();
    private static final AppPlugin FREEZE_PLUGIN = new FreezePlugin();

    private static final Page FREEZE_PAGE = page(FREEZE_PLUGIN, FREEZE);
    private static final Page GUARD_PAGE = new Page(
            "guard", "守卫", MainActivity.class, "guard");

    private static final Page[] INDEXED = {
            page(MEMORY_PLUGIN, MEMORY),
            page(QUIZ_PLUGIN, QUIZ),
            FREEZE_PAGE,
            page(CHECKIN_PLUGIN, CHECKIN),
            GUARD_PAGE
    };

    private static final List<AppPlugin> PLUGINS = Collections.unmodifiableList(
            java.util.Arrays.<AppPlugin>asList(CHECKIN_PLUGIN, MEMORY_PLUGIN, QUIZ_PLUGIN, FREEZE_PLUGIN));

    private DockPages() {
    }

    public static List<AppPlugin> plugins() {
        return PLUGINS;
    }

    /**
     * Current navigation order. Bundled plugin entries are filtered from the
     * stable visual order while the host-owned Guard page remains.
     */
    public static int[] navigation(Context context) {
        return navigationOrder(
                BuiltInPluginStore.enabled(context, CheckinPlugin.ID),
                BuiltInPluginStore.enabled(context, MemoryPlugin.ID),
                BuiltInPluginStore.enabled(context, QuizPlugin.ID),
                BuiltInPluginStore.enabled(context, FreezePlugin.ID));
    }

    /**
     * Pure navigation projection used by tests and by {@link #navigation}.
     * Disabling an entry never changes another page's stable index.
     */
    public static int[] navigationOrder(boolean checkin, boolean memory, boolean quiz, boolean freeze) {
        int size = 1;
        if (checkin) size++;
        if (memory) size++;
        if (quiz) size++;
        if (freeze) size++;
        int[] pages = new int[size];
        int at = 0;
        for (int page : NAV_ORDER) {
            if (page == CHECKIN && !checkin) continue;
            if (page == MEMORY && !memory) continue;
            if (page == QUIZ && !quiz) continue;
            if (page == FREEZE && !freeze) continue;
            pages[at++] = page;
        }
        return pages;
    }

    public static boolean isEnabled(Context context, int pageIndex) {
        if (!isPlugin(pageIndex)) return true;
        Page page = page(pageIndex);
        return page != null && BuiltInPluginStore.enabled(context, page.id);
    }

    public static AppPlugin plugin(String id) {
        for (AppPlugin plugin : PLUGINS) {
            if (plugin.id().equals(id)) return plugin;
        }
        return null;
    }

    public static boolean isPlugin(int pageIndex) {
        Page page = page(pageIndex);
        return page != null && page.plugin;
    }

    public static int indexOfPlugin(String id) {
        if (CheckinPlugin.ID.equals(id)) return CHECKIN;
        if (MemoryPlugin.ID.equals(id)) return MEMORY;
        if (QuizPlugin.ID.equals(id)) return QUIZ;
        if (FreezePlugin.ID.equals(id)) return FREEZE;
        return -1;
    }

    public static Class<? extends Activity> activity(int pageIndex) {
        Page page = page(pageIndex);
        return page == null ? null : page.activity;
    }

    public static String label(int pageIndex) {
        Page page = page(pageIndex);
        return page == null ? "" : page.label;
    }

    public static Class<?>[] activities() {
        Class<?>[] out = new Class<?>[INDEXED.length];
        for (int i = 0; i < INDEXED.length; i++) out[i] = INDEXED[i].activity;
        return out;
    }

    public static String[] labels() {
        String[] out = new String[INDEXED.length];
        for (int i = 0; i < INDEXED.length; i++) out[i] = INDEXED[i].label;
        return out;
    }

    public static String[] navigationLabels() {
        String[] out = new String[NAV_ORDER.length];
        for (int i = 0; i < NAV_ORDER.length; i++) out[i] = label(NAV_ORDER[i]);
        return out;
    }

    public static Intent intent(Activity activity, int pageIndex, boolean fromBottomNav) {
        Class<? extends Activity> target = activity(pageIndex);
        if (target == null) return null;
        Intent intent = new Intent(activity, target);
        intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        if (fromBottomNav) intent.putExtra("from_bottom_nav", true);
        return intent;
    }

    private static Page page(AppPlugin plugin, int index) {
        return new Page(plugin.id(), plugin.label(), plugin.activity(), "plugin");
    }

    private static Page page(int index) {
        return index >= 0 && index < INDEXED.length ? INDEXED[index] : null;
    }

    private static final class Page {
        final String id;
        final String label;
        final Class<? extends Activity> activity;
        final String kind;
        final boolean plugin;

        Page(String id, String label, Class<? extends Activity> activity, String kind) {
            this.id = id;
            this.label = label;
            this.activity = activity;
            this.kind = kind;
            this.plugin = "plugin".equals(kind);
        }
    }
}
