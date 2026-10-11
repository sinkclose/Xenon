"""Verify Prism shader selection and the shared optical uniform contract without an SDK.

Run: python3 -m unittest discover -s tests -p 'test_glass*.py' -v
"""

import re
import unittest

from test_glass_blur import ROOT
from test_glass_chat_refresh import run_java


def java_class(name):
    code = (ROOT / f"org/telegram/ui/Components/blur3/{name}.java").read_text()
    code = re.sub(r"^(?:package|import) .*;\n", "", code, flags=re.M)
    code = re.sub(r"@RequiresApi\([^\n]*\)\n", "", code)
    for qualified, simple in (
        ("org.telegram.messenger.LiteMode", "LiteMode"),
        ("org.telegram.messenger.FileLog", "FileLog"),
        ("org.telegram.ui.Components.blur3.drawable.color.impl.BlurredBackgroundProviderImpl", "TintProvider"),
        ("org.telegram.ui.ActionBar.Theme", "Theme"),
        ("zxc.iconic.xenon.NekoConfig", "NekoConfig"),
    ):
        code = code.replace(qualified, simple)
    return code.replace(f"public class {name}", f"static class {name}").replace(
        f"final class {name}", f"static final class {name}")


class PrismGlassTest(unittest.TestCase):
    def test_uniform_caching_isolation_settings_and_switching(self):
        harness = r'''
import java.util.*;
public class PrismHarness {
    static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    static void near(float expected, float value) {
        check(Math.abs(expected - value) < .001f, expected + " != " + value);
    }
    static class Color {
        static final int WHITE = -1, TRANSPARENT = 0;
        static int alpha(int c) { return c >>> 24; }
        static int red(int c) { return c >> 16 & 255; }
        static int green(int c) { return c >> 8 & 255; }
        static int blue(int c) { return c & 255; }
    }
    static class AndroidUtilities {
        static float density = 2;
        static int dp(float v) { return Math.round(v * density); }
        static float dpf2(float v) { return v * density; }
    }
    static class NekoConfig {
        static boolean usePrismGlass = true, useAdvancedLiquidGlass = true;
        static float advancedGlassFresnel = 1, advancedGlassDispersion = 1;
        static float advancedGlassGlare = 1, advancedGlassGlareAngle = 45;
        static int advancedGlassTintPercent = 20;
    }
    static class TintProvider { static boolean bw; static boolean isBlackWhiteTintEnabled() { return bw; } }
    static class Theme {
        static int multAlpha(int c, float a) { return (c & 0xFFFFFF) | Math.round(Color.alpha(c) * a) << 24; }
    }
    static class LiteMode {
        static final int FLAG_LIQUID_GLASS = 1;
        static boolean enabled = true;
        static boolean isEnabled(int flag) { return enabled; }
    }
    static class FileLog { static int failures; static void e(Throwable error) { failures++; } }
    static class RuntimeShader {
        Map<String, float[]> values = new HashMap<>();
        final String kind;
        int writes;
        RuntimeShader(String kind) { this.kind = kind; }
        void setFloatUniform(String name, float... values) {
            for (float v : values) check(Float.isFinite(v), "Nonfinite " + name);
            this.values.put(name, values.clone()); writes++;
        }
        void setColorUniform(String name, int c) {}
    }
    static class GlassShaderCache {
        static int creations; static boolean available = true;
        static RuntimeShader acquireRefraction(boolean advanced, boolean prism, Runnable onReady) {
            if (!available) return null;
            creations++; return new RuntimeShader((prism ? "prism_" : "") + (advanced ? "advanced" : "standard"));
        }
        static RuntimeShader acquireHighlight(Runnable onReady) { return available ? new RuntimeShader("highlight") : null; }
        static void prewarm(boolean enabled, boolean advanced, boolean prism) {}
    }
    static class RenderEffect {
        RuntimeShader shader;
        Map<String, float[]> snapshot;
        static RenderEffect createRuntimeShaderEffect(RuntimeShader shader, String name) {
            check(name.equals("img"), "Wrong input shader");
            RenderEffect effect = new RenderEffect(); effect.shader = shader;
            effect.snapshot = new HashMap<>(shader.values); return effect;
        }
    }
    static class RenderNode {
        int width = 240, height = 80, updates;
        RenderEffect effect;
        int getWidth() { return width; } int getHeight() { return height; }
        void setRenderEffect(RenderEffect e) { effect = e; updates++; }
    }
    static class BlendMode { static final int PLUS = 1; }
    static class Paint {
        static final int ANTI_ALIAS_FLAG = 1;
        static class Style { static final int STROKE = 1; }
        Paint(int flags) {}
        void setStyle(int v) {} void setStrokeWidth(float v) {} void setBlendMode(int v) {}
        void setColor(int v) {} void setAlpha(int v) {} void setShader(RuntimeShader s) {}
    }
    static class RectF { void set(float l, float t, float r, float b) {} }
    static class Path {
        static class Direction { static final int CW = 1; }
        boolean empty = true;
        boolean isEmpty() { return empty; } void reset() { empty = true; }
        void addRoundRect(RectF r, float[] corners, int direction) { empty = false; }
    }
    static class Canvas {
        int strokes;
        void save() {} void restore() {} void clipPath(Path path) {}
        void drawPath(Path p, Paint paint) { strokes++; }
    }
    EFFECT_CLASS
    static void update(LiquidGlassEffect effect) {
        effect.update(0, 0, 240, 80, 12, 18, 22, 8, 11, .75f, 1.5f, 0x80402010);
    }
    public static void main(String[] args) {
        GlassShaderCache.available = false;
        RenderNode coldNode = new RenderNode(); LiquidGlassEffect cold = new LiquidGlassEffect(coldNode);
        update(cold); check(coldNode.effect == null && !cold.hasRefractionShader(), "Cold surface must keep fallback");
        GlassShaderCache.available = true;
        check(cold.ensureShaders(), "Prepared shaders must attach");
        update(cold);
        check(coldNode.effect != null && cold.highlightShader != null, "Stable geometry must initialize late uniforms");
        near(32, coldNode.effect.shader.values.get("refractionHeight")[0]);
        check(!cold.ensureShaders(), "Prepared instances must be retained");
        RenderNode node = new RenderNode(); LiquidGlassEffect effect = new LiquidGlassEffect(node);
        update(effect);
        check(node.effect.shader.kind.equals("prism_advanced"), "Pipeline must use the existing advanced shader");
        RuntimeShader shader = node.effect.shader;
        near(32, shader.values.get("refractionHeight")[0]);
        near(-64, shader.values.get("refractionAmount")[0]);
        near(12, shader.values.get("radius")[0]);
        int writes = shader.writes, updates = node.updates, creations = GlassShaderCache.creations;
        for (int frame = 0; frame < 1000; frame++) update(effect);
        check(shader.writes == writes && node.updates == updates, "Idle frame rebuilt effect");
        check(GlassShaderCache.creations == creations, "Idle frame compiled shader");

        // Switching Prism changes the GPU program, preserving optical uniforms.
        NekoConfig.usePrismGlass = false;
        check(!effect.isConfigurationCurrent(), "Pipeline switch did not invalidate GPU program");
        RenderNode legacyNode = new RenderNode();
        LiquidGlassEffect legacy = new LiquidGlassEffect(legacyNode); update(legacy);
        check(legacyNode.effect.shader.kind.equals("advanced"), "Legacy shader selection");
        for (String uniform : shader.values.keySet()) {
            check(Arrays.equals(shader.values.get(uniform), legacyNode.effect.shader.values.get(uniform)),
                    "Pipeline changed optical uniform " + uniform);
        }
        NekoConfig.usePrismGlass = true;
        check(effect.isConfigurationCurrent() && !legacy.isConfigurationCurrent(), "Reverse pipeline switch");
        update(effect);
        check(node.effect.shader == shader && shader.writes == writes, "Unchanged Prism recompiled shader");

        Canvas canvas = new Canvas(); effect.drawHighlight(canvas, 1);
        check(canvas.strokes == 1, "Existing glare pass must be preserved");
        NekoConfig.advancedGlassDispersion = 0; update(effect);
        near(0, node.effect.snapshot.get("chromaticAberration")[0]);
        check(node.updates == updates + 1, "Dispersion did not publish uniforms");
        updates = node.updates;
        NekoConfig.advancedGlassGlareAngle = 180; update(effect);
        check(node.updates == updates, "Glare-only change rebuilt refraction effect");

        RenderNode otherNode = new RenderNode(); LiquidGlassEffect other = new LiquidGlassEffect(otherNode);
        other.update(10, 20, 90, 60, 200, -2, 50, 1, 11, .75f, 1.5f, 0);
        check(otherNode.effect.shader != shader, "Surfaces share mutable uniforms");
        near(120, shader.values.get("center")[0]);
        near(50, otherNode.effect.snapshot.get("center")[0]);
        near(200, otherNode.effect.snapshot.get("radius")[0]);
        near(-2, otherNode.effect.snapshot.get("radius")[1]);

        NekoConfig.useAdvancedLiquidGlass = false;
        check(!effect.isConfigurationCurrent(), "Optical mode change not detected");
        LiquidGlassEffect standard = new LiquidGlassEffect(node); update(standard);
        check(node.effect.shader.kind.equals("prism_standard"), "Standard mode must use the existing standard shader");
        near(128f / 255, node.effect.snapshot.get("foreground_color_premultiplied")[3]);
        near(22, node.effect.snapshot.get("radius")[0]);
        near(18, node.effect.snapshot.get("radius")[1]);
        near(8, node.effect.snapshot.get("radius")[2]);
        near(12, node.effect.snapshot.get("radius")[3]);
        standard.update(0, 0, 240, 80, 12, 18, 22, 8, 11, 0, 2f, 0);
        near(0, node.effect.snapshot.get("refract_intensity")[0]);
        near(2, node.effect.snapshot.get("refract_index")[0]);
        TintProvider.bw = true; NekoConfig.advancedGlassTintPercent = 0; update(standard);
        near(0, node.effect.snapshot.get("foreground_color_premultiplied")[3]);

        LiquidGlassEffect fixed = new LiquidGlassEffect(node, true); update(fixed);
        float fixedAmount = node.effect.snapshot.get("refractionAmount")[0];
        NekoConfig.advancedGlassFresnel = 2; update(fixed);
        near(fixedAmount, node.effect.snapshot.get("refractionAmount")[0]);
        NekoConfig.useAdvancedLiquidGlass = true;
        check(fixed.isConfigurationCurrent(), "Fixed capsule incorrectly follows advanced toggle");
        LiteMode.enabled = false;
        check(!fixed.isConfigurationCurrent(), "Master switch change not detected");
        LiquidGlassEffect frosted = new LiquidGlassEffect(node); update(frosted);
        check(node.effect == null, "Disabled refraction left a stale effect");
        Canvas frostedCanvas = new Canvas(); frosted.drawHighlight(frostedCanvas, 1);
        check(frostedCanvas.strokes == 1, "Frosted glare must remain unchanged");
    }
}
'''.replace("EFFECT_CLASS", java_class("LiquidGlassEffect"))
        run_java(self, "PrismHarness", harness)


if __name__ == "__main__":
    unittest.main()
