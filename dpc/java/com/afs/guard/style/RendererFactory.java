package com.afs.guard.style;

import android.content.Context;

/** Metadata is cheap; create is called only for the visible style. Never reuse released instances. */
public interface RendererFactory {
    String id();
    String displayName(Context context);
    StyleRenderer create();
}
