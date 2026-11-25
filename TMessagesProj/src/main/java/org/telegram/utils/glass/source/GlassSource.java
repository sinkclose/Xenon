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

    default void drawWithBlurOpacity(@NonNull Canvas canvas, float left, float top, float right, float bottom, float opacity) {
        draw(canvas, left, top, right, bottom);
    }

    default long getGenerationId() {
        return 0;
    }
}
