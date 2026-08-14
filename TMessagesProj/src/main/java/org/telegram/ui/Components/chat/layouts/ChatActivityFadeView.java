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
        setup(factory, null);
    }

    public void setup(BlurredBackgroundDrawableViewFactory factory, BlurredBackgroundColorProvider colorProvider) {
        fadeDrawableTop = createEdgeDrawable(factory, colorProvider, true);
        fadeDrawableBottom = createEdgeDrawable(factory, colorProvider, false);
    }

    private Drawable createEdgeDrawable(BlurredBackgroundDrawableViewFactory factory, BlurredBackgroundColorProvider colorProvider, boolean top) {
        BlurredBackgroundDrawable source = factory.create(this).setColorProvider(colorProvider);
        BlurredBackgroundWithFadeDrawable drawable = new BlurredBackgroundWithFadeDrawable(source);
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
        if (fadeDrawableTop instanceof BlurredBackgroundWithFadeDrawable) {
            ((BlurredBackgroundWithFadeDrawable) fadeDrawableTop).setDimColor(color);
        }
        if (fadeDrawableBottom instanceof BlurredBackgroundWithFadeDrawable) {
            ((BlurredBackgroundWithFadeDrawable) fadeDrawableBottom).setDimColor(color);
        }
    }
    
    private void checkBounds() {
        fadeDrawableTop.setBounds(0, topOffset, getMeasuredWidth(), Math.max(topOffset, fadeZoneTop));
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
        fadeDrawableTop.draw(canvas);
        fadeDrawableBottom.draw(canvas);
    }

    @Override
    public void updateColors() {
        if (sourceColor != null && colorKey != -1) {
            sourceColor.setColor(Theme.getColor(colorKey));
            invalidate();
        }
    }
}

