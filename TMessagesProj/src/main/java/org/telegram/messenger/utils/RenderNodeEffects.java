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
}
