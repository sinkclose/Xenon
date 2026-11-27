package org.telegram.ui.Components;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Shader;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.core.graphics.ColorUtils;

import zxc.iconic.xenon.NekoConfig;

public class DialogsActivityTopBubblesFadeView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix matrix = new Matrix();
    private Shader shader;
    private int lastColor;
    private boolean ios27DimmingAllowed = true;
    private boolean ios27SeparatorVisible = true;
    private final org.telegram.ui.Components.AnimatedFloat ios27DividerAlpha = new org.telegram.ui.Components.AnimatedFloat(
            0f, this, 0, 260, new org.telegram.ui.Components.CubicBezierInterpolator(.42, 0, .58, 1));
    private View ios27SeparatorSource;
    private int ios27SeparatorFirstPosition;
    private final android.view.ViewTreeObserver.OnPreDrawListener ios27DividerObserver = () -> {
        updateIos27Divider();
        return true;
    };

    public void setIos27SeparatorSource(View source, int firstPosition) {
        if (ios27SeparatorSource == source && ios27SeparatorFirstPosition == firstPosition) return;
        ios27SeparatorSource = source;
        ios27SeparatorFirstPosition = firstPosition;
        setIos27SeparatorVisible(false);
        updateIos27Divider();
    }

    private void updateIos27Divider() {
        if (ios27DimmingAllowed && NekoConfig.ios27DimmingEnabled() && ios27SeparatorSource != null && getVisibility() == VISIBLE) {
            setIos27SeparatorVisible(HeaderSurfaceDivider.isScrolled(ios27SeparatorSource, ios27SeparatorFirstPosition, ios27SeparatorVisible));
        }
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        getViewTreeObserver().addOnPreDrawListener(ios27DividerObserver);
    }

    @Override protected void onDetachedFromWindow() {
        if (getViewTreeObserver().isAlive()) getViewTreeObserver().removeOnPreDrawListener(ios27DividerObserver);
        super.onDetachedFromWindow();
    }

    public void setIos27SeparatorVisible(boolean visible) {
        if (ios27SeparatorVisible == visible) return;
        ios27SeparatorVisible = visible;
        invalidate();
    }

    public DialogsActivityTopBubblesFadeView(Context context) {
        super(context);
    }

    public void setIos27DimmingAllowed(boolean allowed) {
        ios27DimmingAllowed = allowed;
        invalidate();
    }

    public void setColor(int color) {
        if (lastColor != color) {
            lastColor = color;

            final int alpha = Color.alpha(color);
            shader = new LinearGradient(0, 0, 0, 1, new int[]{
                    ColorUtils.setAlphaComponent(color, 0xE8 * alpha / 255),
                    ColorUtils.setAlphaComponent(color, 0xC0 * alpha / 255),
                    ColorUtils.setAlphaComponent(color, 0x90 * alpha / 255),
                    ColorUtils.setAlphaComponent(color, 0)
            }, null, Shader.TileMode.CLAMP);
            paint.setShader(shader);
            shader.setLocalMatrix(matrix);
            invalidate();
        }
    }

    private float fadeStart, fadeHeight;

    public void setPosition(float fadeStart, float fadeHeight) {
        if (this.fadeStart != fadeStart || this.fadeHeight != fadeHeight) {
            this.fadeStart = fadeStart;
            this.fadeHeight = fadeHeight;

            matrix.reset();
            matrix.setScale(1, fadeHeight);
            matrix.postTranslate(0, fadeStart);
            if (shader != null) {
                shader.setLocalMatrix(matrix);
            }
            invalidate();
        }
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        updateIos27Divider();
        boolean ios27Dimming = ios27DimmingAllowed && NekoConfig.ios27DimmingEnabled();
        float dividerAlpha = ios27DividerAlpha.set(ios27Dimming && ios27SeparatorVisible ? 1f : 0f);
        if (ios27Dimming) {
            paint.setShader(null);
            paint.setColor(ColorUtils.setAlphaComponent(org.telegram.ui.ActionBar.Theme.getColor(
                    org.telegram.ui.ActionBar.Theme.key_windowBackgroundGray), 191));
            canvas.drawRect(0, 0, getMeasuredWidth(), fadeStart + fadeHeight, paint);
            if (dividerAlpha > 0f) {
                paint.setColor(HeaderSurfaceDivider.color(org.telegram.ui.ActionBar.Theme.getColor(org.telegram.ui.ActionBar.Theme.key_windowBackgroundGray), org.telegram.ui.ActionBar.Theme.getColor(org.telegram.ui.ActionBar.Theme.key_divider)));
                paint.setAlpha(Math.round(255 * dividerAlpha));
                canvas.drawRect(0, fadeStart + fadeHeight - Math.max(1, getResources().getDisplayMetrics().density * .75f),
                        getMeasuredWidth(), fadeStart + fadeHeight, paint);
                paint.setAlpha(255);
            }
            paint.setShader(shader);
        } else canvas.drawRect(0, 0, getMeasuredWidth(), fadeStart + fadeHeight, paint);
    }
}
