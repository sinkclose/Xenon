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
            if (continuousUpdating) {
                // HWUI can refresh child display lists without calling the
                // parent's dispatchDraw. Preserve changes before that draw
                // clears the dirty flags, then capture after the traversal.
                boolean contentDirty = captureView.isDirty();
                for (int i = 0; i < additionalCaptureViews.size(); i++) {
                    contentDirty |= additionalCaptureViews.get(i).isDirty();
                }
                if (contentDirty || captureTransform() != lastCaptureTransform) {
                    capturePending = true;
                    scheduleUpdate(1);
                }
            }
            return true;
        }
    };
    private boolean updatePending;
    private boolean capturePending;
    private long lastCaptureTransform;
    private final Runnable updateRunnable = new Runnable() {
        @Override
        public void run() {
            updatePending = false;
            if (fadeView.isAttachedToWindow()) {
                invalidate();
            }
        }
    };

    private void scheduleUpdate(long delay) {
        if (!updatePending) {
            updatePending = true;
            fadeView.postDelayed(updateRunnable, Math.max(1, delay));
        }
    }
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
                stopContinuousUpdates();
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
        // NOTE: dim keeps the default zone (-1) so its gradient aligns with the
        // fade mask. Forcing it to fadeZoneTop pushes the whole dim gradient
        // below the drawable bounds (flipped geometry), making dim invisible.
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

    private int fadeViewRequestedVisibility = View.VISIBLE;

    public boolean isEnabled() {
        return NekoConfig.progressiveFadeBlurOtherActivitiesEnabled();
    }

    // Applies the stock behavior when the master blur is off: loops stopped,
    // blur fade hidden so the plain rect / header shadow path draws instead.
    // Safe to call from any state; idempotent.
    public void syncState() {
        if (isEnabled()) {
            fadeView.setVisibility(fadeViewRequestedVisibility);
        } else {
            stopContinuousUpdates();
            fadeView.setVisibility(View.GONE);
        }
    }

    public void setFadeViewVisibility(int visibility) {
        fadeViewRequestedVisibility = visibility;
        fadeView.setVisibility(isEnabled() ? visibility : View.GONE);
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
        // Real draw passes request captures. Never invalidate the parent every
        // vsync: that kept idle lists and their blur filters rendering forever.
        scheduleUpdate(1);
    }

    public void stopContinuousUpdates() {
        continuousUpdating = false;
        fadeView.removeCallbacks(updateRunnable);
        updatePending = false;
        capturePending = false;
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
        syncState();
        if (!isEnabled()) {
            return;
        }
        // Ignore the redraw requested by our previous capture, unless the
        // captured content itself is dirty (scrolling/animations can coincide).
        final long transform = captureTransform();
        boolean contentDirty = captureView.isDirty();
        for (int i = 0; i < additionalCaptureViews.size(); i++) {
            contentDirty |= additionalCaptureViews.get(i).isDirty();
        }
        if (!capturePending && drawCount == lastProcessedDrawCount && !contentDirty && transform == lastCaptureTransform) {
            return;
        }
        final int fw = captureView.getWidth();
        final int fh = captureView.getHeight();
        if (fw <= 0 || fh <= 0) {
            return;
        }
        final long now = SystemClock.uptimeMillis();
        final long interval = Math.max(1, updateAtScreenRefreshRate
                ? (long) Math.ceil(AndroidUtilities.screenRefreshTime)
                : (long) Math.ceil(1000.0 / Math.max(15, NekoConfig.progressiveFadeBlurRefreshRate)));
        final long remaining = interval - (now - lastUpdateTime);
        if (remaining > 0) {
            // Coalesce requests, but always capture the final scroll/animation
            // state even if no further draw follows the rate-limited request.
            capturePending = true;
            scheduleUpdate(remaining);
            return;
        }
        fadeView.removeCallbacks(updateRunnable);
        updatePending = false;
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
        source.setProgressiveBlur(AndroidUtilities.dpf2(NekoConfig.progressiveFadeBlurMaxRadius) / pixelation, Math.max(1, Math.round(fw / (float) pixelation)), Math.max(1, Math.round(fh / (float) pixelation)), topFraction, bottomFraction, NekoConfig.progressiveFadeBlurSamples);
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
        capturePending = false;
        lastCaptureTransform = transform;
        lastProcessedDrawCount = drawCount + 1;
        fadeView.invalidate();
    }

    // RenderNode property animations (folder swipes) need not dirty the view's
    // display list. Track their geometry as well as real draw passes.
    private long captureTransform() {
        long hash = viewTransform(captureView);
        for (int i = 0; i < additionalCaptureViews.size(); i++) {
            hash = hash * 31 + viewTransform(additionalCaptureViews.get(i));
        }
        return hash;
    }

    private static long viewTransform(View view) {
        long hash = System.identityHashCode(view);
        hash = hash * 31 + view.getLeft();
        hash = hash * 31 + view.getTop();
        hash = hash * 31 + view.getWidth();
        hash = hash * 31 + view.getHeight();
        hash = hash * 31 + Float.floatToIntBits(view.getTranslationX());
        hash = hash * 31 + Float.floatToIntBits(view.getTranslationY());
        hash = hash * 31 + Float.floatToIntBits(view.getScaleX());
        hash = hash * 31 + Float.floatToIntBits(view.getScaleY());
        return hash;
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
