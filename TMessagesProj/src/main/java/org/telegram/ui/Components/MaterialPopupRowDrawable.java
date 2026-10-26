package org.telegram.ui.Components;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

import androidx.dynamicanimation.animation.FloatValueHolder;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;

import org.telegram.messenger.AndroidUtilities;

/** List-items geometry, with the same FastSpatial press spring as Material folders. */
public final class MaterialPopupRowDrawable extends Drawable {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rect = new RectF();
    private final float[] radii = new float[8];
    private final SpringAnimation press;
    private boolean top, bottom, pressed;
    private float progress;

    public MaterialPopupRowDrawable(int color, boolean top, boolean bottom) {
        paint.setColor(color);
        this.top = top;
        this.bottom = bottom;
        press = new SpringAnimation(new FloatValueHolder());
        press.setSpring(new SpringForce(0).setDampingRatio(.6f).setStiffness(800f));
        press.setMinimumVisibleChange(.001f);
        press.addUpdateListener((a, value, velocity) -> { progress = value; invalidateSelf(); });
    }

    public void setCorners(boolean top, boolean bottom) {
        if (this.top == top && this.bottom == bottom) return;
        this.top = top;
        this.bottom = bottom;
        invalidateSelf();
    }

    @Override public boolean isStateful() { return true; }

    @Override protected boolean onStateChange(int[] state) {
        boolean down = false;
        for (int value : state) if (value == android.R.attr.state_pressed) down = true;
        if (pressed == down) return false;
        pressed = down;
        press.animateToFinalPosition(down ? 1 : 0);
        return true;
    }

    @Override public boolean setVisible(boolean visible, boolean restart) {
        if (!visible) jumpToCurrentState();
        return super.setVisible(visible, restart);
    }

    @Override public void jumpToCurrentState() {
        press.cancel();
        progress = pressed ? 1 : 0;
        press.setStartValue(progress);
        invalidateSelf();
    }

    @Override public void draw(Canvas canvas) {
        rect.set(getBounds());
        rect.inset(AndroidUtilities.dpf2(1), AndroidUtilities.dpf2(1));
        float t = Math.max(0, Math.min(1, progress));
        float topRadius = radius(top ? 20 : 8, t);
        float bottomRadius = radius(bottom ? 20 : 8, t);
        radii[0] = radii[1] = radii[2] = radii[3] = topRadius;
        radii[4] = radii[5] = radii[6] = radii[7] = bottomRadius;
        path.rewind();
        path.addRoundRect(rect, radii, Path.Direction.CW);
        canvas.drawPath(path, paint);
    }

    private static float radius(float normal, float progress) {
        return AndroidUtilities.dpf2(normal >= 20 ? normal : normal + (2 - normal) * progress);
    }

    @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); invalidateSelf(); }
    @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); invalidateSelf(); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
