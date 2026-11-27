package com.afs.guard;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.TextView;
import com.afs.guard.style.*;

/** Disposable emulator only; verifies the real settings click path and resource failures. */
public final class StyleEngineDeviceTest extends Instrumentation {
    private int checks;
    private Activity current;
    private void check(boolean ok,String label){if(!ok)throw new AssertionError(label);checks++;}
    private void ui(Runnable work){final Throwable[] error={null};runOnMainSync(()->{try{work.run();}catch(Throwable e){error[0]=e;}});if(error[0]!=null)throw new AssertionError(error[0]);}
    private void tick(){SystemClock.sleep(600);ui(()->{});}
    private Activity open(Intent intent){
        ActivityMonitor monitor=addMonitor(ThemeActivity.class.getName(),null,false);
        try{
            ui(()->{if(current==null)getTargetContext().startActivity(intent);else current.startActivity(intent);});
            Activity activity=monitor.waitForActivityWithTimeout(10000);
            if(activity==null)throw new AssertionError("theme activity did not open");
            tick();return activity;
        }finally{removeMonitor(monitor);}
    }
    private View find(View view,String label){
        if(view instanceof TextView&&label.contentEquals(((TextView)view).getText()))return view;
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){
            View hit=find(((ViewGroup)view).getChildAt(i),label);if(hit!=null)return hit;
        }
        return null;
    }
    private Object field(Object obj,String name)throws Exception{java.lang.reflect.Field f=obj.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(obj);}
    private static final class Probe implements RendererFactory {
        int creates,releases;boolean fail;View decoration;
        public String id(){return fail?"qa_broken":"qa_probe";}
        public String displayName(Context c){return id();}
        public StyleRenderer create(){creates++;final String id=id();return new StyleRenderer(){
            public String id(){return id;}
            public String displayName(Context c){return id;}
            public void attach(Context c,ViewGroup root,DesignTokens t,MotionTokens m,EffectQuality q){
                decoration=new View(c);root.addView(decoration);if(fail)throw new IllegalStateException("expected QA failure");
            }
            public void detach(){if(fail)throw new IllegalStateException("expected detach failure");}
            public void release(){releases++;if(decoration!=null&&decoration.getParent()!=null)((ViewGroup)decoration.getParent()).removeView(decoration);decoration=null;}
        };}
    }
    @Override public void onCreate(Bundle args){start();}
    @Override public void onStart(){
        Context c=getTargetContext();String oldStyle=StyleManager.selected(c),oldQuality=EffectQualityManager.preference(c);
        String rules=RuleStore.exportRules(c);int records=StudyStore.recs(c).size();Bundle result=new Bundle();Throwable failure=null;
        try{
            StyleManager.select(c,"paper");EffectQualityManager.setPreference(c,"low");
            current=open(new Intent(c,ThemeActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));tick();
            final Activity activity=current;final ViewGroup decor=(ViewGroup)activity.getWindow().getDecorView();
            final View preview=(View)field(activity,"preview");final EditText draft=new EditText(activity);
            ui(()->{draft.setText("保留搜索草稿");((ViewGroup)activity.findViewById(android.R.id.content)).addView(draft,new ViewGroup.LayoutParams(200,60));});
            final View fluid=find(decor,"流体渐变");check(fluid!=null,"gradient option exists");
            check(StyleRegistry.find("liquidglass")!=null,"glass style is available alongside minimal style");
            ui(()->((View)fluid.getParent()).performClick());tick();
            check("fluidgradient".equals(StyleManager.activeId()),"actual selector attaches gradient");
            check(!activity.isDestroyed()&&!activity.isFinishing(),"switch retains activity");
            check(field(activity,"preview")==preview,"switch retains same preview instance");
            check("保留搜索草稿".contentEquals(draft.getText()),"switch retains draft");
            check(find(decor,"流体渐变 ✓")!=null,"selector reflects selection in place");
            ui(()->StyleManager.select(c,"missing-style"));tick();
            check("paper".equals(StyleManager.activeId()),"unknown style falls back");
            ui(()->StyleManager.select(c,"fluidgradient"));tick();
            Activity second=open(new Intent(c,ThemeActivity.class).putExtra("page","fonts"));tick();
            final ViewGroup secondRoot=(ViewGroup)second.getWindow().getDecorView();
            ui(()->StyleManager.release(decor));
            check("fluidgradient".equals(StyleManager.activeId()),"old root cannot release visible root");
            ui(second::finish);tick();
            check("fluidgradient".equals(StyleManager.activeId()),"back navigation reattaches style");
            ui(activity::finish);tick();
            check(StyleManager.activeId()==null,"leaving page releases renderer");
            Probe probe=new Probe();Probe broken=new Probe();broken.fail=true;
            ui(()->{StyleRegistry.register(probe);StyleRegistry.register(broken);});
            check(probe.creates==0&&broken.creates==0,"registration is lazy");
            Ui look=new Ui(activity);FrameLayout scratch=new FrameLayout(activity);
            ui(()->{StyleManager.select(c,probe.id());StyleManager.apply(c,scratch,look.tokens(),look.motionTokens());});
            check(probe.creates==1,"selected factory creates once");
            ui(()->StyleManager.apply(c,scratch,look.tokens(),look.motionTokens()));
            check(probe.creates==1,"same root apply is idempotent");
            ui(()->{StyleManager.select(c,broken.id());StyleManager.apply(c,scratch,look.tokens(),look.motionTokens());});
            check(probe.releases==1,"previous instance released");
            check(broken.releases==1&&scratch.getChildCount()==0,"failed attach/detach still releases decoration");
            check("paper".equals(StyleManager.activeId()),"broken renderer falls back");
            ui(()->{EffectQualityManager.setPreference(c,"auto");for(int i=0;i<20;i++)EffectQualityManager.reportFrame(55);});
            check(EffectQualityManager.resolve(c)==EffectQuality.LOW||EffectQualityManager.resolve(c)==EffectQuality.REDUCED_MOTION,"measured slow frames lower auto quality");
            check(rules.equals(RuleStore.exportRules(c))&&records==StudyStore.recs(c).size(),"styles preserve rules and study records");
        }catch(Throwable e){failure=e;}
        finally{ui(()->{StyleManager.select(c,oldStyle);EffectQualityManager.setPreference(c,oldQuality);StyleManager.releaseActive();if(current!=null)current.finish();});}
        if(failure!=null){java.io.StringWriter trace=new java.io.StringWriter();failure.printStackTrace(new java.io.PrintWriter(trace));result.putString("stream",trace.toString());finish(0,result);}
        else{result.putString("stream","STYLE ENGINE DEVICE: "+checks+" assertions passed; preferences restored\n");finish(-1,result);}
    }
}
