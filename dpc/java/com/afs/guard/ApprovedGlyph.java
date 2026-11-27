package com.afs.guard;
import android.content.Context;
import android.graphics.*;
import android.view.View;
/** Three distinct native icon families from the approved reference, never text glyphs. */
final class ApprovedGlyph extends View {
    private final String style;private final int kind;private final Painter painter=new Painter();private final int color;
    ApprovedGlyph(Context c,String label,int color){super(c);style=new Ui(c).style();kind=kind(label);this.color=color;setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);}
    static int kind(String label){
        if(T.s("番茄钟").equals(label))return 9;
        if(T.s("正念空间").equals(label))return 10;
        if(T.s("未来倒计时").equals(label))return 11;
        if(T.s("使用时间统计").equals(label)||T.s("屏幕使用统计").equals(label))return 8;
        if(T.s("卡管理").equals(label)||T.s("打卡").equals(label)||"◎".equals(label))return 0;
        if(T.s("打卡历史").equals(label)||T.s("历史").equals(label)||T.s("时间限额").equals(label)||"◷".equals(label))return 1;
        if(T.s("备忘录").equals(label)||T.s("记忆").equals(label)||"✎".equals(label))return 2;
        if(T.s("工具箱").equals(label)||"▦".equals(label))return 3;
        if(T.s("待办卡").equals(label)||T.s("刷题").equals(label)||"✓".equals(label))return 4;
        if(T.s("守卫").equals(label))return 5;
        if(T.s("冻结").equals(label)||T.s("冻结时段").equals(label)||"❄".equals(label))return 6;
        if(T.s("设置").equals(label)||"⚙".equals(label))return 7;
        return -1;
    }
    @Override protected void onDraw(Canvas c){if(kind<0)return;float size=Math.min(getWidth(),getHeight());int s=c.save();c.translate((getWidth()-size)/2,(getHeight()-size)/2);c.scale(size/48,size/48);painter.draw(c,kind,style,color);c.restoreToCount(s);}
    static final class Painter {
        private final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);private final Path path=new Path();private final RectF r=new RectF();
        private boolean glass,spatial;private int ink,fill;
        void draw(Canvas c,int kind,String style,int requested){
            glass=StyleMood.glass(style);spatial=StyleMood.spatial(style);ink=glass?requested:spatial?0xFF664629:0xFFC56A43;fill=spatial?0xFFFFF3C6:0xFFFFF2BC;
            p.setShader(null);p.setStrokeWidth(glass?1.25f:spatial?2.7f:3.6f);p.setStrokeCap(Paint.Cap.ROUND);p.setStrokeJoin(Paint.Join.ROUND);
            if(kind==0){
                if(glass){round(c,11,7,37,41,2,false);line(c,29,7,29,16);line(c,29,16,37,16);line(c,17,25,31,25);line(c,17,31,27,31);check(c,17,16,3,3,5,-7);}
                else if(spatial){round(c,10,10,38,40,6,true);line(c,17,7,17,15);line(c,31,7,31,15);line(c,10,20,38,20);check(c,17,29,5,5,10,-11);}
                else{blob(c,8,7,40,42);round(c,14,13,36,37,7,true);check(c,18,25,5,5,9,-12);}
            }else if(kind==1){
                if(glass){circle(c,24,24,16,false);line(c,8,7,8,15);line(c,8,15,16,15);line(c,24,13,24,25);line(c,24,25,32,30);}
                else{if(spatial){line(c,15,7,9,13);line(c,33,7,39,13);line(c,16,39,13,43);line(c,33,39,36,43);circle(c,24,26,16,true);}else{blob(c,7,8,41,41);circle(c,25,24,12,true);}line(c,24,16,24,27);line(c,24,27,32,31);}
            }else if(kind==2){
                if(glass){round(c,15,8,36,40,1,false);for(int y=13;y<=37;y+=8)line(c,10,y,17,y);line(c,22,16,30,16);line(c,22,23,30,23);line(c,22,30,27,30);}
                else if(spatial){int s=c.save();c.rotate(-7,24,24);round(c,12,9,38,41,2,true);c.translate(-4,-4);round(c,12,9,38,41,2,true);line(c,18,17,31,17);line(c,18,24,31,24);line(c,18,31,26,31);c.restoreToCount(s);}
                else{blob(c,9,8,39,40);fill=0xFFFFF6D2;round(c,7,7,33,37,3,true);line(c,14,16,27,16);line(c,14,23,27,23);line(c,14,30,22,30);}
            }else if(kind==3){
                if(glass){round(c,8,20,40,39,2,false);round(c,16,12,32,20,1,false);line(c,8,26,40,26);round(c,21,26,27,32,1,false);}
                else if(spatial){round(c,7,15,41,41,5,true);round(c,16,8,32,15,2,false);line(c,7,25,41,25);round(c,20,25,28,32,1,false);}
                else{blob(c,6,13,42,41);round(c,9,20,43,40,9,true);p.setColor(0xFFE4A05B);p.setStyle(Paint.Style.FILL);c.drawRect(18,25,31,34,p);}
            }else if(kind==4){
                if(glass){for(int y=14;y<=38;y+=12){circle(c,12,y,4,false);line(c,22,y,y==26?34:38,y);}check(c,9,26,2,2,4,-5);}
                else if(spatial){round(c,11,8,39,41,5,true);round(c,19,5,31,13,1,true);check(c,16,23,3,3,5,-6);check(c,16,34,3,3,5,-6);line(c,28,23,33,23);line(c,28,34,33,34);}
                else{round(c,9,7,39,42,12,true);check(c,15,18,3,3,5,-7);check(c,15,31,3,3,5,-7);line(c,28,18,33,18);line(c,28,31,33,31);}
            }else if(kind==5){
                path.rewind();path.moveTo(24,5);path.lineTo(40,12);path.lineTo(38,28);path.quadTo(35,37,24,43);path.quadTo(13,37,10,28);path.lineTo(8,12);path.close();paintPath(c,!glass);check(c,16,23,6,6,11,-13);
            }else if(kind==6){
                if(!glass){if(spatial){path.rewind();path.moveTo(24,5);path.lineTo(40,14);path.lineTo(40,35);path.lineTo(24,44);path.lineTo(8,35);path.lineTo(8,14);path.close();paintPath(c,true);}else blob(c,6,6,42,42);}
                for(int i=0;i<6;i++){int s=c.save();c.rotate(i*60,24,24);line(c,24,glass?5:13,24,24);if(glass||spatial){line(c,glass?17:19,glass?9:15,24,glass?16:20);line(c,glass?31:29,glass?9:15,24,glass?16:20);}c.restoreToCount(s);}
            }else if(kind==8){
                line(c,8,8,8,40);line(c,8,40,42,40);
                if(glass){round(c,14,25,20,35,1,false);round(c,25,16,31,35,1,false);round(c,36,9,42,35,1,false);}
                else{round(c,14,25,20,35,spatial?1:3,true);round(c,25,16,31,35,spatial?1:3,true);round(c,36,9,42,35,spatial?1:3,true);}
            }else if(kind==9){
                circle(c,24,27,15,!glass);line(c,19,5,29,5);line(c,24,5,24,10);line(c,36,11,40,15);line(c,24,27,24,17);line(c,24,27,30,30);
            }else if(kind==10){
                circle(c,24,24,5,!glass);circle(c,24,24,12,false);line(c,24,3,24,7);line(c,24,41,24,45);line(c,3,24,7,24);line(c,41,24,45,24);
            }else if(kind==11){
                round(c,9,10,39,41,glass?3:7,!glass);line(c,17,6,17,15);line(c,31,6,31,15);line(c,10,20,38,20);line(c,18,28,30,28);line(c,30,28,23,36);
            }else if(kind==7){
                if(glass){circle(c,24,24,8,false);circle(c,24,24,17,false);for(int i=0;i<6;i++){int s=c.save();c.rotate(i*60,24,24);line(c,24,3,24,9);c.restoreToCount(s);}}
                else{blob(c,5,5,43,43);circle(c,24,24,8,true);}
            }
        }
        private void line(Canvas c,float x,float y,float a,float b){p.setStyle(Paint.Style.STROKE);p.setColor(ink);c.drawLine(x,y,a,b,p);}
        private void check(Canvas c,float x,float y,float ax,float ay,float bx,float by){line(c,x,y,x+ax,y+ay);line(c,x+ax,y+ay,x+ax+bx,y+ay+by);}
        private void round(Canvas c,float l,float t,float rr,float b,float rad,boolean filled){r.set(l,t,rr,b);if(filled){p.setStyle(Paint.Style.FILL);p.setColor(fill);c.drawRoundRect(r,rad,rad,p);}if(glass||spatial){p.setStyle(Paint.Style.STROKE);p.setColor(ink);c.drawRoundRect(r,rad,rad,p);}}
        private void circle(Canvas c,float x,float y,float rad,boolean filled){if(filled){p.setStyle(Paint.Style.FILL);p.setColor(fill);c.drawCircle(x,y,rad,p);}if(glass||spatial){p.setStyle(Paint.Style.STROKE);p.setColor(ink);c.drawCircle(x,y,rad,p);}}
        private void blob(Canvas c,float l,float t,float rr,float b){path.rewind();path.moveTo(l+10,t);path.cubicTo(rr,t-4,rr+4,t+10,rr,b-10);path.cubicTo(rr,b+4,l-4,b+4,l,b-10);path.quadTo(l-5,t,l+10,t);path.close();p.setStyle(Paint.Style.FILL);p.setColor(glass?0x66FFFFFF:spatial?fill:0xFFCF7250);c.drawPath(path,p);if(spatial){p.setStyle(Paint.Style.STROKE);p.setColor(ink);c.drawPath(path,p);}}
        private void paintPath(Canvas c,boolean filled){if(filled){p.setStyle(Paint.Style.FILL);p.setColor(fill);c.drawPath(path,p);}if(glass||spatial){p.setStyle(Paint.Style.STROKE);p.setColor(ink);c.drawPath(path,p);}}
    }
}
