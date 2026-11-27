package com.afs.guard.style;

import android.content.Context;
import android.view.ViewGroup;
import com.afs.guard.Ui;

/** Visible-root resource owner. Never replaces content or owns page/navigation state. */
public final class StyleManager {
    public static final String KEY="style_id";
    private static StyleRenderer active;
    private static String activeId,requestedId;
    private static EffectQuality activeQuality;
    private static boolean external;
    private static Object externalOwner;
    private static final java.lang.ref.WeakReference<ViewGroup> NO_ROOT=new java.lang.ref.WeakReference<>(null);
    private static java.lang.ref.WeakReference<ViewGroup> activeRoot=NO_ROOT;
    private StyleManager(){}
    private static android.content.SharedPreferences prefs(Context c){return c.getSharedPreferences(Ui.SP,0);}
    public static String selected(Context c){return prefs(c).getString(KEY,"paper");}
    public static void select(Context c,String id){prefs(c).edit().putString(KEY,id==null?"paper":id).apply();}
    public static StyleScreenState state(Context c){return new StyleScreenState(selected(c),EffectQualityManager.preference(c));}
    public static void dispatch(Context c,StyleUiAction action){
        StyleScreenState next=state(c).reduce(action);
        if(action.kind==StyleUiAction.Kind.SELECT_STYLE)select(c,next.selectedId);
        else EffectQualityManager.setPreference(c,next.qualityPreference);
    }
    public static synchronized void apply(Context context,ViewGroup root,DesignTokens tokens,MotionTokens motion){
        String requested=selected(context);
        EffectQuality quality=EffectQualityManager.resolve(context);
        if(!external&&active!=null&&activeRoot.get()==root&&requested.equals(requestedId)&&quality==activeQuality)return;
        releaseActive();
        RendererFactory factory=StyleRegistry.find(requested);
        if(factory==null)factory=StyleRegistry.find("paper");
        if(factory==null)return;
        if(!attach(factory,context,root,tokens,motion,quality)&&!"paper".equals(factory.id())){
            RendererFactory fallback=StyleRegistry.find("paper");
            if(fallback!=null)attach(fallback,context,root,tokens,motion,EffectQuality.LOW);
        }
        requestedId=requested;activeQuality=quality;
    }
    private static boolean attach(RendererFactory factory,Context c,ViewGroup root,
                                  DesignTokens tokens,MotionTokens motion,EffectQuality quality){
        StyleRenderer next=null;
        try{
            next=factory.create();
            if(next==null||!factory.id().equals(next.id()))throw new IllegalStateException("Invalid renderer factory");
            next.attach(c,root,tokens,motion,quality);
            active=next;activeId=next.id();activeRoot=new java.lang.ref.WeakReference<>(root);
            return true;
        }catch(RuntimeException|LinkageError failure){
            android.util.Log.e("AFSStyle","renderer "+factory.id()+" failed; falling back",failure);
            dispose(next);return false;
        }
    }
    private static void dispose(StyleRenderer renderer){
        if(renderer==null)return;
        try{renderer.detach();}catch(RuntimeException|LinkageError ignored){}
        try{renderer.release();}catch(RuntimeException|LinkageError ignored){}
    }
    public static synchronized void release(ViewGroup root){if(activeRoot.get()==root)releaseActive();}
    /** Compose owns drawing; keep lifecycle ownership unified without adding a second View overlay. */
    public static synchronized void applyExternal(ViewGroup root,String id,Object owner,Runnable pause){
        if(external&&activeRoot.get()==root&&id.equals(activeId)&&externalOwner==owner)return;
        releaseActive();
        active=new StyleRenderer(){
            public String id(){return id;}
            public String displayName(Context c){return id;}
            public void attach(Context c,ViewGroup r,DesignTokens t,MotionTokens m,EffectQuality q){}
            public void detach(){pause.run();}
            public void release(){}
        };
        activeId=id;activeRoot=new java.lang.ref.WeakReference<>(root);external=true;externalOwner=owner;
    }
    public static synchronized void releaseActive(){
        StyleRenderer old=active;
        active=null;activeId=null;requestedId=null;activeQuality=null;activeRoot=NO_ROOT;
        external=false;externalOwner=null;
        dispose(old);
    }
    public static synchronized String activeId(){return activeId;}
    public static synchronized void pointer(ViewGroup root,int action,float x,float y){
        if(!external&&active!=null&&activeRoot.get()==root){
            try{active.pointer(action,x,y);}catch(RuntimeException failure){
                android.util.Log.w("AFSStyle","Pointer effect stopped",failure);releaseActive();
            }
        }
    }
}
