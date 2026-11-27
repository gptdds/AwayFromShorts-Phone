package com.afs.guard.style;
import android.content.Context;
import android.view.ViewGroup;
/** Application metadata and wiring only; implementation is owned by its visual module. */
public final class FluidGradientStyle implements StyleRenderer {
    private com.afs.style.fluidgradient.nativeview.NativeFluidRenderer delegate;
    @Override public String id(){return "fluidgradient";}
    @Override public String displayName(Context context){return com.afs.guard.T.s("流体渐变");}
    public void attach(Context c,ViewGroup root,DesignTokens tokens,MotionTokens motion,EffectQuality quality){
        detach();delegate=new com.afs.style.fluidgradient.nativeview.NativeFluidRenderer(id(),displayName(c),com.afs.guard.R.id.visual_card,com.afs.guard.R.id.visual_page);delegate.attach(c,root,tokens,motion,quality);
    }
    public void detach(){if(delegate!=null){delegate.release();delegate=null;}}
    public void release(){detach();}
    public void pointer(int action,float x,float y){if(delegate!=null)delegate.pointer(action,x,y);}
}
