package org.telegram.ui.Components;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.os.Build;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.Utilities;

import zxc.iconic.xenon.NekoConfig;

/** Full-screen avatar reflection. Geometry is in the profile root's coordinates. */
public final class ProfileBackdrop {
    private final Paint imagePaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint fadePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fixedFadePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint titleMaskPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float lastTitleTop = Float.NaN;
    private float lastMaskCollapse = Float.NaN;
    private float lastFixedStart = Float.NaN;
    private int lastFixedHeight, lastFixedColor;
    private final Paint blurMaskPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float lastBlurStart = Float.NaN;
    private final RectF destination = new RectF();
    private final android.graphics.Matrix imageMatrix = new android.graphics.Matrix();
    private android.graphics.BitmapShader imageShader;
    private Bitmap shaderImage;
    private RenderNode node, resultNode;
    private final Paint clearPaint = new Paint();
    private int viewportHeight;
    private boolean progressiveEffectApplied;
    private int effectWidth = -1, effectHeight = -1;
    private float effectStart = Float.NaN, effectMinimum = Float.NaN, effectMaximum = Float.NaN;
    private float regularRadius = Float.NaN;
    private float photoHeight;
    private final RectF backgroundPhotoBounds = new RectF();
    private float manualAppearanceAlpha = -1f;
    private boolean appearancePending;

    public void setBackgroundPhotoBounds(float left, float top, float width, float height) {
        backgroundPhotoBounds.set(left, top, left + width, top + height);
    }

    public boolean needsAppearanceAnimation() {
        return appearancePending;
    }

    public void setAppearanceOpacity(float alpha) {
        appearancePending = false;
        manualAppearanceAlpha = Math.max(0, Math.min(1, alpha));
    }

    public float getAppearanceOpacity() {
        return frameAppearanceAlpha;
    }
    private float frameAppearanceAlpha;
    private float sharedAppearanceAlpha = Float.NaN;
    private long disappearanceStart = -1;
    private float disappearanceFrom = 1f;

    private RuntimeShader horizontal, vertical;
    private boolean shaderFailed;
    private Bitmap fallback, fallbackSource;
    private int fallbackGeneration;
    private int lastFadeHeight, lastFadeColor, lastFadeAlpha = -1, lastFadeBottomAlpha = -1;
    private long appearanceStart = -1;


    public void setPhotoHeight(float height) {
        photoHeight = height;
    }

    public void syncAppearanceFrom(ProfileBackdrop source) {
        sharedAppearanceAlpha = source.frameAppearanceAlpha;
    }

    public boolean hasAppearanceStarted() {
        return appearanceStart >= 0;
    }

    public void startAppearance() {
        appearanceStart = android.view.animation.AnimationUtils.currentAnimationTimeMillis();
        disappearanceStart = -1;
        frameAppearanceAlpha = 0;
        manualAppearanceAlpha = 1f / 255f;
        appearancePending = true;
    }

    public void showImmediately() {
        appearanceStart = android.view.animation.AnimationUtils.currentAnimationTimeMillis() - 300;
        disappearanceStart = -1;
        frameAppearanceAlpha = 1;
        manualAppearanceAlpha = -1;
        appearancePending = false;
    }

    public void cancelDisappearance() {
        disappearanceStart = -1;
    }

    public void startDisappearance() {
        manualAppearanceAlpha = -1;
        appearancePending = false;
        disappearanceFrom = frameAppearanceAlpha;
        disappearanceStart = android.view.animation.AnimationUtils.currentAnimationTimeMillis();
    }

    public void resetAppearance() {
        appearanceStart = -1;
        disappearanceStart = -1;
        sharedAppearanceAlpha = Float.NaN;
        manualAppearanceAlpha = -1;
        appearancePending = false;
        frameAppearanceAlpha = 0;
    }

    public boolean isAppearing() {
        if (manualAppearanceAlpha >= 0) return manualAppearanceAlpha < 1f;
        long now = android.view.animation.AnimationUtils.currentAnimationTimeMillis();
        return disappearanceStart >= 0 ? now - disappearanceStart < 300
                : appearanceStart >= 0 && now - appearanceStart < 300;
    }

    public void drawCached(Canvas canvas) {
        if (Build.VERSION.SDK_INT >= 29 && canvas.isHardwareAccelerated()
                && resultNode != null && resultNode.hasDisplayList()) canvas.drawRenderNode(resultNode);
    }

    public void maskTitleBlur(Canvas canvas, int width, float bottom, float titleTop) {
        if (lastTitleTop != titleTop) {
            lastTitleTop = titleTop;
            titleMaskPaint.setShader(new LinearGradient(0, titleTop, 0, titleTop + AndroidUtilities.dp(32),
                    0x00ffffff, 0xffffffff, Shader.TileMode.CLAMP));
            titleMaskPaint.setXfermode(new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN));
        }
        // Mask after filtering the whole frame. A clip before the image filter
        // changes its input origin and exposes unfiltered damage rectangles.
        clearPaint.setXfermode(new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR));
        canvas.drawRect(0, 0, width, titleTop, clearPaint);
        canvas.drawRect(0, bottom, width, viewportHeight, clearPaint);
        canvas.drawRect(0, titleTop, width, bottom, titleMaskPaint);
    }

    public void draw(Canvas canvas, Bitmap image, int width, int height, float squareBottom,
                     float blurStart, float collapse, float scroll, int background) {
        if (width <= 0 || height <= 0) return;
        viewportHeight = height;
        if (Build.VERSION.SDK_INT >= 29 && canvas.isHardwareAccelerated()) {
            // Record independently of the caller's clip. The glass capture and
            // screen replay the same completed background for this frame.
            if (resultNode == null) {
                resultNode = new RenderNode("profileBackdropResult");
                resultNode.setUseCompositingLayer(true, null);
            }
            resultNode.setPosition(0, 0, width, height);
            Canvas recording = resultNode.beginRecording();
            drawInternal(recording, image, width, height, squareBottom, blurStart, collapse, scroll, background);
            resultNode.endRecording();
            canvas.drawRenderNode(resultNode);
        } else {
            drawInternal(canvas, image, width, height, squareBottom, blurStart, collapse, scroll, background);
        }
    }

    private void drawInternal(Canvas canvas, Bitmap image, int width, int height, float squareBottom,
                              float blurStart, float collapse, float scroll, int background) {
        if (width <= 0 || height <= 0) return;
        boolean hasImage = image != null && !image.isRecycled();
        final float hidden = Utilities.clamp01(scroll);
        final int fadeAlpha = Math.round(255 * (0.5f * collapse + 0.25f * hidden));
        final int fadeBottomAlpha = Math.round(255 * AndroidUtilities.lerp(0.5f, 1f, collapse));
        long now = android.view.animation.AnimationUtils.currentAnimationTimeMillis();
        float appearanceProgress = appearanceStart < 0 ? 0 : Math.min(1f, (now - appearanceStart) / 300f);
        float appearanceAlpha = CubicBezierInterpolator.EASE_OUT_QUINT.getInterpolation(appearanceProgress);
        if (disappearanceStart >= 0) {
            float closingProgress = Math.min(1f, (now - disappearanceStart) / 300f);
            appearanceAlpha = disappearanceFrom * (1f - CubicBezierInterpolator.EASE_OUT_QUINT.getInterpolation(closingProgress));
        }
        if (manualAppearanceAlpha >= 0) appearanceAlpha = manualAppearanceAlpha;
        if (!Float.isNaN(sharedAppearanceAlpha)) appearanceAlpha = sharedAppearanceAlpha;
        frameAppearanceAlpha = appearanceAlpha;
        int appearanceLayer = appearanceAlpha < 1f
                ? canvas.saveLayerAlpha(0, 0, width, height, Math.round(255 * appearanceAlpha)) : -1;
        blurStart = Math.max(0, Math.min(height - 1, blurStart));
        if (hasImage) {
            drawImage(canvas, image, width, height, squareBottom);
            if (lastBlurStart != blurStart || lastMaskCollapse != collapse) {
                lastBlurStart = blurStart;
                lastMaskCollapse = collapse;
                float feather = AndroidUtilities.dp(32);
                blurMaskPaint.setShader(new LinearGradient(0, blurStart - feather * collapse,
                        0, blurStart + feather * (1f - collapse),
                        0x00ffffff, 0xffffffff, Shader.TileMode.CLAMP));
                blurMaskPaint.setXfermode(new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN));
            }
            final int blurLayer = canvas.saveLayer(0, 0, width, height, null);
            if (Build.VERSION.SDK_INT >= 31 && canvas.isHardwareAccelerated()) {
                drawHardware(canvas, image, width, height, squareBottom, blurStart, collapse, hidden);
            } else {
                drawSoftware(canvas, image, width, height, squareBottom);
            }
            canvas.drawRect(0, 0, width, height, blurMaskPaint);
            canvas.restoreToCount(blurLayer);
        }
        // This lower fade never moves with the foreground avatar or the list.
        if (lastFixedStart != squareBottom || lastFixedHeight != height || lastFixedColor != background) {
            lastFixedStart = squareBottom;
            lastFixedHeight = height;
            lastFixedColor = background;
            fixedFadePaint.setShader(new LinearGradient(0, Math.min(squareBottom, height - 1), 0, height,
                    background & 0x00ffffff, background | 0xff000000, Shader.TileMode.CLAMP));
        }
        canvas.drawRect(0, Math.min(squareBottom, height - 1), width, height, fixedFadePaint);
        // The full-screen fade never changes its bounds. Only its endpoint
        // opacities interpolate: expanded 0/50, circular 50/100, hidden 75/100.
        if (lastFadeHeight != height || lastFadeColor != background
                || lastFadeAlpha != fadeAlpha || lastFadeBottomAlpha != fadeBottomAlpha) {
            lastFadeHeight = height;
            lastFadeColor = background;
            lastFadeAlpha = fadeAlpha;
            lastFadeBottomAlpha = fadeBottomAlpha;
            fadePaint.setShader(new LinearGradient(0, 0, 0, height,
                    (background & 0x00ffffff) | (fadeAlpha << 24),
                    (background & 0x00ffffff) | (fadeBottomAlpha << 24), Shader.TileMode.CLAMP));
        }
        canvas.drawRect(0, 0, width, height, fadePaint);
        if (appearanceLayer >= 0) canvas.restoreToCount(appearanceLayer);
    }

    private void drawImage(Canvas canvas, Bitmap image, int width, int height, float bottom) {
        if (photoHeight > 0) {
            // The foreground photo stretches during overscroll. Its effects
            // must use that photo's crop, never the stationary square's crop.
            if (shaderImage != image) {
                shaderImage = image;
                imageShader = new android.graphics.BitmapShader(image, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            }
            float scale = Math.max(width / (float) image.getWidth(), photoHeight / image.getHeight());
            imageMatrix.setScale(scale, scale);
            imageMatrix.postTranslate((width - image.getWidth() * scale) / 2f,
                    (photoHeight - image.getHeight() * scale) / 2f);
            imageShader.setLocalMatrix(imageMatrix);
            imagePaint.setShader(imageShader);
            // Keep the filter input opaque past the photo's visible edge;
            // otherwise its kernel exposes a sharp/transparent horizontal strip.
            canvas.drawRect(0, 0, width, height, imagePaint);
            imagePaint.setShader(null);
            return;
        }
        float left = backgroundPhotoBounds.isEmpty() ? 0 : backgroundPhotoBounds.left;
        float top = backgroundPhotoBounds.isEmpty() ? bottom - width : backgroundPhotoBounds.top;
        float photoWidth = backgroundPhotoBounds.isEmpty() ? width : backgroundPhotoBounds.width();
        float photoBottom = backgroundPhotoBounds.isEmpty() ? bottom : backgroundPhotoBounds.bottom;
        float photoH = photoBottom - top;
        if (shaderImage != image) {
            shaderImage = image;
            imageShader = new android.graphics.BitmapShader(image, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        }
        float scale = Math.max(photoWidth / image.getWidth(), photoH / image.getHeight());
        imageMatrix.setScale(scale, scale);
        imageMatrix.postTranslate(left + (photoWidth - image.getWidth() * scale) / 2f,
                top + (photoH - image.getHeight() * scale) / 2f);
        imageShader.setLocalMatrix(imageMatrix);
        imagePaint.setShader(imageShader);
        destination.set(left, top, left + photoWidth, photoBottom);
        canvas.drawRect(destination, imagePaint);
        if (height > photoBottom && photoH > 0) {
            canvas.save();
            canvas.translate(0, photoBottom);
            canvas.scale(1, -(height - photoBottom) / photoH);
            canvas.translate(0, -photoBottom);
            canvas.drawRect(destination, imagePaint);
            canvas.restore();
        }
        imagePaint.setShader(null);
    }

    @androidx.annotation.RequiresApi(31)
    private void drawHardware(Canvas canvas, Bitmap image, int width, int height, float bottom,
                              float blurStart, float collapse, float scroll) {
        if (node == null) {
            node = new RenderNode("profileBackdrop");
            node.setUseCompositingLayer(true, null);
        }
        // Filtering a downsampled source keeps full-screen progressive blur affordable.
        final float scale = Math.max(2, NekoConfig.blurredFadePixelation);
        int w = Math.max(1, (int) Math.ceil(width / scale));
        int h = Math.max(1, (int) Math.ceil(height / scale));
        node.setPosition(0, 0, w, h);
        Canvas recording = node.beginRecording();
        recording.scale(1 / scale, 1 / scale);
        drawImage(recording, image, width, height, bottom);
        node.endRecording();
        boolean progressive = Build.VERSION.SDK_INT >= 33 && NekoConfig.progressiveFadeBlurEnabled() && !shaderFailed;
        if (progressive) {
            try {
                applyProgressive(w, h, blurStart / scale, (7 * collapse + 3 * scroll) * AndroidUtilities.density / scale,
                        3f * Math.max(10, NekoConfig.progressiveFadeBlurMaxRadius) * AndroidUtilities.density / scale);
            } catch (RuntimeException e) {
                shaderFailed = true;
                FileLog.e(e);
                progressive = false;
            }
        }
        if (!progressive) {
            float radius = AndroidUtilities.dpf2(NekoConfig.blurredFadeBlurStrength) / scale;
            if (progressiveEffectApplied || regularRadius != radius) {
                node.setRenderEffect(radius > 0f
                        ? RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP) : null);
                regularRadius = radius;
            }
            progressiveEffectApplied = false;
        }
        canvas.save();
        canvas.scale(scale, scale);
        canvas.drawRenderNode(node);
        canvas.restore();
    }

    @androidx.annotation.RequiresApi(33)
    private void applyProgressive(int width, int height, float start, float minimum, float maximum) {
        maximum = Math.max(minimum, maximum);
        if (progressiveEffectApplied && effectWidth == width && effectHeight == height
                && effectStart == start && effectMinimum == minimum && effectMaximum == maximum) return;
        if (horizontal == null) {
            String shader = "uniform shader image; uniform float2 direction; uniform float2 size;"
                    + "uniform float start; uniform float minimum; uniform float maximum;"
                    + "half4 main(float2 p) { float t = clamp((p.y-start)/max(1.0,size.y-start),0.0,1.0);"
                    + "float r = mix(minimum,maximum,t); half4 c = half4(0.0);"
                    + "for (int i = -5; i <= 5; ++i) { float x = float(i);"
                    + "float weight = exp(-x*x/12.5);"
                    + "c += image.eval(clamp(p+direction*r*x/5.0,float2(0.5),size-float2(0.5)))*weight; }"
                    + "return c / 6.09878; }";
            horizontal = new RuntimeShader(shader);
            vertical = new RuntimeShader(shader);
            horizontal.setFloatUniform("direction", 1, 0);
            vertical.setFloatUniform("direction", 0, 1);
        }
        for (RuntimeShader shader : new RuntimeShader[]{horizontal, vertical}) {
            shader.setFloatUniform("size", width, height);
            shader.setFloatUniform("start", start);
            shader.setFloatUniform("minimum", minimum);
            shader.setFloatUniform("maximum", Math.max(minimum, maximum));
        }
        node.setRenderEffect(RenderEffect.createChainEffect(
                RenderEffect.createRuntimeShaderEffect(vertical, "image"),
                RenderEffect.createRuntimeShaderEffect(horizontal, "image")));
        effectWidth = width;
        effectHeight = height;
        effectStart = start;
        effectMinimum = minimum;
        effectMaximum = maximum;
        progressiveEffectApplied = true;
    }

    private void drawSoftware(Canvas canvas, Bitmap image, int width, int height, float bottom) {
        if (fallbackSource != image || fallbackGeneration != image.getGenerationId()) {
            if (fallback == null) fallback = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888);
            Canvas small = new Canvas(fallback);
            imagePaint.setShader(null);
            int side = Math.min(image.getWidth(), image.getHeight());
            int left = (image.getWidth() - side) / 2;
            int top = (image.getHeight() - side) / 2;
            small.drawBitmap(image, new android.graphics.Rect(left, top, left + side, top + side),
                    new RectF(0, 0, 64, 64), imagePaint);
            Utilities.stackBlurBitmap(fallback, 6);
            fallbackSource = image;
            fallbackGeneration = image.getGenerationId();
        }
        drawImage(canvas, fallback, width, height, bottom);
    }

    public void destroy() {
        if (Build.VERSION.SDK_INT >= 29) {
            if (node != null) node.discardDisplayList();
            if (resultNode != null) resultNode.discardDisplayList();
        }
        node = resultNode = null;
        progressiveEffectApplied = false;
        regularRadius = Float.NaN;
        if (fallback != null) fallback.recycle();
        fallback = fallbackSource = null;
        imageShader = null;
        shaderImage = null;
        imagePaint.setShader(null);
    }
}
