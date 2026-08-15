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
import org.telegram.messenger.R;

@RequiresApi(api = 33)
public class LiquidGlassEffect {

    private final RenderNode node;
    private RuntimeShader shader;

    // Highlight (edge glare)
    private RuntimeShader highlightShader;
    private final Paint highlightPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path highlightClipPath = new Path();
    private final RectF highlightRect = new RectF();
    private float[] highlightCornerRadii;

    public LiquidGlassEffect(RenderNode node) {
        this.node = node;
        boolean advanced = zxc.iconic.xenon.NekoConfig.useAdvancedLiquidGlass;
        // Refraction (the liquid-glass distortion) only runs when liquid glass is on.
        // The highlight (glare) is always created so it can be drawn over the frosted blur too.
        if (org.telegram.messenger.LiteMode.isEnabled(org.telegram.messenger.LiteMode.FLAG_LIQUID_GLASS)) {
            String code = advanced
                    ? AndroidUtilities.readRes(R.raw.liquid_glass_shader_advanced)
                    : AndroidUtilities.readRes(R.raw.liquid_glass_shader);
            shader = new RuntimeShader(code);
            node.setRenderEffect(RenderEffect.createRuntimeShaderEffect(shader, "img"));
        } else {
            shader = null;
        }

        String highlightCode = AndroidUtilities.readRes(R.raw.liquid_glass_highlight);
        highlightShader = new RuntimeShader(highlightCode);
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

    private float resolutionX, resolutionY;
    private float centerX, centerY;
    private float sizeX, sizeY;
    private float radiusLeftTop, radiusRightTop, radiusRightBottom, radiusLeftBottom;
    private float thickness, intensity, index;
    private int foregroundColor;

    public void update(
            float left, float top, float right, float bottom,
            float rLT, float rRT, float rRB, float rLB,
            float thickness, float intensity, float index, int foregroundColor
    ) {
        float resX = node.getWidth();
        float resY = node.getHeight();
        float cX = (left + right) / 2f;
        float cY = (top + bottom) / 2f;
        float sX = (right - left) / 2f;
        float sY = (bottom - top) / 2f;

        if (this.resolutionX != resX || this.resolutionY != resY ||
                this.centerX != cX || this.centerY != cY ||
                this.sizeX != sX || this.sizeY != sY ||
                this.radiusLeftTop != rLT || this.radiusRightTop != rRT ||
                this.radiusRightBottom != rRB || this.radiusLeftBottom != rLB ||
                this.thickness != thickness || this.intensity != intensity || this.index != index ||
                this.foregroundColor != foregroundColor) {

            this.resolutionX = resX; this.resolutionY = resY;
            this.centerX = cX; this.centerY = cY;
            this.sizeX = sX; this.sizeY = sY;
            this.radiusLeftTop = rLT; this.radiusRightTop = rRT;
            this.radiusRightBottom = rRB; this.radiusLeftBottom = rLB;
            this.thickness = thickness; this.intensity = intensity;
            this.index = index; this.foregroundColor = foregroundColor;

            final float a = Color.alpha(foregroundColor) / 255f;
            final float r = Color.red(foregroundColor) / 255f * a;
            final float g = Color.green(foregroundColor) / 255f * a;
            final float b = Color.blue(foregroundColor) / 255f * a;

            if (shader != null) {
                if (zxc.iconic.xenon.NekoConfig.useAdvancedLiquidGlass) {
                    final float fresnel = Math.max(0.25f, zxc.iconic.xenon.NekoConfig.advancedGlassFresnel);
                    final float refractionHeight = AndroidUtilities.dp(16f) * fresnel;
                    final float refractionAmount = -AndroidUtilities.dp(32f) * fresnel;
                    final float dispersion = Math.max(0.0f, Math.min(1.0f, zxc.iconic.xenon.NekoConfig.advancedGlassDispersion));

                    shader.setFloatUniform("size", sX * 2f, sY * 2f);
                    shader.setFloatUniform("center", cX, cY);
                    shader.setFloatUniform("radius", rLT, rRT, rRB, rLB);
                    shader.setFloatUniform("refractionHeight", refractionHeight);
                    shader.setFloatUniform("refractionAmount", refractionAmount);
                    shader.setFloatUniform("depthEffect", 0f);
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
            }

            // Highlight (glare) uniforms — always updated so the glare draws
            // over the frosted blur even when liquid glass (refraction) is off.
            if (highlightShader != null) {
                highlightShader.setFloatUniform("size", sX * 2f, sY * 2f);
                highlightShader.setFloatUniform("cornerRadii", rLT, rRT, rRB, rLB);
                highlightShader.setColorUniform("color", Color.WHITE);
                highlightShader.setFloatUniform("angle", (float) Math.toRadians(45));
                highlightShader.setFloatUniform("falloff", Math.max(0.1f, zxc.iconic.xenon.NekoConfig.advancedGlassGlare));

                highlightRect.set(left, top, right, bottom);
                highlightCornerRadii = new float[]{rLT, rLT, rRT, rRT, rRB, rRB, rLB, rLB};
            }
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
        if (highlightShader == null || highlightCornerRadii == null) return;
        // Disable mode: shader glare strength is 0, so the highlight is skipped.
        if (zxc.iconic.xenon.NekoConfig.advancedGlassGlare <= 0f) return;

        canvas.save();
        highlightClipPath.reset();
        highlightClipPath.addRoundRect(highlightRect, highlightCornerRadii, Path.Direction.CW);
        canvas.clipPath(highlightClipPath);

        highlightPaint.setAlpha(Math.round(42f * Math.max(0f, Math.min(1f, parentAlpha))));
        highlightPaint.setShader(highlightShader);
        canvas.drawPath(highlightClipPath, highlightPaint);
        canvas.restore();
    }
}
