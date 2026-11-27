package com.afs.style.liquidglass;

import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import com.afs.guard.style.DesignTokens;
import com.afs.guard.style.EffectQuality;
import java.util.Map;
import java.util.WeakHashMap;

/** Static glass material for semantic View cards. Never owns content, gestures or business state. */
public final class NativeGlassPanels implements AutoCloseable {
    private ViewGroup root;
    private final int role;
    private final DesignTokens tokens;
    private final float density;
    private final EffectQuality quality;
    private final WeakHashMap<View,Original> cards=new WeakHashMap<>();
    private final ViewTreeObserver.OnGlobalLayoutListener layout=()->scan(root);
    private static final class Original {
        final Drawable background,material;final float elevation;
        Original(View v,Drawable material,float elevation){background=v.getBackground();this.elevation=elevation;this.material=material;}
    }
    public NativeGlassPanels(ViewGroup root,int role,DesignTokens tokens,EffectQuality quality){
        this.root=root;this.role=role;this.tokens=tokens;this.quality=quality;
        density=root.getResources().getDisplayMetrics().density;
        if(role!=0){root.getViewTreeObserver().addOnGlobalLayoutListener(layout);try{scan(root);}catch(RuntimeException failure){close();throw failure;}}
    }
    private static void background(View v,Drawable drawable){
        int l=v.getPaddingLeft(),t=v.getPaddingTop(),r=v.getPaddingRight(),b=v.getPaddingBottom();
        v.setBackground(drawable);v.setPadding(l,t,r,b);
    }
    private void scan(View v){
        if(v==null)return;
        if(v.getBackground()!=null&&v.getBackground().getClass().getName().equals("com.afs.guard.ReferenceMaterial")){
            if(v instanceof ViewGroup){ViewGroup group=(ViewGroup)v;for(int i=0;i<group.getChildCount();i++)scan(group.getChildAt(i));}
            return;
        }
        if(Boolean.TRUE.equals(v.getTag(role))&&(!cards.containsKey(v)||v.getBackground()!=cards.get(v).material)){
            GradientDrawable glass=new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                    tokens.dark?new int[]{0xED303B49,0xE51D2835}:new int[]{0xF5FFFFFF,0xE5DFECF7});
            Drawable old=v.getBackground();
            if(old instanceof GradientDrawable){
                GradientDrawable shape=(GradientDrawable)old;
                float[] corners=null;
                // Android 7's getter clones a null array for uniform-radius shapes.
                try{corners=shape.getCornerRadii();}catch(NullPointerException uniformRadius){}
                if(corners!=null)glass.setCornerRadii(corners);else glass.setCornerRadius(shape.getCornerRadius());
            }else glass.setCornerRadius(12*density);
            glass.setStroke(Math.max(1,Math.round(density)),tokens.dark?0x668EA4BC:Color.WHITE);
            Original previous=cards.get(v);
            cards.put(v,new Original(v,glass,previous==null?v.getElevation():previous.elevation));background(v,glass);
            v.setElevation(quality==EffectQuality.HIGH?2*density:0);
        }
        if(v instanceof ViewGroup){ViewGroup group=(ViewGroup)v;for(int i=0;i<group.getChildCount();i++)scan(group.getChildAt(i));}
    }
    @Override public void close(){
        if(root!=null&&root.getViewTreeObserver().isAlive())root.getViewTreeObserver().removeOnGlobalLayoutListener(layout);
        for(Map.Entry<View,Original> item:cards.entrySet()){
            View v=item.getKey();Original old=item.getValue();
            // A page may update its own presentation while attached; never overwrite that update.
            if(v.getBackground()==old.material)background(v,old.background);
            if(v.getElevation()==(quality==EffectQuality.HIGH?2*density:0))v.setElevation(old.elevation);
        }
        cards.clear();root=null;
    }
}
