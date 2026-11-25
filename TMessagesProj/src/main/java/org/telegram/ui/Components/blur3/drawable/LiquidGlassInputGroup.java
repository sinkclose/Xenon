package org.telegram.ui.Components.blur3.drawable;

import android.graphics.Canvas;
import android.graphics.RectF;

import org.telegram.ui.Components.LiquidTouchEffect;

/**
 * Group of glass shapes drawn by the glass engine. The merged "union" shader of the previous
 * pipeline is not available in the new glass architecture, so members are drawn individually.
 */
public final class LiquidGlassInputGroup {
    private final int shapeCount;

    public static LiquidGlassInputGroup acquirePrepared(int shapeCount, Runnable onReady) {
        return new LiquidGlassInputGroup(shapeCount);
    }

    private LiquidGlassInputGroup(int shapeCount) {
        this.shapeCount = shapeCount;
    }

    public void setMergeDistance(float distance) {}

    public void setPairMergeDistance(int a, int b, float distance) {}

    public void release() {}

    public void draw(Canvas canvas, BlurredBackgroundDrawable[] members, RectF[] bounds, float[] alpha,
                     LiquidTouchEffect[] effects) {
        draw(canvas, members, bounds, alpha, effects, null, null, true);
    }

    public void draw(Canvas canvas, BlurredBackgroundDrawable[] members, RectF[] bounds, float[] alpha,
                     LiquidTouchEffect[] effects, float[] origins) {
        draw(canvas, members, bounds, alpha, effects, origins, origins, true);
    }

    public void draw(Canvas canvas, BlurredBackgroundDrawable[] members, RectF[] bounds, float[] alpha,
                     LiquidTouchEffect[] effects, float[] origins, float[] sourceOrigins) {
        draw(canvas, members, bounds, alpha, effects, origins, sourceOrigins, true);
    }

    public void draw(Canvas canvas, BlurredBackgroundDrawable[] members, RectF[] bounds, float[] alpha,
                     LiquidTouchEffect[] effects, float[] origins, float[] sourceOrigins, boolean shrinkWithVisibility) {
        for (int i = 0; i < members.length && i < shapeCount; i++) {
            final BlurredBackgroundDrawable member = members[i];
            if (member == null) continue;
            final float a = alpha != null && i < alpha.length ? alpha[i] : 1f;
            if (a <= 0f) continue;
            if (bounds != null && i < bounds.length && bounds[i] != null) {
                member.setBounds(Math.round(bounds[i].left), Math.round(bounds[i].top), Math.round(bounds[i].right), Math.round(bounds[i].bottom));
            }
            member.setAlpha(Math.round(255 * Math.min(1f, a)));
            member.draw(canvas);
        }
    }
}
