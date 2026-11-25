package org.telegram.utils.glass.source;

import android.graphics.Canvas;
import android.graphics.RecordingCanvas;
import android.graphics.RectF;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.RuntimeShader;
import org.telegram.messenger.FileLog;
import org.telegram.ui.Components.blur3.GlassShaderCache;
import android.graphics.Shader;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import org.telegram.ui.Components.blur3.DownscaleScrollableNoiseSuppressor;
import org.telegram.ui.Components.blur3.BlurredBackgroundDrawableViewFactory;
import org.telegram.ui.Components.blur3.RenderNodeWithHash;
import org.telegram.ui.Components.blur3.drawable.BlurredBackgroundDrawable;
import org.telegram.utils.glass.GlassCaptureSourceCanvas;
import org.telegram.utils.glass.fade.GlassFadeBlurredDrawable;

import me.vkryl.core.reference.ReferenceList;

@RequiresApi(api = Build.VERSION_CODES.Q)
public final class GlassSourceRenderNode implements GlassSource, BlurredBackgroundDrawableViewFactory.DrawableOwner, GlassFadeBlurredDrawable.GlassFadeDownsampledSource {
    private final GlassSource fallbackSource;
    private final RenderNode renderNode;
    private RenderNodeWithHash renderNodeWithHash;

    private DownscaleScrollableNoiseSuppressor scrollableNoiseSuppressor;
    private int scrollableNoiseSuppressorIndex;
    public GlassSource underSource;

    private GlassSource glassCaptureSourceCanvas;

    private boolean noClip;

    public GlassSourceRenderNode(GlassSource fallbackSource) {
        this.fallbackSource = fallbackSource;

        renderNode = new RenderNode(null);
    }

    public void setupRenderer(RenderNodeWithHash.Renderer renderer) {
        if (renderNodeWithHash == null) {
            renderNodeWithHash = new RenderNodeWithHash(renderNode, renderer);
        }
    }

    public void updateDisplayListIfNeeded() {
        renderNodeWithHash.updateDisplayListIfNeeded();
    }

    public void invalidateRenderer() {
        renderNodeWithHash.invalidate();
    }

    public void setSize(int width, int height) {
        renderNode.setPosition(0, 0, width, height);
    }

    public void setScrollableNoiseSuppressor(DownscaleScrollableNoiseSuppressor scrollableNoiseSuppressor, int index) {
        this.scrollableNoiseSuppressor = scrollableNoiseSuppressor;
        this.scrollableNoiseSuppressorIndex = index;
    }

    @RequiresApi(api = Build.VERSION_CODES.S)
    public void setGlassCaptureSourceCanvas(GlassCaptureSourceCanvas glassCaptureSourceCanvas, GlassCaptureSourceCanvas.SourceIndex sourceIndex) {
        this.glassCaptureSourceCanvas = glassCaptureSourceCanvas.getSource(sourceIndex);
    }

    public void setUnderSource(GlassSource underSource) {
        this.underSource = underSource;
    }

    @RequiresApi(api = Build.VERSION_CODES.S)
    public void setBlur(float radius) {
        renderNode.setRenderEffect(radius > 0 ? RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP) : null);
    }

    @RequiresApi(api = Build.VERSION_CODES.S)
    public void setBlur(float radius, RenderEffect effect) {
        renderNode.setRenderEffect(RenderEffect.createChainEffect(RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP), effect));
    }



    public void noClip() {
        this.noClip = true;
    }

    private boolean inRecording;
    private RecordingCanvas recordingCanvas;

    public boolean needUpdateDisplayList(int width, int height) {
        return !renderNode.hasDisplayList() || renderNode.getWidth() != width || renderNode.getHeight() != height;
    }

    public RecordingCanvas beginRecording(int width, int height) {
        if (inRecording) {
            throw new IllegalStateException();
        }

        inRecording = true;

        renderNode.setPosition(0, 0, width, height);
        recordingCanvas = renderNode.beginRecording(width, height);
        return recordingCanvas;
    }

    public void endRecording() {
        if (!inRecording) {
            throw new IllegalStateException();
        }

        renderNode.endRecording();
        inRecording = false;
        recordingCanvas = null;
    }

    public boolean isRecordingCanvas(Canvas canvas) {
        return canvas != null && canvas == recordingCanvas;
    }

    public boolean inRecording() {
        return inRecording;
    }

    @Override
    public void draw(@NonNull Canvas canvas, float left, float top, float right, float bottom) {
        if (!canvas.isHardwareAccelerated()) {
            if (fallbackSource != null) {
                fallbackSource.draw(canvas, left, top, right, bottom);
            }
            return;
        }

        if (inRecording) {
            throw new IllegalStateException();
        }

        if (underSource != null) {
            underSource.draw(canvas, left, top, right, bottom);
        }
        canvas.save();
        if (!noClip) {
            canvas.clipRect(left, top, right, bottom);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && glassCaptureSourceCanvas != null) {
            glassCaptureSourceCanvas.draw(canvas, left, top, right, bottom);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && scrollableNoiseSuppressor != null) {
            scrollableNoiseSuppressor.drawInline(canvas, scrollableNoiseSuppressorIndex);
        } else {
            canvas.drawRenderNode(renderNode);
        }

        canvas.restore();
    }

    /* Downsampled, for the lens */

    @Nullable
    private GlassFadeBlurredDrawable.GlassFadeDownsampledSource getDownsampledCapture() {
        return glassCaptureSourceCanvas instanceof GlassFadeBlurredDrawable.GlassFadeDownsampledSource
            ? (GlassFadeBlurredDrawable.GlassFadeDownsampledSource) glassCaptureSourceCanvas : null;
    }

    @Override
    public boolean isDownsampledSourceSupported() {
        return getDownsample() > 1;
    }

    @Override
    public int getDownsample() {
        final GlassFadeBlurredDrawable.GlassFadeDownsampledSource capture = getDownsampledCapture();
        if (capture != null) {
            return capture.getDownsample();
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && scrollableNoiseSuppressor != null) {
            return scrollableNoiseSuppressor.getDownsample(scrollableNoiseSuppressorIndex);
        }
        return 1;
    }

    @Override
    public int getDownsampleOffsetX() {
        final GlassFadeBlurredDrawable.GlassFadeDownsampledSource capture = getDownsampledCapture();
        return capture != null ? capture.getDownsampleOffsetX() : 0;
    }

    @Override
    public int getDownsampleOffsetY() {
        final GlassFadeBlurredDrawable.GlassFadeDownsampledSource capture = getDownsampledCapture();
        return capture != null ? capture.getDownsampleOffsetY() : 0;
    }

    @Override
    public void drawDownsampled(@NonNull Canvas canvas, @NonNull RectF boundsRelativeRoot, int targetDownsample, float targetOriginX, float targetOriginY) {
        final GlassFadeBlurredDrawable.GlassFadeDownsampledSource capture = getDownsampledCapture();
        if (underSource != null || capture == null) {
            canvas.save();
            canvas.scale(1f / targetDownsample, 1f / targetDownsample);
            canvas.translate(-targetOriginX, -targetOriginY);
            if (underSource != null) {
                // the node goes beyond the bounds by its padding
                final int outset = targetDownsample * 2;
                underSource.draw(canvas, boundsRelativeRoot.left - outset, boundsRelativeRoot.top - outset, boundsRelativeRoot.right + outset, boundsRelativeRoot.bottom + outset);
            }
            if (capture == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && scrollableNoiseSuppressor != null) {
                scrollableNoiseSuppressor.drawInline(canvas, scrollableNoiseSuppressorIndex);
            }
            canvas.restore();
        }
        if (capture != null) {
            capture.drawDownsampled(canvas, boundsRelativeRoot, targetDownsample, targetOriginX, targetOriginY);
        }
    }


    // ---- Xenon extensions ----
    private static final ThreadLocal<Canvas> unblurredCanvas = new ThreadLocal<>();
    private float pixelationScale = 1f;
    private float lastBlurRadius = -1f;
    private RenderEffect lastBlurEffect;
    private boolean glassBlurEnabled;
    private float standardGlassBlurRadius;
    private RenderEffect standardGlassBlurEffect;

    public static boolean isUnblurredCanvas(Canvas canvas) {
        return canvas == unblurredCanvas.get();
    }

    @Override
    public void drawUnblurred(Canvas canvas, float left, float top, float right, float bottom) {
        Canvas previous = unblurredCanvas.get();
        unblurredCanvas.set(canvas);
        try {
            draw(canvas, left, top, right, bottom);
        } finally {
            if (previous == null) unblurredCanvas.remove();
            else unblurredCanvas.set(previous);
        }
    }

    public void setPixelation(float scale) {
        this.pixelationScale = Math.max(1f, scale);
    }

    @Override
    public void prepareToDraw() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && glassBlurEnabled) syncGlassBlur();
    }

    /** Companion glass/frosted sources shared a wallpaper layer in the old pipeline; the new engine captures it itself. */
    public void shareWallpaperWith(GlassSourceRenderNode owner) {
    }

    public void setWallpaperSize(int width, int height) {
    }

    public void invalidateWallpaper() {
        invalidateDisplayListForDrawables();
    }

    @RequiresApi(api = Build.VERSION_CODES.S)
    public void setPlainBlur(float radius) {
        setPlainBlur(radius, null);
    }

    @RequiresApi(api = Build.VERSION_CODES.S)
    public void setPlainBlur(float radius, RenderEffect effect) {
        glassBlurEnabled = false;
        applyBlur(radius, effect);
    }

    @RequiresApi(api = Build.VERSION_CODES.S)
    public void setGlassBlur(float standardRadius) {
        setGlassBlur(standardRadius, null);
    }

    @RequiresApi(api = Build.VERSION_CODES.S)
    public void setGlassBlur(float standardRadius, RenderEffect standardEffect) {
        glassBlurEnabled = true;
        standardGlassBlurRadius = standardRadius;
        standardGlassBlurEffect = standardEffect;
        syncGlassBlur();
    }

    @RequiresApi(api = Build.VERSION_CODES.S)
    private void syncGlassBlur() {
        if (!org.telegram.messenger.SharedConfig.chatBlurEnabled()) {
            applyBlur(0f, null);
            return;
        }
        if (org.telegram.messenger.LiteMode.isEnabled(org.telegram.messenger.LiteMode.FLAG_LIQUID_GLASS)
                && zxc.iconic.xenon.NekoConfig.isAdvancedGlassEnabled()) {
            applyBlur(org.telegram.messenger.AndroidUtilities.dpf2(zxc.iconic.xenon.NekoConfig.getGlassBlurRadiusDp()), null);
        } else {
            applyBlur(standardGlassBlurRadius, standardGlassBlurEffect);
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.S)
    private void applyBlur(float radius, RenderEffect effect) {
        if (lastBlurRadius == radius && lastBlurEffect == effect) return;
        progressiveEffectApplied = false;
        lastBlurRadius = radius;
        lastBlurEffect = effect;
        RenderEffect result = radius > 0 ? RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP) : null;
        if (effect != null) {
            result = result != null ? RenderEffect.createChainEffect(result, effect) : effect;
        }
        renderNode.setRenderEffect(result);
    }

    private RuntimeShader progressiveShader;
    private RuntimeShader progressiveVerticalShader;
    private RenderEffect progressiveEffect;
    private boolean progressiveEffectApplied;
    private boolean progressiveShaderFailed;
    private int progressiveShaderSamples = -1;
    private float progressiveMaxRadius = -1f;
    private int progressiveWidth = -1;
    private int progressiveHeight = -1;
    private float progressiveFadeZoneTopFraction = -1f;
    private float progressiveFadeZoneBottomFraction = -1f;
    private int progressiveSamples = -1;
    private Runnable onProgressiveBlurReadyListener;
    private final Runnable onProgressiveShadersReady = () -> {
        progressiveEffectApplied = false;
        if (onProgressiveBlurReadyListener != null) onProgressiveBlurReadyListener.run();
    };

    public void setOnProgressiveBlurReadyListener(Runnable listener) {
        onProgressiveBlurReadyListener = listener;
    }

    // Two materialized image-filter passes replace the N*N kernel with 2*N
    // taps. Gaussian weights are normalized on the CPU only when quality changes;
    // the GPU does no exp(), weight summation or division per pixel. For a varying
    // radius this is a separable approximation: the vertical pass samples rows
    // already blurred at their own radius, preserving a smooth progressive edge.
    private static String buildProgressiveBlurShader(int samples) {
        final int h = Math.max(1, Math.min(12, samples / 2));
        final double gaussK = 2.0 / (h * h);
        double totalWeight = 0;
        for (int i = -h; i <= h; i++) {
            totalWeight += Math.exp(-i * i * gaussK);
        }
        StringBuilder shader = new StringBuilder(4096);
        shader.append(
                "uniform shader inputTexture;\n" +
                "uniform float maxRadius;\n" +
                "uniform float2 textureSize;\n" +
                "uniform float2 direction;\n" +
                "uniform float fadeZoneTopFraction;\n" +
                "uniform float fadeZoneBottomFraction;\n" +
                "half4 main(float2 coord) {\n" +
                "    float t = coord.y / textureSize.y;\n" +
                "    float radius = 0.0;\n" +
                "    if (fadeZoneTopFraction > 0.0 && t < fadeZoneTopFraction) {\n" +
                "        radius = maxRadius * (1.0 - t / fadeZoneTopFraction);\n" +
                "    } else if (fadeZoneBottomFraction > 0.0 && t > 1.0 - fadeZoneBottomFraction) {\n" +
                "        radius = maxRadius * ((t - (1.0 - fadeZoneBottomFraction)) / fadeZoneBottomFraction);\n" +
                "    }\n" +
                "    if (radius < 0.5) return inputTexture.eval(coord);\n" +
                "    float2 step = direction * radius / " + h + ".0;\n" +
                "    half4 result = half4(0.0);\n");
        for (int i = -h; i <= h; i++) {
            shader.append("    result += inputTexture.eval(clamp(coord + step * ")
                    .append(i).append(".0, float2(0.5), textureSize - float2(0.5))) * ")
                    .append((float) (Math.exp(-i * i * gaussK) / totalWeight)).append(";\n");
        }
        return shader.append("    return result;\n}").toString();
    }

    @RequiresApi(api = Build.VERSION_CODES.TIRAMISU)
    public void setProgressiveBlur(float maxRadius, int sourceWidth, int sourceHeight, float fadeZoneTopFraction, float fadeZoneBottomFraction, int samples) {
        if (sourceWidth <= 0 || sourceHeight <= 0) return;
        final float topFraction = Math.max(0f, Math.min(1f, fadeZoneTopFraction));
        final float bottomFraction = Math.max(0f, Math.min(1f, fadeZoneBottomFraction));
        if (maxRadius <= 0f || (topFraction == 0f && bottomFraction == 0f)) {
            setBlur(0f);
            return;
        }
        final int sampleCount = Math.max(3, Math.min(25, samples | 1));
        if (progressiveEffectApplied && progressiveMaxRadius == maxRadius && progressiveWidth == sourceWidth && progressiveHeight == sourceHeight && progressiveFadeZoneTopFraction == topFraction && progressiveFadeZoneBottomFraction == bottomFraction && progressiveSamples == sampleCount) return;
        progressiveMaxRadius = maxRadius;
        progressiveWidth = sourceWidth;
        progressiveHeight = sourceHeight;
        progressiveFadeZoneTopFraction = topFraction;
        progressiveFadeZoneBottomFraction = bottomFraction;
        progressiveSamples = sampleCount;
        if (progressiveShaderFailed) {
            setBlur(maxRadius);
            return;
        }
        if (progressiveShader == null || progressiveVerticalShader == null || progressiveShaderSamples != sampleCount) {
            try {
                if (progressiveShaderSamples != sampleCount) {
                    progressiveShader = progressiveVerticalShader = null;
                    progressiveShaderSamples = sampleCount;
                }
                if (progressiveShader == null) progressiveShader = GlassShaderCache.acquireProgressive(
                        sampleCount, () -> buildProgressiveBlurShader(sampleCount), onProgressiveShadersReady);
                if (progressiveVerticalShader == null) progressiveVerticalShader = GlassShaderCache.acquireProgressive(
                        sampleCount, () -> buildProgressiveBlurShader(sampleCount), onProgressiveShadersReady);
                if (progressiveShader == null || progressiveVerticalShader == null) {
                    setBlur(maxRadius);
                    return;
                }
                progressiveShader.setFloatUniform("direction", 1f, 0f);
                progressiveVerticalShader.setFloatUniform("direction", 0f, 1f);
            } catch (RuntimeException e) {
                // A shader compile error must never crash the app (e.g. the
                // abs(int) AGSL crash): fall back to the platform blur.
                FileLog.e(e);
                progressiveShaderFailed = true;
                setBlur(maxRadius);
                return;
            }
        }
        updateProgressiveUniforms(progressiveShader, maxRadius, sourceWidth, sourceHeight, topFraction, bottomFraction);
        updateProgressiveUniforms(progressiveVerticalShader, maxRadius, sourceWidth, sourceHeight, topFraction, bottomFraction);
        // createChainEffect(outer, inner): rasterize horizontal first, then vertical.
        progressiveEffect = RenderEffect.createChainEffect(
                RenderEffect.createRuntimeShaderEffect(progressiveVerticalShader, "inputTexture"),
                RenderEffect.createRuntimeShaderEffect(progressiveShader, "inputTexture"));
        renderNode.setRenderEffect(progressiveEffect);
        progressiveEffectApplied = true;
        lastBlurRadius = -1f;
    }

    @RequiresApi(api = Build.VERSION_CODES.TIRAMISU)
    private static void updateProgressiveUniforms(RuntimeShader shader, float radius, int width, int height, float top, float bottom) {
        shader.setFloatUniform("maxRadius", radius);
        shader.setFloatUniform("textureSize", (float) width, (float) height);
        shader.setFloatUniform("fadeZoneTopFraction", top);
        shader.setFloatUniform("fadeZoneBottomFraction", bottom);
    }


    public GlassSource getFallbackSource() {
        return fallbackSource;
    }

    private final ReferenceList<BlurredBackgroundDrawable> drawables = new ReferenceList<>();

    /** Re-records the drawables of this source, e.g. when what is behind them changed without a scroll (wallpaper, theme). */
    public void invalidateDisplayListForDrawables() {
        for (BlurredBackgroundDrawable d : drawables) {
            d.invalidateDisplayList();
        }
    }

    /** A glass drawable over this source: it draws the node itself, so it can show the liquid glass effect. */
    @Override
    public BlurredBackgroundDrawable createDrawable() {
        final BlurredBackgroundDrawable d = new BlurredBackgroundDrawable(this);
        attachDrawable(d);
        return d;
    }

    @Override
    public void attachDrawable(BlurredBackgroundDrawable drawable) {
        drawables.add(drawable);
    }
}
