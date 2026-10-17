package zxc.iconic.xenon.helpers;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RecordingCanvas;
import android.graphics.Rect;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.Shader;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Choreographer;
import android.view.PixelCopy;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;

import androidx.annotation.RequiresApi;

import org.telegram.messenger.FileLog;

import zxc.iconic.xenon.NekoConfig;

/** Captures the activity window and blurs a small, cached GPU layer behind a sheet. */
@RequiresApi(Build.VERSION_CODES.S)
public final class BottomSheetBlurView extends View {
    private static final int MAX_BLUR_DIMENSION = 512;

    private final Window sourceWindow;
    private final View sourceView;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Choreographer choreographer = Choreographer.getInstance();
    private final RenderNode blurNode = new RenderNode("BottomSheetBlur");
    private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Rect bitmapBounds = new Rect();
    private ViewTreeObserver sourceObserver;
    private Bitmap frontBitmap;
    private Bitmap spareBitmap;
    private Runnable onReady;
    private int sourceWidth;
    private int sourceHeight;
    private float blurRadius;
    private float effectRadiusX = -1f;
    private float effectRadiusY = -1f;
    private boolean capturePending;
    private boolean captureScheduled;
    private boolean copyInFlight;
    private boolean stopped;
    private boolean released;

    private final ViewTreeObserver.OnDrawListener sourceDrawListener = () -> {
        if (!stopped && (frontBitmap == null || NekoConfig.blurOverlayRefresh)) {
            capturePending = true;
            scheduleCapture();
        }
    };

    private final Choreographer.FrameCallback captureFrameCallback = frameTimeNanos -> {
        captureScheduled = false;
        if (!stopped && !released && (frontBitmap == null || NekoConfig.blurOverlayRefresh)) {
            capture();
        }
    };

    public BottomSheetBlurView(Context context, Window sourceWindow) {
        super(context);
        this.sourceWindow = sourceWindow;
        sourceView = sourceWindow.getDecorView();
        // Force a layer in node coordinates: scaling its output must not turn
        // the blur back into a full-screen offscreen render target.
        blurNode.setUseCompositingLayer(true, null);
        blurNode.setPivotX(0f);
        blurNode.setPivotY(0f);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    public void start(Runnable onReady) {
        this.onReady = onReady;
        sourceObserver = sourceView.getViewTreeObserver();
        sourceObserver.addOnDrawListener(sourceDrawListener);
        capturePending = true;
        capture();
    }

    private void scheduleCapture() {
        if (stopped || released || captureScheduled || copyInFlight || !capturePending) return;
        captureScheduled = true;
        // Choreographer follows the display's actual vsync, including refresh
        // rate changes. Source draws request at most one copy for the next frame;
        // a static background never keeps this callback running.
        choreographer.postFrameCallback(captureFrameCallback);
    }

    private static int captureDownscale(int width, int height) {
        int downscale = 1 + Math.max(0, NekoConfig.blurPixelation) / 5;
        if (!NekoConfig.disableBlurBs && NekoConfig.blurOverlayRadius > 0) {
            downscale = Math.max(downscale, Math.max(4,
                    (Math.max(width, height) + MAX_BLUR_DIMENSION - 1) / MAX_BLUR_DIMENSION));
        }
        return downscale;
    }

    private void capture() {
        if (stopped || released || copyInFlight || !capturePending) return;
        int width = sourceView.getWidth();
        int height = sourceView.getHeight();
        if (width <= 0 || height <= 0 || !sourceView.isAttachedToWindow() || !sourceView.isShown()) return;
        int downscale = captureDownscale(width, height);
        int bitmapWidth = Math.max(1, (width + downscale - 1) / downscale);
        int bitmapHeight = Math.max(1, (height + downscale - 1) / downscale);
        Bitmap bitmap = spareBitmap;
        spareBitmap = null;
        if (bitmap == null || bitmap.getWidth() != bitmapWidth || bitmap.getHeight() != bitmapHeight) {
            bitmap = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888);
        }
        final Bitmap destination = bitmap;
        copyInFlight = true;
        capturePending = false;
        try {
            PixelCopy.request(sourceWindow, destination,
                    result -> onCaptureFinished(destination, width, height, result), handler);
        } catch (IllegalArgumentException e) {
            copyInFlight = false;
            spareBitmap = destination;
            stopUpdates();
            FileLog.e(e);
        }
    }

    private void onCaptureFinished(Bitmap bitmap, int width, int height, int result) {
        copyInFlight = false;
        if (released || stopped) return;
        if (result == PixelCopy.SUCCESS) {
            // PixelCopy writes only into the spare buffer, never the displayed
            // one. Record the replacement before allowing another capture.
            spareBitmap = frontBitmap;
            frontBitmap = bitmap;
            sourceWidth = width;
            sourceHeight = height;
            ViewGroup.LayoutParams params = getLayoutParams();
            if (params != null && params.height != height) {
                params.height = height;
                setLayoutParams(params);
            }
            blurNode.setPosition(0, 0, bitmap.getWidth(), bitmap.getHeight());
            RecordingCanvas recording = blurNode.beginRecording();
            recording.drawBitmap(bitmap, 0f, 0f, bitmapPaint);
            blurNode.endRecording();
            updateNodeScale();
            updateBlurEffect();
            invalidate();
            if (onReady != null) {
                Runnable callback = onReady;
                onReady = null;
                callback.run();
            }
        } else {
            spareBitmap = bitmap;
            if (result == PixelCopy.ERROR_SOURCE_INVALID || result == PixelCopy.ERROR_DESTINATION_INVALID) {
                stopUpdates();
            }
        }
        // Preserve changes made while PixelCopy was outstanding, including the
        // final frame of an animation. Idle windows schedule no captures.
        if (NekoConfig.blurOverlayRefresh) scheduleCapture();
    }

    public void setBlurRadius(float radius) {
        blurRadius = Math.max(0f, radius);
        updateBlurEffect();
    }

    public float getBlurRadius() {
        return blurRadius;
    }

    private void updateBlurEffect() {
        if (frontBitmap == null || released) return;
        float radiusX = blurRadius * frontBitmap.getWidth() / sourceWidth;
        float radiusY = blurRadius * frontBitmap.getHeight() / sourceHeight;
        if (radiusX == effectRadiusX && radiusY == effectRadiusY) return;
        effectRadiusX = radiusX;
        effectRadiusY = radiusY;
        blurNode.setRenderEffect(radiusX > 0f && radiusY > 0f
                ? RenderEffect.createBlurEffect(radiusX, radiusY, Shader.TileMode.CLAMP) : null);
        invalidate();
    }

    private void updateNodeScale() {
        if (frontBitmap == null) return;
        blurNode.setScaleX((float) (getWidth() > 0 ? getWidth() : sourceWidth) / frontBitmap.getWidth());
        blurNode.setScaleY((float) (getHeight() > 0 ? getHeight() : sourceHeight) / frontBitmap.getHeight());
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        updateNodeScale();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (frontBitmap == null || released) return;
        if (canvas.isHardwareAccelerated()) {
            canvas.drawRenderNode(blurNode);
        } else {
            bitmapBounds.set(0, 0, getWidth(), getHeight());
            canvas.drawBitmap(frontBitmap, null, bitmapBounds, bitmapPaint);
        }
    }

    public void stopUpdates() {
        stopped = true;
        onReady = null;
        capturePending = false;
        captureScheduled = false;
        choreographer.removeFrameCallback(captureFrameCallback);
        if (sourceObserver != null && sourceObserver.isAlive()) {
            sourceObserver.removeOnDrawListener(sourceDrawListener);
        }
        sourceObserver = null;
    }

    public void release() {
        stopUpdates();
        released = true;
        blurNode.discardDisplayList();
        blurNode.setRenderEffect(null);
        // HWUI may still hold an older display list. Let Bitmap's native
        // references release the buffers instead of recycling a drawn bitmap.
        frontBitmap = null;
        spareBitmap = null;
    }

    @Override
    protected void onDetachedFromWindow() {
        release();
        super.onDetachedFromWindow();
    }
}
