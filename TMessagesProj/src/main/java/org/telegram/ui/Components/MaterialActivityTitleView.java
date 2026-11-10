package org.telegram.ui.Components;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;

import com.google.android.material.internal.CollapsingTextHelper;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;

import zxc.iconic.xenon.helpers.ClientFonts;
import zxc.iconic.xenon.helpers.TypefaceHelper;

/** Material title rendering for screens with their own coordinated scrolling. */
public class MaterialActivityTitleView extends View {
    private final CollapsingTextHelper title = new CollapsingTextHelper(this);
    private final Paint surface = new Paint();
    private float fraction;
    private boolean fade;
    private int fontGeneration = -1;
    private CharSequence lastText;
    private boolean lastCentered;
    private int lastLeadingInset = -1;
    private int lastBackground, lastForeground, lastActionsWidth;
    private int lastWidth = -1;
    private int lastHeight = -1;

    public MaterialActivityTitleView(Context context) {
        super(context);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        title.setExpandedTextSize(36 * getResources().getDisplayMetrics().scaledDensity);
        title.setCollapsedTextSize(24 * getResources().getDisplayMetrics().scaledDensity);
        title.setTitleTextEllipsize(TextUtils.TruncateAt.END);
        title.setExpandedMaxLines(2);
        title.setFadeModeEnabled(false);
    }

    private CharSequence resolveTitle(CharSequence text) {
        if (text != null && !TextUtils.isEmpty(text.toString().trim())) return text;
        if (!TextUtils.isEmpty(lastText)) return lastText;
        return TypefaceHelper.getTitleText().toString();
    }

    public void update(CharSequence text, float progress, boolean centered, boolean useFade,
                       int background, int foreground, int actionsWidth, int leadingInset) {
        text = resolveTitle(text);
        float next = Math.max(0f, Math.min(1f, progress));
        int generation = ClientFonts.generation();
        if (lastWidth == getWidth() && lastHeight == getHeight() && TextUtils.equals(lastText, text) && fraction == next
                && lastCentered == centered && lastLeadingInset == leadingInset && fade == useFade && lastBackground == background
                && lastForeground == foreground && lastActionsWidth == actionsWidth && fontGeneration == generation) return;
        lastWidth = getWidth();
        lastHeight = getHeight();
        lastText = text == null ? null : text.toString();
        lastCentered = centered;
        lastLeadingInset = leadingInset;
        lastBackground = background;
        lastForeground = foreground;
        lastActionsWidth = actionsWidth;
        fraction = next;
        fade = useFade;
        surface.setColor(background | 0xFF000000);
        setContentDescription(text);
        setLayoutDirection(LocaleController.isRTL ? LAYOUT_DIRECTION_RTL : LAYOUT_DIRECTION_LTR);
        title.setText(text);
        title.setCollapsedAndExpandedTextColor(ColorStateList.valueOf(foreground));
        if (fontGeneration != generation) {
            fontGeneration = generation;
            title.setTypefaces(TypefaceHelper.supportsRoundedTypeface()
                    ? TypefaceHelper.createRoundedTypeface(550, false)
                    : ClientFonts.resolve(Typeface.create("sans-serif", Typeface.NORMAL)));
        }
        int horizontal = centered ? Gravity.CENTER_HORIZONTAL : Gravity.START;
        title.setExpandedTextGravity(Gravity.BOTTOM | Gravity.START);
        title.setCollapsedTextGravity(Gravity.CENTER_VERTICAL | horizontal);
        int end = actionsWidth == 0 ? AndroidUtilities.dp(24) : actionsWidth + AndroidUtilities.dp(32);
        end = Math.min(end, Math.max(0, getWidth() - AndroidUtilities.dp(72)));
        int start = collapsedStartInset(getWidth(), leadingInset, end, false);
        Paint measure = new Paint(Paint.ANTI_ALIAS_FLAG);
        measure.setTypeface(title.getCollapsedTypeface());
        measure.setTextSize(title.getCollapsedTextSize());
        int symmetricInset = Math.max(start, end);
        if (centered && measure.measureText(text.toString()) <= getWidth() - 2 * symmetricInset) {
            start = end = symmetricInset;
        }
        title.setCollapsedBounds(LocaleController.isRTL ? end : start, 0,
                Math.max(0, getWidth() - (LocaleController.isRTL ? start : end)), AndroidUtilities.dp(64));
        title.setExpandedBounds(AndroidUtilities.dp(24), AndroidUtilities.dp(64),
                Math.max(0, getWidth() - AndroidUtilities.dp(24)), AndroidUtilities.dp(128));
        title.recalculate();
        title.setExpansionFraction(fraction);
    }

    private int collapsedStartInset(int width, int leadingInset, int end, boolean centered) {
        int maximum = Math.max(0, centered ? (width - AndroidUtilities.dp(48)) / 2
                : width - end - AndroidUtilities.dp(48));
        return Math.min(Math.max(AndroidUtilities.dp(24), leadingInset), maximum);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        // CollapsingTextHelper skips layout while its host has zero dimensions.
        // Replay the saved state once layout supplies the actual size.
        lastWidth = lastHeight = -1;
        if (lastText != null) update(lastText, fraction, lastCentered, fade,
                lastBackground, lastForeground, lastActionsWidth, lastLeadingInset);
    }

    @Override
    public boolean onTouchEvent(android.view.MotionEvent event) {
        // The view retains its expanded height while the drawn title collapses.
        // Let touches below the visible header reach the list.
        if (event.getActionMasked() == android.view.MotionEvent.ACTION_DOWN
                && event.getY() > AndroidUtilities.dp(152 - 88 * fraction)) return false;
        return super.onTouchEvent(event);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int save = canvas.save();
        canvas.clipRect(0, 0, getWidth(), AndroidUtilities.dp(152 - 88 * fraction));
        if (!fade) canvas.drawRect(0, 0, getWidth(), getHeight(), surface);
        title.draw(canvas);
        canvas.restoreToCount(save);
    }
}
