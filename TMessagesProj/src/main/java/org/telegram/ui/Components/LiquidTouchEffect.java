package org.telegram.ui.Components;

import android.graphics.Canvas;
import android.graphics.RectF;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.view.MotionEvent;
import android.view.View;

import androidx.dynamicanimation.animation.FloatValueHolder;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;

import org.telegram.messenger.AndroidUtilities;

/** Canvas-only port of AndroidLiquidGlass LiquidButton's interactive layer.
 * Observes gestures without consuming them or changing layout / View animation properties.
 * Use the same instance for a surface and its contents, in their parent's coordinates.
 */
public final class LiquidTouchEffect {
    private final RectF bounds = new RectF();
    private final View host;
    private View contentView;
    private float progress, offsetX, offsetY;
    private float downX, downY;
    private int pointerId = -1;
    private final SpringAnimation press;
    private final SpringAnimation returnX;
    private final SpringAnimation returnY;
    private float dragStrength = 1f;
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path glowClip = new Path();
    private final RectF glowBounds = new RectF();
    private RadialGradient glowShader;
    private float glowX, glowY, glowRadius;

    public LiquidTouchEffect(View host) {
        this.host = host;
        press = spring(value -> progress = value);
        returnX = spring(value -> offsetX = value);
        returnY = spring(value -> offsetY = value);
        glowPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.ADD));
    }

    /** Changes drag deformation without changing press enlargement. */
    public void setDragStrength(float strength) {
        dragStrength = Math.max(0f, strength);
    }

    public void setContentView(View view) {
        contentView = view;
    }

    private void invalidateHost() {
        host.invalidate();
        // A shared parent effect is rendered inside this child's display list.
        if (contentView != null) contentView.invalidate();
    }

    private interface Update { void set(float value); }

    private SpringAnimation spring(Update update) {
        SpringAnimation animation = new SpringAnimation(new FloatValueHolder());
        animation.setSpring(new SpringForce(0f).setDampingRatio(0.5f).setStiffness(300f));
        animation.setMinimumVisibleChange(0.001f);
        animation.addUpdateListener((a, value, velocity) -> {
            update.set(value);
            invalidateHost();
        });
        return animation;
    }

    public void setBounds(float left, float top, float right, float bottom) {
        bounds.set(left, top, right, bottom);
    }

    public void onTouchEvent(MotionEvent event, boolean enabled) {
        onTouchEvent(event, enabled, contains(event.getX(), event.getY()));
    }

    public boolean contains(float x, float y) {
        return bounds.contains(x, y);
    }

    /** A detached avatar can share the title's layer while having its own hit area. */
    public void onTouchEvent(MotionEvent event, boolean enabled, boolean hit) {
        if (!enabled || bounds.isEmpty()) {
            reset();
            return;
        }
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (!hit) {
                    return;
                }
                returnX.cancel();
                returnY.cancel();
                offsetX = offsetY = 0f;
                downX = event.getX();
                downY = event.getY();
                pointerId = event.getPointerId(0);
                press.animateToFinalPosition(1f);
                break;
            case MotionEvent.ACTION_MOVE:
                if (pointerId == -1) return;
                int index = event.findPointerIndex(pointerId);
                if (index < 0) {
                    release();
                    return;
                }
                // Bound stretching even during long text-selection / recording gestures.
                float limit = Math.max(bounds.width(), bounds.height()) * 2f;
                offsetX = Math.max(-limit, Math.min(limit, event.getX(index) - downX));
                offsetY = Math.max(-limit, Math.min(limit, event.getY(index) - downY));
                invalidateHost();
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                release();
                break;
            case MotionEvent.ACTION_POINTER_UP:
                if (event.getPointerId(event.getActionIndex()) == pointerId) release();
                break;
        }
    }

    private void release() {
        if (pointerId == -1) return;
        pointerId = -1;
        press.animateToFinalPosition(0f);
        returnX.setStartValue(offsetX).animateToFinalPosition(0f);
        returnY.setStartValue(offsetY).animateToFinalPosition(0f);
    }

    /** Returns a save count to restore, or -1 when the layer is at rest. */
    public int begin(Canvas canvas) {
        if (bounds.isEmpty() || (progress == 0f && offsetX == 0f && offsetY == 0f)) return -1;
        float width = bounds.width(), height = bounds.height();
        float min = Math.min(width, height), max = Math.max(width, height);
        float dragScale = Math.min(0.12f, AndroidUtilities.dpf2(4f) / height);
        float scale = 1f + dragScale * progress;
        dragScale *= dragStrength;
        double angle = Math.atan2(offsetY, offsetX);
        float scaleX = scale + dragScale * (float) Math.abs(Math.cos(angle) * offsetX / max) * Math.min(width / height, 1f);
        float scaleY = scale + dragScale * (float) Math.abs(Math.sin(angle) * offsetY / max) * Math.min(height / width, 1f);
        int save = canvas.save();
        canvas.translate(min * (float) Math.tanh(0.05f * dragStrength * offsetX / min),
                min * (float) Math.tanh(0.05f * dragStrength * offsetY / min));
        canvas.scale(scaleX, scaleY, bounds.centerX(), bounds.centerY());
        return save;
    }

    /** Apply a parent-coordinate effect while drawing inside one of its children. */
    public int beginInChild(Canvas canvas, float childX, float childY) {
        int save = canvas.save();
        canvas.translate(-childX, -childY);
        if (begin(canvas) == -1) {
            canvas.restoreToCount(save);
            return -1;
        }
        canvas.translate(childX, childY);
        return save;
    }

    /** Transform a child's complete render node, including its clipping bounds. */
    public int beginInParent(Canvas canvas, float childX, float childY) {
        return beginInChild(canvas, -childX, -childY);
    }

    /** Additive 8% surface light and 15% touch halo, matching InteractiveHighlight. */
    public void drawHighlight(Canvas canvas, float cornerRadius) {
        drawHighlight(canvas, cornerRadius, 0f);
    }

    public void drawHighlight(Canvas canvas, float cornerRadius, float inset) {
        if (bounds.isEmpty() || progress <= 0f) return;
        glowBounds.set(bounds.left + inset, bounds.top + inset, bounds.right - inset, bounds.bottom - inset);
        if (glowBounds.isEmpty()) return;
        float p = Math.min(1f, progress);
        glowClip.rewind();
        glowClip.addRoundRect(glowBounds, cornerRadius, cornerRadius, Path.Direction.CW);
        int save = canvas.save();
        canvas.clipPath(glowClip);
        glowPaint.setShader(null);
        glowPaint.setColor(0xffffffff);
        glowPaint.setAlpha(Math.round(255f * 0.08f * p));
        canvas.drawRect(glowBounds, glowPaint);
        float x = Math.max(glowBounds.left, Math.min(glowBounds.right, downX + offsetX));
        float y = Math.max(glowBounds.top, Math.min(glowBounds.bottom, downY + offsetY));
        float radius = Math.min(glowBounds.width(), glowBounds.height()) * 1.5f;
        if (glowShader == null || glowX != x || glowY != y || glowRadius != radius) {
            // Samples of the reference smoothstep(radius, radius * .5, distance).
            glowShader = new RadialGradient(x, y, radius,
                    new int[]{0xffffffff, 0xffffffff, 0xd7ffffff, 0x80ffffff, 0x28ffffff, 0x00ffffff},
                    new float[]{0f, .5f, .625f, .75f, .875f, 1f}, Shader.TileMode.CLAMP);
            glowX = x;
            glowY = y;
            glowRadius = radius;
        }
        glowPaint.setShader(glowShader);
        glowPaint.setAlpha(Math.round(255f * 0.15f * p));
        canvas.drawRect(glowBounds, glowPaint);
        canvas.restoreToCount(save);
    }

    public void reset() {
        press.cancel();
        returnX.cancel();
        returnY.cancel();
        pointerId = -1;
        progress = offsetX = offsetY = 0f;
        press.setStartValue(0f);
        invalidateHost();
    }
}
