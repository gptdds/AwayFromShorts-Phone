package com.afs.guard.style;

/** Pure conversion between decorated-root and screen coordinates for visual hit testing. */
public final class PointerCoordinates {
    private PointerCoordinates(){}
    public static float screen(float local,float rootOrigin,float scale){return rootOrigin+local*(scale>0?scale:1f);}
    public static float normalized(float screen,float viewOrigin,float viewSize){
        return Math.max(-1,Math.min(1,2*(screen-viewOrigin)/Math.max(1,viewSize)-1));
    }
}
