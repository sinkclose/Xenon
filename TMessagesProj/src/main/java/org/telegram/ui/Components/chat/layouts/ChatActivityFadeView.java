package org.telegram.ui.Components.chat.layouts;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.view.View;

import androidx.annotation.NonNull;

import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.blur3.BlurredBackgroundDrawableViewFactory;
import org.telegram.ui.Components.blur3.BlurredBackgroundWithFadeDrawable;
import org.telegram.ui.Components.blur3.drawable.BlurredBackgroundDrawable;
import org.telegram.ui.Components.blur3.drawable.color.BlurredBackgroundColorProvider;
import org.telegram.ui.Components.blur3.source.BlurredBackgroundSourceColor;


public class ChatActivityFadeView extends View implements Theme.Colorable {
    private Drawable fadeDrawableTop;
    private Drawable fadeDrawableBottom;
    private int fadeZoneTop, fadeZoneBottom;
    private int topOffset;
    private boolean ios27Dimming;
    private boolean ios27SeparatorVisible = true;
    private final org.telegram.ui.Components.AnimatedFloat ios27DividerAlpha = new org.telegram.ui.Components.AnimatedFloat(
            0f, this, 0, 260, new org.telegram.ui.Components.CubicBezierInterpolator(.42, 0, .58, 1));
    private int ios27Bottom, ios27Color;
    private View ios27SeparatorSource;
    private int ios27SeparatorFirstPosition = androidx.recyclerview.widget.RecyclerView.NO_POSITION;
    private final android.view.ViewTreeObserver.OnPreDrawListener ios27DividerObserver = () -> {
        updateIos27Divider();
        return true;
    };
    private void updateIos27Divider() {
        if (ios27Dimming && ios27SeparatorSource != null && getVisibility() == VISIBLE) {
            setIos27SeparatorVisible(org.telegram.ui.Components.HeaderSurfaceDivider.isScrolled(
                    ios27SeparatorSource, ios27SeparatorFirstPosition, ios27SeparatorVisible));
        }
    }

    public void setIos27SeparatorSource(View source) {
        setIos27SeparatorSource(source, androidx.recyclerview.widget.RecyclerView.NO_POSITION);
    }

    public void setIos27SeparatorSource(View source, int firstPosition) {
        if (ios27SeparatorSource == source && ios27SeparatorFirstPosition == firstPosition) return;
        ios27SeparatorSource = source;
        ios27SeparatorFirstPosition = firstPosition;
        setIos27SeparatorVisible(false);
        updateIos27Divider();
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        getViewTreeObserver().addOnPreDrawListener(ios27DividerObserver);
    }

    @Override protected void onDetachedFromWindow() {
        if (getViewTreeObserver().isAlive()) getViewTreeObserver().removeOnPreDrawListener(ios27DividerObserver);
        super.onDetachedFromWindow();
    }

    private final android.graphics.Paint ios27Paint = new android.graphics.Paint();

    public void setIos27Dimming(boolean enabled, int bottom, int color) {
        bottom = Math.max(0, bottom);
        if (ios27Dimming == enabled && ios27Bottom == bottom && ios27Color == color) return;
        ios27Dimming = enabled;
        ios27Bottom = bottom;
        ios27Color = color;
        checkBounds();
        invalidate();
    }

    public void setIos27SeparatorVisible(boolean visible) {
        if (ios27SeparatorVisible == visible) return;
        ios27SeparatorVisible = visible;
        invalidate();
    }

    public ChatActivityFadeView(Context context) {
        super(context);
    }


    private BlurredBackgroundSourceColor sourceColor;
    private BlurredBackgroundDrawableViewFactory factory;
    private int colorKey;

    public void setupColorKey(int colorKey) {
        this.colorKey = colorKey;
        if (sourceColor == null) {
            sourceColor = new BlurredBackgroundSourceColor();
            sourceColor.setColor(Theme.getColor(colorKey));
            factory = new BlurredBackgroundDrawableViewFactory(sourceColor);
            setup(factory);
        }
    }


    public void setup(BlurredBackgroundDrawableViewFactory factory) {
        setup(factory, null, null);
    }

    public void setup(BlurredBackgroundDrawableViewFactory factory, BlurredBackgroundColorProvider colorProvider) {
        setup(factory, null, colorProvider);
    }

    public void setup(BlurredBackgroundDrawableViewFactory factory, BlurredBackgroundDrawableViewFactory dimFactory) {
        setup(factory, dimFactory, null);
    }

    public void setup(BlurredBackgroundDrawableViewFactory factory, BlurredBackgroundDrawableViewFactory dimFactory, BlurredBackgroundColorProvider colorProvider) {
        fadeDrawableTop = createEdgeDrawable(factory, dimFactory, colorProvider, true);
        fadeDrawableBottom = createEdgeDrawable(factory, dimFactory, colorProvider, false);
        checkBounds();
    }

    private Drawable createEdgeDrawable(BlurredBackgroundDrawableViewFactory factory, BlurredBackgroundDrawableViewFactory dimFactory, BlurredBackgroundColorProvider colorProvider, boolean top) {
        BlurredBackgroundDrawable source = factory.create(this).setColorProvider(colorProvider);
        BlurredBackgroundWithFadeDrawable drawable = new BlurredBackgroundWithFadeDrawable(source);
        if (dimFactory != null) {
            drawable.setDimDrawable(dimFactory.create(this));
        }
        drawable.setFadeHeight(top ? -dp(30) : dp(30), true);
        return drawable;
    }

    public void setFadeHeightTop(int height, boolean opacity) {
        if (fadeDrawableTop instanceof BlurredBackgroundWithFadeDrawable) {
            ((BlurredBackgroundWithFadeDrawable) fadeDrawableTop).setFadeHeight(-height, opacity);
        }
    }

    public void setFadeHeightTopInverted(int height, boolean opacity) {
        if (fadeDrawableTop instanceof BlurredBackgroundWithFadeDrawable) {
            ((BlurredBackgroundWithFadeDrawable) fadeDrawableTop).setFadeHeight(height, opacity);
        }
    }

    public void setFadeHeightTop(int height) {
        if (fadeDrawableTop instanceof BlurredBackgroundWithFadeDrawable) {
            ((BlurredBackgroundWithFadeDrawable) fadeDrawableTop).setFadeHeight(-height, true);
        }
    }

    public void setFadeHeightBottom(int height) {
        if (fadeDrawableBottom instanceof BlurredBackgroundWithFadeDrawable) {
            ((BlurredBackgroundWithFadeDrawable) fadeDrawableBottom).setFadeHeight(height, true);
        }
    }

    public void setFadeHeightBottom(int height, boolean opacity) {
        if (fadeDrawableBottom instanceof BlurredBackgroundWithFadeDrawable) {
            ((BlurredBackgroundWithFadeDrawable) fadeDrawableBottom).setFadeHeight(height, opacity);
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        checkBounds();
    }

    public void setFadeZoneTop(int height) {
        if (fadeZoneTop != height) {
            fadeZoneTop = height;
            checkBounds();
            invalidate();
        }
    }

    public void setTopOffset(int offset) {
        if (topOffset != offset) {
            topOffset = offset;
            checkBounds();
            invalidate();
        }
    }

    public int getFadeZoneTop() {
        return fadeZoneTop;
    }

    public void setFadeZoneBottom(int height) {
        if (fadeZoneBottom != height) {
            fadeZoneBottom = height;
            checkBounds();
            invalidate();
        }
    }

    public int getFadeZoneBottom() {
        return fadeZoneBottom;
    }

    public void setFadeTopAlpha(int alpha) {
        if (fadeDrawableTop.getAlpha() != alpha) {
            fadeDrawableTop.setAlpha(alpha);
            invalidate();
        }
    }

    public void setDimFadeZoneTop(int height) {
        if (fadeDrawableTop instanceof BlurredBackgroundWithFadeDrawable) {
            ((BlurredBackgroundWithFadeDrawable) fadeDrawableTop).setDimFadeZoneTop(height);
        }
    }

    public void setDimColor(int color) {
        setDimColor(color, color);
    }

    public void setDimColor(int topColor, int bottomColor) {
        if (fadeDrawableTop instanceof BlurredBackgroundWithFadeDrawable) {
            ((BlurredBackgroundWithFadeDrawable) fadeDrawableTop).setDimColor(topColor);
        }
        if (fadeDrawableBottom instanceof BlurredBackgroundWithFadeDrawable) {
            ((BlurredBackgroundWithFadeDrawable) fadeDrawableBottom).setDimColor(bottomColor);
        }
    }
    
    private void checkBounds() {
        if (fadeDrawableTop == null || fadeDrawableBottom == null) return;
        if (fadeDrawableTop instanceof BlurredBackgroundWithFadeDrawable) {
            ((BlurredBackgroundWithFadeDrawable) fadeDrawableTop).setHardEdge(ios27Dimming);
        }
        fadeDrawableTop.setBounds(0, topOffset, getMeasuredWidth(), Math.max(topOffset, ios27Dimming ? ios27Bottom : fadeZoneTop));
        fadeDrawableBottom.setBounds(0, getMeasuredHeight() - fadeZoneBottom, getMeasuredWidth(), getMeasuredHeight());
    }

    public void setIgnoreFastWay(boolean ignoreFastWay) {
        if (fadeDrawableTop instanceof BlurredBackgroundWithFadeDrawable) {
            ((BlurredBackgroundWithFadeDrawable) fadeDrawableTop).setIgnoreFastWay(ignoreFastWay);
        }
        if (fadeDrawableBottom instanceof BlurredBackgroundWithFadeDrawable) {
            ((BlurredBackgroundWithFadeDrawable) fadeDrawableBottom).setIgnoreFastWay(ignoreFastWay);
        }
    }

    public void setDim(int alpha) {
        if (fadeDrawableTop instanceof BlurredBackgroundWithFadeDrawable) {
            ((BlurredBackgroundWithFadeDrawable) fadeDrawableTop).setDimAlpha(alpha);
        }
        if (fadeDrawableBottom instanceof BlurredBackgroundWithFadeDrawable) {
            ((BlurredBackgroundWithFadeDrawable) fadeDrawableBottom).setDimAlpha(alpha);
        }
        invalidate();
    }

    public void setOpaqueFade(boolean opaqueFade) {
        if (fadeDrawableTop instanceof BlurredBackgroundWithFadeDrawable) {
            ((BlurredBackgroundWithFadeDrawable) fadeDrawableTop).setOpaqueFade(opaqueFade);
        }
        if (fadeDrawableBottom instanceof BlurredBackgroundWithFadeDrawable) {
            ((BlurredBackgroundWithFadeDrawable) fadeDrawableBottom).setOpaqueFade(opaqueFade);
        }
        invalidate();
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);
        updateIos27Divider();
        float dividerAlpha = ios27DividerAlpha.set(ios27Dimming && ios27SeparatorVisible ? 1f : 0f);
        fadeDrawableTop.draw(canvas);
        if (ios27Dimming && ios27Bottom > topOffset) {
            ios27Paint.setColor((ios27Color & 0x00ffffff) | 0xbf000000);
            canvas.drawRect(0, topOffset, getWidth(), ios27Bottom, ios27Paint);
            if (dividerAlpha > 0f) {
                ios27Paint.setColor(org.telegram.ui.Components.HeaderSurfaceDivider.color(ios27Color, Theme.getColor(Theme.key_divider)));
                ios27Paint.setAlpha(Math.round(255 * dividerAlpha));
                canvas.drawRect(0, ios27Bottom - Math.max(1, getResources().getDisplayMetrics().density * 0.75f),
                        getWidth(), ios27Bottom, ios27Paint);
                ios27Paint.setAlpha(255);
            }
        }
        fadeDrawableBottom.draw(canvas);
    }

    @Override
    public void updateColors() {
        updateColors(null);
    }

    public void updateColors(Theme.ResourcesProvider resourcesProvider) {
        if (sourceColor != null && colorKey != -1) {
            sourceColor.setColor(Theme.getColor(colorKey, resourcesProvider));
            invalidate();
        }
    }
}

