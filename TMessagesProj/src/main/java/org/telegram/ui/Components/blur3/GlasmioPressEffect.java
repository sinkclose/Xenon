package org.telegram.ui.Components.blur3;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.RuntimeShader;
import android.graphics.RuntimeXfermode;
import androidx.annotation.RequiresApi;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;

/** Backdrop-aware big/little flex glow from Glasmio's PressHighlightRenderer. */
@RequiresApi(33)
public final class GlasmioPressEffect {
    private final RuntimeShader mask;
    private final NativeBlender blender;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float appliedGain = Float.NaN;

    @RequiresApi(36)
    GlasmioPressEffect(String maskSource) {
        mask = new RuntimeShader(maskSource);
        blender = new NativeBlender();
        paint.setShader(mask);
        paint.setXfermode(blender.mode);
    }

    private static float gain() { return 1f / (1f - (Theme.isCurrentThemeDark() ? .75f : .4f)); }

    static void configure(RuntimeShader shader, RectF bounds, float x, float y, float progress, float dissipation) {
        float density = AndroidUtilities.density;
        float min = Math.min(bounds.width(), bounds.height()), max = Math.max(bounds.width(), bounds.height());
        boolean compact = max / density < 120f;
        float t = compact ? 0f : Math.max(0f, Math.min(1f, (min / density - 44f) / 116f));
        float radius = Math.min(min * .75f, 80f * density) * (1f + 3f * Math.max(0f, Math.min(1f, dissipation)));
        shader.setFloatUniform("pressCenter", Math.max(bounds.left, Math.min(bounds.right, x)), Math.max(bounds.top, Math.min(bounds.bottom, y)));
        shader.setFloatUniform("pressRadius", Math.max(.001f, radius), Math.max(.001f, radius));
        float alpha = Math.max(0f, Math.min(1f, progress));
        shader.setFloatUniform("pressOpacity", alpha * (1f - t), alpha * (compact ? 1f : .3f - .1f * t));
        shader.setFloatUniform("pressDodgeGain", gain());
        shader.setFloatUniform("pressBounds", bounds.left, bounds.top, bounds.right, bounds.bottom);
    }

    @RequiresApi(36)
    public void draw(Canvas canvas, RectF bounds, float x, float y, float progress, float dissipation) {
        if (progress <= 0f || !canvas.isHardwareAccelerated()) return;
        configure(mask, bounds, x, y, progress, dissipation);
        float gain = gain();
        if (appliedGain != gain) {
            blender.mode.setFloatUniform("glowGain", gain);
            appliedGain = gain;
        }
        canvas.drawRect(bounds, paint);
    }

    @RequiresApi(36)
    private static final class NativeBlender {
        final RuntimeXfermode mode = new RuntimeXfermode(AndroidUtilities.readRes(R.raw.glasmio_press_blender));
    }
}
