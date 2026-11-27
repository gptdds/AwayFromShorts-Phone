package com.afs.style.spatialnative;
import com.afs.guard.style.DesignTokens;
import com.afs.guard.style.MotionTokens;
import com.afs.guard.style.EffectQuality;
import com.afs.guard.style.StyleRenderer;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import java.util.WeakHashMap;

/** Compatibility presentation for existing View pages; no page or enforcement state. */
public final class NativeSpatialRenderer implements StyleRenderer {
    private ViewGroup root;
    private float elevation;
    private float screenOriginX,screenOriginY;
    private static final class Original {
        final float elevation,x,y,camera;
        Original(View v){elevation=v.getElevation();x=v.getRotationX();y=v.getRotationY();camera=v.getCameraDistance();}
    }
    private final WeakHashMap<View,Original> originals=new WeakHashMap<>();
    private final java.util.Map<View,android.animation.ValueAnimator> springs=new java.util.HashMap<>();
    private View pressed;
    private boolean motionEnabled;
    private MotionTokens motion;
    private final ViewTreeObserver.OnGlobalLayoutListener layout=()->scan(root);
    private final String styleId,title;
    private final int cardRole;
    public NativeSpatialRenderer(String styleId,String title,int cardRole){this.styleId=styleId;this.title=title;this.cardRole=cardRole;}
    public String id(){return styleId;}
    public String displayName(Context c){return title;}
    public void attach(Context c,ViewGroup root,DesignTokens tokens,MotionTokens motion,EffectQuality quality){
        detach();this.root=root;
        this.motion=motion;motionEnabled=quality.allowMotion()&&quality!=EffectQuality.LOW;
        elevation=c.getResources().getDisplayMetrics().density*(quality==EffectQuality.HIGH?7:2);
        int[] offset=new int[2];root.getLocationOnScreen(offset);
        screenOriginX=offset[0];screenOriginY=offset[1];
        root.getViewTreeObserver().addOnGlobalLayoutListener(layout);scan(root);
    }
    private void scan(View view){
        if(view==null)return;
        if(Boolean.TRUE.equals(view.getTag(cardRole))){
            if(!originals.containsKey(view))originals.put(view,new Original(view));
            view.setElevation(elevation);
        }
        if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++)scan(group.getChildAt(i));}
    }
    @Override public void pointer(int action,float x,float y){
        if(!motionEnabled)return;
        if(action==android.view.MotionEvent.ACTION_DOWN){
            pressed=null;
            android.graphics.Rect bounds=new android.graphics.Rect();long smallest=Long.MAX_VALUE;
            // Pointer coordinates are relative to the decorated root; press feedback uses screen bounds.
            for(View card:originals.keySet())if(card.getGlobalVisibleRect(bounds)&&bounds.contains((int)(x+screenOriginX),(int)(y+screenOriginY))){
                long area=(long)bounds.width()*bounds.height();if(area<smallest){smallest=area;pressed=card;}
            }
            if(pressed!=null){android.animation.ValueAnimator old=springs.remove(pressed);if(old!=null)old.cancel();}
        }
        if(pressed==null)return;
        if(action==android.view.MotionEvent.ACTION_CANCEL){
            Original old=originals.get(pressed);pressed.setRotationX(old.x);pressed.setRotationY(old.y);pressed.setCameraDistance(old.camera);pressed=null;return;
        }
        if(action==android.view.MotionEvent.ACTION_DOWN||action==android.view.MotionEvent.ACTION_MOVE){
            int[] origin=new int[2];pressed.getLocationOnScreen(origin);Original old=originals.get(pressed);
            float px=com.afs.guard.style.PointerCoordinates.normalized(x+screenOriginX,origin[0],pressed.getWidth());
            float py=com.afs.guard.style.PointerCoordinates.normalized(y+screenOriginY,origin[1],pressed.getHeight());
            pressed.setCameraDistance(8000*pressed.getResources().getDisplayMetrics().density);
            pressed.setRotationX(old.x-py*2.5f);pressed.setRotationY(old.y+px*2.5f);
        }else if(action==android.view.MotionEvent.ACTION_UP||action==android.view.MotionEvent.ACTION_CANCEL){
            final View card=pressed;pressed=null;final Original old=originals.get(card);
            final float dx=card.getRotationX()-old.x,dy=card.getRotationY()-old.y;
            android.animation.ValueAnimator spring=android.animation.ValueAnimator.ofFloat(0,1);
            spring.setDuration(650);spring.setInterpolator(new android.view.animation.LinearInterpolator());
            final double omega=Math.sqrt(motion.springStiffness),z=motion.elasticDamping,d=Math.sqrt(1-z*z);
            spring.addUpdateListener(value->{
                double t=value.getAnimatedFraction()*.65;
                float remaining=(float)(Math.exp(-z*omega*t)*(Math.cos(omega*d*t)+z/d*Math.sin(omega*d*t)));
                card.setRotationX(old.x+dx*remaining);card.setRotationY(old.y+dy*remaining);
            });
            spring.addListener(new android.animation.AnimatorListenerAdapter(){
                @Override public void onAnimationEnd(android.animation.Animator animation){
                    card.setRotationX(old.x);card.setRotationY(old.y);card.setCameraDistance(old.camera);springs.remove(card);
                }
            });
            springs.put(card,spring);spring.start();
        }
    }
    public void detach(){
        if(root!=null&&root.getViewTreeObserver().isAlive())root.getViewTreeObserver().removeOnGlobalLayoutListener(layout);
        pressed=null;
        for(android.animation.ValueAnimator spring:new java.util.ArrayList<>(springs.values())){spring.removeAllUpdateListeners();spring.removeAllListeners();spring.cancel();}
        springs.clear();
        for(java.util.Map.Entry<View,Original> entry:originals.entrySet()){
            View v=entry.getKey();Original old=entry.getValue();v.setElevation(old.elevation);
            v.setRotationX(old.x);v.setRotationY(old.y);v.setCameraDistance(old.camera);
        }
        originals.clear();root=null;
    }
    public void release(){detach();}
}
