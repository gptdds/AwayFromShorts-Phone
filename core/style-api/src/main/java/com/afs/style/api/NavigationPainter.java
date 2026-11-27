package com.afs.style.api;

import android.graphics.Canvas;
import android.graphics.RectF;
import com.afs.guard.style.DesignTokens;
import com.afs.guard.style.EffectQuality;

/** Visual-only navigation capability. Selection geometry is owned by the shared interaction layer. */
public interface NavigationPainter {
    /** A null indicator means that this page is not one of the visible main tabs. */
    boolean draw(Canvas canvas,RectF panel,RectF indicator,DesignTokens tokens,EffectQuality quality,
                 float density,float lift,float jelly,float pointerX,boolean floating);
    default void release(){}
}
