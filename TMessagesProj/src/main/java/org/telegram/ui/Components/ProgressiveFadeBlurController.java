package org.telegram.ui.Components;

import android.graphics.Canvas;
import android.graphics.Color;
import android.os.Build;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.ui.Components.blur3.BlurredBackgroundDrawableViewFactory;
import org.telegram.ui.Components.blur3.source.BlurredBackgroundSourceColor;
import org.telegram.ui.Components.blur3.source.BlurredBackgroundSourceRenderNode;
import org.telegram.ui.Components.chat.layouts.ChatActivityFadeView;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntSupplier;

import zxc.iconic.xenon.NekoConfig;

public class ProgressiveFadeBlurController {

    private final BlurredBackgroundSourceRenderNode source;
    private final BlurredBackgroundSourceColor underSource;
    private final ChatActivityFadeView fadeView;
    private final ViewGroup parent;
    private View captureView;
    private final List<View> additionalCaptureViews = new ArrayList<>();
    private boolean dimEnabled = true;
    private boolean flipped;
    private boolean continuousUpdating;
    private boolean updateAtScreenRefreshRate;
    private int drawCount;
    private int lastProcessedDrawCount = -1;
    private final ViewTreeObserver.OnPreDrawListener drawCountListener = new ViewTreeObserver.OnPreDrawListener() {
        @Override
        public boolean onPreDraw() {
            drawCount++;
            return true;
        }
    };
    private final Runnable updateRunnable = new Runnable() {
        @Override
        public void run() {
            if (!continuousUpdating) {
                return;
            }
            try {
                invalidate();
            } catch (Exception e) {
                FileLog.e(e);
            }
            fadeView.postDelayed(this, Math.max(8, 1000 / Math.max(15, NekoConfig.progressiveFadeBlurRefreshRate)));
        }
    };
    private int fadeZoneTop;
    private int fadeZoneBottom;
    private int topOffset;
    private long lastUpdateTime;
    private int background = Color.TRANSPARENT;
    private IntSupplier backgroundColorProvider;
    private int lastBackgroundColor = Integer.MIN_VALUE;

    public ProgressiveFadeBlurController(ViewGroup parent, View captureView) {
        this(parent, captureView, -1);
    }

    public ProgressiveFadeBlurController(ViewGroup parent, View captureView, int insertIndex) {
        this(parent, captureView, insertIndex, null);
    }

    public ProgressiveFadeBlurController(ViewGroup parent, View captureView, int insertIndex, IntSupplier backgroundColorProvider) {
        this.backgroundColorProvider = backgroundColorProvider;
        this.parent = parent;
        this.captureView = captureView;
        underSource = new BlurredBackgroundSourceColor();
        source = new BlurredBackgroundSourceRenderNode(null);
        source.setUnderSource(underSource);
        fadeView = new ChatActivityFadeView(parent.getContext());
        fadeView.setup(new BlurredBackgroundDrawableViewFactory(source));
        fadeView.setOpaqueFade(true);
        fadeView.setFadeHeightTop(AndroidUtilities.dp(48), false);
        fadeView.setFadeHeightBottom(AndroidUtilities.dp(48), false);
        fadeView.setFadeTopAlpha(255);
        if (insertIndex >= 0) {
            parent.addView(fadeView, insertIndex, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        } else {
            parent.addView(fadeView, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        }
        fadeView.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View v) {
                v.getViewTreeObserver().addOnPreDrawListener(drawCountListener);
            }

            @Override
            public void onViewDetachedFromWindow(View v) {
                if (v.getViewTreeObserver().isAlive()) {
                    v.getViewTreeObserver().removeOnPreDrawListener(drawCountListener);
                }
            }
        });
    }

    public void setFadeZoneTop(int fadeZoneTop) {
        if (fadeZoneTop < topOffset) {
            fadeZoneTop = topOffset;
        }
        this.fadeZoneTop = fadeZoneTop;
        fadeView.setFadeZoneTop(fadeZoneTop);
        fadeView.setDimFadeZoneTop(fadeZoneTop);
    }

    public void setTopOffset(int topOffset) {
        if (topOffset < 0) {
            topOffset = 0;
        }
        if (this.topOffset != topOffset) {
            this.topOffset = topOffset;
            fadeView.setTopOffset(topOffset);
        }
    }

    public void setFadeZoneBottom(int fadeZoneBottom) {
        this.fadeZoneBottom = fadeZoneBottom;
        fadeView.setFadeZoneBottom(fadeZoneBottom);
    }

    public void setDimEnabled(boolean dimEnabled) {
        this.dimEnabled = dimEnabled;
    }

    public void setFadeViewVisibility(int visibility) {
        fadeView.setVisibility(visibility);
    }

    public void setFlipped(boolean flipped) {
        if (this.flipped == flipped) {
            return;
        }
        this.flipped = flipped;
        if (flipped) {
            fadeView.setFadeHeightTopInverted(AndroidUtilities.dp(48), false);
        } else {
            fadeView.setFadeHeightTop(AndroidUtilities.dp(48), false);
        }
    }

    public void setUpdateAtScreenRefreshRate(boolean updateAtScreenRefreshRate) {
        this.updateAtScreenRefreshRate = updateAtScreenRefreshRate;
    }

    public void startContinuousUpdates() {
        if (continuousUpdating) {
            return;
        }
        continuousUpdating = true;
        // In screen-refresh-rate mode updates are fully event-driven: captures are
        // requested from real draw passes (see invalidate) and skip themselves when
        // nothing on screen changes, so no continuous loop is needed.
        if (!updateAtScreenRefreshRate) {
            fadeView.removeCallbacks(updateRunnable);
            fadeView.post(updateRunnable);
        }
    }

    public void stopContinuousUpdates() {
        continuousUpdating = false;
        fadeView.removeCallbacks(updateRunnable);
    }

    public void addCaptureView(View view) {
        additionalCaptureViews.add(view);
    }

    public void setCaptureViews(View mainView, List<View> extraViews) {
        captureView = mainView;
        additionalCaptureViews.clear();
        if (extraViews != null) {
            additionalCaptureViews.addAll(extraViews);
        }
    }

    public void setBackgroundColor(int color) {
        backgroundColorProvider = null;
        background = color;
    }

    public void invalidate() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || source.inRecording() || SizeNotifierFrameLayout.drawingBlur) {
            return;
        }
        // When the only draw pass since the previous record is the fade-view redraw
        // scheduled by that record itself, the captured content is unchanged: skip
        // re-recording and do not invalidate again, so an idle screen stops rendering
        // instead of looping record -> redraw -> record at the display refresh rate.
        if (drawCount == lastProcessedDrawCount) {
            return;
        }
        final int fw = captureView.getWidth();
        final int fh = captureView.getHeight();
        if (fw <= 0 || fh <= 0) {
            return;
        }
        final long now = SystemClock.uptimeMillis();
        if (now - lastUpdateTime < (updateAtScreenRefreshRate ? (long) AndroidUtilities.screenRefreshTime : 1000 / Math.max(15, NekoConfig.progressiveFadeBlurRefreshRate))) {
            return;
        }
        lastUpdateTime = now;
        final int color = backgroundColorProvider != null ? backgroundColorProvider.getAsInt() : background;
        if (color != lastBackgroundColor) {
            lastBackgroundColor = color;
            int opaqueColor = (color & 0x00FFFFFF) | 0xFF000000;
            underSource.setColor(opaqueColor);
            fadeView.setDimColor(color);
        }
        final int pixelation = Math.max(2, NekoConfig.blurredFadePixelation);
        source.setPixelation(pixelation);
        final float topFraction = fadeZoneTop > AndroidUtilities.dp(48) ? Math.min(1f, (fadeZoneTop - AndroidUtilities.dp(48)) / (float) fh) : 1f;
        final float bottomFraction = fadeZoneBottom > 0 ? Math.min(1f, fadeZoneBottom / (float) fh) : 0f;
        source.setProgressiveBlur(AndroidUtilities.dpf2(NekoConfig.progressiveFadeBlurMaxRadius) / pixelation, fw / pixelation, fh / pixelation, topFraction, bottomFraction, NekoConfig.progressiveFadeBlurSamples);
        try {
            Canvas c = source.beginRecording(fw, fh);
            try {
                c.drawColor(color);
                drawCapturedView(c, captureView);
                for (int i = 0; i < additionalCaptureViews.size(); i++) {
                    drawCapturedView(c, additionalCaptureViews.get(i));
                }
            } finally {
                source.endRecording();
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        fadeView.setDim(dimEnabled && NekoConfig.blurredFadeDimming ? NekoConfig.blurredFadeDimStrength * 255 / 100 : 0);
        // +1 accounts for the fade-view redraw this record schedules, so it is not
        // mistaken for a content change on the next draw pass.
        lastProcessedDrawCount = drawCount + 1;
        fadeView.invalidate();
    }

    private void drawCapturedView(Canvas c, View view) {
        c.save();
        c.translate(view.getLeft() + view.getTranslationX(), view.getTop() + view.getTranslationY());
        final float sx = view.getScaleX();
        final float sy = view.getScaleY();
        if (sx != 1f || sy != 1f) {
            c.scale(sx, sy, view.getPivotX(), view.getPivotY());
        }
        view.draw(c);
        c.restore();
    }
}