package org.telegram.ui.Components.chat.layouts;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.SharedConfig;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.blur3.BlurredBackgroundDrawableViewFactory;
import org.telegram.ui.Components.blur3.BlurredBackgroundWithFadeDrawable;
import org.telegram.ui.Components.blur3.drawable.BlurredBackgroundDrawable;
import org.telegram.ui.Components.blur3.drawable.color.BlurredBackgroundColorProvider;
import org.telegram.utils.glass.source.GlassSourceColor;
import java.util.HashSet;


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


    private GlassSourceColor sourceColor;
    private BlurredBackgroundDrawableViewFactory factory;
    private int colorKey;

    public void setupColorKey(int colorKey) {
        this.colorKey = colorKey;
        if (sourceColor == null) {
            sourceColor = new GlassSourceColor();
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
        overlayShaderTop = overlayShaderBottom = null;
    }

    // extra tint over the fades, e.g. a subtle shadow when the background is a plain color
    private int overlayColor;
    private final Paint overlayPaint = new Paint();
    private Shader overlayShaderTop, overlayShaderBottom;

    private Theme.ResourcesProvider resourcesProvider;

    public void setResourcesProvider(Theme.ResourcesProvider resourcesProvider) {
        this.resourcesProvider = resourcesProvider;
    }

    public void setOverlayColor(int color) {
        if (overlayColor != color) {
            overlayColor = color;
            overlayShaderTop = overlayShaderBottom = null;
            invalidate();
        }
    }

    private Shader createOverlayShader(boolean top) {
        final int edge = overlayColor;
        final int middle = ColorUtils.setAlphaComponent(overlayColor, Color.alpha(overlayColor) * 2 / 3);
        final int none = ColorUtils.setAlphaComponent(overlayColor, 0);
        if (top) {
            return new LinearGradient(0, 0, 0, fadeZoneTop, new int[]{edge, middle, none}, new float[]{0, 0.82f, 1}, Shader.TileMode.CLAMP);
        }
        final int y = getMeasuredHeight() - fadeZoneBottom;
        return new LinearGradient(0, y, 0, getMeasuredHeight(), new int[]{none, middle, edge}, new float[]{0, 0.18f, 1}, Shader.TileMode.CLAMP);
    }

    private void drawOverlay(Canvas canvas) {
        if (Color.alpha(overlayColor) == 0) {
            return;
        }
        if (fadeZoneTop > 0 && fadeDrawableTop.getAlpha() > 0) {
            if (overlayShaderTop == null) {
                overlayShaderTop = createOverlayShader(true);
            }
            overlayPaint.setShader(overlayShaderTop);
            overlayPaint.setAlpha(fadeDrawableTop.getAlpha());
            canvas.drawRect(0, 0, getMeasuredWidth(), fadeZoneTop, overlayPaint);
        }
        if (fadeZoneBottom > 0) {
            if (overlayShaderBottom == null) {
                overlayShaderBottom = createOverlayShader(false);
            }
            overlayPaint.setShader(overlayShaderBottom);
            overlayPaint.setAlpha(0xFF);
            canvas.drawRect(0, getMeasuredHeight() - fadeZoneBottom, getMeasuredWidth(), getMeasuredHeight(), overlayPaint);
        }
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
        if (Theme.isCompactMode(resourcesProvider)) {
            drawCompactFades(canvas);
        } else {
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
        drawOverlay(canvas);
    }

    // compact mode follows the design: the background fades out from the edges, through the middle color at 56% (theme keys)
    private final Paint compactFadePaint = new Paint();
    private Shader compactShaderTop, compactShaderBottom;
    private int compactShaderMiddle;
    private int compactShaderColor, compactShaderTopHeight, compactShaderBottomHeight, compactShaderHeight;

    private void drawCompactFades(Canvas canvas) {
        final int color = Theme.getColor(Theme.key_chat_compact_fadeEdge);
        final int middle = Theme.getColor(Theme.key_chat_compact_fadeMiddle);
        final int h = getMeasuredHeight();
        if (compactShaderTop == null || compactShaderColor != color || compactShaderMiddle != middle || compactShaderTopHeight != fadeZoneTop || compactShaderBottomHeight != fadeZoneBottom || compactShaderHeight != h) {
            compactShaderColor = color;
            compactShaderMiddle = middle;
            compactShaderTopHeight = fadeZoneTop;
            compactShaderBottomHeight = fadeZoneBottom;
            compactShaderHeight = h;
            final int[] colors = new int[] { color, middle, ColorUtils.setAlphaComponent(middle, 0) };
            compactShaderTop = new LinearGradient(0, 0, 0, Math.max(1, fadeZoneTop), colors, new float[] { 0, .557f, 1 }, Shader.TileMode.CLAMP);
            compactShaderBottom = new LinearGradient(0, h, 0, h - Math.max(1, fadeZoneBottom), colors, new float[] { 0, .557f, 1 }, Shader.TileMode.CLAMP);
        }
        if (fadeZoneTop > 0 && fadeDrawableTop.getAlpha() > 0) {
            compactFadePaint.setShader(compactShaderTop);
            compactFadePaint.setAlpha(fadeDrawableTop.getAlpha());
            canvas.drawRect(0, 0, getMeasuredWidth(), fadeZoneTop, compactFadePaint);
        }
        if (fadeZoneBottom > 0) {
            compactFadePaint.setShader(compactShaderBottom);
            compactFadePaint.setAlpha(0xFF);
            canvas.drawRect(0, h - fadeZoneBottom, getMeasuredWidth(), h, compactFadePaint);
        }
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
    @Override
    public void addColorKeys(HashSet<Integer> colors) {
        colors.add(colorKey);
    }
}
