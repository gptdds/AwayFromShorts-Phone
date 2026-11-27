package com.afs.guard;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

/** Selected settings concept 03: a fine six-tooth outline, tinted by the current theme. */
final class GearIcon extends View {
    private static final Path OUTLINE = outline();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

    GearIcon(Context c, int color) {
        super(c);
        paint.setColor(color);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }
    void setColor(int value) {
        if (paint.getColor() == value) return;
        paint.setColor(value);
        invalidate();
    }
    /** Shared 24-unit geometry for toolbar, menu and alternate-style settings icons. */
    static void drawGear(Canvas canvas, Paint paint) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.2f);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        canvas.drawPath(OUTLINE, paint);
    }
    private static Path outline() {
        float[] x=new float[24],y=new float[24];
        float[] angles={-21f,-14f,14f,21f};
        for(int tooth=0;tooth<6;tooth++)for(int corner=0;corner<4;corner++){
            double angle=Math.toRadians(-90f+tooth*60f+angles[corner]);
            float radius=corner==1||corner==2?9.7f:7.3f;
            int i=tooth*4+corner;x[i]=12f+(float)Math.cos(angle)*radius;y[i]=12f+(float)Math.sin(angle)*radius;
        }
        Path path=new Path();
        // Round each polygon corner once, rather than constructing paths during drawing.
        for(int i=0;i<24;i++){
            int previous=(i+23)%24,next=(i+1)%24;
            float before=(float)Math.hypot(x[previous]-x[i],y[previous]-y[i]);
            float after=(float)Math.hypot(x[next]-x[i],y[next]-y[i]);
            float r=Math.min(.5f,Math.min(before,after)*.3f);
            float inX=x[i]+(x[previous]-x[i])*r/before,inY=y[i]+(y[previous]-y[i])*r/before;
            float outX=x[i]+(x[next]-x[i])*r/after,outY=y[i]+(y[next]-y[i])*r/after;
            if(i==0)path.moveTo(inX,inY);else path.lineTo(inX,inY);
            path.quadTo(x[i],y[i],outX,outY);
        }
        path.close();path.addCircle(12,12,3.8f,Path.Direction.CW);
        return path;
    }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w=getWidth()-getPaddingLeft()-getPaddingRight(),h=getHeight()-getPaddingTop()-getPaddingBottom();
        float size=Math.min(w,h);if(size<=0)return;
        canvas.save();canvas.translate(getPaddingLeft()+(w-size)/2f,getPaddingTop()+(h-size)/2f);
        canvas.scale(size/24f,size/24f);drawGear(canvas,paint);canvas.restore();
    }
}
