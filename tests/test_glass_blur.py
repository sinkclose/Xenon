"""Verify glass radius and effect updates using production Java methods.

Run: python3 -m unittest discover -s tests -p 'test_glass*.py' -v
Requires Java 17+ with the jdk.compiler module; no Android SDK is needed.
"""

import pathlib
import re
import subprocess
import tempfile
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[1] / "TMessagesProj/src/main/java"


def method(source, signature):
    start = source.index(signature)
    end = source.index("{", start) + 1
    depth = 1
    while depth:
        depth += (source[end] == "{") - (source[end] == "}")
        end += 1
    return source[start:end]


class GlassBlurTest(unittest.TestCase):
    def test_radius_and_live_effect_updates(self):
        config = (ROOT / "zxc/iconic/xenon/NekoConfig.java").read_text()
        source = (ROOT / "org/telegram/ui/Components/blur3/source/BlurredBackgroundSourceRenderNode.java").read_text()
        downscale = (ROOT / "org/telegram/ui/Components/blur3/DownscaleScrollableNoiseSuppressor.java").read_text()
        config_methods = method(config, "public static float getGlassBlurRadiusDp()")
        source_methods = "\n".join(method(source, signature) for signature in (
            "public void setBlur(float radius)",
            "public void setBlur(float radius, RenderEffect effect)",
            "public void setGlassBlur(float standardRadius)",
            "public void setGlassBlur(float standardRadius, RenderEffect standardEffect)",
            "private void syncGlassBlur()",
            "public void prepareToDraw()"))
        source_methods = source_methods.replace("org.telegram.messenger.AndroidUtilities", "AndroidUtilities")
        source_methods = source_methods.replace("zxc.iconic.xenon.NekoConfig", "NekoConfig")
        source_methods = re.sub(r"\s*@RequiresApi\([^\n]*\)", "", source_methods)
        downscale_methods = "\n".join(method(downscale, signature) for signature in (
            "public static float convertRadiusToSigma(float radius)",
            "public static float convertSigmaToRadius(float sigma)",
            "public static float downscaleRadius(float radius, float scale)"))
        harness = """
public class GlassBlurHarness {
    static class NekoConfig {
        static int blurStrength;
        static boolean useAdvancedLiquidGlass;
        CONFIG_METHODS
    }
    static class AndroidUtilities {
        static float dpf2(float dp) { return dp * 2; }
    }
    static class Build {
        static class VERSION { static int SDK_INT = 33; }
        static class VERSION_CODES { static final int S = 31; }
    }
    static class Shader { enum TileMode { CLAMP } }
    static class RenderEffect {
        float radius;
        static RenderEffect createBlurEffect(float x, float y, Shader.TileMode mode) {
            if (x <= 0 || y <= 0) throw new AssertionError("Nonpositive blur radius");
            RenderEffect effect = new RenderEffect(); effect.radius = x; return effect;
        }
        static RenderEffect createChainEffect(RenderEffect blur, RenderEffect other) { return blur; }
    }
    static class RenderNode {
        RenderEffect effect;
        int updates;
        void setRenderEffect(RenderEffect effect) { this.effect = effect; updates++; }
    }
    static class Source {
        RenderNode renderNode = new RenderNode();
        boolean progressiveEffectApplied;
        float lastBlurRadius = -1;
        RenderEffect lastBlurEffect;
        boolean glassBlurEnabled;
        float standardGlassBlurRadius;
        RenderEffect standardGlassBlurEffect;
        Object underSource;
        int wallpaperParentW, wallpaperParentH;
        void syncWallpaperEffect() {}
        SOURCE_METHODS
    }
    static final float BLUR_SIGMA_SCALE = 0.57735f;
    DOWNSCALE_METHODS
    static void check(boolean condition) { if (!condition) throw new AssertionError(); }
    public static void main(String[] args) {
        NekoConfig.useAdvancedLiquidGlass = true;
        Source[] sources = {new Source(), new Source(), new Source(), new Source()};
        float[] defaults = {4, 7, 8, 24};
        for (int i = 0; i < sources.length; i++) sources[i].setGlassBlur(defaults[i]);
        for (int strength : new int[]{0, 1, 15, 30, 40, 0}) {
            NekoConfig.blurStrength = strength;
            for (Source source : sources) {
                source.prepareToDraw();
                if (strength == 0) check(source.renderNode.effect == null);
                else check(Math.abs(source.renderNode.effect.radius - strength / 3.75f * 2) < 0.0001);
                int updates = source.renderNode.updates;
                source.prepareToDraw();
                check(source.renderNode.updates == updates);
            }
        }
        check(downscaleRadius(0, 2) == 0);
        check(downscaleRadius(16, 2) > 0);
        NekoConfig.useAdvancedLiquidGlass = false;
        for (int i = 0; i < sources.length; i++) {
            sources[i].prepareToDraw();
            check(sources[i].renderNode.effect.radius == defaults[i]);
        }
        RenderEffect saturation = new RenderEffect();
        sources[0].setGlassBlur(4, saturation);
        check(sources[0].lastBlurEffect == saturation);
        NekoConfig.useAdvancedLiquidGlass = true;
        NekoConfig.blurStrength = 0;
        sources[0].prepareToDraw();
        check(sources[0].renderNode.effect == null);
        check(sources[0].lastBlurEffect == null);
    }
}
""".replace("CONFIG_METHODS", config_methods).replace("SOURCE_METHODS", source_methods).replace("DOWNSCALE_METHODS", downscale_methods)
        with tempfile.TemporaryDirectory() as directory:
            path = pathlib.Path(directory) / "GlassBlurHarness.java"
            path.write_text(harness)
            compiled = subprocess.run(["java", "-m", "jdk.compiler/com.sun.tools.javac.Main", str(path)],
                                      capture_output=True, text=True)
            self.assertEqual(0, compiled.returncode, compiled.stderr)
            result = subprocess.run(["java", "-cp", directory, "GlassBlurHarness"], capture_output=True, text=True)
            self.assertEqual(0, result.returncode, result.stderr)


if __name__ == "__main__":
    unittest.main()
