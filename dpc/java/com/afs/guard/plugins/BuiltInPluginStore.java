package com.afs.guard.plugins;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Persists whether bundled page plugins are shown in the host navigation.
 *
 * <p>Plugin-owned data stays in its existing stores. Disabling a plugin only
 * hides its page entry; it never deletes learning records or changes freeze
 * integration.</p>
 */
public final class BuiltInPluginStore {
    public static final String SP = "afs_builtin_plugins";
    private static final String ENABLED_PREFIX = "enabled.";
    private static final String REVISION = "revision";

    private BuiltInPluginStore() {
    }

    public static boolean enabled(Context context, String pluginId) {
        if (!known(pluginId)) return false;
        return prefs(context).getBoolean(key(pluginId), true);
    }

    public static boolean setEnabled(Context context, String pluginId, boolean enabled) {
        if (!known(pluginId)) return false;
        SharedPreferences prefs = prefs(context);
        long revision = prefs.getLong(REVISION, 0L) + 1L;
        return prefs.edit()
                .putBoolean(key(pluginId), enabled)
                .putLong(REVISION, revision)
                .commit();
    }

    public static long revision(Context context) {
        return prefs(context).getLong(REVISION, 0L);
    }

    public static int enabledCount(Context context) {
        int count = 0;
        for (AppPlugin plugin : plugins()) {
            if (enabled(context, plugin.id())) count++;
        }
        return count;
    }

    public static boolean backupKey(String key) {
        return key != null && key.startsWith(ENABLED_PREFIX);
    }

    private static boolean known(String pluginId) {
        return "usage-stats".equals(pluginId)||DockPages.indexOfPlugin(pluginId) >= 0;
    }

    public static java.util.List<AppPlugin> plugins(){
        java.util.List<AppPlugin> list=new java.util.ArrayList<>(DockPages.plugins());list.add(new UsageStatsPlugin());return java.util.Collections.unmodifiableList(list);
    }

    private static String key(String pluginId) {
        return ENABLED_PREFIX + pluginId;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(SP, Context.MODE_PRIVATE);
    }
}
