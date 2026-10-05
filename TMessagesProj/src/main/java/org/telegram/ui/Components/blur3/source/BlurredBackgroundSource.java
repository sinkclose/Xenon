package org.telegram.ui.Components.blur3.source;

import android.graphics.Canvas;

import org.telegram.ui.Components.blur3.drawable.BlurredBackgroundDrawable;

public interface BlurredBackgroundSource {
    BlurredBackgroundDrawable createDrawable();

    void draw(Canvas canvas, float left, float top, float right, float bottom);

    /** Update source effects even when a drawable reuses its recorded display list. */
    default void prepareToDraw() {}

    default void dispatchOnDrawablesRelativePositionChange() {}
}
