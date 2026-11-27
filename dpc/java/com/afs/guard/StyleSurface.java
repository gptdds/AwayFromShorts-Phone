package com.afs.guard;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.view.View;

/** Native ambient materials; input and application state remain owned by the pages. */
final class StyleSurface {
    private StyleSurface() { }

    /** 简约不需要装饰：直接铺基色，免得纸面被额外渐变弄脏。 */
    static void apply(View view, Ui ui, String styleId) {
        if (view == null) return;
        String id = StyleMood.of(styleId);
        if (StyleMood.is(id, "paper")) return;
        if(view.getBackground() instanceof Wash)return;
        view.setBackground(new Wash(id));
        AmbientTicker.attach(view);
    }

    private static final class Wash extends Drawable {
        // Unit-canvas paths and shaders are cached until the bounds change.
        private final String id;
        private final Path[] paths = new Path[32];
        private final Paint[] paints = new Paint[32];
        private int count;
        private int alpha = 255;
        private ColorFilter filter;
        private final Paint ambient=new Paint(Paint.ANTI_ALIAS_FLAG);

        Wash(String id) { this.id = id; }

        @Override protected void onBoundsChange(Rect bounds) {
            super.onBoundsChange(bounds);
            for (int i = 0; i < count; i++) {
                paths[i] = null;
                paints[i] = null;
            }
            count = 0;
            if (bounds.isEmpty()) return;
            if (StyleMood.glass(id)) glass();
            else if (StyleMood.spatial(id)) spatial();
            else fluid(); // Complete static surface for every non-paper style.
        }

        private void layer(Path path, Shader shader) {
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
            paint.setShader(shader);
            paint.setAlpha(alpha);
            paint.setColorFilter(filter);
            paths[count] = path;
            paints[count++] = paint;
        }

        private static Path polygon(float... xy) {
            Path path = new Path();
            path.moveTo(xy[0], xy[1]);
            for (int i = 2; i < xy.length; i += 2) path.lineTo(xy[i], xy[i + 1]);
            path.close();
            return path;
        }

        private static Path full() {
            return polygon(0, 0, 1, 0, 1, 1, 0, 1);
        }

        private static Shader gradient(float x0, float y0, float x1, float y1,
                                       int... colors) {
            return new LinearGradient(x0, y0, x1, y1, colors, null,
                    Shader.TileMode.CLAMP);
        }

        private void glow(float x, float y, float radius, int color) {
            layer(full(), new RadialGradient(x, y, radius, color,
                    color & 0x00FFFFFF, Shader.TileMode.CLAMP));
        }

        private void glass() {
            layer(full(), gradient(0, 0, 1, 1,
                    0xFFF7FCF6, 0xFFD9EFF4, 0xFFBCE2EB, 0xFFEEF6EB));
            glow(.10f, .16f, .72f, 0xAAFFFFFF);
            glow(.92f, .60f, .61f, 0x444AB4CB);

            // Broad translucent folds with a dark inner face and a luminous lip.
            Path sweep = new Path();
            sweep.moveTo(-.16f, .02f);
            sweep.cubicTo(.94f, -.14f, 1.31f, .27f, .57f, .46f);
            sweep.cubicTo(-.13f, .64f, -.04f, .80f, 1.16f, .91f);
            sweep.lineTo(1.16f, 1.09f);
            sweep.cubicTo(-.49f, .91f, -.43f, .50f, .47f, .36f);
            sweep.cubicTo(1.10f, .25f, .73f, .02f, -.16f, .14f);
            sweep.close();
            layer(sweep, gradient(.07f, .15f, .88f, .82f,
                    0x10FFFFFF, 0x88FFFFFF, 0x2491C7D4, 0xBBFFFFFF, 0x18FFFFFF));

            Path lip = new Path();
            lip.moveTo(-.16f, .02f);
            lip.cubicTo(.94f, -.14f, 1.31f, .27f, .57f, .46f);
            lip.cubicTo(-.13f, .64f, -.04f, .80f, 1.16f, .91f);
            lip.lineTo(1.16f, .922f);
            lip.cubicTo(-.10f, .81f, -.18f, .63f, .56f, .45f);
            lip.cubicTo(1.27f, .27f, .91f, -.12f, -.16f, .033f);
            lip.close();
            layer(lip, gradient(0, .1f, 1, .9f,
                    0x60FFFFFF, 0xDDFFFFFF, 0x999BDBE7, 0xEEFFFFFF, 0x44FFFFFF));

            Path fold = new Path();
            fold.moveTo(.69f, -.12f);
            fold.cubicTo(.15f, .13f, .18f, .30f, .81f, .48f);
            fold.cubicTo(1.40f, .65f, .87f, .88f, .30f, 1.10f);
            fold.lineTo(.49f, 1.10f);
            fold.cubicTo(1.20f, .84f, 1.43f, .62f, .88f, .44f);
            fold.cubicTo(.39f, .28f, .30f, .15f, .87f, -.12f);
            fold.close();
            layer(fold, gradient(.3f, .05f, 1, .85f,
                    0x00FFFFFF, 0x55FFFFFF, 0x99D1F0F4, 0xAAFFFFFF, 0x00FFFFFF));
            glow(.61f, .43f, .29f, 0x55FFFFFF);
        }

        private void spatial() {
            layer(full(), gradient(0, 0, 1, 1,
                    0xFFFFF8E9, 0xFFF5E5CB, 0xFFE8EFDF));
            glow(.72f, .18f, .85f, 0x55FFE5A5);
            // Overlapping extruded blocks: bright tops, saturated fronts, shaded sides.
            block(-.28f, -.04f, .50f, .16f, .12f, .05f,
                    0xFFFFEFC5, 0xFFF2D48C, 0xFFE0BA72);
            block(.88f, -.13f, .40f, .22f, .12f, .05f,
                    0xFFFFF3DB, 0xFFF6DFAC, 0xFFE8CF98);
            block(.88f, .72f, .52f, .33f, .18f, .11f,
                    0xFFBBE2D8, 0xFF9BCDC3, 0xFF7BB7B0);
        }

        private void block(float x, float y, float w, float h, float dx, float dy,
                           int top, int front, int side) {
            layer(polygon(x, y + h, x + w, y + h, x + w + dx, y + h - dy,
                    x + w + dx + .09f, y + h + .035f, x + .04f, y + h + .10f),
                    gradient(x, y + h - dy, x, y + h + .10f, 0x309B8053, 0x009B8053));
            layer(polygon(x, y, x + dx, y - dy, x + w + dx, y - dy, x + w, y),
                    gradient(x, y - dy, x + w, y, top, front));
            layer(polygon(x, y, x + w, y, x + w, y + h, x, y + h),
                    gradient(x, y, x + w, y + h, front, side));
            layer(polygon(x + w, y, x + w + dx, y - dy,
                    x + w + dx, y + h - dy, x + w, y + h),
                    gradient(x + w, y, x + w + dx, y + h, side, StyleMood.blend(side,0xFFAA936D,.18f)));
        }

        private void fluid() {
            layer(full(), gradient(0, 0, 1, 1,
                    0xFFFFC42C, 0xFFFF6A0A, 0xFFF51E45, 0xFFC20878));
            glow(.10f, .06f, .75f, 0xCCFFF05A);

            Path hot = new Path();
            hot.moveTo(-.18f, .10f);
            hot.cubicTo(.28f, -.08f, 1.37f, .13f, .92f, .42f);
            hot.cubicTo(.57f, .65f, -.31f, .39f, -.12f, .87f);
            hot.lineTo(-.24f, 1.14f);
            hot.cubicTo(-.52f, .39f, .41f, .49f, .68f, .32f);
            hot.cubicTo(.91f, .17f, .18f, .06f, -.18f, .24f);
            hot.close();
            layer(hot, gradient(.1f, .05f, .75f, .82f,
                    0xFFFFF26B, 0xFFFFA20C, 0xFFFF4A12, 0xFFCC094D));

            Path rose = new Path();
            rose.moveTo(1.12f, .03f);
            rose.cubicTo(.43f, .13f, .38f, .30f, .75f, .47f);
            rose.cubicTo(1.34f, .73f, .63f, .80f, .06f, 1.10f);
            rose.lineTo(.57f, 1.14f);
            rose.cubicTo(1.38f, .83f, 1.38f, .61f, .92f, .39f);
            rose.cubicTo(.63f, .25f, .83f, .18f, 1.12f, .16f);
            rose.close();
            layer(rose, gradient(.60f, .06f, .90f, 1,
                    0xFFFF5A19, 0xFFFF1648, 0xFFD5008D, 0xFFFF3C6F));

            Path gold = new Path();
            gold.moveTo(-.14f, .52f);
            gold.cubicTo(.17f, .35f, .61f, .50f, .52f, .66f);
            gold.cubicTo(.42f, .84f, -.01f, .78f, -.08f, 1.12f);
            gold.lineTo(.26f, 1.12f);
            gold.cubicTo(.22f, .91f, .80f, .83f, .76f, .63f);
            gold.cubicTo(.71f, .35f, .12f, .28f, -.14f, .43f);
            gold.close();
            layer(gold, gradient(.12f, .35f, .53f, 1,
                    0xFFFFF477, 0xFFFFCF24, 0xFFFF7910, 0xFFEF2445));
            glow(.33f, .56f, .23f, 0x66FFF588);
            layer(full(), gradient(0,0,1,1,0x481B0012,0x10000000,0x301A0019));
        }

        @Override public void draw(Canvas canvas) {
            Rect bounds = getBounds();
            if (bounds.isEmpty()) return;
            int save = canvas.save();
            canvas.clipRect(bounds);
            canvas.translate(bounds.left, bounds.top);
            canvas.scale(bounds.width(), bounds.height());
            double time=AmbientTicker.time();
            for (int i = 0; i < count; i++) {
                int layerSave=canvas.save();
                if(i>1)canvas.translate((float)Math.sin(time/(8+i%3)+i)*.018f,(float)Math.cos(time/(10+i%4)+i)*.012f);
                canvas.drawPath(paths[i], paints[i]);canvas.restoreToCount(layerSave);
            }
            if(AmbientTicker.particles())for(int i=0;i<20;i++){
                float x=((i*37+13)%100)/100f+(float)Math.sin(time/8+i)*.015f;
                float y=(float)((((i*23+9)%100)/100d-time/(20+i%9)+10000)%1);
                float radius=(1+i%3)/(float)Math.max(1,bounds.width());
                ambient.setColor(StyleMood.spatial(id)?(i%2==0?0x80C79C57:0x8065B4B0):StyleMood.glass(id)?0xCCFFFFFF:0xB3FFF2B9);
                if(StyleMood.spatial(id))canvas.drawRect(x-radius,y-radius,x+radius,y+radius,ambient);else canvas.drawCircle(x,y,radius,ambient);
            }
            canvas.restoreToCount(save);
        }

        @Override public void setAlpha(int value) {
            alpha = Math.max(0, Math.min(255, value));
            for (int i = 0; i < count; i++) paints[i].setAlpha(alpha);
            invalidateSelf();
        }

        @Override public int getAlpha() { return alpha; }

        @Override public void setColorFilter(ColorFilter value) {
            filter = value;
            for (int i = 0; i < count; i++) paints[i].setColorFilter(value);
            invalidateSelf();
        }

        @Override public int getOpacity() {
            return alpha == 255 && filter == null ? PixelFormat.OPAQUE : PixelFormat.TRANSLUCENT;
        }
    }
}
