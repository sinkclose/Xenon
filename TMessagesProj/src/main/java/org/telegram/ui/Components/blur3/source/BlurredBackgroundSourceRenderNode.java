package org.telegram.ui.Components.blur3.source;

import android.graphics.Canvas;
import android.graphics.RecordingCanvas;
import android.graphics.RectF;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.os.Build;

import androidx.annotation.RequiresApi;

import org.telegram.messenger.FileLog;
import org.telegram.ui.Components.blur3.DownscaleScrollableNoiseSuppressor;
import org.telegram.ui.Components.blur3.GlassShaderCache;
import org.telegram.ui.Components.blur3.RenderNodeWithHash;
import org.telegram.ui.Components.blur3.drawable.BlurredBackgroundDrawable;
import org.telegram.ui.Components.blur3.drawable.BlurredBackgroundDrawableRenderNode;

import java.util.List;

import me.vkryl.core.reference.ReferenceList;

@RequiresApi(api = Build.VERSION_CODES.Q)
public class BlurredBackgroundSourceRenderNode implements BlurredBackgroundSource {
    private final BlurredBackgroundSource fallbackSource;
    private final RenderNode renderNode;
    private RenderNodeWithHash renderNodeWithHash;

    private DownscaleScrollableNoiseSuppressor scrollableNoiseSuppressor;
    private int scrollableNoiseSuppressorIndex;
    public BlurredBackgroundSource underSource;
    // Scope raw replay to this recording canvas; nested sources inherit it.
    private static final ThreadLocal<Canvas> unblurredCanvas = new ThreadLocal<>();

    public static boolean isUnblurredCanvas(Canvas canvas) {
        return canvas == unblurredCanvas.get();
    }

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

    private boolean noClip;
    private float pixelationScale = 1f;
    private float lastBlurRadius = -1f;
    private RenderEffect lastBlurEffect;
    private boolean glassBlurEnabled;
    private float standardGlassBlurRadius;
    private RenderEffect standardGlassBlurEffect;

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

    public void setPixelation(float scale) {
        this.pixelationScale = Math.max(1f, scale);
    }

    public BlurredBackgroundSourceRenderNode(BlurredBackgroundSource fallbackSource) {
        this.fallbackSource = fallbackSource;

        renderNode = new RenderNode(null);
        wallpaperNode = new RenderNode("WallpaperBlur");
    }

    public void setupRenderer(RenderNodeWithHash.Renderer renderer) {
        if (renderNodeWithHash == null) {
            renderNodeWithHash = new RenderNodeWithHash(renderNode, renderer);
        }
    }

    public void updateDisplayListIfNeeded() {
        renderNodeWithHash.updateDisplayListIfNeeded();
    }

    public void setSize(int width, int height) {
        renderNode.setPosition(0, 0, width, height);
    }

    public void setScrollableNoiseSuppressor(DownscaleScrollableNoiseSuppressor scrollableNoiseSuppressor, int index) {
        this.scrollableNoiseSuppressor = scrollableNoiseSuppressor;
        this.scrollableNoiseSuppressorIndex = index;
    }

    public void setUnderSource(BlurredBackgroundSource underSource) {
        if (wallpaperOwner != null && wallpaperOwner.underSource != underSource) {
            wallpaperParentW = wallpaperOwner.wallpaperParentW;
            wallpaperParentH = wallpaperOwner.wallpaperParentH;
            wallpaperOwner = null;
        }
        this.underSource = underSource;
        wallpaperDirty = true;
    }

    /** Companion glass/frosted sources use the same wallpaper and blur material. */
    public void shareWallpaperWith(BlurredBackgroundSourceRenderNode owner) {
        if (owner == this || owner.wallpaperOwner != null || owner.underSource != underSource) {
            throw new IllegalArgumentException("Wallpaper sources must match");
        }
        wallpaperOwner = owner;
    }

    // ------------------------------------------------------------------
    // Glass wallpaper layer: the sharp wallpaper (underSource) is recorded
    // into a half-size RenderNode once per content/size change and blurred
    // on the GPU with the same radius the glass content uses. Recording
    // happens here on explicit invalidation — never per-frame inside draw —
    // so there is no race with the render thread and no UI-thread stackBlur.
    // Screens that never call setWallpaperSize keep the legacy direct draw.
    // ------------------------------------------------------------------

    private final RenderNode wallpaperNode;
    private BlurredBackgroundSourceRenderNode wallpaperOwner;
    private int wallpaperParentW, wallpaperParentH;
    private boolean wallpaperDirty = true;
    private boolean wallpaperRecording;
    private float wallpaperEffectRadius = -1f;
    private boolean wallpaperEffectSaturated;

    /** Parent-space size the wallpaper shader matrix is built for. */
    public void setWallpaperSize(int width, int height) {
        if (wallpaperOwner != null) {
            wallpaperOwner.setWallpaperSize(width, height);
            return;
        }
        if (this.wallpaperParentW != width || this.wallpaperParentH != height) {
            this.wallpaperParentW = width;
            this.wallpaperParentH = height;
            this.wallpaperDirty = true;
        }
    }

    /** Re-record the wallpaper node on next draw (bitmap finished loading). */
    public void invalidateWallpaper() {
        if (wallpaperOwner != null) wallpaperOwner.invalidateWallpaper();
        wallpaperDirty = true;
        invalidateDisplayListForDrawables();
    }

    @RequiresApi(api = Build.VERSION_CODES.S)
    private void syncWallpaperEffect() {
        // Mirror the glass-content blur from DownscaleScrollableNoiseSuppressor
        // so wallpaper and messages share one softness (the Blur slider).
        final boolean advanced = zxc.iconic.xenon.NekoConfig.useAdvancedLiquidGlass;
        final float full = org.telegram.messenger.AndroidUtilities.dpf2(
                zxc.iconic.xenon.NekoConfig.getGlassBlurRadiusDp());
        final float radius = org.telegram.ui.Components.blur3.DownscaleScrollableNoiseSuppressor.downscaleRadius(full, 2);
        if (radius == wallpaperEffectRadius && advanced == wallpaperEffectSaturated) {
            return;
        }
        wallpaperEffectRadius = radius;
        wallpaperEffectSaturated = advanced;
        if (radius > 0) {
            final android.graphics.RenderEffect blur = android.graphics.RenderEffect.createBlurEffect(
                    radius, radius, android.graphics.Shader.TileMode.CLAMP);
            wallpaperNode.setRenderEffect(!advanced
                    ? android.graphics.RenderEffect.createChainEffect(blur,
                            org.telegram.messenger.utils.RenderNodeEffects.getSaturationX1_25RenderEffect())
                    : blur);
        } else {
            wallpaperNode.setRenderEffect(null);
        }
    }

    private void recordWallpaperIfNeeded() {
        if (!wallpaperDirty && wallpaperNode.hasDisplayList()) {
            return;
        }
        if (underSource == null || underSource == this
                || wallpaperParentW <= 0 || wallpaperParentH <= 0 || wallpaperRecording) {
            return;
        }
        // Half resolution, same as the glass DownscaledRenderNode (scale 2,2):
        // cheap to record, and the blur radius is converted accordingly.
        final int rw = Math.max(1, wallpaperParentW / 2);
        final int rh = Math.max(1, wallpaperParentH / 2);
        wallpaperNode.setPosition(0, 0, rw, rh);
        wallpaperRecording = true;
        try {
            final RecordingCanvas rc = wallpaperNode.beginRecording(rw, rh);
            rc.scale((float) rw / wallpaperParentW, (float) rh / wallpaperParentH);
            underSource.draw(rc, 0, 0, wallpaperParentW, wallpaperParentH);
            wallpaperNode.endRecording();
            wallpaperDirty = false;
        } finally {
            wallpaperRecording = false;
        }
    }

    private void drawWallpaper(Canvas canvas, float left, float top, float right, float bottom) {
        if (wallpaperOwner != null) {
            wallpaperOwner.drawWallpaper(canvas, left, top, right, bottom);
            return;
        }
        if (underSource == null) {
            return;
        }
        if (!isUnblurredCanvas(canvas) && zxc.iconic.xenon.NekoConfig.blurStrength > 0
                && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && wallpaperParentW > 0 && wallpaperParentH > 0) {
            syncWallpaperEffect();
            recordWallpaperIfNeeded();
            if (wallpaperNode.hasDisplayList()) {
                canvas.save();
                if (!noClip) {
                    canvas.clipRect(left, top, right, bottom);
                }
                // The node holds parent-space wallpaper at half resolution,
                // so 2x maps node pixels back onto the requested rect.
                canvas.scale(2f, 2f);
                canvas.drawRenderNode(wallpaperNode);
                canvas.restore();
                return;
            }
        }
        // Size unknown (non-chat screens) or recording failed: legacy direct draw.
        underSource.draw(canvas, left, top, right, bottom);
    }

    @RequiresApi(api = Build.VERSION_CODES.S)
    public void setBlur(float radius) {
        setBlur(radius, null);
    }

    @RequiresApi(api = Build.VERSION_CODES.S)
    public void setBlur(float radius, RenderEffect effect) {
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

    /** Use a fixed blur without following the liquid glass settings. */
    @RequiresApi(api = Build.VERSION_CODES.S)
    public void setPlainBlur(float radius) {
        glassBlurEnabled = false;
        setBlur(radius);
    }

    /** Keep advanced glass in sync with the slider, preserving the standard style. */
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
        if (zxc.iconic.xenon.NekoConfig.useAdvancedLiquidGlass) {
            setBlur(org.telegram.messenger.AndroidUtilities.dpf2(
                    zxc.iconic.xenon.NekoConfig.getGlassBlurRadiusDp()));
        } else {
            setBlur(standardGlassBlurRadius, standardGlassBlurEffect);
        }
    }

    @Override
    public void prepareToDraw() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (glassBlurEnabled) syncGlassBlur();
            if (wallpaperOwner != null) {
                wallpaperOwner.prepareToDraw();
            } else if (underSource != null && wallpaperParentW > 0 && wallpaperParentH > 0) {
                syncWallpaperEffect();
                // A cached glass drawable may never call source.draw() again.
                // Flush wallpaper updates before drawing its existing display list.
                recordWallpaperIfNeeded();
            }
        }
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

        final float s = Math.max(1f, pixelationScale);
        final int rw = Math.max(1, Math.round(width / s));
        final int rh = Math.max(1, Math.round(height / s));
        renderNode.setPosition(0, 0, rw, rh);
        recordingCanvas = renderNode.beginRecording(rw, rh);
        if (s > 1f) {
            recordingCanvas.scale(1f / s, 1f / s);
        }
        renderNode.setScaleX(s);
        renderNode.setScaleY(s);
        renderNode.setPivotX(0);
        renderNode.setPivotY(0);
        return recordingCanvas;
    }

    public void endRecording() {
        if (!inRecording) {
            throw new IllegalStateException();
        }

        try {
            renderNode.endRecording();
        } finally {
            inRecording = false;
            recordingCanvas = null;
        }
    }

    public boolean isRecordingCanvas(Canvas canvas) {
        return canvas != null && canvas == recordingCanvas;
    }

    public boolean inRecording() {
        return inRecording;
    }

    @Override
    public void draw(Canvas canvas, float left, float top, float right, float bottom) {
        if (!canvas.isHardwareAccelerated()) {
            if (fallbackSource != null) {
                fallbackSource.draw(canvas, left, top, right, bottom);
            }
            return;
        }

        if (inRecording) {
            throw new IllegalStateException();
        }

        // Wallpaper layer: sharp source recorded once into a half-size node
        // and blurred on the GPU (see drawWallpaper). Steady-state draw()
        // performs zero recording — no render-thread race, no CPU stackBlur.
        if (!isUnblurredCanvas(canvas)) prepareToDraw();
        drawWallpaper(canvas, left, top, right, bottom);
        canvas.save();
        if (!noClip) {
            canvas.clipRect(left, top, right, bottom);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && scrollableNoiseSuppressor != null) {
            scrollableNoiseSuppressor.drawInline(canvas, scrollableNoiseSuppressorIndex);
        } else if (isUnblurredCanvas(canvas) && renderNodeWithHash != null) {
            renderNodeWithHash.drawUnfiltered(canvas);
        } else {
            canvas.drawRenderNode(renderNode);
        }
        canvas.restore();
    }

    public BlurredBackgroundSource getFallbackSource() {
        return fallbackSource;
    }

    public int getVisiblePositions(List<RectF> positions, int index, int expand) {
        int count = 0;

        for (BlurredBackgroundDrawableRenderNode d : drawables) {
            if (d.hasDisplayList() && d.getAlpha() > 0 && !d.getPaddedBounds().isEmpty()) {
                final RectF rectf;
                if (index < positions.size()) {
                    rectf = positions.get(index);
                } else {
                    rectf = new RectF();
                    positions.add(rectf);
                }
                d.getPositionRelativeSource(rectf);
                rectf.inset(-expand, -expand);

                index++;
                count++;
            }
        }

        return count;
    }

    private final ReferenceList<BlurredBackgroundDrawableRenderNode> drawables = new ReferenceList<>();

    private Runnable onDrawablesRelativePositionChangeListener;
    public void setOnDrawablesRelativePositionChangeListener(Runnable callback) {
        onDrawablesRelativePositionChangeListener = callback;
    }

    @Override
    public void dispatchOnDrawablesRelativePositionChange() {
        if (onDrawablesRelativePositionChangeListener != null) {
            onDrawablesRelativePositionChangeListener.run();
        }
    }

    public void invalidateDisplayListForDrawables() {
        for (BlurredBackgroundDrawableRenderNode d : drawables) {
            d.invalidateDisplayList();
        }
    }

    @Override
    public BlurredBackgroundDrawable createDrawable() {
        BlurredBackgroundDrawableRenderNode d = new BlurredBackgroundDrawableRenderNode(this);
        drawables.add(d);
        return d;
    }
}
