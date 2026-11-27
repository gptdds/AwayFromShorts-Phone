package com.afs.guard;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;

/** Global font scaling, typography, and refreshing cached pages after appearance changes. */
public class ThemedActivity extends Activity {
    private String appearanceSignature;
    private final Runnable motionChanged=()->{onMotionPolicyChanged();refreshMotionStyle();};
    protected void onMotionPolicyChanged(){}
    private long visualEventTime=Long.MIN_VALUE,visualDownTime=Long.MIN_VALUE;
    private int visualAction=-1;private float visualX,visualY;
    /** Custom gesture owners can observe before consuming; forwarding to super stays idempotent. */
    protected final void observeVisualTouchEvent(android.view.MotionEvent event){
        int action=event.getAction();
        if(visualEventTime==event.getEventTime()&&visualDownTime==event.getDownTime()&&visualAction==action&&visualX==event.getX()&&visualY==event.getY())return;
        visualEventTime=event.getEventTime();visualDownTime=event.getDownTime();visualAction=action;visualX=event.getX();visualY=event.getY();
        if(styleResumed&&decor instanceof ViewGroup)
            com.afs.guard.style.StyleManager.pointer((ViewGroup)decor,
                    event.getPointerCount()>1?android.view.MotionEvent.ACTION_CANCEL:event.getActionMasked(),event.getX(),event.getY());
    }
    @Override public boolean dispatchTouchEvent(android.view.MotionEvent event){
        observeVisualTouchEvent(event);
        return super.dispatchTouchEvent(event);
    }
    private Bundle pageScroll;
    private View decor;
    private void refreshMotionStyle(){if(styleResumed&&decor!=null)applyStyle();}
    private boolean styleResumed;
    final boolean visualsActive(){return styleResumed;}
    private com.afs.guard.style.FrameMetricsWatcher frameMetrics;
    private final Runnable refreshStyle=()->{if(styleResumed&&decor!=null)applyStyle();};
    private final android.content.SharedPreferences.OnSharedPreferenceChangeListener styleChanged=(prefs,key)->{
        if(com.afs.guard.style.StyleManager.KEY.equals(key)||"effect_quality".equals(key)){
            if(decor!=null){decor.removeCallbacks(refreshStyle);decor.post(refreshStyle);}
        }
    };
    private final Runnable applyTypography=()->{if(decor!=null)ThemeStore.typography(decor);};
    /**
     * 同一轮布局里可能连续来好几次回调；合并成一次挂到下一帧再走，
     * 避免每次 layout 都把整棵 View 树按字体/网格遍历两遍（切页时最明显的白工）。
     */
    private final ViewTreeObserver.OnGlobalLayoutListener typography=()->{
        if(decor==null)return;
        decor.removeCallbacks(applyTypography);
        decor.post(applyTypography);
    };
    @Override protected void attachBaseContext(Context base){
        // 语言开关在这里刷新：切语言走 recreate()，新 Activity 一建就能拿到新语言。
        // init 只在首次读翻译表，之后只更新语言标志，代价可以忽略。
        T.init(base);
        super.attachBaseContext(ThemeStore.context(base));
    }
    @Override protected void onCreate(Bundle saved){
        pageScroll=saved==null?null:saved.getBundle("afs_page_scroll");
        int theme=getResources().getIdentifier(ThemeStore.dark(this)?"NavThemeDark":"NavTheme","style",getPackageName());
        if(theme!=0)setTheme(theme);appearanceSignature=ThemeStore.signature(this);super.onCreate(saved);FloatingNav.fullscreen(this);
        // 页面切换直接显示，不叠加系统窗口淡入、滑动或缩放。
        getWindow().setWindowAnimations(0);
    }
    @Override protected void onPostCreate(Bundle saved){
        super.onPostCreate(saved);decor=getWindow().getDecorView();decor.getViewTreeObserver().addOnGlobalLayoutListener(typography);ThemeStore.typography(decor);
    }
    private void applyStyle(){
        try{
            Ui ui=new Ui(this);
            // 窗口底色也跟风格走：页面内容之外（沉浸式留白、切换瞬间）不能露出上一风格的颜色。
            getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(ui.bg()));
            com.afs.guard.style.StyleManager.apply(this,(ViewGroup)decor,ui.tokens(),ui.motionTokens());
        }catch(RuntimeException ignored){}
    }
    @Override protected void onResume(){GuardClock.pin(this);super.onResume();MotionPolicy.add(this,motionChanged);onMotionPolicyChanged();if(!isFinishing()&&!ThemeStore.signature(this).equals(appearanceSignature))recreate();}
    protected final void resetPageScroll(){pageScroll=null;}
    @Override protected void onPostResume(){super.onPostResume();styleResumed=true;getSharedPreferences(Ui.SP,0).registerOnSharedPreferenceChangeListener(styleChanged);com.afs.guard.style.EffectQualityManager.addListener(refreshStyle);frameMetrics=new com.afs.guard.style.FrameMetricsWatcher(getWindow());applyStyle();PageScrollState.restore(this,pageScroll);pageScroll=null;}
    @Override protected void onPause(){styleResumed=false;if(frameMetrics!=null){frameMetrics.close();frameMetrics=null;}com.afs.guard.style.EffectQualityManager.removeListener(refreshStyle);getSharedPreferences(Ui.SP,0).unregisterOnSharedPreferenceChangeListener(styleChanged);if(decor!=null){decor.removeCallbacks(refreshStyle);com.afs.guard.style.StyleManager.release((ViewGroup)decor);}FloatingNav.cancelPending(this);MotionPolicy.remove(motionChanged);pageScroll=PageScrollState.capture(this);super.onPause();}
    @Override protected void onSaveInstanceState(Bundle out){out.putBundle("afs_page_scroll",pageScroll!=null?pageScroll:PageScrollState.capture(this));super.onSaveInstanceState(out);}
    /** 页面自己已经按新主题重建过了：记下新签名，免得下一次 onResume 再整页 recreate 闪一下。 */
    protected final void markAppearanceApplied(){appearanceSignature=ThemeStore.signature(this);}
    @Override public void onWindowFocusChanged(boolean focused){super.onWindowFocusChanged(focused);LiveNavWindow.focusChanged(this);}
    @Override protected void onDestroy(){
        FloatingNav.cancelPending(this);
        if(decor!=null){
            decor.removeCallbacks(refreshStyle);
            com.afs.guard.style.StyleManager.release((ViewGroup)decor);
            decor.removeCallbacks(applyTypography);
            if(decor.getViewTreeObserver().isAlive())decor.getViewTreeObserver().removeOnGlobalLayoutListener(typography);
        }
        super.onDestroy();
    }
}
