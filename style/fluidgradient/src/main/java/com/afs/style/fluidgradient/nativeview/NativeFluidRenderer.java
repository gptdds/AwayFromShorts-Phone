package com.afs.style.fluidgradient.nativeview;
import android.content.Context;
import android.view.ViewGroup;
import com.afs.guard.style.*;
import com.afs.style.api.NativeComponentLayer;

/** Fluid material lives inside native controls and takes its ambient color from their page. */
public final class NativeFluidRenderer implements StyleRenderer {
    private final String styleId,title;
    private final int cardRole,pageRole;
    private NativeComponentLayer components;
    public NativeFluidRenderer(String id,String title,int cardRole){this(id,title,cardRole,0);}
    public NativeFluidRenderer(String id,String title,int cardRole,int pageRole){this.styleId=id;this.title=title;this.cardRole=cardRole;this.pageRole=pageRole;}
    public String id(){return styleId;}
    public String displayName(Context context){return title;}
    public void attach(Context c,ViewGroup root,DesignTokens tokens,MotionTokens motion,EffectQuality quality){
        detach();components=new NativeComponentLayer(c,root,tokens,quality,cardRole,pageRole,true,"visual/fluid-universe/native-components.agsl");
    }
    public void pointer(int action,float x,float y){if(components!=null)components.pointer(action,x,y);}
    public void detach(){if(components!=null){components.close();components=null;}}
    public void release(){detach();}
}
