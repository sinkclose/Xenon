package org.telegram.ui.Components.blur3;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.BlendMode;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.RuntimeShader;
import androidx.annotation.RequiresApi;
import org.telegram.messenger.AndroidUtilities;

@RequiresApi(api = 33)
public class LiquidGlassEffect {

    private final RenderNode node;
    private RuntimeShader shader;
    private final boolean fixedRefraction;
    private final boolean advanced;
    private final boolean prism;
    private final boolean ios27;
    private final boolean refractionEnabled;
    private final boolean customOptics;
    private final float customRefraction, customDispersion;
    private final Runnable onShadersReady;
    private boolean uniformsInvalidated = true;

    // Highlight (edge glare)
    private RuntimeShader highlightShader;
    private final Paint highlightPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path highlightClipPath = new Path();
    private final RectF highlightRect = new RectF();
    private final float[] highlightCornerRadii = new float[8];
    private boolean highlightGeometryInitialized;
    private float highlightGeometryLeft, highlightGeometryTop, highlightGeometryRight, highlightGeometryBottom;
    private float refractionScale = 1f;

    /** Local lens depth, used while a popup changes from a droplet to a settled sheet. */
    public void setRefractionScale(float scale) {
        scale = Math.max(.25f, Math.min(3f, scale));
        if (refractionScale != scale) {
            refractionScale = scale;
            uniformsInvalidated = true;
            refractionUniformsInitialized = false;
        }
    }
    private boolean refractionUniformsInitialized;

    public static void prewarmShaders() {
        GlassShaderCache.prewarm(
                org.telegram.messenger.LiteMode.isEnabled(org.telegram.messenger.LiteMode.FLAG_LIQUID_GLASS),
                zxc.iconic.xenon.NekoConfig.isAdvancedGlassEnabled(),
                zxc.iconic.xenon.NekoConfig.isPrismGlassEnabled());
    }

    public LiquidGlassEffect(RenderNode node) {
        this(node, false);
    }

    public LiquidGlassEffect(RenderNode node, boolean fixedRefraction) {
        this(node, fixedRefraction, null);
    }

    public LiquidGlassEffect(RenderNode node, boolean fixedRefraction, Runnable onShadersReady) {
        this(node, fixedRefraction, onShadersReady, Float.NaN, Float.NaN);
    }

    /** Local advanced-glass optics; never reads or writes the global optical sliders. */
    public LiquidGlassEffect(RenderNode node, float refraction, float dispersion, Runnable onShadersReady) {
        this(node, false, onShadersReady, refraction, dispersion);
    }

    private LiquidGlassEffect(RenderNode node, boolean fixedRefraction, Runnable onShadersReady,
                              float refraction, float dispersion) {
        customOptics = !Float.isNaN(refraction) && !Float.isNaN(dispersion);
        customRefraction = Math.max(0f, Math.min(2f, refraction));
        customDispersion = Math.max(0f, Math.min(1f, dispersion));
        this.node = node;
        this.fixedRefraction = fixedRefraction;
        this.onShadersReady = onShadersReady;
        ios27 = zxc.iconic.xenon.NekoConfig.ios27LiquidGlass;
        prism = zxc.iconic.xenon.NekoConfig.isPrismGlassEnabled();
        advanced = customOptics || fixedRefraction || zxc.iconic.xenon.NekoConfig.isAdvancedGlassEnabled();
        refractionEnabled = customOptics || org.telegram.messenger.LiteMode.isEnabled(org.telegram.messenger.LiteMode.FLAG_LIQUID_GLASS);
        // Refraction (the liquid-glass distortion) only runs when liquid glass is on.
        // The highlight (glare) is always created so it can be drawn over the frosted blur too.
        node.setRenderEffect(null);
        ensureShaders();
        highlightPaint.setStyle(Paint.Style.STROKE);
        highlightPaint.setStrokeWidth(AndroidUtilities.dp(0.35f) * 2f);
        // Intentionally NOT using BlurMaskFilter on the stroke. A
        // mask-filtered stroke with a RuntimeShader forces software
        // rasterisation of the blurred mask every frame on every glass
        // surface, which is what dropped frame rates from 120 Hz to
        // 30–60 Hz once advanced glass was enabled (commit 918e5861b).
        // The runtime shader's directional gradient already provides
        // sufficient softness on its own; the dropped 0.5 dp Gaussian
        // edge bleed is barely perceptible at this stroke width and alpha.
        highlightPaint.setBlendMode(BlendMode.PLUS);
        highlightPaint.setColor(Color.WHITE);
        highlightPaint.setAlpha(42);
    }

    public boolean ensureShaders() {
        boolean changed = false;
        if (refractionEnabled && shader == null) {
            shader = GlassShaderCache.acquireRefraction(advanced, prism, onShadersReady);
            if (shader != null) {
                refractionUniformsInitialized = false;
                changed = true;
            }
        }
        if (highlightShader == null) {
            highlightShader = GlassShaderCache.acquireHighlight(onShadersReady);
            changed |= highlightShader != null;
        }
        uniformsInvalidated |= changed;
        return changed;
    }

    public boolean hasRefractionShader() {
        return shader != null;
    }

    private float resolutionX, resolutionY;
    private float centerX, centerY;
    private float sizeX, sizeY;
    private float radiusLeftTop, radiusRightTop, radiusRightBottom, radiusLeftBottom;
    private float thickness, intensity, index;
    private float glareAngle;
    private boolean cachedDark;
    private final float[] ios27Lens = new float[4];
    private int ios27Revision = -1;
    private float cachedFresnel, cachedDispersion, cachedGlare;
    private int foregroundColor;

    public boolean isConfigurationCurrent() {
        return ios27 == zxc.iconic.xenon.NekoConfig.ios27LiquidGlass
                && prism == zxc.iconic.xenon.NekoConfig.isPrismGlassEnabled()
                && (customOptics || advanced == (fixedRefraction || zxc.iconic.xenon.NekoConfig.isAdvancedGlassEnabled())
                    && refractionEnabled == org.telegram.messenger.LiteMode.isEnabled(org.telegram.messenger.LiteMode.FLAG_LIQUID_GLASS));
    }

    public void update(
            float left, float top, float right, float bottom,
            float rLT, float rRT, float rRB, float rLB,
            float thickness, float intensity, float index, int foregroundColor
    ) {
        if (!advanced && org.telegram.ui.Components.blur3.drawable.color.impl.BlurredBackgroundProviderImpl.isBlackWhiteTintEnabled()) {
            // B/W has no accent RGB to blend. Control the premultiplied tint's
            // opacity instead, before comparing the cached uniform values.
            final float strength = Math.max(0f, Math.min(1f,
                    zxc.iconic.xenon.NekoConfig.getGlassTintPercent() / 100f));
            foregroundColor = org.telegram.ui.ActionBar.Theme.multAlpha(foregroundColor, strength);
        }
        float resX = node.getWidth();
        float resY = node.getHeight();
        float cX = (left + right) / 2f;
        float cY = (top + bottom) / 2f;
        float sX = (right - left) / 2f;
        float sY = (bottom - top) / 2f;
        final float maxRadius = Math.max(0f, Math.min(sX, sY));
        rLT = Math.max(0f, Math.min(maxRadius, rLT));
        rRT = Math.max(0f, Math.min(maxRadius, rRT));
        rRB = Math.max(0f, Math.min(maxRadius, rRB));
        rLB = Math.max(0f, Math.min(maxRadius, rLB));

        final float refractionSetting = ios27 ? zxc.iconic.xenon.NekoConfig.getGlassRefraction() : customOptics ? customRefraction
                : zxc.iconic.xenon.NekoConfig.getGlassRefraction();
        final float dispersionSetting = ios27 ? zxc.iconic.xenon.NekoConfig.getGlassDispersion() : customOptics ? customDispersion
                : zxc.iconic.xenon.NekoConfig.getGlassDispersion();
        final float angleDeg = zxc.iconic.xenon.NekoConfig.getGlassGlareAngle();
        if (ios27 && ios27Revision != zxc.iconic.xenon.NekoConfig.getIos27GlassRevision()) {
            ios27Revision = zxc.iconic.xenon.NekoConfig.getIos27GlassRevision();
            uniformsInvalidated = true;
            refractionUniformsInitialized = false;
        }
        final boolean dark = org.telegram.ui.ActionBar.Theme.isCurrentThemeDark();
        if (ios27 && cachedDark != dark) {
            uniformsInvalidated = true;
            refractionUniformsInitialized = false;
        }
        cachedDark = dark;
        final boolean refractionChanged = this.resolutionX != resX || this.resolutionY != resY
                || this.centerX != cX || this.centerY != cY || this.sizeX != sX || this.sizeY != sY
                || this.radiusLeftTop != rLT || this.radiusRightTop != rRT
                || this.radiusRightBottom != rRB || this.radiusLeftBottom != rLB
                || (advanced
                    ? (!fixedRefraction && this.cachedFresnel != refractionSetting)
                        || this.cachedDispersion != dispersionSetting
                    : this.thickness != thickness || this.intensity != intensity || this.index != index
                        || this.foregroundColor != foregroundColor);

        if (uniformsInvalidated || this.resolutionX != resX || this.resolutionY != resY ||
                this.centerX != cX || this.centerY != cY ||
                this.sizeX != sX || this.sizeY != sY ||
                this.radiusLeftTop != rLT || this.radiusRightTop != rRT ||
                this.radiusRightBottom != rRB || this.radiusLeftBottom != rLB ||
                this.thickness != thickness || this.intensity != intensity || this.index != index ||
                this.glareAngle != angleDeg ||
                this.cachedFresnel != refractionSetting ||
                this.cachedDispersion != dispersionSetting ||
                this.cachedGlare != zxc.iconic.xenon.NekoConfig.getGlassGlare() ||
                this.foregroundColor != foregroundColor) {

            this.resolutionX = resX; this.resolutionY = resY;
            this.centerX = cX; this.centerY = cY;
            this.sizeX = sX; this.sizeY = sY;
            this.radiusLeftTop = rLT; this.radiusRightTop = rRT;
            this.radiusRightBottom = rRB; this.radiusLeftBottom = rLB;
            this.thickness = thickness; this.intensity = intensity; this.index = index;
            this.glareAngle = angleDeg;
            this.cachedFresnel = refractionSetting;
            this.cachedDispersion = dispersionSetting;
            this.cachedGlare = zxc.iconic.xenon.NekoConfig.getGlassGlare();
            this.foregroundColor = foregroundColor;

            final float a = Color.alpha(foregroundColor) / 255f;
            final float r = Color.red(foregroundColor) / 255f * a;
            final float g = Color.green(foregroundColor) / 255f * a;
            final float b = Color.blue(foregroundColor) / 255f * a;

            if (shader != null && (!refractionUniformsInitialized || refractionChanged)) {
                if (advanced) {
                    // The navigation capsule uses the settings slider's 50 value.
                    final float fresnel = !ios27 && fixedRefraction ? 0.5f
                            : Math.max(0.25f, refractionSetting);
                    final float refractionHeight = ios27 ? AndroidUtilities.dpf2(30f) : AndroidUtilities.dp(16f) * fresnel * refractionScale;
                    final float refractionAmount = ios27 ? AndroidUtilities.dpf2(30f) * .7f : -AndroidUtilities.dp(32f) * fresnel * refractionScale;
                    final float dispersion = Math.max(0.0f, Math.min(1.0f, dispersionSetting));

                    shader.setFloatUniform("resolution", resX, resY);
                    shader.setFloatUniform("size", sX * 2f, sY * 2f);
                    shader.setFloatUniform("center", cX, cY);
                    shader.setFloatUniform("radius", rLT, rRT, rRB, rLB);
                    shader.setFloatUniform("refractionHeight", refractionHeight);
                    shader.setFloatUniform("refractionAmount", refractionAmount);
                    shader.setFloatUniform("depthEffect", 0f);
                    if (ios27) {
                        Ios27GlassMaterial.lens(sX * 2f, sY * 2f, AndroidUtilities.density, ios27Lens, 0);
                        ios27Lens[0] *= refractionSetting / .7f;
                        shader.setFloatUniform("lensParams", ios27Lens);
                        shader.setFloatUniform("lightIntensity", zxc.iconic.xenon.NekoConfig.getIos27GlassLightIntensity());
                        shader.setFloatUniform("lightAngle", (float) Math.toRadians(angleDeg));
                        shader.setFloatUniform("darkMode", dark ? 1f : 0f);
                        shader.setFloatUniform("pixelScale", AndroidUtilities.density);
                    }
                    shader.setFloatUniform("chromaticAberration", dispersion);
                    node.setRenderEffect(RenderEffect.createRuntimeShaderEffect(shader, "img"));
                } else {
                    shader.setFloatUniform("resolution", resX, resY);
                    shader.setFloatUniform("center", cX, cY);
                    shader.setFloatUniform("size", sX, sY);
                    shader.setFloatUniform("radius", rRB, rRT, rLB, rLT);
                    shader.setFloatUniform("thickness", thickness);
                    shader.setFloatUniform("refract_intensity", intensity);
                    shader.setFloatUniform("refract_index", index);
                    shader.setFloatUniform("foreground_color_premultiplied", r, g, b, a);
                    node.setRenderEffect(RenderEffect.createRuntimeShaderEffect(shader, "img"));
                }
                refractionUniformsInitialized = true;
            }

            // Highlight (glare) uniforms — always updated so the glare draws
            // over the frosted blur even when liquid glass (refraction) is off.
            if (highlightShader != null) {
                highlightShader.setFloatUniform("size", sX * 2f, sY * 2f);
                highlightShader.setFloatUniform("origin", left, top);
                highlightShader.setFloatUniform("cornerRadii", rLT, rRT, rRB, rLB);
                highlightShader.setColorUniform("color", Color.WHITE);
                if (ios27) {
                    highlightShader.setFloatUniform("pixelScale", AndroidUtilities.density);
                    highlightShader.setFloatUniform("darkMode", dark ? 1f : 0f);
                    highlightShader.setFloatUniform("lightIntensity", zxc.iconic.xenon.NekoConfig.getIos27GlassLightIntensity());
                    highlightShader.setFloatUniform("lightAngle", (float) Math.toRadians(angleDeg));
                    highlightShader.setFloatUniform("lightFalloff", zxc.iconic.xenon.NekoConfig.ios27GlassGlare);
                    highlightShader.setFloatUniform("solidGlare", zxc.iconic.xenon.NekoConfig.ios27GlassGlareMode
                            == zxc.iconic.xenon.NekoConfig.GLASS_GLARE_SOLID ? 1f : 0f);
                } else {
                    highlightShader.setFloatUniform("angle", (float) Math.toRadians(angleDeg));
                    highlightShader.setFloatUniform("falloff", Math.max(0.1f, zxc.iconic.xenon.NekoConfig.advancedGlassGlare));
                }

                highlightRect.set(left, top, right, bottom);
                if (!highlightGeometryInitialized
                        || highlightCornerRadii[0] != rLT || highlightCornerRadii[2] != rRT
                        || highlightCornerRadii[4] != rRB || highlightCornerRadii[6] != rLB
                        || highlightClipPath.isEmpty()
                        || highlightGeometryLeft != left || highlightGeometryTop != top
                        || highlightGeometryRight != right || highlightGeometryBottom != bottom) {
                    highlightCornerRadii[0] = highlightCornerRadii[1] = rLT;
                    highlightCornerRadii[2] = highlightCornerRadii[3] = rRT;
                    highlightCornerRadii[4] = highlightCornerRadii[5] = rRB;
                    highlightCornerRadii[6] = highlightCornerRadii[7] = rLB;
                    highlightGeometryLeft = left;
                    highlightGeometryTop = top;
                    highlightGeometryRight = right;
                    highlightGeometryBottom = bottom;
                    highlightClipPath.reset();
                    highlightClipPath.addRoundRect(highlightRect, highlightCornerRadii, Path.Direction.CW);
                    highlightGeometryInitialized = true;
                }
            }
            uniformsInvalidated = false;
        }
    }

    /**
     * Draw the directional edge highlight on top of the glass.
     * Call this from onDraw() AFTER the glass RenderNode has been drawn.
     *
     * <p>The highlight is a thin (0.7 dp) anti-aliased stroke shaded by a
     * directional RuntimeShader and composited with PLUS blend at low alpha.
     * Without {@code BlurMaskFilter} on the paint this is a fully
     * GPU-accelerated draw — clipPath + drawPath with a runtime-shaded
     * stroke costs almost nothing per frame on hardware-accelerated canvases.
     */
    public void drawHighlight(Canvas canvas, float parentAlpha) {
        if (highlightShader == null || !highlightGeometryInitialized) return;
        // Disable mode: shader glare strength is 0, so the highlight is skipped.
        if (!ios27 && zxc.iconic.xenon.NekoConfig.advancedGlassGlare <= 0f) return;

        canvas.save();
        canvas.clipPath(highlightClipPath);

        highlightPaint.setStyle(ios27 ? Paint.Style.FILL : Paint.Style.STROKE);
        highlightPaint.setBlendMode(ios27 ? BlendMode.SRC_OVER : BlendMode.PLUS);
        highlightPaint.setAlpha(Math.round((ios27 ? 255f : 42f) * Math.max(0f, Math.min(1f, parentAlpha))));
        highlightPaint.setShader(highlightShader);
        canvas.drawPath(highlightClipPath, highlightPaint);
        canvas.restore();
    }
}
