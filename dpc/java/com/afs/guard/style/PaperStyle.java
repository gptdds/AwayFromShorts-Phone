package com.afs.guard.style;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

/** Minimal paper/ink presentation; the baseline every other style degrades to. */
public final class PaperStyle implements StyleRenderer {
    @Override public String id(){return "paper";}
    @Override public String displayName(Context context){return com.afs.guard.T.s("简约风格");}
    @Override public void attach(Context context,ViewGroup root,DesignTokens tokens,MotionTokens motion,EffectQuality quality){}
    @Override public void detach(){}
    @Override public void release(){}
}
