package com.afs.style.liquidglass;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import com.afs.guard.style.DesignTokens;
import com.afs.guard.style.EffectQuality;
import com.afs.style.api.NavigationPainter;

/** Event-driven liquid lens: no idle clock, captured bitmap, engine, view or navigation callbacks. */
public final class GlassNavigationPainter implements NavigationPainter {
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF lens=new RectF();
    private boolean released;
    public boolean isReleased(){return released;}
    public boolean draw(Canvas canvas,RectF panel,RectF indicator,DesignTokens tokens,EffectQuality quality,
                        float density,float lift,float jelly,float pointerX,boolean floating){
        if(released||panel.width()<=0||panel.height()<=0)return false;
        float radius=floating?16*density:0;
        paint.setStyle(Paint.Style.FILL);paint.setShader(null);paint.setColor(tokens.surface);
        canvas.drawRoundRect(panel,radius,radius,paint);
        paint.setShader(new LinearGradient(0,panel.top,0,panel.bottom,
                mix(tokens.surface,Color.WHITE,tokens.dark?.055f:.28f),tokens.surface,Shader.TileMode.CLAMP));
        canvas.drawRoundRect(panel,radius,radius,paint);
        paint.setShader(null);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(.7f*density);
        paint.setColor(mix(tokens.line,Color.WHITE,tokens.dark?.14f:.35f));
        canvas.drawRoundRect(panel,radius,radius,paint);
        if(indicator==null||indicator.isEmpty())return true;
        float deformation=quality.allowMotion()&&quality!=EffectQuality.LOW?Math.min(.12f,Math.max(0,jelly)):0;
        float raised=quality.allowMotion()?Math.max(0,Math.min(1,lift)):0;
        float halfWidth=indicator.width()*(1+deformation)*.5f;
        float halfHeight=indicator.height()*(1-deformation*.4f)*.5f;
        lens.set(Math.max(panel.left+2*density,indicator.centerX()-halfWidth),
                Math.max(panel.top+2*density,indicator.centerY()-halfHeight),
                Math.min(panel.right-2*density,indicator.centerX()+halfWidth),
                Math.min(panel.bottom-2*density,indicator.centerY()+halfHeight));
        if(lens.isEmpty())return true;
        float rounding=lens.height()*.5f;
        paint.setStyle(Paint.Style.FILL);
        int top=mix(tokens.surface,Color.WHITE,tokens.dark?.17f:.72f);
        int bottom=mix(tokens.surface,tokens.accent,tokens.dark?.23f:.13f);
        paint.setShader(new LinearGradient(lens.left,lens.top,lens.right,lens.bottom,top,bottom,Shader.TileMode.CLAMP));
        canvas.drawRoundRect(lens,rounding,rounding,paint);
        if(raised>0&&quality==EffectQuality.HIGH){
            float x=Math.max(lens.left,Math.min(lens.right,pointerX));
            paint.setShader(new RadialGradient(x,lens.top,lens.width(),
                    Color.argb((int)(raised*32),255,255,255),Color.TRANSPARENT,Shader.TileMode.CLAMP));
            canvas.drawRoundRect(lens,rounding,rounding,paint);
        }
        paint.setShader(null);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(density);
        paint.setColor(mix(tokens.line,Color.WHITE,tokens.dark?.36f:.85f));
        canvas.drawRoundRect(lens,rounding,rounding,paint);
        paint.setStyle(Paint.Style.FILL);paint.setColor(tokens.accent);
        float x=lens.centerX(),y=panel.top+4*density;
        canvas.drawRoundRect(x-8*density,y,x+8*density,y+2*density,density,density,paint);
        return true;
    }
    private static int mix(int base,int tint,float amount){
        float keep=1-amount;
        return Color.rgb((int)(Color.red(base)*keep+Color.red(tint)*amount),
                (int)(Color.green(base)*keep+Color.green(tint)*amount),
                (int)(Color.blue(base)*keep+Color.blue(tint)*amount));
    }
    public void release(){paint.setShader(null);released=true;}
}
