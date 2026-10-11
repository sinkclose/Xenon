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
        int margin = AndroidUtilities.dp(12);
        int x = (int) Math.floor(left) - margin, y = (int) Math.floor(top) - margin;
        int width = (int) Math.ceil(right) - x + margin, height = (int) Math.ceil(bottom) - y + margin;
        if (width <= 0 || height <= 0) return;
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
            float scale = Math.min(b.width() / Math.max(1, members[i].getPaddedBounds().width()),
                    b.height() / Math.max(1, members[i].getPaddedBounds().height()));
            radii[i] = Math.min(members[i].boundProps.radii[0] * scale * visibility,
                    Math.min(shapes[j + 2], shapes[j + 3]));
            for (int corner = 0; corner < 4; corner++) {
                cornerRadii[j + corner] = Math.min(members[i].boundProps.radii[corner * 2] * scale * visibility,
                        Math.min(shapes[j + 2], shapes[j + 3]));
            }
            opacity[i] = visibility * members[i].getAlpha() / 255f;
            int color = members[i].backgroundColor;
            float tint = refractive && NekoConfig.useAdvancedLiquidGlass
                    ? Math.max(0f, Math.min(1f, NekoConfig.advancedGlassTintPercent / 100f)) * .60f : 1f;
            if (refractive && !NekoConfig.useAdvancedLiquidGlass && BlurredBackgroundProviderImpl.isBlackWhiteTintEnabled()) {
                tint *= Math.max(0f, Math.min(1f, NekoConfig.advancedGlassTintPercent / 100f));
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
        shader.setFloatUniform("advanced", refractive && NekoConfig.useAdvancedLiquidGlass ? 1f : 0f);
        float fresnel = Math.max(.25f, NekoConfig.advancedGlassFresnel);
        shader.setFloatUniform("depth", AndroidUtilities.dp(16) * fresnel);
        shader.setFloatUniform("amount", -AndroidUtilities.dp(32) * fresnel);
        shader.setFloatUniform("thickness", Math.max(1, AndroidUtilities.dp(NekoConfig.liquidGlassThickness)));
        shader.setFloatUniform("intensity", NekoConfig.liquidGlassIntensity);
        shader.setFloatUniform("index", Math.max(1.001f, center.boundProps.liquidIndex));
        shader.setFloatUniform("dispersion", Math.max(0f, Math.min(1f, NekoConfig.advancedGlassDispersion)));
        shader.setFloatUniform("angle", (float) Math.toRadians(NekoConfig.advancedGlassGlareAngle));
        shader.setFloatUniform("glare", Math.max(0f, NekoConfig.advancedGlassGlare));
        shader.setFloatUniform("glassAlpha", refractive && NekoConfig.useAdvancedLiquidGlass
                ? Math.max(0f, Math.min(1f, NekoConfig.advancedGlassAlpha / 100f)) : 1f);
        shader.setColorUniform("shadow", center.shadowColor);
        shader.setFloatUniform("shadowRadius", Math.max(1f, center.shadowLayerRadius));
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
