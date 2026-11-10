package org.telegram.ui.Components.blur3.drawable;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.RectF;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.RuntimeShader;
import androidx.annotation.RequiresApi;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.messenger.LiteMode;
import org.telegram.messenger.SharedConfig;
import org.telegram.ui.Components.blur3.source.BlurredBackgroundSource;
import org.telegram.ui.Components.blur3.drawable.color.impl.BlurredBackgroundProviderImpl;
import org.telegram.ui.Components.LiquidTouchEffect;
import zxc.iconic.xenon.NekoConfig;
import java.util.Arrays;

/** Optical union and connected touch light, retaining independent surface springs. */
@RequiresApi(33)
public final class LiquidGlassInputGroup {
    private final RenderNode node = new RenderNode("LiquidInputUnion");
    private final RuntimeShader shader;
    private final int shapeCount;
    private final float[] shapes = new float[32];
    private final float[] shapeScales = new float[16];
    private final float[] lensParams = new float[32];
    private int ios27MaterialRevision = -1;
    private final float[] radii = new float[8];
    private final float[] cornerRadii = new float[32];
    private final float[] colors = new float[32];
    private final float[] opacity = new float[8];
    private final float[] glows = new float[32];
    private final float[] links = new float[64];
    private final float[] merges = new float[64];

    public LiquidGlassInputGroup() {
        this(8);
    }

    public LiquidGlassInputGroup(int shapeCount) {
        if (shapeCount < 1 || shapeCount > 8) throw new IllegalArgumentException("Invalid glass group size");
        this.shapeCount = shapeCount;
        shader = new RuntimeShader(AndroidUtilities.readRes(R.raw.liquid_glass_input_union)
                .replace("const int SHAPE_COUNT = 8;", "const int SHAPE_COUNT = " + shapeCount + ";"));
        Arrays.fill(merges, AndroidUtilities.dp(8));
    }

    public void setMergeDistance(float distance) {
        for (int i = 0; i < 8; i++) setPairMergeDistance(2, i, distance);
    }

    public void setPairMergeDistance(int a, int b, float distance) {
        merges[a * 8 + b] = merges[b * 8 + a] = Math.max(1f, distance);
    }

    public void release() {
        node.setRenderEffect(null);
        node.discardDisplayList();
    }

    public void draw(Canvas canvas, BlurredBackgroundDrawable[] members, RectF[] bounds, float[] alpha,
                     LiquidTouchEffect[] effects) {
        draw(canvas, members, bounds, alpha, effects, null);
    }

    /** Origins translate each member's local spring coordinates into the group's host. */
    public void draw(Canvas canvas, BlurredBackgroundDrawable[] members, RectF[] bounds, float[] alpha,
                     LiquidTouchEffect[] effects, float[] origins) {
        draw(canvas, members, bounds, alpha, effects, origins, origins);
    }

    public void draw(Canvas canvas, BlurredBackgroundDrawable[] members, RectF[] bounds, float[] alpha,
                     LiquidTouchEffect[] effects, float[] origins, float[] sourceOrigins) {
        if (members.length > shapeCount) throw new IllegalArgumentException("Glass group exceeds shader slots");
        if (NekoConfig.ios27LiquidGlass && ios27MaterialRevision != NekoConfig.getIos27GlassRevision()) {
            ios27MaterialRevision = NekoConfig.getIos27GlassRevision();
            for (BlurredBackgroundDrawable member : members) if (member != null) member.updateColors();
        }
        int sourceIndex = members.length > 1 && members[1] != null ? 1 : 0;
        while (sourceIndex < members.length && members[sourceIndex] == null) sourceIndex++;
        if (sourceIndex >= members.length) return;
        BlurredBackgroundDrawable center = members[sourceIndex];
        Arrays.fill(opacity, 0f);
        Arrays.fill(glows, 0f);
        boolean refractive = SharedConfig.chatBlurEnabled() && LiteMode.isEnabled(LiteMode.FLAG_LIQUID_GLASS);
        float left = bounds[sourceIndex].left, top = bounds[sourceIndex].top;
        float right = bounds[sourceIndex].right, bottom = bounds[sourceIndex].bottom;
        for (int i = 0; i < members.length; i++) {
            if (members[i] == null || alpha[i] <= 0f || bounds[i].isEmpty()) continue;
            left = Math.min(left, bounds[i].left); top = Math.min(top, bounds[i].top);
            right = Math.max(right, bounds[i].right); bottom = Math.max(bottom, bounds[i].bottom);
        }
        int margin = AndroidUtilities.dp(NekoConfig.ios27LiquidGlass ? NekoConfig.getIos27GlassSamplePaddingDp() : 12);
        int x = (int) Math.floor(left) - margin, y = (int) Math.floor(top) - margin;
        int width = (int) Math.ceil(right) - x + margin, height = (int) Math.ceil(bottom) - y + margin;
        if (width <= 0 || height <= 0) return;
        shader.setFloatUniform("resolution", (float) width, (float) height);
        boolean touchActive = false;
        for (int i = 0; i < members.length; i++) {
            if (members[i] == null || bounds[i].isEmpty()) continue;
            RectF b = bounds[i];
            int j = i * 4;
            // A disappearing recording button shrinks out of the union instead of leaving a bridge.
            float visibility = Math.max(0f, Math.min(1f, alpha[i]));
            shapes[j] = b.centerX() - x; shapes[j + 1] = b.centerY() - y;
            shapes[j + 2] = b.width() * .5f * visibility;
            shapes[j + 3] = b.height() * .5f * visibility;
            org.telegram.ui.Components.blur3.Ios27GlassMaterial.lens(
                    shapes[j + 2] * 2f, shapes[j + 3] * 2f, AndroidUtilities.density, lensParams, j);
            lensParams[j] *= NekoConfig.ios27GlassRefraction / .7f;
            float scale = Math.min(b.width() / Math.max(1, members[i].getPaddedBounds().width()),
                    b.height() / Math.max(1, members[i].getPaddedBounds().height()));
            // Preserve the affine shape: a stretched circle is an ellipse, not a capsule.
            shapeScales[i * 2] = b.width() / Math.max(1, members[i].getPaddedBounds().width()) / Math.max(.001f, scale);
            shapeScales[i * 2 + 1] = b.height() / Math.max(1, members[i].getPaddedBounds().height()) / Math.max(.001f, scale);
            radii[i] = Math.min(members[i].boundProps.radii[0] * scale * visibility,
                    Math.min(shapes[j + 2], shapes[j + 3]));
            for (int corner = 0; corner < 4; corner++) {
                cornerRadii[j + corner] = Math.min(members[i].boundProps.radii[corner * 2] * scale * visibility,
                        Math.min(shapes[j + 2], shapes[j + 3]));
            }
            opacity[i] = visibility * members[i].getAlpha() / 255f;
            int color = members[i].backgroundColor;
            float tint = refractive && NekoConfig.isAdvancedGlassEnabled()
                    ? Math.max(0f, Math.min(1f, NekoConfig.getGlassTintPercent() / 100f)) * .60f : 1f;
            if (refractive && !NekoConfig.isAdvancedGlassEnabled() && BlurredBackgroundProviderImpl.isBlackWhiteTintEnabled()) {
                tint *= Math.max(0f, Math.min(1f, NekoConfig.getGlassTintPercent() / 100f));
            }
            float a = Color.alpha(color) / 255f * tint;
            colors[j] = Color.red(color) / 255f * a;
            colors[j + 1] = Color.green(color) / 255f * a;
            colors[j + 2] = Color.blue(color) / 255f * a; colors[j + 3] = a;
            if (effects != null && effects[i] != null) effects[i].writeGlow(glows, j,
                    x - (origins == null ? 0f : origins[i * 2]), y - (origins == null ? 0f : origins[i * 2 + 1]));
            else glows[j + 3] = 0f;
            touchActive |= glows[j + 3] > 0f;
        }
        shader.setFloatUniform("shapes", shapes);
        shader.setFloatUniform("shapeScales", shapeScales);
        shader.setFloatUniform("lensParams", lensParams);
        shader.setFloatUniform("cornerRadii", cornerRadii);
        shader.setFloatUniform("tints", colors);
        shader.setFloatUniform("opacity", opacity);
        shader.setFloatUniform("merges", merges);
        shader.setFloatUniform("refractive", refractive ? 1f : 0f);
        Arrays.fill(links, 0f);
        if (touchActive) {
            for (int i = 0; i < members.length; i++) {
                links[i * 8 + i] = opacity[i] > 0 ? 1f : 0f;
                for (int j = i + 1; j < members.length; j++) {
                    links[i * 8 + j] = links[j * 8 + i] = connection(i, j, merges[i * 8 + j]);
                }
            }
            propagateLight(links, members.length);
        }
        shader.setFloatUniform("touchActive", touchActive ? 1f : 0f);
        shader.setFloatUniform("links", links);
        shader.setFloatUniform("glows", glows);
        shader.setFloatUniform("ios27", NekoConfig.ios27LiquidGlass ? 1f : 0f);
        shader.setFloatUniform("ios27LightIntensity", NekoConfig.getIos27GlassLightIntensity());
        shader.setFloatUniform("ios27LightFalloff", NekoConfig.ios27GlassGlare);
        shader.setFloatUniform("ios27SolidGlare", NekoConfig.ios27GlassGlareMode == NekoConfig.GLASS_GLARE_SOLID ? 1f : 0f);
        shader.setFloatUniform("darkMode", org.telegram.ui.ActionBar.Theme.isCurrentThemeDark() ? 1f : 0f);
        shader.setFloatUniform("pixelScale", AndroidUtilities.density);
        shader.setFloatUniform("advanced", refractive && NekoConfig.isAdvancedGlassEnabled() ? 1f : 0f);
        float fresnel = Math.max(.25f, NekoConfig.getGlassRefraction());
        shader.setFloatUniform("depth", NekoConfig.ios27LiquidGlass ? AndroidUtilities.dpf2(30f) : AndroidUtilities.dp(16) * fresnel);
        shader.setFloatUniform("amount", NekoConfig.ios27LiquidGlass ? AndroidUtilities.dpf2(21f) : -AndroidUtilities.dp(32) * fresnel);
        shader.setFloatUniform("thickness", Math.max(1, AndroidUtilities.dp(NekoConfig.liquidGlassThickness)));
        shader.setFloatUniform("intensity", NekoConfig.liquidGlassIntensity);
        shader.setFloatUniform("index", Math.max(1.001f, center.boundProps.liquidIndex));
        shader.setFloatUniform("dispersion", Math.max(0f, Math.min(1f, NekoConfig.getGlassDispersion())));
        shader.setFloatUniform("angle", (float) Math.toRadians(NekoConfig.getGlassGlareAngle()));
        shader.setFloatUniform("glare", Math.max(0f, NekoConfig.getGlassGlare()));
        shader.setFloatUniform("glassAlpha", refractive && NekoConfig.isAdvancedGlassEnabled()
                ? Math.max(0f, Math.min(1f, NekoConfig.getGlassAlphaPercent() / 100f)) : 1f);
        shader.setColorUniform("shadow", NekoConfig.ios27LiquidGlass
                ? (org.telegram.ui.ActionBar.Theme.isCurrentThemeDark() ? 0x0a000000 : 0x05000000) : center.shadowColor);
        shader.setFloatUniform("shadowRadius", NekoConfig.ios27LiquidGlass ? AndroidUtilities.dpf2(15f) : Math.max(1f, center.shadowLayerRadius));
        BlurredBackgroundSource source = center.getSource();
        source.prepareToDraw();
        node.setPosition(0, 0, width, height);
        Canvas recording = node.beginRecording();
        // Sample the shared backdrop in host coordinates; spring movement affects shapes only.
        float sourceX = center.sourceOffsetX - (sourceOrigins == null ? 0f : sourceOrigins[sourceIndex * 2]);
        float sourceY = center.sourceOffsetY - (sourceOrigins == null ? 0f : sourceOrigins[sourceIndex * 2 + 1]);
        recording.translate(-x - sourceX, -y - sourceY);
        source.draw(recording, x + sourceX, y + sourceY,
                x + width + sourceX, y + height + sourceY);
        node.endRecording();
        node.setRenderEffect(RenderEffect.createRuntimeShaderEffect(shader, "img"));
        int save = canvas.save();
        try {
            canvas.translate(x, y);
            canvas.drawRenderNode(node);
        } finally {
            canvas.restoreToCount(save);
        }
    }

    private static void propagateLight(float[] links, int count) {
        for (int k = 0; k < count; k++) {
            for (int i = 0; i < count; i++) {
                for (int j = 0; j < count; j++) {
                    links[i * 8 + j] = Math.max(links[i * 8 + j], links[i * 8 + k] * links[k * 8 + j]);
                }
            }
        }
    }

    private float connection(int a, int b, float merge) {
        if (opacity[a] <= 0f || opacity[b] <= 0f) return 0f;
        int i = a * 4, j = b * 4;
        float rx = Math.max(0f, Math.abs(shapes[i] - shapes[j])
                - shapes[i + 2] - shapes[j + 2] + radii[a] + radii[b]);
        float ry = Math.max(0f, Math.abs(shapes[i + 1] - shapes[j + 1])
                - shapes[i + 3] - shapes[j + 3] + radii[a] + radii[b]);
        float gap = (float) Math.hypot(rx, ry) - radii[a] - radii[b];
        float t = Math.max(0f, Math.min(1f, (merge * .5f - gap) / (merge * .25f)));
        return t * t * (3f - 2f * t);
    }
}
