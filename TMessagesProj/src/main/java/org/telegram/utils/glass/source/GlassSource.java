package org.telegram.utils.glass.source;

import android.graphics.Canvas;

import androidx.annotation.NonNull;

public interface GlassSource {
    void draw(@NonNull Canvas canvas, float left, float top, float right, float bottom);

    default void dispatchOnDrawablesRelativePositionChange() {}

    default void prepareToDraw() {}

    default long retainedDrawCommandsKey() {
        return -1;
    }

    default void drawUnblurred(@NonNull Canvas canvas, float left, float top, float right, float bottom) {
        draw(canvas, left, top, right, bottom);
    }

    /** Blend the sharp scene and its blur BEFORE the optical shader samples it. */
    default void drawWithBlurOpacity(@NonNull Canvas canvas, float left, float top, float right, float bottom, float opacity) {
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

    default long getGenerationId() {
        return 0;
    }
}
