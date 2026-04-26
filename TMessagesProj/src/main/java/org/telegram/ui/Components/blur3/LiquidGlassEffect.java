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
    private final RuntimeShader shader;

    // Highlight (edge glare)
    private RuntimeShader highlightShader;
    private final Paint highlightPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path highlightClipPath = new Path();
    private final RectF highlightRect = new RectF();
    private float[] highlightCornerRadii;

    public LiquidGlassEffect(RenderNode node) {
        this.node = node;
        boolean advanced = zxc.iconic.xenon.NekoConfig.useAdvancedLiquidGlass;
        String code = advanced
                ? AndroidUtilities.readRes(R.raw.liquid_glass_shader_advanced)
                : AndroidUtilities.readRes(R.raw.liquid_glass_shader);
        shader = new RuntimeShader(code);
        node.setRenderEffect(RenderEffect.createRuntimeShaderEffect(shader, "img"));

        if (advanced) {
            String highlightCode = AndroidUtilities.readRes(R.raw.liquid_glass_highlight);
            highlightShader = new RuntimeShader(highlightCode);
            highlightPaint.setStyle(Paint.Style.STROKE);
            highlightPaint.setStrokeWidth(AndroidUtilities.dp(0.5f) * 2f);
            highlightPaint.setBlendMode(BlendMode.PLUS);
            highlightPaint.setColor(Color.WHITE);
            highlightPaint.setAlpha(128); // 50% white, like the lib's default
        }
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

                // Update highlight shader uniforms
                if (highlightShader != null) {
                    highlightShader.setFloatUniform("size", sX * 2f, sY * 2f);
                    highlightShader.setFloatUniform("cornerRadii", rLT, rRT, rRB, rLB);
                    highlightShader.setColorUniform("color", Color.WHITE);
                    highlightShader.setFloatUniform("angle", (float) Math.toRadians(45));
                    highlightShader.setFloatUniform("falloff", Math.max(0.1f, zxc.iconic.xenon.NekoConfig.advancedGlassGlare));

                    highlightRect.set(left, top, right, bottom);
                    highlightCornerRadii = new float[]{rLT, rLT, rRT, rRT, rRB, rRB, rLB, rLB};
                }
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
    }

    /**
     * Draw the directional edge highlight on top of the glass.
     * Call this from onDraw() AFTER the glass RenderNode has been drawn.
     */
    public void drawHighlight(Canvas canvas) {
        if (highlightShader == null || highlightCornerRadii == null) return;

        canvas.save();
        highlightClipPath.reset();
        highlightClipPath.addRoundRect(highlightRect, highlightCornerRadii, Path.Direction.CW);
        canvas.clipPath(highlightClipPath);

        highlightPaint.setShader(highlightShader);
        canvas.drawRoundRect(highlightRect, highlightCornerRadii[0], highlightCornerRadii[2], highlightPaint);
        canvas.restore();
    }
}

