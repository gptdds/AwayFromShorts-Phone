package com.afs.guard.style;
import android.content.Context;
import android.view.ViewGroup;

/** Application wiring only; all native drawing and motion live in the spatial style module. */
public final class SpatialStyle implements StyleRenderer {
    private com.afs.style.spatialnative.NativeSpatialRenderer delegate;
    public String id(){return "spatial3d";}
    public String displayName(Context c){return com.afs.guard.T.s("空间立体");}
    public void attach(Context c,ViewGroup root,DesignTokens tokens,MotionTokens motion,EffectQuality quality){
        detach();
        delegate=new com.afs.style.spatialnative.NativeSpatialRenderer(id(),displayName(c),com.afs.guard.R.id.visual_card);
        delegate.attach(c,root,tokens,motion,quality);
    }
    public void pointer(int action,float x,float y){if(delegate!=null)delegate.pointer(action,x,y);}
    public void detach(){if(delegate!=null){delegate.release();delegate=null;}}
    public void release(){detach();}
}
