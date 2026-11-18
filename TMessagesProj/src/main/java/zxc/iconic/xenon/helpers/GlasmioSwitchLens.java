package zxc.iconic.xenon.helpers;

import android.graphics.Canvas;
import android.graphics.RectF;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import androidx.annotation.RequiresApi;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.blur3.GlasmioGlassRenderer;

/** Complete Glasmio SmallVariant SwitchThumb kernel, including its map-before-blur plane. */
@RequiresApi(33)
public final class GlasmioSwitchLens {
    private final boolean slider;
    public GlasmioSwitchLens() { this(false); }
    private final RenderNode source = new RenderNode("GlasmioSwitchSource");
    private final RenderNode warped = new RenderNode("GlasmioSwitchWarpThenBlur");
    private final RenderNode output = new RenderNode("GlasmioSwitchLens");
    private final RuntimeShader extract = new RuntimeShader(AndroidUtilities.readRes(R.raw.glasmio_switch_extract));
    private final RuntimeShader shader = new RuntimeShader(AndroidUtilities.readRes(R.raw.glasmio_switch));
    private final float[] rects = new float[32];

    public GlasmioSwitchLens(boolean slider) {
        this.slider = slider;
        source.setUseCompositingLayer(true, null);
        warped.setUseCompositingLayer(true, null);
    }

    public static float tintAlpha(int color, int percent, float lift) {
        return (color >>> 24) / 255f * Math.max(0, Math.min(100, percent)) / 100f * .6f
                * Math.max(0f, Math.min(1f, lift));
    }

    public void draw(Canvas canvas, RectF thumb, float lift, int onColor,
                     Theme.ResourcesProvider provider, IosGlassThumb.BackdropPainter painter) {
        float d = AndroidUtilities.density, padding = 72f * d, guard = (float) Math.ceil(25f * d);
        int w = Math.max(1, (int) Math.ceil(thumb.width() + 2 * padding));
        int h = Math.max(1, (int) Math.ceil(thumb.height() + 2 * padding));
        float rest = Math.max(0f, Math.min(1f, 1f - lift));
        float opacity = Math.max(0f, Math.min(1f, lift));
        float refraction = zxc.iconic.xenon.NekoConfig.ios27GlassRefraction / .7f;
        float belowAmount = slider ? 0f : (50f - 41f * lift) * d * refraction;
        source.setPosition(0, 0, w, h);
        Canvas c = source.beginRecording();
        c.drawColor(Theme.getColor(Theme.key_windowBackgroundWhite, provider));
        c.translate(padding - thumb.left, padding - thumb.top);
        painter.draw(c);
        source.endRecording();
        extract.setFloatUniform("planeSize", (float) w, (float) h);
        extract.setFloatUniform("size", thumb.width(), thumb.height());
        float r = thumb.height() * .5f;
        extract.setFloatUniform("cornerRadii", r, r, r, r);
        extract.setFloatUniform("extractOrigin", padding, padding);
        extract.setFloatUniform("extractScale", 1f);
        extract.setFloatUniform("extractWarp", 1f, belowAmount, 36f * d);
        warped.setPosition(0, 0, w, h);
        c = warped.beginRecording(); c.drawRenderNode(source); warped.endRecording();
        RenderEffect warp = RenderEffect.createRuntimeShaderEffect(extract, "content");
        float blur = 6f * d * rest * zxc.iconic.xenon.NekoConfig.getIos27GlassBlurRadiusDp() / 5f;
        warped.setRenderEffect(blur > .001f
                ? RenderEffect.createBlurEffect(blur, blur, warp, Shader.TileMode.CLAMP) : warp);
        output.setPosition(0, 0, Math.max(w, (int) Math.ceil(thumb.width() + guard * 2)), h * 3);
        c = output.beginRecording();
        c.drawRenderNode(source);
        c.translate(0, h * 2f); c.drawRenderNode(warped);
        output.endRecording();
        for (int i = 0; i < 3; i++) {
            rects[i * 4] = 0; rects[i * 4 + 1] = i * h;
            rects[i * 4 + 2] = w; rects[i * 4 + 3] = h;
        }
        shader.setFloatUniform("nativeAtlasBaseSize", (float) w, (float) h);
        shader.setFloatUniform("nativeAtlasRects", rects);
        shader.setFloatUniform("nativeAtlasLastLevel", 2f);
        shader.setFloatUniform("size", thumb.width(), thumb.height());
        shader.setFloatUniform("cornerRadii", r, r, r, r);
        shader.setFloatUniform("lensOrigin", padding, padding);
        shader.setFloatUniform("lensDensity", d);
        shader.setFloatUniform("lensScale", 1f);
        shader.setFloatUniform("lensSmall", 1f);
        shader.setFloatUniform("lensTab", 0f);
        shader.setFloatUniform("lensBlurScale", 1f);
        shader.setFloatUniform("lensRest", rest);
        shader.setFloatUniform("lensMaterialProgress", Math.max(0f, lift));
        shader.setFloatUniform("lensMaterialOpacity", opacity);
        shader.setFloatUniform("lensFullyResting", lift <= 0f ? 1f : 0f);
        float light = zxc.iconic.xenon.NekoConfig.getIos27GlassLightIntensity() / .25f;
        shader.setFloatUniform("lensHighlight", Math.max(0f, lift) * light);
        shader.setFloatUniform("lensSettingsAngle", (float) Math.toRadians(zxc.iconic.xenon.NekoConfig.ios27GlassGlareAngle));
        shader.setFloatUniform("lensSettingsSpread", zxc.iconic.xenon.NekoConfig.ios27GlassGlare);
        shader.setFloatUniform("lensSettingsSolid", zxc.iconic.xenon.NekoConfig.ios27GlassGlareMode == zxc.iconic.xenon.NekoConfig.GLASS_GLARE_SOLID ? 1f : 0f);
        shader.setFloatUniform("lensSettingsRefraction", refraction);
        shader.setFloatUniform("lensSettingsDispersion", zxc.iconic.xenon.NekoConfig.getGlassDispersion() * 6f * d);
        int blurPercent = zxc.iconic.xenon.NekoConfig.getGlassAlphaPercent();
        shader.setFloatUniform("lensSettingsBlurOpacity", blurPercent == 0 ? 1f : blurPercent / 100f);
        int tint = org.telegram.ui.Components.blur3.drawable.color.impl.BlurredBackgroundProviderImpl.topPanel(provider).getBackgroundColor();
        // Match the tint to the lens opacity, so the last lifted frame meets the
        // untinted white resting thumb continuously instead of flashing.
        float tintAlpha = tintAlpha(tint, zxc.iconic.xenon.NekoConfig.getGlassTintPercent(), opacity);
        shader.setFloatUniform("lensSettingsTint", android.graphics.Color.red(tint) / 255f * tintAlpha,
                android.graphics.Color.green(tint) / 255f * tintAlpha, android.graphics.Color.blue(tint) / 255f * tintAlpha, tintAlpha);
        shader.setFloatUniform("lensHasContent", 0f);
        shader.setFloatUniform("lensHasBlur", blur > .001f ? 1f : 0f);
        shader.setFloatUniform("lensBelowAmount", belowAmount);
        shader.setFloatUniform("lensInner", .18f * thumb.height() * Math.max(0f, lift), -.3f * thumb.height() * Math.max(0f, lift) * refraction);
        shader.setFloatUniform("lensPortal", 0f, 0f);
        shader.setFloatUniform("lensAberration", zxc.iconic.xenon.NekoConfig.getGlassDispersion() * thumb.height() / 19f, thumb.height() / 1.8f);
        shader.setFloatUniform("lensKeyThreshold", -.5f);
        shader.setFloatUniform("lensRingOpacity", (Theme.isCurrentThemeDark() ? .4f : .15f) * opacity * light);
        shader.setFloatUniform("lensShadowOpacity", .0018f * opacity);
        shader.setFloatUniform("lensSliderShadow", slider ? .14f * rest : 0f);
        shader.setFloatUniform("lensRestColor", 1f, 1f, 1f, 1f);
        float red = android.graphics.Color.red(onColor) / 255f;
        float green = android.graphics.Color.green(onColor) / 255f;
        float blue = android.graphics.Color.blue(onColor) / 255f;
        float high = Math.max(red, Math.max(green, blue)), low = Math.min(red, Math.min(green, blue));
        float chroma = high - low;
        float saturation = chroma == 0f ? 0f : chroma / (high + low <= 1f ? high + low : 2f - high - low);
        float shadowChroma = .225f * saturation, minimum = .45f - shadowChroma * .5f;
        shader.setFloatUniform("lensShadowColor", minimum + (chroma == 0f ? 0f : (red - low) / chroma * shadowChroma),
                minimum + (chroma == 0f ? 0f : (green - low) / chroma * shadowChroma),
                minimum + (chroma == 0f ? 0f : (blue - low) / chroma * shadowChroma));
        shader.setFloatUniform("lensOutputOrigin", guard, guard);
        shader.setFloatUniform("lensOutputSize", thumb.width(), thumb.height());
        GlasmioGlassRenderer.configureVibrancy(shader);
        output.setRenderEffect(RenderEffect.createRuntimeShaderEffect(shader, "content"));
        canvas.save(); canvas.translate(thumb.left - guard, thumb.top - guard);
        canvas.drawRenderNode(output); canvas.restore();
    }

    public void release() {
        source.discardDisplayList(); warped.discardDisplayList(); output.discardDisplayList();
    }
}
