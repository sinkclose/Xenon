"""Visible surfaces acquire isolated shaders; hidden chat controls do no compilation."""

import unittest

from test_glass_blur import ROOT, method
from test_glass_chat_refresh import run_java


class GlassLazyEffectTest(unittest.TestCase):
    def test_hidden_surfaces_and_configuration_changes(self):
        source = (ROOT / "org/telegram/ui/Components/blur3/drawable/BlurredBackgroundDrawableRenderNode.java").read_text()
        methods = "\n".join(method(source, signature) for signature in (
            "public void setLiquidGlassEffectAllowed(boolean fixedRefraction)",
            "public void recreateLiquidGlassEffect()", "private void ensureLiquidGlassEffect()"))
        harness = r'''
public class LazyGlassHarness {
    static void check(boolean condition) { if (!condition) throw new AssertionError(); }
    static class RenderNode { void setRenderEffect(Object value) {} }
    static class LiquidGlassEffect {
        static int creations, configuration;
        final int createdConfiguration = configuration;
        final boolean fixed;
        boolean ensureShaders() { return false; }
        LiquidGlassEffect(RenderNode node, boolean fixed, Runnable onReady) { creations++; this.fixed = fixed; }
        boolean isConfigurationCurrent() { return createdConfiguration == configuration; }
    }
    static class Surface {
        boolean liquidGlassEffectAllowed, fixedRefraction, renderNodeInvalidated;
        LiquidGlassEffect liquidGlassEffect;
        RenderNode renderNodeFill = new RenderNode();
        Runnable onShadersReady;
        int alpha = 255;
        int getAlpha() { return alpha; }
        METHODS
    }
    public static void main(String[] args) {
        Surface[] hidden = new Surface[100];
        for (int i = 0; i < hidden.length; i++) {
            hidden[i] = new Surface(); hidden[i].setLiquidGlassEffectAllowed(i % 2 == 0);
        }
        check(LiquidGlassEffect.creations == 0);
        Surface visible = hidden[0]; visible.ensureLiquidGlassEffect();
        check(LiquidGlassEffect.creations == 1 && visible.liquidGlassEffect.fixed);
        LiquidGlassEffect first = visible.liquidGlassEffect;
        for (int frame = 0; frame < 1000; frame++) visible.ensureLiquidGlassEffect();
        check(LiquidGlassEffect.creations == 1 && first == visible.liquidGlassEffect);
        hidden[1].ensureLiquidGlassEffect();
        check(LiquidGlassEffect.creations == 2 && hidden[1].liquidGlassEffect != first);
        Surface transparent = hidden[2]; transparent.alpha = 0; transparent.ensureLiquidGlassEffect();
        check(LiquidGlassEffect.creations == 2);
        LiquidGlassEffect.configuration++;
        for (Surface surface : hidden) surface.recreateLiquidGlassEffect();
        check(LiquidGlassEffect.creations == 2);
        visible.ensureLiquidGlassEffect();
        check(LiquidGlassEffect.creations == 3 && first != visible.liquidGlassEffect);
        transparent.alpha = 255; transparent.ensureLiquidGlassEffect();
        check(LiquidGlassEffect.creations == 4 && transparent.liquidGlassEffect.fixed);
        LiquidGlassEffect.configuration++;
        visible.ensureLiquidGlassEffect();
        check(LiquidGlassEffect.creations == 5 && visible.liquidGlassEffect.isConfigurationCurrent());
        Surface plain = new Surface(); plain.ensureLiquidGlassEffect();
        check(LiquidGlassEffect.creations == 5 && plain.liquidGlassEffect == null);
    }
}
'''.replace("METHODS", methods)
        run_java(self, "LazyGlassHarness", harness)


if __name__ == "__main__":
    unittest.main()
