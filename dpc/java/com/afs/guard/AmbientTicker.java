package com.afs.guard;
import android.view.View;
import android.view.ViewTreeObserver;
import android.content.Context;
import android.content.ContextWrapper;
import com.afs.guard.style.EffectQualityManager;
/** One lightweight, lifecycle-gated ambient callback per decorated View. */
final class AmbientTicker implements Runnable,View.OnAttachStateChangeListener,ViewTreeObserver.OnPreDrawListener {
    private final View view;private boolean queued;
    private static boolean motion,particles;
    private static double time;
    private AmbientTicker(View view){this.view=view;}
    static void attach(View view){AmbientTicker ticker=new AmbientTicker(view);view.addOnAttachStateChangeListener(ticker);if(view.isAttachedToWindow())ticker.onViewAttachedToWindow(view);}
    static double time(){return time;}
    static boolean particles(){return particles;}
    private boolean enabled(){
        if(!view.isAttachedToWindow()||!view.isShown()||view.getWindowVisibility()!=View.VISIBLE)return false;
        Context c=view.getContext();while(c instanceof ContextWrapper){if(c instanceof ThemedActivity&&!((ThemedActivity)c).visualsActive())return false;Context next=((ContextWrapper)c).getBaseContext();if(next==c)break;c=next;}
        android.os.PowerManager power=(android.os.PowerManager)view.getContext().getSystemService(Context.POWER_SERVICE);
        return (power==null||power.isInteractive())&&MotionPolicy.enabled(view.getContext())&&EffectQualityManager.resolve(view.getContext()).allowMotion();
    }
    private void stop(){view.removeCallbacks(this);queued=false;}
    public void run(){queued=false;if(!enabled())return;time=android.os.SystemClock.uptimeMillis()/1000d;particles=EffectQualityManager.resolve(view.getContext()).allowParticles();view.invalidate();queued=true;view.postDelayed(this,50);}
    public boolean onPreDraw(){if(!enabled()){stop();return true;}if(!queued){queued=true;view.post(this);}return true;}
    public void onViewAttachedToWindow(View v){v.getViewTreeObserver().addOnPreDrawListener(this);onPreDraw();}
    public void onViewDetachedFromWindow(View v){stop();if(v.getViewTreeObserver().isAlive())v.getViewTreeObserver().removeOnPreDrawListener(this);}
}
