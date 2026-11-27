package com.afs.style.api;

import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.os.SystemClock;
import android.view.*;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.ImageView;
import com.afs.guard.style.DesignTokens;
import com.afs.guard.style.EffectQuality;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/** Native background decoration only. Never replaces children, listeners or business state. */
public final class NativeComponentLayer implements AutoCloseable {
    private static final ExecutorService COMPILER=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"AFS-materials");t.setDaemon(true);return t;});
    private final ViewGroup root;
    private final DesignTokens tokens;
    private final EffectQuality quality;
    private final int cardRole,pageRole;
    private final boolean fluid;
    private final LinkedHashMap<View,Surface> surfaces=new LinkedHashMap<>();
    private final LinkedHashMap<TextView,TextShadow> textShadows=new LinkedHashMap<>();
    private final ViewTreeObserver.OnGlobalLayoutListener layout=this::scan;
    private final Paint fallback=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int[] rootXY=new int[2],viewXY=new int[2];
    private Api33 gpu;
    private Future<?> compilation;
    private Bitmap backdrop;
    private View page;
    private Drawable pageBackground;
    private int pageWidth,pageHeight,pageX,pageY;
    private int sourceSignature;
    private boolean closed,posted;
    private Surface touching;
    private long previous=SystemClock.uptimeMillis();
    private float time;
    private int pointerEvents;
    private final Runnable tick=()->{posted=false;step();};
    public NativeComponentLayer(Context context,ViewGroup root,DesignTokens tokens,EffectQuality quality,
                                int cardRole,int pageRole,boolean fluid,String shaderAsset){
        this.root=root;this.tokens=tokens;this.quality=quality;this.cardRole=cardRole;this.pageRole=pageRole;this.fluid=fluid;
        root.getViewTreeObserver().addOnGlobalLayoutListener(layout);
        scan();
        if(Build.VERSION.SDK_INT>=33&&quality.allowShaders()) {
            Context app=context.getApplicationContext();
            compilation=COMPILER.submit(()->{
                try {
                    StringBuilder source=new StringBuilder();
                    try(BufferedReader reader=new BufferedReader(new InputStreamReader(app.getAssets().open(shaderAsset),"UTF-8"))){String line;while((line=reader.readLine())!=null)source.append(line).append('\n');}
                    Api33 ready=new Api33(source.toString());
                    root.post(()->{if(closed)return;gpu=ready;refreshBackdrop(true);invalidate();schedule();});
                } catch(Exception failure){android.util.Log.w("AFSMaterial","Native material unavailable",failure);}
            });
        }
        schedule();
    }
    public int componentCount(){return surfaces.size();}
    public boolean hasMaterial(View view){return surfaces.containsKey(view)&&view.getBackground()==surfaces.get(view);}
    public boolean shaderReady(){return gpu!=null;}
    public boolean isClosed(){return closed;}
    public boolean frameScheduled(){return posted;}
    public int observedPointerEvents(){return pointerEvents;}
    private void scan(){
        if(closed)return;
        Iterator<Map.Entry<View,Surface>> iterator=surfaces.entrySet().iterator();
        while(iterator.hasNext()){
            Map.Entry<View,Surface> entry=iterator.next();
            if(!entry.getKey().isAttachedToWindow()||entry.getKey().getBackground()!=entry.getValue()){
                Surface old=entry.getValue();if(old.view.getBackground()==old)background(old.view,old.original);iterator.remove();
            }
        }
        page=null;visit(root);if(page==null)page=root;refreshBackdrop(false);schedule();
    }
    private void visit(View view){
        if(page==null&&pageRole!=0&&view.getTag(pageRole)!=null)page=view;
        if(fluid&&view instanceof TextView)applyFluidInk((TextView)view);
        Drawable bg=view.getBackground();
        boolean card=cardRole!=0&&Boolean.TRUE.equals(view.getTag(cardRole));
        boolean field=view instanceof EditText;
        boolean control=view.isClickable()&&bg instanceof GradientDrawable;
        boolean capsule=view instanceof TextView&&bg instanceof GradientDrawable;
        if(!(bg instanceof Surface)&&bg!=null&&(card||field||control||capsule)){
            Surface surface=new Surface(view,bg,field?3:card?2:control?0:5);
            surfaces.put(view,surface);background(view,surface);
        }
        if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++)visit(group.getChildAt(i));}
    }
    private static void background(View view,Drawable bg){
        int l=view.getPaddingLeft(),t=view.getPaddingTop(),r=view.getPaddingRight(),b=view.getPaddingBottom();
        view.setBackground(bg);view.setPadding(l,t,r,b);
        if(bg instanceof NativeComponentLayer.Surface)((NativeComponentLayer.Surface)bg).original.setCallback((Drawable.Callback)bg);
    }
    private void refreshBackdrop(boolean force){
        if(Build.VERSION.SDK_INT<33||!quality.allowShaders())return;
        if(page==null||page.getWidth()==0||page.getHeight()==0||root.getWidth()==0)return;
        root.getLocationOnScreen(rootXY);page.getLocationOnScreen(viewXY);
        int x=viewXY[0]-rootXY[0],y=viewXY[1]-rootXY[1];
        Drawable bg=page.getBackground();
        Object sources=pageRole==0?null:page.getTag(pageRole);int signature=0;
        if(sources instanceof View[])for(View source:(View[])sources)signature=31*signature+System.identityHashCode(source instanceof ImageView?((ImageView)source).getDrawable():source.getBackground())+source.getVisibility();
        if(!force&&signature==sourceSignature&&bg==pageBackground&&page.getWidth()==pageWidth&&page.getHeight()==pageHeight&&pageX==x&&pageY==y&&backdrop!=null)return;
        sourceSignature=signature;
        pageBackground=bg;pageWidth=page.getWidth();pageHeight=page.getHeight();pageX=x;pageY=y;
        if(backdrop==null)backdrop=Bitmap.createBitmap(384,Math.max(1,Math.min(896,384*root.getHeight()/Math.max(1,root.getWidth()))),Bitmap.Config.ARGB_8888);
        Canvas canvas=new Canvas(backdrop);canvas.drawColor(tokens.bg,PorterDuff.Mode.SRC);
        canvas.scale(backdrop.getWidth()/(float)root.getWidth(),backdrop.getHeight()/(float)Math.max(1,root.getHeight()));
        canvas.translate(x,y);
        // Sample the native page's drawable, never its text, forms or application data.
        if(bg!=null)bg.draw(canvas);
        // Explicitly supplied background image/tint views are safe to sample; never draw the page subtree.
        if(sources instanceof View[])for(View source:(View[])sources)if(source.getVisibility()==View.VISIBLE){int save=canvas.save();canvas.translate(source.getLeft(),source.getTop());source.draw(canvas);canvas.restoreToCount(save);}
        if(gpu!=null)gpu.backdrop(backdrop);
    }
    private void schedule(){
        if(closed||posted||gpu==null||!root.isAttachedToWindow()||!root.isShown()||!quality.allowMotion()||!quality.allowShaders())return;
        posted=true;root.postDelayed(tick,quality==EffectQuality.HIGH?33:66);
    }
    private void step(){
        if(closed)return;
        long now=SystemClock.uptimeMillis();float dt=Math.min(.05f,(now-previous)/1000f);previous=now;time+=dt;
        for(Surface surface:surfaces.values()){
            float target=surface==touching?1:0;
            surface.velocity+=(target-surface.pressure)*dt*220f;
            surface.velocity*=Math.exp(-dt*22f);
            surface.pressure+=surface.velocity*dt;
            if(surface.view.isShown()&&surface.view.getGlobalVisibleRect(surface.visible))surface.invalidateSelf();
        }
        schedule();
    }
    private void invalidate(){for(Surface surface:surfaces.values())surface.invalidateSelf();}
    /** Coordinates are decor-root relative, matching ThemedActivity's existing read-only stream. */
    public void pointer(int action,float x,float y){
        if(closed)return;
        pointerEvents++;
        root.getLocationOnScreen(rootXY);x+=rootXY[0];y+=rootXY[1];
        if(action==MotionEvent.ACTION_DOWN){
            touching=null;long smallest=Long.MAX_VALUE;
            for(Surface surface:surfaces.values()){
                if(!surface.view.isShown())continue;surface.view.getLocationOnScreen(viewXY);
                long area=(long)surface.view.getWidth()*surface.view.getHeight();
                if(area<smallest&&x>=viewXY[0]&&y>=viewXY[1]&&x<viewXY[0]+surface.view.getWidth()&&y<viewXY[1]+surface.view.getHeight()) { touching=surface;smallest=area; }
            }
        }
        if(touching!=null){
            touching.view.getLocationOnScreen(viewXY);touching.fingerX=(x-viewXY[0])/Math.max(1,touching.view.getWidth());touching.fingerY=(y-viewXY[1])/Math.max(1,touching.view.getHeight());
            if(action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_CANCEL){touching.released=SystemClock.uptimeMillis();touching=null;}
        }
        invalidate();schedule();
    }
    @Override public void close(){
        if(closed)return;closed=true;root.removeCallbacks(tick);posted=false;
        if(compilation!=null)compilation.cancel(true);
        if(root.getViewTreeObserver().isAlive())root.getViewTreeObserver().removeOnGlobalLayoutListener(layout);
        for(Surface surface:surfaces.values())if(surface.view.getBackground()==surface)background(surface.view,surface.original);
        for(Map.Entry<TextView,TextShadow> entry:textShadows.entrySet()){
            TextShadow original=entry.getValue();
            if(original.appliedTo(entry.getKey()))
                entry.getKey().setShadowLayer(original.originalRadius,original.originalDx,original.originalDy,original.originalColor);
        }
        surfaces.clear();textShadows.clear();touching=null;gpu=null;backdrop=null;page=null;pageBackground=null;
    }
    /**
     * Fluid pages keep the native warm background, so light ink needs a soft dark
     * contour when it crosses a yellow/orange region. This only changes text paint.
     */
    private void applyFluidInk(TextView view){
        if(textShadows.containsKey(view)||view instanceof EditText||luma(view.getCurrentTextColor())<.55f)return;
        TextShadow original=new TextShadow(view.getShadowRadius(),view.getShadowDx(),view.getShadowDy(),view.getShadowColor());
        float density=view.getResources().getDisplayMetrics().density;
        float radius=2.4f*density,dy=1f*density;int color=0xB3000000;
        textShadows.put(view,original);
        view.setShadowLayer(radius,0,dy,color);
        original.appliedRadius=radius;original.appliedDx=0;original.appliedDy=dy;original.appliedColor=color;original.active=true;
    }
    private static final class TextShadow {
        final float originalRadius,originalDx,originalDy;final int originalColor;
        float appliedRadius,appliedDx,appliedDy;int appliedColor;boolean active;
        TextShadow(float radius,float dx,float dy,int color){
            originalRadius=radius;originalDx=dx;originalDy=dy;originalColor=color;
        }
        boolean appliedTo(TextView view){
            return active&&Float.compare(view.getShadowRadius(),appliedRadius)==0&&Float.compare(view.getShadowDx(),appliedDx)==0
                    &&Float.compare(view.getShadowDy(),appliedDy)==0&&view.getShadowColor()==appliedColor;
        }
    }
    private final class Surface extends Drawable implements Drawable.Callback {
        final View view;final Drawable original,material;final int role;final float corner;final Rect visible=new Rect();
        final int ink;final boolean semantic;final Paint staticPaint=new Paint(Paint.ANTI_ALIAS_FLAG);
        float pressure,velocity,fingerX=.5f,fingerY=.5f;long released;
        Surface(View view,Drawable original,int role){this.view=view;this.original=original;this.role=role;
            material=materialContent(original);
            ink=ink(view,tokens.text);
            semantic=material instanceof NativeMaterialSource&&((NativeMaterialSource)material).semanticMaterialColor()||role==5;
            float radius=12*view.getResources().getDisplayMetrics().density;
            if(material instanceof GradientDrawable){GradientDrawable shape=(GradientDrawable)material;radius=shape.getCornerRadius();try{float[] radii=shape.getCornerRadii();if(radii!=null&&radii.length>0)radius=radii[0];}catch(NullPointerException uniformRadius){}}
            corner=radius;}
        @Override public void draw(Canvas canvas){
            original.setBounds(getBounds());original.setState(getState());
            if(closed){original.draw(canvas);return;}
            Rect b=getBounds();if(b.isEmpty())return;
            if(gpu!=null&&backdrop!=null){
                root.getLocationOnScreen(rootXY);view.getLocationOnScreen(viewXY);
                int saved=canvas.save();canvas.translate(b.left,b.top);
                gpu.draw(canvas,b.width(),b.height(),viewXY[0]-rootXY[0],viewXY[1]-rootXY[1],root.getWidth(),root.getHeight(),time,
                        fingerX,fingerY,pressure,released==0?10:(SystemClock.uptimeMillis()-released)/1000f,role,corner,view.isFocused()?1:0,baseColor(),ink,semantic);
                canvas.restoreToCount(saved);
            } else {
                int base=baseColor();boolean light=luma(ink)>.55f;
                int face=semantic?base:fluid?(light?0xFF192F72:0xFFD5EBFA):(light?0xFF244859:0xFFECF7FB);
                staticPaint.setShader(new LinearGradient(0,b.top,b.right,b.bottom,new int[]{mix(face,Color.WHITE,.18f),face,mix(face,fluid?0xFF7565CF:0xFF284F62,.15f)},null,Shader.TileMode.CLAMP));
                canvas.drawRoundRect(new RectF(b),corner,corner,staticPaint);staticPaint.setShader(null);
                staticPaint.setColor(light?0x6695D8EE:0x99FFFFFF);staticPaint.setStyle(Paint.Style.STROKE);staticPaint.setStrokeWidth(view.getResources().getDisplayMetrics().density);
                RectF rim=new RectF(b);rim.inset(1,1);canvas.drawRoundRect(rim,corner,corner,staticPaint);staticPaint.setStyle(Paint.Style.FILL);
            }
        }
        private int baseColor(){if(material instanceof NativeMaterialSource)return ((NativeMaterialSource)material).materialColor();if(material instanceof GradientDrawable){android.content.res.ColorStateList c=((GradientDrawable)material).getColor();if(c!=null)return c.getColorForState(getState(),c.getDefaultColor());}return tokens.surface;}
        @Override public void getOutline(Outline outline){original.getOutline(outline);}
        @Override public int getIntrinsicWidth(){return original.getIntrinsicWidth();}
        @Override public int getIntrinsicHeight(){return original.getIntrinsicHeight();}
        @Override public int getMinimumWidth(){return original.getMinimumWidth();}
        @Override public int getMinimumHeight(){return original.getMinimumHeight();}
        @Override public void invalidateDrawable(Drawable who){invalidateSelf();}
        @Override public void scheduleDrawable(Drawable who,Runnable what,long when){scheduleSelf(what,when);}
        @Override public void unscheduleDrawable(Drawable who,Runnable what){unscheduleSelf(what);}
        @Override public boolean getPadding(Rect rect){return original.getPadding(rect);}
        @Override public boolean isStateful(){return true;}
        @Override protected boolean onStateChange(int[] state){original.setState(state);invalidateSelf();return true;}
        @Override public void setAlpha(int alpha){original.setAlpha(alpha);}
        @Override public void setColorFilter(ColorFilter filter){original.setColorFilter(filter);}
        @Override public int getOpacity(){return PixelFormat.TRANSLUCENT;}
    }
    private static Drawable materialContent(Drawable drawable){
        if(drawable instanceof RippleDrawable){
            RippleDrawable ripple=(RippleDrawable)drawable;
            for(int i=0;i<ripple.getNumberOfLayers();i++)
                if(ripple.getId(i)!=android.R.id.mask)return materialContent(ripple.getDrawable(i));
        }
        return drawable;
    }
    private static int ink(View view,int fallback){if(view instanceof TextView)return ((TextView)view).getCurrentTextColor();if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++){View child=group.getChildAt(i);if(child instanceof TextView||child instanceof ViewGroup){int color=ink(child,0);if(color!=0)return color;}}}return fallback;}
    private static float luma(int color){return (Color.red(color)*.2126f+Color.green(color)*.7152f+Color.blue(color)*.0722f)/255f;}
    private static int mix(int a,int b,float t){return Color.rgb(Math.round(Color.red(a)*(1-t)+Color.red(b)*t),Math.round(Color.green(a)*(1-t)+Color.green(b)*t),Math.round(Color.blue(a)*(1-t)+Color.blue(b)*t));}
    /** Isolated API class keeps the Java 8 / API 24 compatibility path verifiable. */
    private static final class Api33 {
        final RuntimeShader shader;final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        Api33(String source){shader=new RuntimeShader(source);paint.setShader(shader);}
        void backdrop(Bitmap bitmap){BitmapShader sampler=new BitmapShader(bitmap,Shader.TileMode.CLAMP,Shader.TileMode.CLAMP);sampler.setFilterMode(BitmapShader.FILTER_MODE_LINEAR);shader.setInputShader("backdrop",sampler);shader.setFloatUniform("textureSize",bitmap.getWidth(),bitmap.getHeight());}
        void draw(Canvas canvas,float w,float h,float x,float y,float rw,float rh,float time,float fx,float fy,float pressure,float age,int role,float radius,int focus,int base,int ink,boolean semantic){
            shader.setFloatUniform("extent",w,h);shader.setFloatUniform("origin",x,y);shader.setFloatUniform("resolution",Math.max(1,rw),Math.max(1,rh));shader.setFloatUniform("time",time);
            shader.setFloatUniform("finger",fx,fy);shader.setFloatUniform("pressure",pressure);shader.setFloatUniform("releaseAge",age);shader.setFloatUniform("role",role);shader.setFloatUniform("corner",radius);shader.setFloatUniform("focus",focus);
            shader.setFloatUniform("baseColor",Color.red(base)/255f,Color.green(base)/255f,Color.blue(base)/255f);
            shader.setFloatUniform("lightInk",luma(ink)>.55f?1:0);shader.setFloatUniform("semantic",semantic?1:0);
            canvas.drawRect(0,0,w,h,paint);
        }
    }
}
