package com.afs.guard;
import android.content.Context;
import android.graphics.*;
import android.view.View;
/** Existing launcher artwork, cropped only for the Guard hero; never used on freeze cards. */
final class GuardArtwork extends View {
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
    private final Bitmap artwork;private final Rect crop=new Rect();private final RectF target=new RectF();
    GuardArtwork(Context c){super(c);int id=c.getResources().getIdentifier("ic_launcher_foreground","drawable",c.getPackageName());artwork=id==0?null:BitmapFactory.decodeResource(c.getResources(),id);if(artwork!=null)crop.set(artwork.getWidth()*155/668,artwork.getHeight()*125/668,artwork.getWidth()*545/668,artwork.getHeight()*540/668);setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);}
    @Override protected void onDraw(Canvas c){if(artwork==null)return;float h=Math.min(getHeight(),getWidth()*crop.height()/(float)crop.width()),w=h*crop.width()/crop.height();target.set((getWidth()-w)/2,(getHeight()-h)/2,(getWidth()+w)/2,(getHeight()+h)/2);c.drawBitmap(artwork,crop,target,paint);}
}
