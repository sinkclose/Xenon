package zxc.iconic.xenon.helpers;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

import org.telegram.messenger.AndroidUtilities;

import androidx.core.graphics.ColorUtils;

import zxc.iconic.xenon.NekoConfig;

public class Md3FilterTabsHelper {

    private static final Paint backgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Path path = new Path();
    private static final RectF rectF = new RectF();
    private static final float[] radii = new float[8];

    public static boolean isEnabled() {
        return NekoConfig.md3Folders;
    }

    public static int getContrastingColor(int background) {
        // Compare actual contrast, rather than a luminance threshold of 0.5
        // (which incorrectly chooses white for many medium-bright accents).
        int opaque = ColorUtils.setAlphaComponent(background, 255);
        return ColorUtils.calculateContrast(Color.WHITE, opaque)
                >= ColorUtils.calculateContrast(Color.BLACK, opaque) ? Color.WHITE : Color.BLACK;
    }

    public static int getContainerColor(boolean checked, int activeColor, int inactiveColor, int backgroundColor) {
        int surface = ColorUtils.setAlphaComponent(backgroundColor, 255);
        return checked ? ColorUtils.compositeColors(activeColor, surface)
                : ColorUtils.blendARGB(ColorUtils.compositeColors(inactiveColor, surface), surface, 0.78f);
    }

    public static int getContentColor(boolean checked, int activeColor, int inactiveColor, int backgroundColor) {
        int container = getContainerColor(checked, activeColor, inactiveColor, backgroundColor);
        int preferred = checked ? getContrastingColor(container)
                : ColorUtils.compositeColors(inactiveColor, container);
        return ColorUtils.calculateContrast(preferred, container) >= 4.5
                ? preferred : getContrastingColor(container);
    }

    public static void drawTabBackground(Canvas canvas, int width, int height, int position,
            int tabCount, float innerRadius, int containerColor, Drawable ripple) {
        if (!isEnabled() || width <= 0 || height <= 0) {
            return;
        }
        // 1dp inset on each side gives ConnectedButtonGroupSmallTokens' 2dp gap.
        final float inset = AndroidUtilities.dp(1);
        final float contentWidth = width - 2 * inset;
        final float contentHeight = height - 2 * inset;
        if (contentWidth <= 0f || contentHeight <= 0f) {
            return;
        }
        // Bound spring overshoot and narrow text-only tabs to the actual rect.
        final float pillR = Math.min(contentWidth, contentHeight) / 2f;
        final float inner = Math.max(0f, Math.min(innerRadius, pillR));
        final float left = position == 0 ? pillR : inner;
        final float right = position == tabCount - 1 ? pillR : inner;
        radii[0] = radii[1] = radii[6] = radii[7] = left;
        radii[2] = radii[3] = radii[4] = radii[5] = right;
        rectF.set(inset, inset, width - inset, height - inset);
        backgroundPaint.setColor(containerColor);
        backgroundPaint.setAlpha(255);
        path.rewind();
        path.addRoundRect(rectF, radii, Path.Direction.CW);
        canvas.drawPath(path, backgroundPaint);
        if (ripple != null) {
            canvas.save();
            canvas.clipPath(path);
            ripple.draw(canvas);
            canvas.restore();
        }
    }
}
