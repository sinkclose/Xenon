package org.telegram.ui.Components;

import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.view.View;

/**
 * Drives a self-animating drawable's redraw loop with a capped frame rate.
 *
 * <p>Self-animating drawables keep themselves scheduled by invalidating from
 * {@link Drawable#draw(android.graphics.Canvas)}; doing that unconditionally makes every
 * host view redraw at the display refresh rate. This helper forwards invalidation at most
 * once per frame interval and, when a frame is skipped, schedules exactly one delayed
 * invalidation so the animation continues at the capped rate instead of stalling.
 */
public final class ThrottledDrawableInvalidator {

    public static final long FRAME_INTERVAL_30_FPS = 1000 / 30;

    private final Drawable drawable;
    private final long frameIntervalMs;
    private long lastInvalidateTime;
    private boolean pending;

    private final Runnable invalidateRunnable = new Runnable() {
        @Override
        public void run() {
            pending = false;
            drawable.invalidateSelf();
        }
    };

    public ThrottledDrawableInvalidator(Drawable drawable, long frameIntervalMs) {
        this.drawable = drawable;
        this.frameIntervalMs = frameIntervalMs;
    }

    /** Call from {@link Drawable#draw(android.graphics.Canvas)} to keep the animation scheduled. */
    public void onDrawn() {
        if (!(drawable.getCallback() instanceof View)) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        long elapsed = now - lastInvalidateTime;
        if (elapsed >= frameIntervalMs) {
            lastInvalidateTime = now;
            drawable.invalidateSelf();
        } else if (!pending) {
            pending = true;
            ((View) drawable.getCallback()).postOnAnimationDelayed(invalidateRunnable, frameIntervalMs - elapsed);
        }
    }
}
