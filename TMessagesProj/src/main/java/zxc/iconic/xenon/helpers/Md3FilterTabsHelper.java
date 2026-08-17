package zxc.iconic.xenon.helpers;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

import org.telegram.messenger.AndroidUtilities;

import androidx.core.graphics.ColorUtils;

import zxc.iconic.xenon.NekoConfig;

public class Md3FilterTabsHelper {

    private static final Paint backgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Path path = new Path();
    private static final RectF rectF = new RectF();
    private static final float[] radii = new float[8];

    static {
        backgroundPaint.setStyle(Paint.Style.FILL);
    }

    public static boolean isEnabled() {
        return NekoConfig.md3Folders;
    }

    public static void drawTabBackground(Canvas canvas, int width, int height, int position, int tabCount, float selectionProgress, int activeColor, int unactiveColor, int backgroundColor) {
        if (!isEnabled() || width <= 0 || height <= 0) {
            return;
        }

        float inner = AndroidUtilities.dp(7);
        float inset = AndroidUtilities.dp(1);
        float pillR = (height - 2 * inset) / 2f;

        boolean first = position == 0;
        boolean last = position == tabCount - 1;
        float tl = first ? pillR : inner;
        float tr = last ? pillR : inner;
        float br = last ? pillR : inner;
        float bl = first ? pillR : inner;

        float radiusProgress = spring(selectionProgress);
        tl += (pillR - tl) * radiusProgress;
        tr += (pillR - tr) * radiusProgress;
        br += (pillR - br) * radiusProgress;
        bl += (pillR - bl) * radiusProgress;
        radii[0] = tl;
        radii[1] = tl;
        radii[2] = tr;
        radii[3] = tr;
        radii[4] = br;
        radii[5] = br;
        radii[6] = bl;
        radii[7] = bl;

        rectF.set(inset, inset, width - inset, height - inset);
        int unselectedColor = ColorUtils.blendARGB(unactiveColor, backgroundColor, 0.78f);
        backgroundPaint.setColor(selectionProgress >= 0.5f ? activeColor : unselectedColor);
        backgroundPaint.setAlpha(255);

        path.rewind();
        path.addRoundRect(rectF, radii, Path.Direction.CW);
        canvas.drawPath(path, backgroundPaint);
    }

    private static float spring(float t) {
        float c1 = 1.70158f;
        float c3 = c1 + 1;
        t -= 1;
        return 1 + c3 * t * t * t + c1 * t * t;
    }
}
