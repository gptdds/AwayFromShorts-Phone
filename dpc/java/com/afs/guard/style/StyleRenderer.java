package com.afs.guard.style;

import android.content.Context;
import android.view.ViewGroup;

/**
 * One visual style. A renderer only paints; it never owns business state or navigation.
 * Lifecycle: attach may be called again after detach, release is terminal.
 */
public interface StyleRenderer {
    String id();
    /** Human-readable name shown in the picker. */
    String displayName(Context context);
    /** Decorate the activity root once per attach; keep this cheap because it runs per page. */
    void attach(Context context,ViewGroup root,DesignTokens tokens,MotionTokens motion,EffectQuality quality);
    /** Stop running animations; resources stay reusable for the next attach. */
    void detach();
    /** Stop animations and free GPU/bitmap resources. */
    void release();
    /** Observe a shared pointer stream; never consume or redirect the page's event. */
    default void pointer(int action,float screenX,float screenY){}
}
