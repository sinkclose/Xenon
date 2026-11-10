package org.telegram.ui.Components.blur3.source;

import android.graphics.Canvas;

import org.telegram.ui.Components.blur3.drawable.BlurredBackgroundDrawable;

public interface BlurredBackgroundSource {
    BlurredBackgroundDrawable createDrawable();

    void draw(Canvas canvas, float left, float top, float right, float bottom);

    default void drawUnblurred(Canvas canvas, float left, float top, float right, float bottom) {
        draw(canvas, left, top, right, bottom);
    }

    /** Blend the sharp scene and its blur BEFORE the optical shader samples it. */
    default void drawWithBlurOpacity(Canvas canvas, float left, float top, float right, float bottom, float opacity) {
        opacity = Math.max(0f, Math.min(1f, opacity));
        if (opacity >= 1f) {
            draw(canvas, left, top, right, bottom);
            return;
        }
        drawUnblurred(canvas, left, top, right, bottom);
        if (opacity <= 0f) return;
        int save = canvas.saveLayerAlpha(left, top, right, bottom, Math.round(255 * opacity));
        draw(canvas, left, top, right, bottom);
        canvas.restoreToCount(save);
    }

    /** Update source effects even when a drawable reuses its recorded display list. */
    default void prepareToDraw() {}

    default void dispatchOnDrawablesRelativePositionChange() {}
}
