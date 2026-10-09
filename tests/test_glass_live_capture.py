"""Verify live glass capture notifications using the production Java method."""

import subprocess
import tempfile
import unittest
from pathlib import Path

from test_glass_blur import ROOT, method


class GlassLiveCaptureTest(unittest.TestCase):
    def test_content_updates_without_geometry_changes(self):
        source = (ROOT / "org/telegram/ui/Components/blur3/DownscaleScrollableNoiseSuppressor.java").read_text()
        capture_method = method(source, "public boolean invalidateResultRenderNodes(IBlur3Capture capture, int width, int height)")
        harness = r'''
import java.util.*;
public class GlassLiveCaptureHarness {
    static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    static class NekoConfig { static int blurStrength = 15; static boolean useAdvancedLiquidGlass = true; }
    static class Canvas { void save() {} void restore() {} void translate(float x, float y) {} }
    static class Rect { int left, top, right = 100, bottom = 100; }
    static class RectF { void set(Rect rect) {} }
    static class RenderNode { boolean recorded; boolean hasDisplayList() { return recorded; } }
    static class SourcePart { Rect position = new Rect(); RenderNode renderNode = new RenderNode(); long lastHash; }
    static class Builder {
        long hash; boolean unsupported;
        void start() { hash = 0; unsupported = false; }
        void add(long value) { hash = hash * 31 + value; }
        void add(boolean value) { add(value ? 1 : 0); }
        long get() { return unsupported ? -1 : hash; }
        boolean isUnsupported() { return unsupported; }
    }
    interface IBlur3Capture {
        void capture(Canvas canvas, RectF position);
        default void captureCalculateHash(Builder builder, RectF position) { builder.unsupported = true; }
    }
    static class Capture implements IBlur3Capture {
        int captures, generation = 1;
        boolean hashed = true;
        public void capture(Canvas canvas, RectF position) { captures++; }
        public void captureCalculateHash(Builder builder, RectF position) {
            if (hashed) builder.add(generation); else builder.unsupported = true;
        }
    }
    static class Suppressor {
        int k, rectRenderNodesCount = 1, compositionChecks;
        boolean isLiquidGlassEnabled = true, allowNoiseSuppress;
        RectF tmpRectF = new RectF(); Builder builder = new Builder();
        List<SourcePart> rectRenderNodes = List.of(new SourcePart());
        static int captureScale(boolean glass, boolean noise, int strength) { return 1; }
        Canvas beginRecordingRect(int index) { return new Canvas(); }
        void endRecordingRect() { rectRenderNodes.get(0).renderNode.recorded = true; }
        // Composition needs rebuilding only once: the same child node receives new content.
        boolean invalidateResultRenderNodes(int width, int height) { return compositionChecks++ == 0; }
        CAPTURE_METHOD
    }
    public static void main(String[] args) {
        Suppressor s = new Suppressor(); Capture capture = new Capture();
        check(s.invalidateResultRenderNodes(capture, 100, 100), "Initial capture must notify");
        check(!s.invalidateResultRenderNodes(capture, 100, 100), "Unchanged content must stay cached");
        check(capture.captures == 1 && s.compositionChecks == 1, "Cache must avoid recording");
        capture.generation++;
        check(s.invalidateResultRenderNodes(capture, 100, 100), "New content with unchanged geometry must notify glass views");
        check(capture.captures == 2 && s.compositionChecks == 2, "New content must reach the existing node");
        check(!s.invalidateResultRenderNodes(capture, 100, 100), "New snapshot must be cached");
        capture.hashed = false;
        for (int frame = 0; frame < 3; frame++) {
            check(s.invalidateResultRenderNodes(capture, 100, 100), "Unhashed animated frames must notify every capture");
        }
        capture.hashed = true;
        check(s.invalidateResultRenderNodes(capture, 100, 100), "Switching back to hashed content must notify");
        NekoConfig.blurStrength++;
        check(s.invalidateResultRenderNodes(capture, 100, 100), "Blur setting changes must notify");
        check(!s.invalidateResultRenderNodes(capture, 100, 100), "Unchanged settings must stay cached");
        s.rectRenderNodesCount = 0;
        check(!s.invalidateResultRenderNodes(capture, 100, 100), "No visible regions must not notify");
    }
}
'''.replace("CAPTURE_METHOD", capture_method.replace("zxc.iconic.xenon.NekoConfig", "NekoConfig"))
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "GlassLiveCaptureHarness.java"
            path.write_text(harness)
            compiled = subprocess.run(["java", "-m", "jdk.compiler/com.sun.tools.javac.Main", str(path)],
                                      capture_output=True, text=True)
            self.assertEqual(0, compiled.returncode, compiled.stderr)
            result = subprocess.run(["java", "-cp", directory, "GlassLiveCaptureHarness"], capture_output=True, text=True)
            self.assertEqual(0, result.returncode, result.stderr)


if __name__ == "__main__":
    unittest.main()
