package com.gathin.tvhome;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;
import android.view.animation.LinearInterpolator;

/**
 * A transparent full-screen overlay for the Up x10 easter egg: a mascot hops from tile to tile along the
 * given points, squashing a little on each landing, then jumps off the top of the screen. The view takes no focus,
 * clicks or accessibility, and allocates nothing per frame. The callback runs on the main thread when it is done.
 */
public final class MascotView extends View {

    private static final long HOP_MS = 850;
    private static final long END_MS = 1000;

    private final float density;
    private final Bitmap sprite;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final float size;
    private final android.graphics.RectF dst = new android.graphics.RectF();
    private float[] xs = new float[0], ys = new float[0];
    private long totalMs;
    private float t;   // elapsed ms
    private ValueAnimator animator;

    public MascotView(Context context) {
        super(context);
        density = context.getResources().getDisplayMetrics().density;
        sprite = BitmapFactory.decodeResource(context.getResources(), R.drawable.mascot);
        size = 130 * density;
        setFocusable(false);
        setClickable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    /** Hops over the points in order (view coordinates, tile centres), then leaves through the top. */
    public void play(float[] x, float[] y, Runnable done) {
        if (x.length == 0 || sprite == null) {
            done.run();
            return;
        }
        xs = x;
        ys = y;
        totalMs = HOP_MS * x.length + END_MS;
        setLayerType(LAYER_TYPE_HARDWARE, null);
        animator = ValueAnimator.ofFloat(0f, totalMs);
        animator.setDuration(totalMs);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(a -> {
            t = (float) a.getAnimatedValue();
            invalidate();
        });
        animator.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator a) {
                setLayerType(LAYER_TYPE_NONE, null);
                done.run();
            }
        });
        animator.start();
    }

    @Override
    protected void onDetachedFromWindow() {
        if (animator != null) animator.cancel();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas c) {
        if (xs.length == 0) return;
        int hop = (int) (t / HOP_MS);
        float x, y, sx = 1f, sy = 1f, rot;
        if (hop < xs.length) {
            float p = (t - hop * HOP_MS) / HOP_MS;   // 0..1 along this hop
            float fromX = hop == 0 ? xs[0] - 180 * density : xs[hop - 1];
            float fromY = hop == 0 ? -size : ys[hop - 1];
            x = fromX + (xs[hop] - fromX) * p;
            float arc = 4f * p * (1f - p) * (hop == 0 ? 70 : 120) * density;
            y = fromY + (ys[hop] - fromY) * p - arc;
            rot = (p - 0.5f) * 24f * (xs[hop] >= fromX ? 1 : -1);
            if (p > 0.88f) {   // landing squash
                float q = (p - 0.88f) / 0.12f;
                sy = 1f - 0.18f * q;
                sx = 1f + 0.14f * q;
            }
        } else {
            float p = (t - xs.length * HOP_MS) / END_MS;
            x = xs[xs.length - 1];
            y = ys[ys.length - 1] - p * p * (ys[ys.length - 1] + 2 * size);
            rot = p * 40f;
        }
        c.save();
        c.translate(x, y);
        c.rotate(rot);
        c.scale(sx, sy);
        float half = size / 2f;
        dst.set(-half, -size, half, 0);   // feet at the point
        c.drawBitmap(sprite, null, dst, paint);
        c.restore();
    }
}
