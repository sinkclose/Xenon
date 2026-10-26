package org.telegram.ui.Components;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

import androidx.dynamicanimation.animation.FloatValueHolder;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.Theme;

/** A crisp moving selection, composited after the frosted background and rows. */
final class IosPopupFeedback {
    private final View root;
    private final Runnable invalidate;
    private final AnimatedFloat visibility;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Rect row = new Rect();
    private final RectF pill = new RectF();
    private final SpringAnimation[] motion = new SpringAnimation[4];
    private final float[] position = new float[4];
    private boolean active, hasRow;

    IosPopupFeedback(View root, Runnable invalidate) {
        this.root = root;
        this.invalidate = invalidate;
        visibility = new AnimatedFloat(invalidate, 160, CubicBezierInterpolator.EASE_OUT_QUINT);
        visibility.set(0, true);
        for (int i = 0; i < motion.length; i++) {
            final int index = i;
            motion[i] = new SpringAnimation(new FloatValueHolder());
            motion[i].setSpring(new SpringForce(0).setDampingRatio(1f).setStiffness(550f));
            motion[i].setMinimumVisibleChange(.1f);
            motion[i].addUpdateListener((a, value, velocity) -> { position[index] = value; invalidate.run(); });
        }
    }

    void touch(MotionEvent event, float x, float y, boolean start) {
        if (start) {
            // New taps start at their own row; only an ongoing drag slides from the previous row.
            if (!active) {
                hasRow = false;
                for (SpringAnimation animation : motion) animation.cancel();
            }
            active = true;
        }
        if (event.getActionMasked() == MotionEvent.ACTION_UP || event.getActionMasked() == MotionEvent.ACTION_CANCEL
                || event.getActionMasked() == MotionEvent.ACTION_POINTER_DOWN) active = false;
        invalidate.run();
    }

    void select(View selected) {
        if (selected == null) return; // crossing a gap never crossfades two row drawables
        selected.getDrawingRect(row);
        if (root instanceof ViewGroup && selected != root) ((ViewGroup) root).offsetDescendantRectToMyCoords(selected, row);
        float[] next = {row.exactCenterX(), row.exactCenterY(), row.width(), row.height()};
        for (int i = 0; i < motion.length; i++) {
            if (!hasRow) {
                motion[i].cancel();
                motion[i].setStartValue(next[i]);
                position[i] = next[i];
            } else {
                // Retargeting a spring preserves its current velocity.
                motion[i].animateToFinalPosition(next[i]);
            }
        }
        hasRow = true;
        invalidate.run();
    }

    void draw(Canvas canvas, float centerX, float centerY, float scaleX, float scaleY) {
        int save = canvas.save();
        canvas.scale(scaleX, scaleY);
        canvas.translate(-centerX, -centerY);
        float opacity = visibility.set(active && hasRow);
        if (hasRow && opacity > 0) {
            paint.setColor(Theme.getColor(Theme.key_actionBarDefaultSubmenuItem));
            pill.set(position[0] - position[2] / 2, position[1] - position[3] / 2,
                    position[0] + position[2] / 2, position[1] + position[3] / 2);
            pill.inset(AndroidUtilities.dpf2(5), AndroidUtilities.dpf2(3));
            paint.setAlpha(Math.round(opacity * 36));
            canvas.drawRoundRect(pill, pill.height() / 2, pill.height() / 2, paint);
        }
        canvas.restoreToCount(save);
    }

    void release() {
        active = hasRow = false;
        for (SpringAnimation animation : motion) animation.cancel();
        visibility.set(0, true);
    }
}
