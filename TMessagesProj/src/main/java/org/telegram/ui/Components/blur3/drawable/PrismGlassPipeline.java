package org.telegram.ui.Components.blur3.drawable;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RenderNode;

import androidx.annotation.RequiresApi;

import org.telegram.messenger.LiteMode;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.blur3.LiquidGlassEffect;
import org.telegram.ui.Components.blur3.source.BlurredBackgroundSource;

import java.util.Arrays;

import zxc.iconic.xenon.NekoConfig;

/** Retained frame assembly using the existing source, refraction and glare contracts. */
@RequiresApi(33)
final class PrismGlassPipeline {
    private final BlurredBackgroundSource source;
    private final RenderNode background;
    private final RenderNode surface;
    private boolean sourceInvalidated = true;
    private boolean initialized;
    private int left, top, right, bottom;
    private float offsetX, offsetY, scaleX, scaleY, translationX, translationY;
    private int backgroundColor, strokeColorTop, strokeColorBottom, tintPercent;
    private float strokeWidthTop, strokeWidthBottom;
    private final float[] radii = new float[8];
    private boolean advanced, refractionEnabled, hasEffect;

    PrismGlassPipeline(BlurredBackgroundSource source, RenderNode background, RenderNode surface) {
        this.source = source;
        this.background = background;
        this.surface = surface;
    }

    void invalidateSource() {
        sourceInvalidated = true;
    }

    void invalidateAll() {
        initialized = false;
        sourceInvalidated = true;
    }

    void update(BlurredBackgroundDrawableRenderNode drawable, LiquidGlassEffect effect,
                boolean fixedRefraction, Paint strokeTop, Paint strokeBottom) {
        final BlurredBackgroundDrawable.Props props = drawable.boundProps;
        final Rect bounds = props.boundsWithPadding;
        final int width = bounds.width(), height = bounds.height();
        final boolean advanced = NekoConfig.useAdvancedLiquidGlass;
        final boolean refractionEnabled = LiteMode.isEnabled(LiteMode.FLAG_LIQUID_GLASS);
        final boolean hasEffect = effect != null && effect.hasRefractionShader();

        final boolean mappingChanged = !initialized || left != bounds.left || top != bounds.top
                || right != bounds.right || bottom != bounds.bottom
                || offsetX != drawable.sourceOffsetX || offsetY != drawable.sourceOffsetY
                || scaleX != drawable.sourceScaleX || scaleY != drawable.sourceScaleY
                || translationX != drawable.sourceTranslationX || translationY != drawable.sourceTranslationY;
        final boolean surfaceChanged = !initialized || !surface.hasDisplayList()
                || right - left != width || bottom - top != height
                || backgroundColor != drawable.backgroundColor
                || strokeColorTop != drawable.strokeColorTop || strokeColorBottom != drawable.strokeColorBottom
                || strokeWidthTop != props.strokeWidthTop || strokeWidthBottom != props.strokeWidthBottom
                || !Arrays.equals(radii, props.radii) || this.advanced != advanced
                || this.refractionEnabled != refractionEnabled || this.hasEffect != hasEffect
                || tintPercent != NekoConfig.advancedGlassTintPercent;

        // Updating RenderEffect uniforms is independent of recording draw commands.
        // The effect selects the optimized Prism program with the same optical
        // uniforms; highlight rendering and material controls remain shared.
        if (effect != null) {
            effect.update(0, 0, width, height,
                    props.shaderRadii[0], props.shaderRadii[2], props.shaderRadii[4], props.shaderRadii[6],
                    props.liquidThickness <= 0 ? dp(NekoConfig.liquidGlassThickness) : props.liquidThickness,
                    props.liquidIntensity <= 0 ? NekoConfig.liquidGlassIntensity : props.liquidIntensity,
                    props.liquidIndex, drawable.backgroundColor);
        }

        if (sourceInvalidated || mappingChanged || !background.hasDisplayList()) {
            Canvas canvas = background.beginRecording();
            try {
                canvas.save();
                canvas.translate(-bounds.left, -bounds.top);
                drawable.transformSourceCanvas(canvas);
                source.draw(canvas,
                        drawable.sourceScaleX * bounds.left + drawable.sourceOffsetX + drawable.sourceTranslationX,
                        drawable.sourceScaleY * bounds.top + drawable.sourceOffsetY + drawable.sourceTranslationY,
                        drawable.sourceScaleX * bounds.right + drawable.sourceOffsetX + drawable.sourceTranslationX,
                        drawable.sourceScaleY * bounds.bottom + drawable.sourceOffsetY + drawable.sourceTranslationY);
                canvas.restore();
            } finally {
                background.endRecording();
            }
        }

        if (surfaceChanged) {
            Canvas canvas = surface.beginRecording();
            try {
                if (Color.alpha(drawable.backgroundColor) == 255) {
                    canvas.drawColor(drawable.backgroundColor);
                } else {
                    canvas.drawRenderNode(background);
                    if (advanced) {
                        final int percent = NekoConfig.advancedGlassTintPercent;
                        if (percent > 0 && Color.alpha(drawable.backgroundColor) != 0) {
                            canvas.drawColor(Theme.multAlpha(drawable.backgroundColor,
                                    Math.min(100, Math.max(0, percent)) / 100f * 0.60f));
                        }
                    } else if (!hasEffect || !refractionEnabled) {
                        if (Color.alpha(drawable.backgroundColor) != 0) canvas.drawColor(drawable.backgroundColor);
                    }
                }
                if (drawable.strokeColorTop != 0) {
                    BlurredBackgroundDrawable.drawStroke(canvas, 0, 0, width, height, props.radii,
                            props.strokeWidthTop, true, strokeTop);
                }
                if (drawable.strokeColorBottom != 0) {
                    BlurredBackgroundDrawable.drawStroke(canvas, 0, 0, width, height, props.radii,
                            props.strokeWidthBottom, false, strokeBottom);
                }
            } finally {
                surface.endRecording();
            }
        }

        // Alpha is a RenderNode property; it never invalidates either display list.
        final float glassAlpha = !fixedRefraction && advanced
                ? Math.max(0f, Math.min(1f, NekoConfig.advancedGlassAlpha / 100f)) : 1f;
        final float alpha = drawable.getAlpha() / 255f * glassAlpha;
        if (surface.getAlpha() != alpha) surface.setAlpha(alpha);

        left = bounds.left; top = bounds.top; right = bounds.right; bottom = bounds.bottom;
        offsetX = drawable.sourceOffsetX; offsetY = drawable.sourceOffsetY;
        scaleX = drawable.sourceScaleX; scaleY = drawable.sourceScaleY;
        translationX = drawable.sourceTranslationX; translationY = drawable.sourceTranslationY;
        backgroundColor = drawable.backgroundColor;
        strokeColorTop = drawable.strokeColorTop; strokeColorBottom = drawable.strokeColorBottom;
        strokeWidthTop = props.strokeWidthTop; strokeWidthBottom = props.strokeWidthBottom;
        System.arraycopy(props.radii, 0, radii, 0, radii.length);
        this.advanced = advanced; this.refractionEnabled = refractionEnabled; this.hasEffect = hasEffect;
        tintPercent = NekoConfig.advancedGlassTintPercent;
        sourceInvalidated = false;
        initialized = true;
    }
}
