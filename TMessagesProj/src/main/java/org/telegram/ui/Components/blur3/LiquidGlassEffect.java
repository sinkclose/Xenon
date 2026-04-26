package org.telegram.ui.Components.blur3;

import android.graphics.Color;
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
    private RenderEffect effect;

    public LiquidGlassEffect(RenderNode node) {
        this.node = node;
        String code = zxc.iconic.xenon.NekoConfig.useAdvancedLiquidGlass
                ? AndroidUtilities.readRes(R.raw.liquid_glass_shader_advanced)
                : AndroidUtilities.readRes(R.raw.liquid_glass_shader);
        shader = new RuntimeShader(code);
        node.setRenderEffect(effect = RenderEffect.createRuntimeShaderEffect(shader, "img"));
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
                this.thickness != thickness || this.foregroundColor != foregroundColor) {

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

            shader.setFloatUniform("resolution", resX, resY);
            shader.setFloatUniform("center", cX, cY);
            shader.setFloatUniform("size", sX, sY);
            shader.setFloatUniform("radius", rRB, rRT, rLB, rLT);
            shader.setFloatUniform("thickness", thickness);
            shader.setFloatUniform("refract_intensity", intensity);
            shader.setFloatUniform("refract_index", index);
            shader.setFloatUniform("foreground_color_premultiplied", r, g, b, a);
            
            if (zxc.iconic.xenon.NekoConfig.useAdvancedLiquidGlass) {
                // Override with hardcoded iOS LiquidGlassKit.regular defaults
                // Refraction: 20*dp(10)*0.354 = 70.71*density = exact iOS pixel offset
                shader.setFloatUniform("thickness", AndroidUtilities.dp(10));
                shader.setFloatUniform("refract_index", 1.5f);
                shader.setFloatUniform("refract_intensity", 0.354f);
                shader.setFloatUniform("config_dispersion", 5.0f);
                shader.setFloatUniform("config_fresnel", 0.0f);
                shader.setFloatUniform("config_glare", 0.1f);
            }

            node.setRenderEffect(effect = RenderEffect.createRuntimeShaderEffect(shader, "img"));
        }
    }
}
