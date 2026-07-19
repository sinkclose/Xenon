package org.telegram.messenger.utils;

import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.RenderEffect;
import android.os.Build;

import androidx.annotation.RequiresApi;

@RequiresApi(api = Build.VERSION_CODES.S)
public class RenderNodeEffects {
    private RenderNodeEffects() {}

    private static RenderEffect saturationUpX2Effect;
    private static RenderEffect saturationUpX1_25Effect;
    private static RenderEffect saturationUpX1_35Effect;
    private static RenderEffect saturationUpX1_5Effect;
    private static RenderEffect saturationUpX3Effect;
    private static RenderEffect saturationUpX4Effect;

    public static RenderEffect getSaturationX2RenderEffect() {
        if (saturationUpX2Effect == null) {
            final ColorMatrix colorMatrix = new ColorMatrix();
            colorMatrix.setSaturation(2f);
            saturationUpX2Effect = RenderEffect.createColorFilterEffect(new ColorMatrixColorFilter(colorMatrix));
        }
        return saturationUpX2Effect;
    }

    public static RenderEffect getSaturationX1_25RenderEffect() {
        if (saturationUpX1_25Effect == null) {
            final ColorMatrix colorMatrix = new ColorMatrix();
            colorMatrix.setSaturation(1.25f);
            saturationUpX1_25Effect = RenderEffect.createColorFilterEffect(new ColorMatrixColorFilter(colorMatrix));
        }
        return saturationUpX1_25Effect;
    }

    public static RenderEffect getSaturationX1_35RenderEffect() {
        if (saturationUpX1_35Effect == null) {
            final ColorMatrix colorMatrix = new ColorMatrix();
            colorMatrix.setSaturation(1.35f);
            saturationUpX1_35Effect = RenderEffect.createColorFilterEffect(new ColorMatrixColorFilter(colorMatrix));
        }
        return saturationUpX1_35Effect;
    }

    // Match library's vibrancy() = saturation 1.5x
    public static RenderEffect getSaturationX1_5RenderEffect() {
        if (saturationUpX1_5Effect == null) {
            final ColorMatrix colorMatrix = new ColorMatrix();
            colorMatrix.setSaturation(1.5f);
            saturationUpX1_5Effect = RenderEffect.createColorFilterEffect(new ColorMatrixColorFilter(colorMatrix));
        }
        return saturationUpX1_5Effect;
    }

    public static RenderEffect getSaturationX3RenderEffect() {
        if (saturationUpX3Effect == null) {
            final ColorMatrix colorMatrix = new ColorMatrix();
            colorMatrix.setSaturation(3f);
            saturationUpX3Effect = RenderEffect.createColorFilterEffect(new ColorMatrixColorFilter(colorMatrix));
        }

        return saturationUpX3Effect;
    }

    public static RenderEffect getSaturationX4RenderEffect() {
        if (saturationUpX4Effect == null) {
            final ColorMatrix colorMatrix = new ColorMatrix();
            colorMatrix.setSaturation(4f);
            saturationUpX4Effect = RenderEffect.createColorFilterEffect(new ColorMatrixColorFilter(colorMatrix));
        }

        return saturationUpX4Effect;
    }

    public static RenderEffect createSaturationXRenderEffect(float x) {
        final ColorMatrix colorMatrix = new ColorMatrix();
        colorMatrix.setSaturation(x);
        return RenderEffect.createColorFilterEffect(new ColorMatrixColorFilter(colorMatrix));
    }
}
