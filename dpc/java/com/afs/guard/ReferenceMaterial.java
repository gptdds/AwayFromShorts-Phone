package com.afs.guard;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.GradientDrawable;

public final class ReferenceMaterial extends GradientDrawable implements com.afs.style.api.NativeMaterialSource {
    private final boolean spatial;
    private final boolean fluid;
    private final float density;
    private final int baseColor;
    private final boolean semanticColor;
    public int materialColor(){return baseColor;}
    public boolean semanticMaterialColor(){return semanticColor;}
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF face = new RectF();
    private final RectF inset = new RectF();
    private final Path facePath = new Path();
    private final Path depthPath = new Path();
    private final Path rimPath = new Path();
    private final Path shinePath = new Path();
    private final Path sweepPath = new Path();
    private final float[] corners = new float[8];
    private final float[] faceCorners = new float[8];
    private final float[] innerCorners = new float[8];
    private Shader depthShader;
    private Shader rimShader;
    private Shader shineShader;
    private Shader sweepShader;
    private float faceScale = 1f;
    private float faceScaleX = 1f;
    private float rimWidth;
    private boolean ready;

    public ReferenceMaterial(String style, int color, float radius, float density) {
        this(style,color,radius,density,false);
    }

    /** Semantic card colors identify card types; style may change finish, never their hue. */
    public ReferenceMaterial(String style,int color,float radius,float density,boolean semantic) {
        this.spatial = "spatial3d".equals(style);
        this.fluid = "fluidgradient".equals(style);
        this.density = Float.isFinite(density) && density > 0f ? density : 1f;
        this.baseColor = color;
        this.semanticColor = semantic;
        boolean dark = luminance(color) < .42f;
        int alpha = Color.alpha(color);
        setOrientation(Orientation.TL_BR);
        if(semantic){
            setColors(new int[]{mix(color,Color.WHITE,luminance(color)<.1f?.025f:.08f),color,mix(color,Color.BLACK,.035f)});
        } else if (spatial) {
            int plastic = color;
            setColors(new int[]{mix(plastic, Color.WHITE, .20f), plastic,
                    mix(plastic, Color.BLACK, .15f)});
        } else if (fluid) {
            float tint = .08f;
            setColors(new int[]{withAlpha(mix(color, 0xFFFFAD68, tint), alpha * 92 / 100),
                    withAlpha(mix(color, 0xFFFF779B, tint), alpha * 88 / 100),
                    withAlpha(mix(color, 0xFFED765E, tint), alpha * 94 / 100)});
        } else {
            setColors(new int[]{withAlpha(mix(color, Color.WHITE, .50f), alpha),
                    withAlpha(mix(color, 0xFFA4DFED, .12f), alpha * 62 / 100),
                    withAlpha(mix(color, Color.WHITE, .25f), alpha * 90 / 100)});
        }
        setShape(RECTANGLE);
        setCornerRadius(radius);
        ready = true;
    }

    @Override
    public void setCornerRadius(float radius) {
        float safeRadius = Float.isFinite(radius) ? Math.max(0f, radius) : 0f;
        super.setCornerRadius(safeRadius);
        if (corners != null) {
            for (int i = 0; i < corners.length; i++) corners[i] = safeRadius;
            rebuild();
        }
    }

    @Override
    public void setCornerRadii(float[] radii) {
        if (radii != null && radii.length < 8) {
            throw new IllegalArgumentException("Eight corner radii are required");
        }
        if (corners == null) {
            super.setCornerRadii(radii);
            return;
        }
        for (int i = 0; i < corners.length; i++) {
            float radius = radii == null ? 0f : radii[i];
            corners[i] = Float.isFinite(radius) ? Math.max(0f, radius) : 0f;
        }
        super.setCornerRadii(corners);
        rebuild();
    }

    @Override
    protected void onBoundsChange(Rect bounds) {
        super.onBoundsChange(bounds);
        rebuild();
    }

    private void rebuild() {
        if (!ready) return;
        Rect bounds = getBounds();
        facePath.rewind();
        depthPath.rewind();
        rimPath.rewind();
        shinePath.rewind();
        sweepPath.rewind();
        if (bounds.isEmpty()) return;
        float width = bounds.width();
        float height = bounds.height();
        float depth = spatial ? Math.min(6f * density, height * .16f) : 0f;
        float side = spatial ? Math.min(3f * density, width * .08f) : 0f;
        faceScale = (height - depth) / height;
        faceScaleX = (width - side) / width;
        face.set(bounds.left, bounds.top, bounds.right - side, bounds.bottom - depth);
        float cornerScale = 1f;
        for (int i = 0; i < 8; i += 2) {
            faceCorners[i] = corners[i] * faceScaleX;
            faceCorners[i + 1] = corners[i + 1] * faceScale;
        }
        cornerScale = fit(cornerScale, face.width(), faceCorners[0] + faceCorners[2]);
        cornerScale = fit(cornerScale, face.width(), faceCorners[4] + faceCorners[6]);
        cornerScale = fit(cornerScale, face.height(), faceCorners[1] + faceCorners[7]);
        cornerScale = fit(cornerScale, face.height(), faceCorners[3] + faceCorners[5]);
        for (int i = 0; i < 8; i++) faceCorners[i] *= cornerScale;
        facePath.addRoundRect(face, faceCorners, Path.Direction.CW);
        inset.set(bounds);
        depthPath.addRoundRect(inset, corners, Path.Direction.CW);
        rimWidth = Math.min((spatial ? 1.6f : 1f) * density,
                Math.min(width, face.height()) * .12f);
        inset.set(face);
        inset.inset(rimWidth, rimWidth);
        for (int i = 0; i < 8; i++) {
            innerCorners[i] = Math.max(0f, faceCorners[i] - rimWidth);
        }
        rimPath.addRoundRect(inset, innerCorners, Path.Direction.CW);
        float left = face.left;
        float top = face.top;
        float right = face.right;
        float bottom = face.bottom;
        float h = face.height();
        shinePath.moveTo(left - width * .05f, top);
        shinePath.lineTo(right + width * .05f, top);
        shinePath.cubicTo(right - width * .12f, top + h * .23f,
                left + width * .38f, top + h * .02f, left, top + h * .42f);
        shinePath.close();
        sweepPath.moveTo(left - width * .08f, bottom - h * .06f);
        sweepPath.cubicTo(left + width * .30f, top + h * .32f,
                left + width * .70f, bottom + h * .16f, right + width * .08f, top + h * .18f);
        sweepPath.cubicTo(left + width * .82f, bottom + h * .30f,
                left + width * .24f, top + h * .64f, left - width * .08f, bottom + h * .08f);
        sweepPath.close();
        depthShader = new LinearGradient(left, top, right, bounds.bottom,
                new int[]{mix(baseColor, Color.BLACK, .24f),
                        mix(baseColor, 0xFF8D682D, .48f)}, null, Shader.TileMode.CLAMP);
        rimShader = new LinearGradient(left, top, right, bottom,
                spatial ? new int[]{0xCFFFFFFF, 0x38FFFFFF, 0x65000000}
                        : fluid ? new int[]{0xE6FFF4DB, 0x40FF9E9E, 0xB3FFD4EB}
                        : new int[]{0xFFFFFFFF, 0xAAC9F5FF, 0xCCFFFFFF},
                new float[]{0f, .54f, 1f}, Shader.TileMode.CLAMP);
        shineShader = new LinearGradient(left, top, left, top + h * .48f,
                spatial ? 0x20FFFFFF : 0x40FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP);
        sweepShader = new RadialGradient(right - width * .18f, bottom,
                Math.max(width, h) * .85f,
                fluid ? 0x40FFF0D1 : 0x309CEBFF, 0x00FFFFFF, Shader.TileMode.CLAMP);
        invalidateSelf();
    }

    @Override
    public void draw(Canvas canvas) {
        if (getBounds().isEmpty()) return;
        int save = canvas.save();
        canvas.clipRect(getBounds());
        if (spatial) {
            prepare(depthShader, Paint.Style.FILL, 0f);
            canvas.drawPath(depthPath, paint);
            int faceSave = canvas.save();
            canvas.scale(faceScaleX, faceScale, face.left, face.top);
            super.draw(canvas);
            canvas.restoreToCount(faceSave);
        } else {
            super.draw(canvas);
        }
        canvas.clipPath(facePath);
        prepare(shineShader, Paint.Style.FILL, 0f);
        canvas.drawPath(shinePath, paint);
        if (!spatial) {
            prepare(sweepShader, Paint.Style.FILL, 0f);
            canvas.drawPath(sweepPath, paint);
        }
        prepare(rimShader, Paint.Style.STROKE, rimWidth);
        canvas.drawPath(rimPath, paint);
        canvas.restoreToCount(save);
    }

    private void prepare(Shader shader, Paint.Style style, float width) {
        paint.setShader(shader);
        paint.setStyle(style);
        paint.setStrokeWidth(width);
        paint.setAlpha(getAlpha() * Color.alpha(baseColor) / 255);
        paint.setColorFilter(getColorFilter());
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }

    private static float fit(float scale, float available, float requested) {
        return requested > available ? Math.min(scale, available / requested) : scale;
    }

    private static float luminance(int color) {
        return (Color.red(color) * .2126f + Color.green(color) * .7152f
                + Color.blue(color) * .0722f) / 255f;
    }

    private static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (alpha << 24);
    }

    private static int mix(int color, int target, float amount) {
        float keep = 1f - amount;
        return Color.argb(Color.alpha(color),
                Math.round(Color.red(color) * keep + Color.red(target) * amount),
                Math.round(Color.green(color) * keep + Color.green(target) * amount),
                Math.round(Color.blue(color) * keep + Color.blue(target) * amount));
    }
}
