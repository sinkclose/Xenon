package org.telegram.ui.Components;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.Theme;

/**
 * A lightweight badge drawable that renders text with NO background shape.
 * Used when {@code badge_id == 1} in the Xenon badge system.
 *
 * <p>The text color matches the theme's primary accent color so the badge
 * stands out without being visually heavy.
 */
public class TextBadgeDrawable extends Drawable {

    private final Paint textPaint;
    private final String text;
    private final int intrinsicW;
    private final int intrinsicH;
    private final RectF boundsRect = new RectF();

    public TextBadgeDrawable(String text, boolean small, Theme.ResourcesProvider resourcesProvider) {
        this.text = (text != null && !text.isEmpty()) ? text : ":3";

        float textSizePx = AndroidUtilities.dp(small ? 11f : 13f);

        textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setFakeBoldText(true);
        textPaint.setTextSize(textSizePx);
        // chats_verifiedBackground stays readable on action-bar blue AND list backgrounds.
        int color = Theme.getColor(Theme.key_chats_verifiedBackground, resourcesProvider);
        if (color == 0) {
            color = Theme.getColor(Theme.key_featuredStickers_addButton, resourcesProvider);
        }
        if (color == 0) {
            color = 0xFF33A8E6;
        }
        textPaint.setColor(0xFF000000 | (color & 0x00FFFFFF));

        // Measure the text so the intrinsic size accurately reflects what will be drawn.
        int measuredW = (int) (textPaint.measureText(this.text) + AndroidUtilities.dp(4));
        Paint.FontMetrics fm = textPaint.getFontMetrics();
        int measuredH = (int) (fm.descent - fm.ascent + AndroidUtilities.dp(2));

        intrinsicW = measuredW;
        intrinsicH = measuredH;
    }

    /** Text rendered by this badge (used to detect when the drawable can be reused). */
    public String getText() {
        return text;
    }

    @Override
    public int getIntrinsicWidth() {
        return intrinsicW;
    }

    @Override
    public int getIntrinsicHeight() {
        return intrinsicH;
    }

    @Override
    public void draw(Canvas canvas) {
        boundsRect.set(getBounds());
        if (boundsRect.isEmpty()) return;

        Paint.FontMetrics fm = textPaint.getFontMetrics();
        // Center text glyphs vertically inside the allocated bounds.
        float textY = boundsRect.centerY() - (fm.ascent + fm.descent) / 2f;
        canvas.drawText(text, boundsRect.centerX(), textY, textPaint);
    }

    @Override
    public void setAlpha(int alpha) {
        textPaint.setAlpha(Math.max(0, Math.min(255, alpha)));
        invalidateSelf();
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        // Ignore filters meant for mono icons — they make the badge vanish on the action bar.
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
