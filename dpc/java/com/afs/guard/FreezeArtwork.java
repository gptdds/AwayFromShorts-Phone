package com.afs.guard;
import android.content.Context;
import android.graphics.*;
import android.view.View;
/** Snow and time illustrations use the mascot palette, never the mascot itself. */
final class FreezeArtwork extends View {
    private final String style;private final boolean clock;private final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);private final Path path=new Path();
    private final Shader gold=new LinearGradient(30,15,110,120,new int[]{0xFFFFF3AF,0xFFFFDB4F,0xFFEEA62E},null,Shader.TileMode.CLAMP);
    FreezeArtwork(Context c,boolean clock){super(c);this.clock=clock;style=new Ui(c).style();setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);}
    @Override protected void onDraw(Canvas c){int save=c.save();c.scale(getWidth()/150f,getHeight()/140f);boolean spatial=StyleMood.spatial(style),fluid=StyleMood.fluid(style);
        p.setStyle(Paint.Style.FILL);p.setColor(0x183D5663);c.drawOval(40,118,112,130,p);p.setAlpha(255);
        if(clock){
            if(spatial||fluid){p.setColor(0xFFD9A449);p.setStrokeWidth(8);p.setStrokeCap(Paint.Cap.ROUND);c.drawLine(48,22,41,13,p);c.drawLine(104,22,111,13,p);c.drawLine(49,112,45,124,p);c.drawLine(101,112,106,124,p);}
            if(fluid)c.rotate(-12,76,67);p.setStyle(Paint.Style.FILL);p.setColor(0xFFC68B2F);c.drawCircle(80,72,43,p);p.setShader(gold);c.drawCircle(76,66,43,p);p.setShader(null);p.setColor(0xFFFFF5D4);c.drawCircle(76,66,32,p);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(spatial?3:1);p.setColor(0xFFFFF4BF);c.drawCircle(76,66,43,p);p.setColor(0xFFAA752B);p.setStrokeWidth(spatial?5:fluid?4:2);p.setStrokeCap(Paint.Cap.ROUND);c.drawLine(76,42,76,67,p);c.drawLine(76,67,94,78,p);
        }else{
            path.rewind();if(fluid){path.moveTo(43,19);path.cubicTo(98,0,122,29,116,72);path.cubicTo(115,116,60,133,34,101);path.cubicTo(12,77,20,36,43,19);}else{path.moveTo(76,14);path.lineTo(119,38);path.lineTo(119,96);path.lineTo(76,121);path.lineTo(33,96);path.lineTo(33,38);}path.close();
            if(spatial){int s=c.save();c.translate(6,6);p.setColor(0xFFC88F2D);p.setStyle(Paint.Style.FILL);c.drawPath(path,p);c.restoreToCount(s);}p.setShader(gold);p.setStyle(Paint.Style.FILL);c.drawPath(path,p);p.setShader(null);p.setColor(0xFFFFF6C8);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(spatial?3:1);c.drawPath(path,p);
            p.setColor(0xFFFFFBEA);p.setStrokeWidth(spatial?5:fluid?5:3);p.setStrokeCap(Paint.Cap.ROUND);for(int i=0;i<6;i++){int s=c.save();c.rotate(i*60,76,68);c.drawLine(76,37,76,68,p);c.drawLine(67,43,76,52,p);c.drawLine(85,43,76,52,p);c.restoreToCount(s);}
        }c.restoreToCount(save);
    }
}
