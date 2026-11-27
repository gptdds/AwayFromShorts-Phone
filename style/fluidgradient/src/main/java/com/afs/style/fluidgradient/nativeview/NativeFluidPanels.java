package com.afs.style.fluidgradient.nativeview;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.SweepGradient;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;

import com.afs.guard.style.DesignTokens;
import com.afs.guard.style.EffectQuality;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * Paints flowing borders over semantic cards without replacing their backgrounds or content.
 * All cards share one shader, one paint and one path; cards stay business-owned Views.
 */
public final class NativeFluidPanels implements AutoCloseable {
    private ViewGroup root;
    private final int cardRole;
    private final float density;
    private final EffectQuality quality;
    private final WeakHashMap<View, CardShape> cards = new WeakHashMap<>();
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF bounds = new RectF();
    private final Matrix matrix = new Matrix();
    private final ViewTreeObserver.OnGlobalLayoutListener layout = () -> scan(root);
    private View invalidateTarget;
    private android.graphics.Shader shader;

    private static final class CardShape {
        final float[] radii;
        CardShape(float[] radii) { this.radii = radii; }
    }

    public NativeFluidPanels(ViewGroup root, int cardRole, DesignTokens tokens, EffectQuality quality) {
        this.root = root;
        this.cardRole = cardRole;
        this.quality = quality;
        this.density = root.getResources().getDisplayMetrics().density;
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        float width = quality == EffectQuality.HIGH || quality == EffectQuality.MEDIUM ? 1.65f : 1.25f;
        stroke.setStrokeWidth(Math.max(1f, density * width));
        int accent = opaque(tokens.accent);
        int highlight = tokens.dark ? 0xB8DDEBFF : 0xE8FFFFFF;
        int quiet = withAlpha(accent, tokens.dark ? 0x66 : 0x78);
        shader = new SweepGradient(0f, 0f, new int[]{accent, highlight, quiet, accent}, null);
        stroke.setShader(shader);
        if (cardRole != 0) {
            root.getViewTreeObserver().addOnGlobalLayoutListener(layout);
            try {
                scan(root);
            } catch (RuntimeException failure) {
                close();
                throw failure;
            }
        }
    }

    /**
     * Draws the border overlay. Coordinates remain in the page tree, so scrolling and late
     * layout changes need no mutation of any card or its background.
     */
    public void draw(Canvas canvas, View overlay, long frame) {
        if (root == null || cards.isEmpty() || canvas == null) return;
        animate(frame, overlay.getWidth(), overlay.getHeight());
        int restore = canvas.save();
        for (Map.Entry<View, CardShape> item : cards.entrySet()) {
            View card = item.getKey();
            if (card == null || !card.isAttachedToWindow() || !card.isShown() || card.getParent() == null || card.getWidth() <= 0 || card.getHeight() <= 0) continue;
            if (cardRole != 0 && !Boolean.TRUE.equals(card.getTag(cardRole))) continue;
            relativeOrigin(card, root, bounds);
            if (bounds.right <= 0 || bounds.bottom <= 0 || bounds.left >= overlay.getWidth()
                    || bounds.top >= overlay.getHeight()) continue;
            float[] radii = item.getValue().radii;
            float inset = stroke.getStrokeWidth() * .5f;
            bounds.inset(inset, inset);
            if (bounds.isEmpty()) continue;
            path.reset();
            if (radii != null && radii.length == 8) {
                path.addRoundRect(bounds, radii, Path.Direction.CW);
            } else {
                float radius = radii == null || radii.length == 0 ? 12f * density : radii[0];
                path.addRoundRect(bounds, radius, radius, Path.Direction.CW);
            }
            canvas.drawPath(path, stroke);
        }
        canvas.restoreToCount(restore);
    }

    public void invalidateWhenLayoutChanges(View target) {
        invalidateTarget = target;
    }

    private void animate(long frame, float width, float height) {
        shader.setLocalMatrix(null);
        if (shader instanceof SweepGradient) {
            matrix.reset();
            boolean moving = quality == EffectQuality.HIGH || quality == EffectQuality.MEDIUM;
            float degrees = moving && quality.allowMotion() ? (frame % 300) * 1.2f : 18f;
            matrix.setRotate(degrees, width * .5f, height * .5f);
            shader.setLocalMatrix(matrix);
        }
    }

    private void scan(View view) {
        if (view == null) return;
        if (view != root && cardRole != 0 && Boolean.TRUE.equals(view.getTag(cardRole))) {
            cards.put(view, new CardShape(cornerRadii(view.getBackground())));
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) scan(group.getChildAt(i));
        }
        if (view == root && invalidateTarget != null) invalidateTarget.invalidate();
    }

    private float[] cornerRadii(Drawable drawable) {
        if (!(drawable instanceof GradientDrawable)) return new float[]{12f * density};
        GradientDrawable shape = (GradientDrawable) drawable;
        try {
            float[] radii = shape.getCornerRadii();
            if (radii != null && radii.length == 8) return radii.clone();
        } catch (NullPointerException ignored) {
            // Android 7 clones a null array for uniform-radius shapes.
        }
        float radius = shape.getCornerRadius();
        return new float[]{radius > 0f ? radius : 12f * density};
    }

    private void relativeOrigin(View child, View rootView, RectF out) {
        float x = 0f, y = 0f;
        View cursor = child;
        while (cursor != null && cursor != rootView) {
            x += cursor.getLeft() + cursor.getTranslationX();
            y += cursor.getTop() + cursor.getTranslationY();
            if (cursor.getParent() instanceof View) {
                View parent = (View) cursor.getParent();
                x -= parent.getScrollX();
                y -= parent.getScrollY();
                cursor = parent;
            } else {
                break;
            }
        }
        out.set(x, y, x + child.getWidth(), y + child.getHeight());
    }

    private static int opaque(int color) {
        return 0xFF000000 | (color & 0x00FFFFFF);
    }

    private static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | ((alpha & 0xFF) << 24);
    }

    @Override public void close() {
        if (root != null && root.getViewTreeObserver().isAlive()) {
            root.getViewTreeObserver().removeOnGlobalLayoutListener(layout);
        }
        cards.clear();
        invalidateTarget = null;
        if (shader != null) {
            stroke.setShader(null);
            shader = null;
        }
        root = null;
    }
}
