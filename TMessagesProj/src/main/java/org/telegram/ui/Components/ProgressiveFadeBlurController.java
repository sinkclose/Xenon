package org.telegram.ui.Components;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.RectF;
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
import org.telegram.ui.Components.blur3.capture.IBlur3Capture;
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
    private final CaptureLayout captureLayout;
    private View captureView;
    private View captureCoordinateRoot;
    private IBlur3Capture contentCapture;
    private final RectF captureBounds = new RectF();

    /** Capture real content in the same coordinate system as the fade host. */
    public void setContentCapture(View coordinateRoot, IBlur3Capture capture) {
        captureCoordinateRoot = coordinateRoot;
        contentCapture = capture;
        capturePending = true;
    }
    private java.util.function.Consumer<Canvas> backgroundCapture;

    public void setBackgroundCapture(java.util.function.Consumer<Canvas> capture) {
        backgroundCapture = capture;
    }

    public void setAlpha(float alpha) {
        fadeView.setAlpha(alpha);
    }

    public void setOpaqueFade(boolean opaque) {
        fadeView.setOpaqueFade(opaque);
    }

    public void setFadeHeightTop(int height) {
        setFadeHeightTop(height, false);
    }

    public void setFadeHeightTop(int height, boolean translucent) {
        fadeView.setFadeHeightTop(height, translucent);
    }

    private float strengthProgress = 1f;

    /** Instance-local strength, leaving the configured target unchanged. */
    public void setStrengthProgress(float progress) {
        progress = Math.max(0f, Math.min(1f, progress));
        if (strengthProgress != progress) {
            strengthProgress = progress;
            capturePending = true;
        }
    }
    private final List<View> additionalCaptureViews = new ArrayList<>();
    private boolean dimEnabled = true;
    private boolean flipped;
    private boolean continuousUpdating;
    private boolean updateAtScreenRefreshRate;
    private boolean lastCaptureHadEdgeEffects;
    private long lastCaptureSettings = Long.MIN_VALUE;
    private final ViewTreeObserver.OnPreDrawListener drawCountListener = new ViewTreeObserver.OnPreDrawListener() {
        @Override
        public boolean onPreDraw() {
            if (continuousUpdating) {
                // HWUI can refresh child display lists without calling the
                // parent's dispatchDraw. Preserve changes before that draw
                // clears the dirty flags, then capture after the traversal.
                boolean contentDirty = captureView.isDirty();
                for (int i = 0; i < additionalCaptureViews.size(); i++) {
                    contentDirty |= additionalCaptureViews.get(i).isDirty();
                }
                if (contentDirty || captureTransform() != lastCaptureTransform
                        || hasActiveEdgeEffects() || lastCaptureHadEdgeEffects) {
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
        captureLayout = new CaptureLayout(parent.getContext());
        underSource = new BlurredBackgroundSourceColor();
        source = new BlurredBackgroundSourceRenderNode(null);
        source.setUnderSource(underSource);
        fadeView = new ChatActivityFadeView(parent.getContext());
        source.setOnProgressiveBlurReadyListener(() -> {
            if (fadeView.isAttachedToWindow() && isEnabled()) {
                capturePending = true;
                scheduleUpdate(1);
            }
        });
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
                if (isEnabled()) {
                    startContinuousUpdates();
                }
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

    private int progressiveBlurZoneTop = -1;

    /** Keep the blur profile independent of the visible fade's edge. */
    public void setProgressiveBlurZoneTop(int zone) {
        if (progressiveBlurZoneTop != zone) {
            progressiveBlurZoneTop = zone;
            capturePending = true;
        }
    }

    private float progressiveTopFraction(int height) {
        int zone = progressiveBlurZoneTop >= 0 ? progressiveBlurZoneTop : fadeZoneTop;
        int originalTransition = AndroidUtilities.dp(48);
        return zone > originalTransition
                ? Math.min(1f, (zone - originalTransition) / (float) height) : 1f;
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
        if (this.dimEnabled != dimEnabled) {
            this.dimEnabled = dimEnabled;
            capturePending = true;
        }
    }

    private int fadeViewRequestedVisibility = View.VISIBLE;

    private boolean uniformBlurAllowed;

    /** Also use the existing fixed-radius fade when progressive blur is off. */
    public void setUniformBlurAllowed(boolean allowed) {
        uniformBlurAllowed = allowed;
    }

    public boolean isEnabled() {
        return NekoConfig.progressiveFadeBlurOtherActivitiesEnabled()
                || uniformBlurAllowed && NekoConfig.fadeViewOtherActivities() && NekoConfig.blurredFadeViewEnabled();
    }

    // Applies the stock behavior when the master blur is off: loops stopped,
    // blur fade hidden so the plain rect / header shadow path draws instead.
    // Safe to call from any state; idempotent.
    public void syncState() {
        if (isEnabled()) {
            fadeView.setVisibility(fadeViewRequestedVisibility);
            if (fadeView.isAttachedToWindow() && fadeViewRequestedVisibility == View.VISIBLE) {
                startContinuousUpdates();
            }
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

    private long captureSettings(int color) {
        long hash = color;
        hash = hash * 31 + (NekoConfig.progressiveFadeBlurEnabled() ? 1 : 0);
        hash = hash * 31 + NekoConfig.progressiveFadeBlurMaxRadius;
        hash = hash * 31 + NekoConfig.blurredFadeBlurStrength;
        hash = hash * 31 + NekoConfig.progressiveFadeBlurSamples;
        hash = hash * 31 + NekoConfig.blurredFadePixelation;
        hash = hash * 31 + (dimEnabled && NekoConfig.blurredFadeDimming ? NekoConfig.blurredFadeDimStrength : 0);
        hash = hash * 31 + Float.floatToIntBits(strengthProgress);
        hash = hash * 31 + fadeZoneTop;
        hash = hash * 31 + fadeZoneBottom;
        hash = hash * 31 + progressiveBlurZoneTop;
        hash = hash * 31 + topOffset;
        hash = hash * 31 + (flipped ? 1 : 0);
        hash = hash * 31 + System.identityHashCode(backgroundCapture);
        hash = hash * 31 + System.identityHashCode(contentCapture);
        hash = hash * 31 + System.identityHashCode(captureCoordinateRoot);
        return hash;
    }

    public void invalidate() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || source.inRecording() || SizeNotifierFrameLayout.drawingBlur) {
            return;
        }
        syncState();
        if (!isEnabled() || fadeView.getVisibility() != View.VISIBLE) {
            return;
        }
        // Ignore the redraw requested by our previous capture, unless the
        // captured content itself is dirty (scrolling/animations can coincide).
        final long transform = captureTransform();
        final int color = backgroundColorProvider != null ? backgroundColorProvider.getAsInt() : background;
        final long settings = captureSettings(color);
        final boolean activeEdgeEffects = hasActiveEdgeEffects();
        boolean contentDirty = captureView.isDirty();
        for (int i = 0; i < additionalCaptureViews.size(); i++) {
            contentDirty |= additionalCaptureViews.get(i).isDirty();
        }
        if (!capturePending && settings == lastCaptureSettings && !contentDirty
                && transform == lastCaptureTransform && !activeEdgeEffects && !lastCaptureHadEdgeEffects) {
            return;
        }
        final View coordinates = captureCoordinateRoot != null ? captureCoordinateRoot : captureView;
        final int fw = coordinates.getWidth();
        final int fh = coordinates.getHeight();
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
        if (color != lastBackgroundColor) {
            lastBackgroundColor = color;
            int opaqueColor = (color & 0x00FFFFFF) | 0xFF000000;
            underSource.setColor(opaqueColor);
            fadeView.setDimColor(color);
        }
        final int pixelation = Math.max(2, NekoConfig.blurredFadePixelation);
        source.setPixelation(pixelation);
        final float topFraction = progressiveTopFraction(fh);
        final float bottomFraction = fadeZoneBottom > 0 ? Math.min(1f, fadeZoneBottom / (float) fh) : 0f;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && NekoConfig.progressiveFadeBlurEnabled()) {
            source.setProgressiveBlur(strengthProgress * AndroidUtilities.dpf2(NekoConfig.progressiveFadeBlurMaxRadius) / pixelation, Math.max(1, Math.round(fw / (float) pixelation)), Math.max(1, Math.round(fh / (float) pixelation)), topFraction, bottomFraction, NekoConfig.progressiveFadeBlurSamples);
        } else {
            source.setPlainBlur(strengthProgress * AndroidUtilities.dpf2(NekoConfig.blurredFadeBlurStrength) / pixelation);
        }
        try {
            Canvas c = source.beginRecording(fw, fh);
            try {
                c.drawColor(color);
                if (backgroundCapture != null) backgroundCapture.accept(c);
                captureContent(c, fw, fh);
            } finally {
                source.endRecording();
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        fadeView.setDim(dimEnabled && NekoConfig.blurredFadeDimming ? NekoConfig.blurredFadeDimStrength * 255 / 100 : 0);
        // Root traversal counts are not content generations: unrelated controls
        // (or this fade itself) can draw while our captured list is unchanged.
        capturePending = false;
        lastCaptureTransform = transform;
        lastCaptureHadEdgeEffects = activeEdgeEffects;
        lastCaptureSettings = settings;
        fadeView.invalidate();
        if (activeEdgeEffects && continuousUpdating) {
            // Stretch runs in HWUI without changing View translation/scale.
            // Keep the filter current until release, including the final frame.
            capturePending = true;
            scheduleUpdate(interval);
        }
    }

    private void captureContent(Canvas canvas, int width, int height) {
        if (contentCapture != null) {
            captureBounds.set(0, 0, width, height);
            contentCapture.capture(canvas, captureBounds);
        } else {
            drawCapturedView(canvas, captureView);
            for (View extra : additionalCaptureViews) drawCapturedView(canvas, extra);
        }
    }

    private boolean hasActiveEdgeEffects() {
        if (hasActiveEdgeEffects(captureView)) {
            return true;
        }
        for (int i = 0; i < additionalCaptureViews.size(); i++) {
            if (hasActiveEdgeEffects(additionalCaptureViews.get(i))) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasActiveEdgeEffects(View view) {
        if (view instanceof RecyclerListView && view.getOverScrollMode() != View.OVER_SCROLL_NEVER
                && ((RecyclerListView) view).hasActiveEdgeEffects()) {
            return true;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                if (hasActiveEdgeEffects(group.getChildAt(i))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static class CaptureLayout extends FrameLayout {
        CaptureLayout(android.content.Context context) {
            super(context);
        }

        void capture(Canvas canvas, View view) {
            // drawChild records the view's actual RenderNode, including Android
            // 12+ stretch. Calling view.draw directly records a separate copy
            // and advances EdgeEffect again instead of sharing the on-screen node.
            super.drawChild(canvas, view, SystemClock.uptimeMillis());
        }
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
        if (view.getVisibility() != View.VISIBLE || view.getAlpha() <= 0f) return;
        if (c.isHardwareAccelerated() && view instanceof RecyclerListView) {
            captureLayout.capture(c, view);
            return;
        }
        // Record page containers in their parent's coordinates explicitly.
        // The detached CaptureLayout cannot reliably apply their animated
        // RenderNode properties. Children still use their normal hardware
        // drawChild path, preserving RecyclerView stretch effects.
        c.save();
        c.translate(view.getLeft() + view.getTranslationX(), view.getTop() + view.getTranslationY());
        final float sx = view.getScaleX();
        final float sy = view.getScaleY();
        if (sx != 1f || sy != 1f) {
            c.scale(sx, sy, view.getPivotX(), view.getPivotY());
        }
        if (view.getAlpha() < 1f) {
            int layer = c.saveLayerAlpha(0, 0, view.getWidth(), view.getHeight(), Math.round(255 * view.getAlpha()));
            view.draw(c);
            c.restoreToCount(layer);
        } else {
            view.draw(c);
        }
        c.restore();
    }
}
