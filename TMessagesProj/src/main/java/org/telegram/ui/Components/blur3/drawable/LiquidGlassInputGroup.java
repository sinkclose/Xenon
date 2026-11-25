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
import org.telegram.ui.Components.blur3.GlassShaderCache;
import org.telegram.ui.Components.blur3.Ios27GlassMaterial;
import org.telegram.ui.Components.blur3.source.BlurredBackgroundSource;
import org.telegram.ui.Components.blur3.drawable.color.impl.BlurredBackgroundProviderImpl;
import org.telegram.ui.Components.LiquidTouchEffect;
import zxc.iconic.xenon.NekoConfig;
import java.util.Arrays;
import java.util.HashMap;

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
    private static final String[] ARRAY_UNIFORMS = {
            "shapes", "shapeScales", "lensParams", "cornerRadii", "tints", "opacity", "merges", "links", "glows"
    };
    private final float[][] arrayValues = {shapes, shapeScales, lensParams, cornerRadii, colors, opacity, merges, links, glows};
    private final float[][] uploadedArrays = new float[ARRAY_UNIFORMS.length][];
    private final HashMap<String, ScalarUniform> uploadedScalars = new HashMap<>();
    private boolean effectDirty = true, shadowColorKnown;
    private int uploadedShadowColor;
    private int effectWidth, effectHeight;

    private BlurredBackgroundSource recordedSource;
    private long recordedSourceKey = -1;
    private int recordedWidth, recordedHeight;
    private float recordedSourceLeft, recordedSourceTop, recordedBlurOpacity;
    private boolean recordedBlurPlane;

    private static final class ScalarUniform {
        float value;
        ScalarUniform(float value) { this.value = value; }
    }

    public static LiquidGlassInputGroup acquirePrepared(int shapeCount, Runnable onReady) {
        RuntimeShader shader = GlassShaderCache.acquireUnion(shapeCount, () -> shaderSource(shapeCount), onReady);
        return shader == null ? null : new LiquidGlassInputGroup(shapeCount, shader);
    }

    private LiquidGlassInputGroup(int shapeCount, RuntimeShader shader) {
        this.shapeCount = shapeCount;
        this.shader = shader;
        Arrays.fill(merges, AndroidUtilities.dp(8));
    }

    private static String shaderSource(int shapeCount) {
        if (shapeCount < 1 || shapeCount > 8) throw new IllegalArgumentException("Invalid glass group size");
        return GlassShaderCache.withIos27PressTransfer(
                GlassShaderCache.withIos27Optics(AndroidUtilities.readRes(R.raw.liquid_glass_input_union)))
                .replace("const int SHAPE_COUNT = 8;", "const int SHAPE_COUNT = " + shapeCount + ";");
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
        effectDirty = true;
        recordedSource = null;
    }

    private boolean materialModeKnown, previousGlassEnabled, previousIos27;

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
        draw(canvas, members, bounds, alpha, effects, origins, sourceOrigins, true);
    }

    /** Persistent navigation shells fade at full geometry; composer members shrink into their union. */
    public void draw(Canvas canvas, BlurredBackgroundDrawable[] members, RectF[] bounds, float[] alpha,
                     LiquidTouchEffect[] effects, float[] origins, float[] sourceOrigins, boolean shrinkWithVisibility) {
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
        boolean ios27 = NekoConfig.ios27LiquidGlass && refractive;
        if (!materialModeKnown || previousGlassEnabled != refractive || previousIos27 != ios27) {
            materialModeKnown = true;
            previousGlassEnabled = refractive;
            previousIos27 = ios27;
            for (BlurredBackgroundDrawable member : members) if (member != null) member.updateColors();
        }
        float left = bounds[sourceIndex].left, top = bounds[sourceIndex].top;
        float right = bounds[sourceIndex].right, bottom = bounds[sourceIndex].bottom;
        for (int i = 0; i < members.length; i++) {
            if (members[i] == null || alpha[i] <= 0f || bounds[i].isEmpty()) continue;
            left = Math.min(left, bounds[i].left); top = Math.min(top, bounds[i].top);
            right = Math.max(right, bounds[i].right); bottom = Math.max(bottom, bounds[i].bottom);
        }
        int margin = AndroidUtilities.dp(ios27 ? NekoConfig.getIos27GlassSamplePaddingDp(
                Math.min(right - left, bottom - top) / AndroidUtilities.density) : 12);
        int x = (int) Math.floor(left) - margin, y = (int) Math.floor(top) - margin;
        int width = (int) Math.ceil(right) - x + margin, height = (int) Math.ceil(bottom) - y + margin;
        if (width <= 0 || height <= 0) return;
        if (effectWidth != width || effectHeight != height) {
            shader.setFloatUniform("resolution", (float) width, (float) height);
            effectWidth = width;
            effectHeight = height;
            effectDirty = true;
        }
        boolean touchActive = false;
        for (int i = 0; i < members.length; i++) {
            if (members[i] == null || bounds[i].isEmpty()) continue;
            RectF b = bounds[i];
            int j = i * 4;
            // A disappearing recording button shrinks out of the union instead of leaving a bridge.
            float visibility = Math.max(0f, Math.min(1f, alpha[i]));
            opacity[i] = visibility * members[i].getAlpha() / 255f;
            if (opacity[i] <= 0f) continue;
            float shapeVisibility = shrinkWithVisibility ? visibility : 1f;
            shapes[j] = b.centerX() - x; shapes[j + 1] = b.centerY() - y;
            shapes[j + 2] = b.width() * .5f * shapeVisibility;
            shapes[j + 3] = b.height() * .5f * shapeVisibility;
            if (ios27) {
                Ios27GlassMaterial.renderLens(
                        shapes[j + 2] * 2f, shapes[j + 3] * 2f, AndroidUtilities.density,
                        NekoConfig.ios27GlassRefraction, false, lensParams, j);
            }
            float scale = Math.min(b.width() / Math.max(1, members[i].getPaddedBounds().width()),
                    b.height() / Math.max(1, members[i].getPaddedBounds().height()));
            // Preserve the affine shape: a stretched circle is an ellipse, not a capsule.
            shapeScales[i * 2] = b.width() / Math.max(1, members[i].getPaddedBounds().width()) / Math.max(.001f, scale);
            shapeScales[i * 2 + 1] = b.height() / Math.max(1, members[i].getPaddedBounds().height()) / Math.max(.001f, scale);
            radii[i] = Math.min(members[i].boundProps.radii[0] * scale * shapeVisibility,
                    Math.min(shapes[j + 2], shapes[j + 3]));
            for (int corner = 0; corner < 4; corner++) {
                cornerRadii[j + corner] = Math.min(members[i].boundProps.radii[corner * 2] * scale * shapeVisibility,
                        Math.min(shapes[j + 2], shapes[j + 3]));
            }
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
            if (effects != null && effects[i] != null) {
                float originX = x - (origins == null ? 0f : origins[i * 2]);
                float originY = y - (origins == null ? 0f : origins[i * 2 + 1]);
                if (ios27) effects[i].writeIos27Glow(glows, j, originX, originY);
                else effects[i].writeGlow(glows, j, originX, originY);
                touchActive |= refractive && opacity[i] > 0f && glows[j + (ios27 ? 2 : 3)] > 0f;
            }
        }
        Arrays.fill(links, 0f);
        if (touchActive && !ios27) {
            for (int i = 0; i < members.length; i++) {
                links[i * 8 + i] = opacity[i] > 0 ? 1f : 0f;
                for (int j = i + 1; j < members.length; j++) {
                    links[i * 8 + j] = links[j * 8 + i] = connection(i, j, merges[i * 8 + j]);
                }
            }
            propagateLight(links, members.length);
        }
        updateArrayUniforms();
        updateScalar("refractive", refractive ? 1f : 0f);
        updateScalar("touchActive", refractive && touchActive ? 1f : 0f);
        boolean blurPlane = ios27 && NekoConfig.ios27GlassBlur > 0;
        updateScalar("backdropBlur", blurPlane ? 1f : 0f);
        updateScalar("blurMix", NekoConfig.ios27GlassAlpha == 0 ? -1f : NekoConfig.getIos27GlassBlurOpacity());
        updateScalar("ios27", ios27 ? 1f : 0f);
        updateScalar("ios27LightIntensity", NekoConfig.getIos27GlassLightIntensity());
        updateScalar("ios27LightFalloff", NekoConfig.ios27GlassGlare);
        updateScalar("ios27SolidGlare", NekoConfig.ios27GlassGlareMode == NekoConfig.GLASS_GLARE_SOLID ? 1f : 0f);
        updateScalar("darkMode", org.telegram.ui.ActionBar.Theme.isCurrentThemeDark() ? 1f : 0f);
        updateScalar("pixelScale", AndroidUtilities.density);
        updateScalar("advanced", refractive && NekoConfig.isAdvancedGlassEnabled() ? 1f : 0f);
        float fresnel = Math.max(.25f, NekoConfig.getGlassRefraction());
        updateScalar("depth", ios27 ? AndroidUtilities.dpf2(30f) : AndroidUtilities.dp(16) * fresnel);
        updateScalar("amount", ios27 ? AndroidUtilities.dpf2(21f) : -AndroidUtilities.dp(32) * fresnel);
        updateScalar("thickness", Math.max(1, AndroidUtilities.dp(NekoConfig.liquidGlassThickness)));
        updateScalar("intensity", NekoConfig.liquidGlassIntensity);
        updateScalar("index", Math.max(1.001f, center.boundProps.liquidIndex));
        updateScalar("dispersion", Math.max(0f, Math.min(1f, NekoConfig.getGlassDispersion())));
        updateScalar("angle", (float) Math.toRadians(NekoConfig.getGlassGlareAngle()));
        updateScalar("glare", refractive ? Math.max(0f, NekoConfig.getGlassGlare()) : 0f);
        // Backdrop blur opacity is resolved before the union refracts the image.
        updateScalar("glassAlpha", 1f);
        int shadowColor = NekoConfig.ios27LiquidGlass
                ? (org.telegram.ui.ActionBar.Theme.isCurrentThemeDark() ? 0x0a000000 : 0x05000000) : center.shadowColor;
        if (!shadowColorKnown || uploadedShadowColor != shadowColor) {
            shader.setColorUniform("shadow", shadowColor);
            uploadedShadowColor = shadowColor;
            shadowColorKnown = true;
            effectDirty = true;
        }
        updateScalar("shadowRadius", ios27 ? AndroidUtilities.dpf2(15f) : Math.max(1f, center.shadowLayerRadius));
        BlurredBackgroundSource source = center.getSource();
        source.prepareToDraw();
        // Sample the shared backdrop in host coordinates; spring movement affects shapes only.
        float sourceX = center.sourceOffsetX - (sourceOrigins == null ? 0f : sourceOrigins[sourceIndex * 2]);
        float sourceY = center.sourceOffsetY - (sourceOrigins == null ? 0f : sourceOrigins[sourceIndex * 2 + 1]);
        float blurOpacity = blurPlane || !refractive || !NekoConfig.isAdvancedGlassEnabled()
                ? 1f : NekoConfig.getGlassAlphaPercent() / 100f;
        recordBackdrop(source, x + sourceX, y + sourceY, width, height, blurPlane, blurOpacity);
        applyEffectIfChanged();
        int save = canvas.save();
        try {
            canvas.translate(x, y);
            canvas.drawRenderNode(node);
        } finally {
            canvas.restoreToCount(save);
        }
    }

    private void applyEffectIfChanged() {
        if (!effectDirty) return;
        node.setRenderEffect(RenderEffect.createRuntimeShaderEffect(shader, "img"));
        effectDirty = false;
    }

    private void updateArrayUniforms() {
        for (int i = 0; i < arrayValues.length; i++) {
            float[] values = arrayValues[i];
            float[] uploaded = uploadedArrays[i];
            if (Arrays.equals(values, uploaded)) continue;
            shader.setFloatUniform(ARRAY_UNIFORMS[i], values);
            if (uploaded == null) uploadedArrays[i] = values.clone();
            else System.arraycopy(values, 0, uploaded, 0, values.length);
            effectDirty = true;
        }
    }

    private void updateScalar(String uniform, float value) {
        ScalarUniform previous = uploadedScalars.get(uniform);
        if (previous != null && Float.compare(previous.value, value) == 0) return;
        shader.setFloatUniform(uniform, value);
        if (previous == null) uploadedScalars.put(uniform, new ScalarUniform(value));
        else previous.value = value;
        effectDirty = true;
    }

    private void recordBackdrop(BlurredBackgroundSource source, float left, float top, int width, int height,
                                boolean blurPlane, float blurOpacity) {
        long sourceKey = source.retainedDrawCommandsKey();
        if (sourceKey >= 0 && node.hasDisplayList() && recordedSource == source && recordedSourceKey == sourceKey
                && recordedSourceLeft == left && recordedSourceTop == top
                && recordedWidth == width && recordedHeight == height
                && recordedBlurPlane == blurPlane && recordedBlurOpacity == blurOpacity) return;

        node.setPosition(0, 0, blurPlane ? Ios27GlassMaterial.packedBackdropWidth(width) : width, height);
        Canvas recording = node.beginRecording();
        try {
            recording.save();
            recording.clipRect(0, 0, width, height);
            recording.translate(-left, -top);
            if (blurPlane) source.drawUnblurred(recording, left, top, left + width, top + height);
            else source.drawWithBlurOpacity(recording, left, top, left + width, top + height, blurOpacity);
            recording.restore();
            if (blurPlane) {
                recording.save();
                recording.translate(width + 2, 0);
                recording.clipRect(0, 0, (width + 1) / 2, (height + 1) / 2);
                recording.scale(.5f, .5f);
                recording.translate(-left, -top);
                source.draw(recording, left, top, left + width, top + height);
                recording.restore();
            }
        } finally {
            node.endRecording();
        }
        recordedSource = source;
        recordedSourceKey = sourceKey;
        recordedSourceLeft = left;
        recordedSourceTop = top;
        recordedWidth = width;
        recordedHeight = height;
        recordedBlurPlane = blurPlane;
        recordedBlurOpacity = blurOpacity;
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
