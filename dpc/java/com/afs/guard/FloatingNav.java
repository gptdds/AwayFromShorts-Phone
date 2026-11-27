package com.afs.guard;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Build;
import android.os.SystemClock;
import android.view.Choreographer;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.ImageView;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.afs.guard.plugins.BuiltInPluginStore;
import com.afs.guard.plugins.DockPages;
import com.afs.style.api.NavigationPainter;
import com.afs.guard.style.DesignTokens;
import com.afs.guard.style.EffectQuality;
import com.afs.guard.style.EffectQualityManager;
import com.afs.guard.style.StyleManager;
import com.afs.guard.style.StyleRegistry;

/** Quiet native navigation with consistent outline icons and release-to-select behavior. */
public final class FloatingNav extends FrameLayout {
    // 系统背景模糊覆盖整个窗口；窗口边界须与胶囊重合，不能留额外的模糊边带。
    static final int SHADOW_ROOM_DP = 0;
    private static final String[] LABELS = DockPages.labels();
    private static final Class<?>[] PAGES = DockPages.activities();
    private static int transitionFrom=-1, transitionTo=-1;
    private static long transitionAt, switchingUntil;
    private static long transitionToken,consumedTransition;
    private static final android.os.Handler navigationQueue=new android.os.Handler(android.os.Looper.getMainLooper());
    private static java.lang.ref.WeakReference<Activity> pendingSource;
    private static Runnable pendingOpen;
    static void cancelPending(Activity source){
        if(pendingSource!=null&&pendingSource.get()==source){
            navigationQueue.removeCallbacks(pendingOpen);pendingOpen=null;pendingSource=null;
        }
    }
    private final Runnable motionChanged=()->{
        if(!MotionPolicy.navigation(getContext()))finishContent();
        if(!motion())resetDock();
        refreshNavigationStyle();
    };
    private final Runnable navigationStyleChanged=this::refreshNavigationStyle;
    private final android.content.SharedPreferences.OnSharedPreferenceChangeListener visualChanged=(prefs,key)->{
        if(StyleManager.KEY.equals(key)||"effect_quality".equals(key))refreshNavigationStyle();
    };
    private NavigationPainter navigationPainter;
    private String navigationStyle;
    private EffectQuality navigationQuality;
    private boolean navigationAttempted;
    private DesignTokens navigationTokens;
    private void resetDock(){
        Choreographer.getInstance().removeFrameCallback(frame);framePosted=false;frameTime=0;
        pressing=false;dragging=false;pointer=-1;if(velocity!=null){velocity.recycle();velocity=null;}
        position.snap(Math.max(0,slotOf(selected)));lift.snap(0);swell.snap(0);pull.snap(0);glow.snap(0);jelly.snap(0);
        updateColors(selected);updateGeometry();
    }

    private final Activity activity;
    private final Ui ui;
    private final AppearanceConfig appearance;
    private final View source;
    private final int[] order;
    private long pluginRevision;
    private NavContentInsets contentInsets;
    private final FrameLayout host, panel;
    private final LinearLayout tabs;
    private final Material material;
    private final Icon[] icons=new Icon[5];
    private final TextView[] labels=new TextView[5];
    /** 界面语言下的标签（默认中文）；图标 glyph 仍用 LABELS，不跟着翻译。构造器里赋值。 */
    private final String[] texts;
    private final int selected;
    private final DockSpring position=new DockSpring(0,300,1);
    private final DockSpring lift=new DockSpring(0,576,1);
    private final DockSpring swell=new DockSpring(0,311,1);
    private final DockSpring jelly=new DockSpring(0,100,1);
    private final DockSpring pull=new DockSpring(0,400,.8);
    private final DockSpring glow=new DockSpring(0,576,1);
    private final RectF lensRect=new RectF(), barOnWindow=new RectF(), lensOnWindow=new RectF();
    private NavGlassRenderer renderer;
    private ImageView snapshotView;
    private boolean rendererAttempted, seeded, glassVisible, pressing, dragging, navigating, framePosted;
    private int pointer=-1, hoverPage, visualPage=-2;
    private float pitch, lensWidth, firstCenter, pressX, lightX, pullPixels;
    private VelocityTracker velocity;
    private long frameTime, playedTransition;
    private long rippleAt;private float rippleX;
    private final ViewTreeObserver.OnPreDrawListener beforeDraw=()->{playTransition();renderGlass();return true;};
    private final Choreographer.FrameCallback frame=this::step;

    public static View wrap(Activity activity, View content, int selected) {
        return wrap(activity,content,selected,content);
    }

    /**
     * 子页面不挂底栏：卡管理、卡设置、编辑器这些是别的页面下面的层级，
     * 底下再亮一条主标签栏，既回不去也容易误触，直接只给内容。
     */
    public static View plain(Activity activity, View content) {
        fullscreen(activity);
        return content;
    }
    public static View wrap(Activity activity, View content, int selected,View safeContent) {
        fullscreen(activity);
        Ui ui=new Ui(activity);
        FrameLayout host=new FrameLayout(activity);
        host.setBackgroundColor(ui.bg());host.setClipChildren(false);host.setClipToPadding(false);
        // 全局滑页手势已删（2026-11-02 用户要求）：切页只走底栏。
        // 页面内容直接进 host，不再套 SwipeNavHost。
        host.addView(content,new LayoutParams(-1,-1));
        FloatingNav nav=new FloatingNav(activity,content,host,selected,null);
        nav.contentInsets=new NavContentInsets(safeContent);
        nav.contentInsets.apply(ui.dp(62+21+12));
        LiveNavWindow.attach(activity,host,nav);
        return host;
    }

    void updateContentInsets(boolean keyboard,int safe,int bottom){
        if(contentInsets==null)return;
        if(keyboard){
            // adjustResize already shrinks some windows; fullscreen windows may only pan.
            // Reserve just the uncovered keyboard overlap, never add its height twice.
            android.graphics.Rect visible=new android.graphics.Rect();host.getWindowVisibleDisplayFrame(visible);
            int[] location=new int[2];host.getLocationOnScreen(location);
            contentInsets.apply(Math.max(0,location[1]+host.getHeight()-visible.bottom));return;
        }
        int fallback=ui.dp(62+12)+(floating()?bottom:safe);
        int[] page=new int[2],dock=new int[2];host.getLocationOnScreen(page);getLocationOnScreen(dock);
        int clearance=getWidth()>0&&getHeight()>0?NavContentInsets.clearance(page[1]+host.getHeight(),dock[1],dock[1]+getHeight(),ui.dp(12),fallback):fallback;
        contentInsets.apply(clearance);
    }

    public static void fullscreen(Activity activity) {
        android.view.Window w=activity.getWindow();
        w.addFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN
                |android.view.WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        w.setStatusBarColor(0);
        if(Build.VERSION.SDK_INT>=28) {
            android.view.WindowManager.LayoutParams lp=w.getAttributes();
            lp.layoutInDisplayCutoutMode=android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            w.setAttributes(lp);
        }
        w.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                |View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN|View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    private FloatingNav(Activity activity,View source,FrameLayout host,int selected,ImageView veil) {
        super(activity);this.activity=activity;this.source=source;this.host=host;this.selected=selected;this.snapshotView=veil;ui=new Ui(activity);appearance=ThemeStore.load(activity);this.texts=Lang.nav(activity);
        order=DockPages.navigation(activity);pluginRevision=BuiltInPluginStore.revision(activity);
        setClipChildren(false);setClipToPadding(false);
        panel=new FrameLayout(activity);panel.setClipChildren(false);panel.setClipToPadding(false);
        LayoutParams panelLp=new LayoutParams(-1,ui.dp(62));
        panelLp.setMargins(ui.dp(shadowRoomDp()),ui.dp(shadowRoomDp()),ui.dp(shadowRoomDp()),0);
        addView(panel,panelLp);
        material=new Material();panel.addView(material,new LayoutParams(-1,-1));
        tabs=new LinearLayout(activity);tabs.setClipChildren(false);tabs.setClipToPadding(false);
        panel.addView(tabs,new LayoutParams(-1,-1));
        for(int index:order) {
            LinearLayout tab=new LinearLayout(activity);
            tab.setGravity(Gravity.CENTER);tab.setOrientation(LinearLayout.VERTICAL);
            tab.setContentDescription(texts[index]);tab.setFocusable(true);
            Icon icon=new Icon(activity,index,ui.text2());icons[index]=icon;
            icon.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            tab.addView(icon,new LinearLayout.LayoutParams(ui.dp(28),ui.dp(28)));
            TextView label=new TextView(activity);labels[index]=label;
            label.setText(texts[index]);label.setTextSize(ui.amap?11:10);label.setIncludeFontPadding(false);
            SwissTypography.role(label,SwissTypography.Role.NAV);
            label.setGravity(Gravity.CENTER);label.setSingleLine(true);
            label.setTypeface(android.graphics.Typeface.create("sans-serif-medium",0));
            label.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            label.setMinHeight(ui.dp(14.5f));
            LinearLayout.LayoutParams textLp=new LinearLayout.LayoutParams(-2,-2);textLp.topMargin=ui.dp(2);
            tab.addView(label,textLp);tabs.addView(tab,new LinearLayout.LayoutParams(0,-1,1));
            tab.setOnClickListener(v->navigate(index));
        }
        updateColors(selected);
        ThemeStore.typography(tabs);
        addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->layoutDock());

    }

    private int slotOf(int page) {for(int i=0;i<order.length;i++)if(order[i]==page)return i;return -1;}
    private static float clamp(float v,float lo,float hi) {return Math.max(lo,Math.min(hi,v));}
    private float value(DockSpring s) {return (float)s.value;}
    private boolean motion() { return MotionPolicy.enabled(getContext())&&EffectQualityManager.resolve(getContext()).allowMotion(); }
    private void releaseNavigationPainter(){
        NavigationPainter old=navigationPainter;
        navigationPainter=null;navigationStyle=null;navigationQuality=null;navigationAttempted=false;
        if(old!=null)try{old.release();}catch(RuntimeException|LinkageError error){android.util.Log.w("AFSStyle","Navigation cleanup failed",error);}
    }
    private void refreshNavigationStyle(){
        if(!StyleManager.selected(getContext()).equals(navigationStyle)||EffectQualityManager.resolve(getContext())!=navigationQuality)
            releaseNavigationPainter();
        if(!motion()){finishContent();resetDock();}
        material.invalidate();
    }
    private boolean drawNavigationStyle(Canvas canvas,RectF bounds){
        if(!glassVisible)return false;
        String id=StyleManager.selected(getContext());EffectQuality quality=EffectQualityManager.resolve(getContext());
        if(!id.equals(navigationStyle)||quality!=navigationQuality)releaseNavigationPainter();
        if(!navigationAttempted){
            navigationAttempted=true;navigationStyle=id;navigationQuality=quality;
            try{navigationPainter=StyleRegistry.createCapability(id,NavigationPainter.class);}
            catch(RuntimeException|LinkageError error){android.util.Log.w("AFSStyle","Navigation factory fallback",error);}
        }
        if(navigationPainter==null)return false;
        if(navigationTokens==null)navigationTokens=ui.tokens();
        int save=canvas.save();
        try{
            return navigationPainter.draw(canvas,bounds,(slotOf(selected)>=0||pressing)?lensRect:null,
                    navigationTokens,quality,getResources().getDisplayMetrics().density,value(lift),value(jelly),lightX,appearance.floating);
        }catch(RuntimeException|LinkageError error){
            NavigationPainter failed=navigationPainter;navigationPainter=null;
            try{failed.release();}catch(RuntimeException|LinkageError ignored){}
            android.util.Log.w("AFSStyle","Navigation draw fallback",error);return false;
        }finally{canvas.restoreToCount(save);}
    }
    private void layoutDock() {
        float span=getWidth()-ui.dp(shadowRoomDp()*2+8);
        if(span<=0||order.length==0)return;
        float even=span/order.length;
        lensWidth=appearance.floating?Math.min(span,Math.max(ui.dp(84),even+ui.dp(8))):even;
        pitch=!appearance.floating||even+ui.dp(8)>=ui.dp(84)||order.length==1?even:(span-lensWidth)/(order.length-1);
        float inset=(lensWidth-pitch)/2;
        firstCenter=ui.dp(4)+inset+pitch/2;
        int pad=Math.round(ui.dp(4)+inset);
        tabs.setPadding(pad,ui.dp(4),pad,ui.dp(4));
        if(!seeded){position.snap(Math.max(0,slotOf(selected)));seeded=true;}
        updateGeometry();post(this::playTransition);
    }

    private float localX(MotionEvent e) {
        return (e.getX()-panel.getX()-panel.getPivotX())/panel.getScaleX()+panel.getPivotX();
    }
    private float pointPosition(float x) {return (x-firstCenter)/Math.max(1,pitch);}
    private int indexAt(float p) {return Math.max(0,Math.min(order.length-1,Math.round(p)));}

    @Override public boolean onInterceptTouchEvent(MotionEvent e) {
        if(e.getActionMasked()==MotionEvent.ACTION_DOWN) {
            return e.getY()>=ui.dp(Math.max(0,shadowRoomDp()-8))&&e.getY()<=ui.dp(shadowRoomDp()+62+(appearance.floating?8:0));
        }
        return pressing;
    }
    @Override public boolean onTouchEvent(MotionEvent e) {
        int action=e.getActionMasked();
        if(action==MotionEvent.ACTION_DOWN) {
            if(SystemClock.uptimeMillis()<switchingUntil)return true;
            pointer=e.getPointerId(0);pressing=true;dragging=false;
            if(velocity!=null)velocity.recycle();velocity=VelocityTracker.obtain();velocity.addMovement(e);
            pressX=localX(e);lightX=pressX;hoverPage=order[indexAt(pointPosition(pressX))];
            if(motion()){rippleAt=SystemClock.uptimeMillis();rippleX=pressX;material.invalidate();}
            position.tune(300,.55);if(appearance.floating)position.target=slotOf(hoverPage);
            lift.target=appearance.floating?1:0;swell.tune(311,.593);swell.target=appearance.floating?1:0;glow.tune(576,1);glow.target=1;
            updateColors(appearance.floating?hoverPage:selected);kick();return true;
        }
        if(action==MotionEvent.ACTION_POINTER_DOWN||action==MotionEvent.ACTION_POINTER_UP){release(false,0);return true;}
        if(!pressing)return false;
        if(velocity!=null)velocity.addMovement(e);
        if(action==MotionEvent.ACTION_MOVE) {
            int pi=e.findPointerIndex(pointer);if(pi<0){release(false,0);return true;}
            float x=localX(e);lightX=x;
            if(!dragging && Math.abs(x-pressX)>ViewConfiguration.get(getContext()).getScaledTouchSlop())dragging=true;
            if(dragging&&appearance.floating) {
                float p=pointPosition(x), last=order.length-1;
                float over=p-clamp(p,0,last);
                float rubber=(float)(Math.signum(over)*(1-1/(1+Math.abs(over)*.55))*.35);
                position.tune(1024,.9);position.target=clamp(p,0,last)+rubber;
                pull.target=rubber;
                int next=order[indexAt(p)];
                if(next!=hoverPage){hoverPage=next;performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);updateColors(hoverPage);}
            }
            kick();return true;
        }
        if(action==MotionEvent.ACTION_UP || action==MotionEvent.ACTION_CANCEL
                ||(action==MotionEvent.ACTION_POINTER_UP&&e.getPointerId(e.getActionIndex())==pointer)) {
            float speed=0;
            if(velocity!=null){velocity.computeCurrentVelocity(1000);speed=velocity.getXVelocity(pointer)/panel.getScaleX();}
            release(action!=MotionEvent.ACTION_CANCEL&&(appearance.floating||!dragging),speed);return true;
        }
        return true;
    }
    @Override public boolean performClick(){super.performClick();return true;}

    private void release(boolean commit,float speed) {
        if(!pressing)return;
        if(commit&&dragging) {
            int finger=slotOf(hoverPage);
            int next=indexAt((float)position.target+speed/Math.max(1,pitch)*.1f);
            next=Math.max(Math.max(0,finger-1),Math.min(Math.min(order.length-1,finger+1),next));
            if(order[next]!=hoverPage)performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);
            hoverPage=order[next];
        }
        pressing=false;dragging=false;pointer=-1;
        if(velocity!=null){velocity.recycle();velocity=null;}
        int target=commit?hoverPage:selected;
        position.tune(300,.55);position.target=Math.max(0,slotOf(target));
        lift.tune(420,.58);lift.target=0;swell.tune(144,.55);swell.target=0;pull.target=0;
        glow.tune(100,1);glow.target=0;
        updateColors(target);kick();
        if(commit){performClick();navigate(target);}
    }

    private void updateColors(int page) {
        if(visualPage==page)return;visualPage=page;
        boolean selectedVisible=slotOf(page)>=0;
        for(int i=0;i<5;i++){
            if(icons[i]==null||labels[i]==null)continue;
            int color=selectedVisible&&i==page?ui.accent():(ui.amap?ui.text():ui.text2());
            icons[i].color=color;icons[i].invalidate();labels[i].setTextColor(color);
        }
    }
    private void kick() {
        if(!motion()) {
            for(DockSpring s:new DockSpring[]{position,lift,swell,jelly,pull,glow})s.snap(s.target);
            updateGeometry();return;
        }
        if(!framePosted&&isAttachedToWindow()&&glassVisible){framePosted=true;Choreographer.getInstance().postFrameCallback(frame);}
    }
    private void step(long now) {
        framePosted=false;
        if(!glassVisible||!isAttachedToWindow()){frameTime=0;return;}
        double dt=frameTime==0?1.0/60:Math.min(.1,(now-frameTime)/1_000_000_000.0);frameTime=now;
        double scale=MotionPolicy.timeScale(getContext());
        if(scale==0){position.snap(Math.max(0,slotOf(selected)));lift.snap(0);swell.snap(0);pull.snap(0);glow.snap(0);jelly.snap(0);updateGeometry();return;}
        dt/=scale;
        position.advance(dt);lift.advance(dt);swell.advance(dt);pull.advance(dt);glow.advance(dt);
        jelly.target=Math.min(.16,Math.abs(position.velocity)*.014);jelly.advance(dt);
        updateGeometry();
        if(position.moving()||lift.moving()||swell.moving()||pull.moving()||glow.moving()||jelly.moving())kick();
        else {frameTime=0;if(!pressing)updateColors(selected);}
    }
    private void updateGeometry() {
        if(pitch<=0)return;
        float raised=0, stretch=0;
        float width=(lensWidth+ui.dp(16)*raised)*(1+stretch);
        float height=appearance.floating?(ui.dp(54)+ui.dp(16)*raised)*(1-stretch*1.4f):ui.dp(30);
        if((ui.amap)&&appearance.floating)width=pitch-ui.dp(2);
        if(!appearance.floating)width=Math.min(ui.dp(64),pitch-ui.dp(8));
        float cx=firstCenter+value(position)*pitch,cy=ui.dp(appearance.floating?31:22);
        lensRect.set(cx-width/2,cy-height/2,cx+width/2,cy+height/2);
        float scale=appearance.floating&&!ui.amap?1+value(swell)*Math.min(.25f,ui.dp(16)/(float)Math.max(1,panel.getWidth())):1;
        pullPixels=ui.amap?0:value(pull)*ui.dp(38);
        panel.setScaleX(scale*(1+Math.abs(pullPixels)/Math.max(1,panel.getWidth())*.5f));
        panel.setScaleY(scale);panel.setTranslationX(pullPixels);
        if(!"paper".equals(ui.style()))for(int i=0;i<icons.length;i++)if(icons[i]!=null){
            float compression=i==(pressing?hoverPage:selected)?value(lift):0;
            icons[i].setScaleX(1-.10f*compression);icons[i].setScaleY(1-.10f*compression);icons[i].setTranslationY(ui.dp(2)*compression);
            if(labels[i]!=null)labels[i].setTranslationY(ui.dp(2)*compression);
        }
        material.invalidate();renderGlass();
    }
    boolean gpuGlass() {return renderer!=null&&renderer.active();}
    boolean floating(){return appearance.floating;}
    boolean hasGlass(){return false;}
    int shadowRoomDp(){return appearance.floating?SHADOW_ROOM_DP:0;}
    void glassVisible(boolean visible) {
        if(glassVisible==visible){if(visible){playTransition();renderGlass();}return;}
        glassVisible=visible;
        if(renderer!=null)renderer.setVisible(visible);
        if(visible){frameTime=0;playTransition();updateGeometry();kick();}
        else {
            finishContent();resetDock();
            releaseNavigationPainter();
            Choreographer.getInstance().removeFrameCallback(frame);framePosted=false;frameTime=0;
            if(pressing)release(false,0);
        }
    }
    private void renderGlass() {
        if(!glassVisible||panel.getWidth()==0)return;
        if(hasGlass()&&renderer==null&&!rendererAttempted&&host.isHardwareAccelerated()) {
            rendererAttempted=true;renderer=NavGlassRenderer.create(activity,host,this,tabs,ui.dark);
            if(renderer!=null){renderer.setVisible(true);post(()->LiveNavWindow.refresh(activity));}
        }
        if(renderer==null)return;
        float sx=panel.getScaleX(),sy=panel.getScaleY();
        float ox=panel.getX()+panel.getPivotX()*(1-sx),oy=panel.getY()+panel.getPivotY()*(1-sy);
        barOnWindow.set(ox,oy,ox+panel.getWidth()*sx,oy+panel.getHeight()*sy);
        lensOnWindow.set(ox+lensRect.left*sx,oy+lensRect.top*sy,ox+lensRect.right*sx,oy+lensRect.bottom*sy);
        boolean was=renderer.active();renderer.update(barOnWindow,lensOnWindow,lensRect,ui.amap?0:clamp(value(lift),0,1));
        if(was&&!renderer.active())post(()->LiveNavWindow.refresh(activity));
    }

    private void finishContent() {
        source.animate().setListener(null).withEndAction(null).cancel();
        source.setTranslationX(0);source.setTranslationY(0);source.setAlpha(1);source.setScaleX(1);source.setScaleY(1);
        dismissSnapshot(true);
    }
    private void navigate(int index) {
        if(index<0||index==selected)return;
        finishContent();request(activity,selected,index);
    }
    private static void request(Activity activity,int from,int to) {
        if(to<0||to>=PAGES.length||activity.isFinishing()||activity.isDestroyed())return;
        if(pendingOpen!=null)navigationQueue.removeCallbacks(pendingOpen);
        pendingOpen=null;pendingSource=null;
        if(from==to)return;
        final long requestedAt=SystemClock.uptimeMillis();
        final java.lang.ref.WeakReference<Activity> source=new java.lang.ref.WeakReference<>(activity);
        pendingSource=source;
        pendingOpen=()->{
            if(pendingSource!=source)return;
            Activity owner=source.get();pendingOpen=null;pendingSource=null;
            if(owner==null||owner.isFinishing()||owner.isDestroyed()||!owner.hasWindowFocus())return;
            transitionFrom=from;transitionTo=to;transitionAt=requestedAt;transitionToken++;
            // 正文直接显示，导航发出时即结束视觉请求，不等底栏重绘来消费过渡。
            consumedTransition=transitionToken;
            open(owner,to);
        };
        navigationQueue.post(pendingOpen);
    }
    private void dismissSnapshot(boolean recycle) { snapshotView=null; }
    /** 全局滑页手势已删；这个入口只剩设备测试在用，用来模拟一次切页请求。 */
    static void swipeNavigate(Activity activity,int from,int to) {request(activity,from,to);}
    public static void open(Activity activity,int index) {
        if(index==DockPages.CHECKIN){
            com.afs.guard.BackToHome.open(activity);
            return;
        }
        Intent intent=DockPages.intent(activity,index,true);
        if(intent==null)return;
        activity.startActivity(intent);activity.overridePendingTransition(0,0);
    }
    /**
     * 新页面正文直接显示；消费导航请求并更新底栏选中位置，不播放正文过渡。
     */
    private void playTransition() {
        if(!glassVisible||!activity.hasWindowFocus()||!source.isAttachedToWindow())return;
        long now=SystemClock.uptimeMillis();
        if(consumedTransition==transitionToken||transitionTo!=selected||playedTransition==transitionToken||now-transitionAt>1500||pitch<=0){
            if(snapshotView!=null&&(transitionTo!=selected||now-transitionAt>1500))dismissSnapshot(true);
            return;
        }
        playedTransition=transitionToken;consumedTransition=transitionToken;
        position.snap(Math.max(0,slotOf(selected)));
        lift.snap(0);swell.snap(0);jelly.snap(0);pull.snap(0);glow.snap(0);
        updateColors(selected);updateGeometry();
        enterContent(slotOf(transitionFrom)<slotOf(selected)?1:-1);
    }
    /** 页面直接到位；底栏自己的按压与气泡交互独立保留。 */
    private void enterContent(int direction) {
        finishContent();
    }
    @Override protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);if(visibility==VISIBLE&&source!=null)post(this::playTransition);
    }
    @Override protected void onAttachedToWindow() {super.onAttachedToWindow();MotionPolicy.add(getContext(),motionChanged);getContext().getSharedPreferences(Ui.SP,0).registerOnSharedPreferenceChangeListener(visualChanged);EffectQualityManager.addListener(navigationStyleChanged);getViewTreeObserver().addOnPreDrawListener(beforeDraw);}
    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if(!hasFocus)return;
        LiveNavWindow.focusChanged(activity);
        reloadPluginConfigIfChanged();
    }

    /** Called from the Activity lifecycle after Settings returns to this page. */
    boolean reloadPluginConfigIfChanged() {
        long current=BuiltInPluginStore.revision(getContext());
        if(current==pluginRevision)return false;
        pluginRevision=current;
        post(() -> {
            if(activity.isFinishing()||activity.isDestroyed())return;
            if(DockPages.isPlugin(selected)&&!DockPages.isEnabled(activity,selected)) {
                open(activity,DockPages.GUARD);
                activity.finish();
            } else {
                activity.recreate();
            }
        });
        return true;
    }
    @Override protected void onDetachedFromWindow() {
        cancelPending(activity);
        getContext().getSharedPreferences(Ui.SP,0).unregisterOnSharedPreferenceChangeListener(visualChanged);
        EffectQualityManager.removeListener(navigationStyleChanged);releaseNavigationPainter();
        MotionPolicy.remove(motionChanged);finishContent();glassVisible(false);dismissSnapshot(false);
        if(getViewTreeObserver().isAlive())getViewTreeObserver().removeOnPreDrawListener(beforeDraw);
        super.onDetachedFromWindow();
    }

    private final class Material extends View {
        private final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF bounds=new RectF();
        private ReferenceMaterial reference;
        Material(){super(activity);setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);}
        @Override protected void onDraw(Canvas c) {
            float h=getHeight(),w=getWidth();
            bounds.set(.5f,.5f,w-.5f,h-.5f);
            String requested=StyleManager.selected(activity);
            if(!"paper".equals(requested)){
                if(reference==null)reference=new ReferenceMaterial(requested,ui.surface(),
                        ui.dp(StyleMood.spatial(requested)?24:30),getResources().getDisplayMetrics().density);
                reference.setBounds(0,0,getWidth(),getHeight());
                reference.draw(c);
                if(slotOf(selected)>=0||pressing){
                    p.setShader(null);p.setStyle(Paint.Style.FILL);p.setColor(0x28FFFFFF);
                    c.drawRoundRect(lensRect,ui.dp(22),ui.dp(22),p);
                    p.setColor(ui.accent());
                    float selectedX=lensRect.centerX();
                    c.drawRoundRect(selectedX-ui.dp(14),h-ui.dp(5),selectedX+ui.dp(14),
                            h-ui.dp(2),ui.dp(2),ui.dp(2),p);
                }
                drawRipple(c);return;
            }
            if(drawNavigationStyle(c,bounds))return;
            String style=StyleMood.of(StyleManager.selected(activity));
            float radius=appearance.floating?ui.dp(16):0;
            // 风格先定底栏的形状语汇：纸面近方角、玻璃球悬浮、立体硬边宽栏、流体渐变胶囊。
            if(StyleMood.glass(style))radius=w/2f;
            else if(StyleMood.spatial(style))radius=ui.dp(6);
            else if(StyleMood.fluid(style))radius=ui.dp(30);
            else radius=ui.dp(8);
            p.setShader(null);p.setStyle(Paint.Style.FILL);
            if(StyleMood.fluid(style)){
                p.setShader(new LinearGradient(0,0,w,h,
                        new int[]{StyleMood.blend(ui.accent(),0xFF3BB6C9,.45f),ui.accent()},
                        null,Shader.TileMode.CLAMP));
                c.drawRoundRect(bounds,radius,radius,p);p.setShader(null);
                p.setColor(ui.surface());
            } else p.setColor(ui.surface());
            c.drawRoundRect(bounds,radius,radius,p);
            p.setStyle(Paint.Style.STROKE);
            if(StyleMood.spatial(style)){
                p.setStrokeWidth(ui.dp(2.2f));p.setColor(ui.line());
                c.drawRoundRect(bounds,radius,radius,p);
                p.setStyle(Paint.Style.FILL);p.setColor(StyleMood.blend(ui.line(),ui.accent(),.55f));
                c.drawRoundRect(ui.dp(14),h-ui.dp(7),w-ui.dp(14),h-ui.dp(3),ui.dp(2),ui.dp(2),p);
            } else if(StyleMood.fluid(style)){
                p.setStrokeWidth(ui.dp(1.2f));p.setColor(0x66FFFFFF);
                c.drawRoundRect(bounds,radius,radius,p);
            } else {
                p.setStrokeWidth(ui.dp(StyleMood.glass(style)?1.2f:.75f));
                p.setColor(StyleMood.glass(style)?ui.line():ui.line());
                c.drawRoundRect(bounds,radius,radius,p);
            }
            if(slotOf(selected)>=0||pressing){
                p.setStyle(Paint.Style.FILL);p.setColor(ui.palette.selection);
                float lens=StyleMood.glass(style)?lensRect.height()/2f:ui.dp(StyleMood.spatial(style)?4:StyleMood.fluid(style)?20:10);
                c.drawRoundRect(lensRect,lens,lens,p);
                p.setColor(ui.accent());
                float x=lensRect.centerX(),y=ui.dp(4);
                c.drawRoundRect(x-ui.dp(8),y,x+ui.dp(8),y+ui.dp(2),ui.dp(1),ui.dp(1),p);
            }
        }
        private void drawRipple(Canvas c){
            if(rippleAt==0||!motion())return;
            float t=(SystemClock.uptimeMillis()-rippleAt)/600f;if(t>=1){rippleAt=0;return;}
            int save=c.save();c.clipRect(bounds);p.setShader(null);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(ui.dp(1.2f));
            p.setColor(StyleMood.glass(ui.style())?0xFFFFFFFF:0xFFFFE9A5);p.setAlpha(Math.round(180*(1-t)));
            float radius=ui.dp(8+100*(1-(1-t)*(1-t)));c.drawCircle(rippleX,getHeight()/2f,radius,p);
            p.setAlpha(Math.round(70*(1-t)));p.setStrokeWidth(ui.dp(6));c.drawCircle(rippleX,getHeight()/2f,radius*.84f,p);p.setAlpha(255);c.restoreToCount(save);postInvalidateOnAnimation();
        }
    }

    private final class Icon extends View {
        private final int type;private int color;private final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
        private final ApprovedGlyph.Painter approved=new ApprovedGlyph.Painter();
        Icon(Activity c,int type,int color){super(c);this.type=type;this.color=color;}
        @Override protected void onDraw(Canvas canvas) {
            String style=StyleManager.selected(activity);int family=ApprovedGlyph.kind(T.s(LABELS[type]));
            if(!"paper".equals(style)&&family>=0){int s=canvas.save();canvas.scale(getWidth()/48f,getHeight()/48f);approved.draw(canvas,family,style,color);canvas.restoreToCount(s);return;}
            Canvas c=canvas;c.save();c.scale(getWidth()/28f,getHeight()/28f);
            // 导航图标始终用同一套细线轮廓，选中只改变颜色与气泡，不改变填充和线条重量。
            AmapStyle.glyph(c,p,T.s(LABELS[type]),color,false);
            c.restore();
        }
    }
}
