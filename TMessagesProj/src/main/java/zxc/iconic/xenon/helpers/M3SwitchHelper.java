package zxc.iconic.xenon.helpers;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.Theme;

public class M3SwitchHelper {

    private static final float TRACK_W = 52f;
    private static final float TRACK_H = 32f;
    private static final float TARGET_TRACK_W = 36f;
    private static final float FULL_TRACK_W = TRACK_W;
    private static final float RADIUS = 16f;
    private static final float THUMB_R_OFF = 8f;
    private static final float THUMB_R_ON = 12f;
    private static final float THUMB_CX_OFF = 16f;
    private static final float THUMB_CX_ON = 36f;
    private static final float ICON_SCALE = 0.7f;
    private static final float ICON_NUDGE_UP = 0.5f;
    private static final float CHECK_SCALE = 0.8f;
    private static final float CHECK_NUDGE_RIGHT = 0.5f;
    private static final float ICON_STROKE = 2f;
    private static final float CROSS_STROKE = 1.5f;

    private static final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Paint thumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Paint iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final RectF rectF = new RectF();
    private static final PorterDuffColorFilter whiteFilter = new PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN);

    static {
        strokePaint.setStyle(Paint.Style.STROKE);
        iconPaint.setStyle(Paint.Style.STROKE);
        iconPaint.setStrokeCap(Paint.Cap.ROUND);
        iconPaint.setStrokeWidth(AndroidUtilities.dpf2(ICON_STROKE));
    }

    public static void draw(
            int measuredWidth, int measuredHeight, float progress, boolean isChecked,
            int drawIconType, float iconProgress, Drawable iconDrawable, float iconVisibility,
            int trackColorKey, int trackCheckedColorKey, int thumbCheckedColorKey,
            Theme.ResourcesProvider resourcesProvider, Canvas canvas) {

        int offColor = Theme.getColor(trackColorKey, resourcesProvider);
        int onColor = Theme.getColor(trackCheckedColorKey, resourcesProvider);
        int thumbOnColor = Theme.getColor(thumbCheckedColorKey, resourcesProvider);
        boolean monetSwitch = Theme.getActiveTheme().isMonet() && Theme.isCurrentThemeDark()
                && (trackCheckedColorKey == Theme.key_switchTrackChecked
                || trackCheckedColorKey == Theme.key_switch2TrackChecked
                || trackCheckedColorKey == Theme.key_switchTrackBlueChecked);
        if (monetSwitch) {
            thumbOnColor = Theme.getColor(Theme.key_switchTrackBlueThumbChecked, resourcesProvider);
        }

        float frameWidthDp = measuredWidth / AndroidUtilities.density;
        float targetW = Math.max(TARGET_TRACK_W, Math.min(frameWidthDp - 1f, FULL_TRACK_W));
        float scale = AndroidUtilities.dpf2(targetW) / TRACK_W;
        float trackW = TRACK_W * scale;
        float h = TRACK_H * scale;
        float left = (measuredWidth - trackW) / 2f;
        float top = (measuredHeight - h) / 2f;

        rectF.set(left, top, left + trackW, top + h);
        float radius = RADIUS * scale;
        if (isChecked) {
            fillPaint.setColor(onColor);
            canvas.drawRoundRect(rectF, radius, radius, fillPaint);
        } else {
            if (monetSwitch) {
                fillPaint.setColor(Theme.getColor(Theme.key_switchTrackBlueThumb, resourcesProvider));
                canvas.drawRoundRect(rectF, radius, radius, fillPaint);
            }
            float sw = AndroidUtilities.dpf2(2f);
            strokePaint.setColor(offColor);
            strokePaint.setStrokeWidth(sw);
            rectF.inset(sw / 2f, sw / 2f);
            canvas.drawRoundRect(rectF, radius - sw / 2f, radius - sw / 2f, strokePaint);
        }

        float cx = left + scale * (THUMB_CX_OFF + (THUMB_CX_ON - THUMB_CX_OFF) * progress);
        float cy = top + h / 2f;
        float rest = scale * (THUMB_R_OFF + (THUMB_R_ON - THUMB_R_OFF) * progress);
        thumbPaint.setColor(isChecked ? thumbOnColor : offColor);
        canvas.drawCircle(cx, cy, rest, thumbPaint);

        int iconColor = isChecked ? onColor : monetSwitch
                ? Theme.getColor(Theme.key_switchTrackBlueThumb, resourcesProvider) : Color.WHITE;
        if (iconDrawable != null) {
            if (monetSwitch) {
                iconDrawable.setColorFilter(new PorterDuffColorFilter(iconColor, PorterDuff.Mode.SRC_IN));
            } else if (iconDrawable.getColorFilter() != whiteFilter) {
                iconDrawable.setColorFilter(whiteFilter);
            }
            if (iconVisibility > 0f) {
                boolean needScale = iconVisibility < 1f;
                if (needScale) {
                    canvas.save();
                    canvas.scale(iconVisibility, iconVisibility, cx, cy);
                }
                int ix = Math.round(cx);
                int iy = Math.round(cy - AndroidUtilities.dpf2(ICON_NUDGE_UP));
                int hw = Math.round(iconDrawable.getIntrinsicWidth() * ICON_SCALE / 2f);
                int hh = Math.round(iconDrawable.getIntrinsicHeight() * ICON_SCALE / 2f);
                iconDrawable.setBounds(ix - hw, iy - hh, ix + hw, iy + hh);
                iconDrawable.draw(canvas);
                if (needScale) canvas.restore();
            }
        } else if (drawIconType == 1) {
            drawCheckmark(canvas, Math.round(cx), Math.round(cy), isChecked, progress, iconColor);
        } else if (drawIconType == 2) {
            drawDot(canvas, Math.round(cx), Math.round(cy), iconProgress, iconColor);
        }
    }

    private static void drawCheckmark(Canvas canvas, int cx0, int cy0, boolean checked, float sizeProgress, int color) {
        float shape = checked ? 1f : 0f;
        iconPaint.setColor(color);
        iconPaint.setAlpha(255);
        iconPaint.setStrokeWidth(AndroidUtilities.dpf2(CROSS_STROKE + (ICON_STROKE - CROSS_STROKE) * shape));

        float s = CHECK_SCALE * (THUMB_R_OFF + (THUMB_R_ON - THUMB_R_OFF) * sizeProgress) / THUMB_R_ON;
        float nudge = AndroidUtilities.dpf2(CHECK_NUDGE_RIGHT) * shape;

        int tx = cx0 - (int)(AndroidUtilities.dp(10.8f) - AndroidUtilities.dp(1.3f) * shape);
        int ty = cy0 - (int)(AndroidUtilities.dp(8.5f) - AndroidUtilities.dp(0.5f) * shape);

        int startX2 = (int)AndroidUtilities.dpf2(4.6f) + tx;
        int startY2 = (int)(AndroidUtilities.dpf2(9.5f) + ty);
        int endX2 = startX2 + AndroidUtilities.dp(2f);
        int endY2 = startY2 + AndroidUtilities.dp(2f);

        int startX = (int)AndroidUtilities.dpf2(7.5f) + tx;
        int startY = (int)(AndroidUtilities.dpf2(5.4f) + ty);
        int endX = startX + AndroidUtilities.dp(7f);
        int endY = startY + AndroidUtilities.dp(7f);

        startX = (int)(startX + (startX2 - startX) * shape);
        startY = (int)(startY + (startY2 - startY) * shape);
        endX = (int)(endX + (endX2 - endX) * shape);
        endY = (int)(endY + (endY2 - endY) * shape);

        canvas.drawLine(fx(cx0, s, nudge, startX), fy(cy0, s, startY), fx(cx0, s, nudge, endX), fy(cy0, s, endY), iconPaint);

        startX = (int)AndroidUtilities.dpf2(7.5f) + tx;
        startY = (int)AndroidUtilities.dpf2(12.5f) + ty;
        endX = startX + AndroidUtilities.dp(7f);
        endY = startY - AndroidUtilities.dp(7f);

        canvas.drawLine(fx(cx0, s, nudge, startX), fy(cy0, s, startY), fx(cx0, s, nudge, endX), fy(cy0, s, endY), iconPaint);
    }

    private static void drawDot(Canvas canvas, int cx, int cy, float iconProgress, int color) {
        iconPaint.setColor(color);
        iconPaint.setAlpha((int)(255 * (1f - iconProgress)));
        iconPaint.setStrokeWidth(AndroidUtilities.dpf2(ICON_STROKE));
        canvas.drawLine(cx, cy, cx, cy - AndroidUtilities.dp(5f), iconPaint);
        canvas.save();
        canvas.rotate(-90 * iconProgress, cx, cy);
        canvas.drawLine(cx, cy, cx + AndroidUtilities.dp(4f), cy, iconPaint);
        canvas.restore();
    }

    private static float fx(int cx, float s, float nudge, int x) {
        return cx + (x - cx) * s + nudge;
    }

    private static float fy(int cy, float s, int y) {
        return cy + (y - cy) * s;
    }
}
