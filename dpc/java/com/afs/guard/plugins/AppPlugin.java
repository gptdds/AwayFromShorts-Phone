package com.afs.guard.plugins;

import android.app.Activity;

/**
 * A first-class application page represented by the host navigation.
 *
 * <p>The navigation only depends on this contract. A plugin implementation can
 * stay bundled for now and later move behind a separate process without
 * teaching the dock about its concrete Activity.</p>
 */
public interface AppPlugin {
    String id();

    String label();

    Class<? extends Activity> activity();
}
