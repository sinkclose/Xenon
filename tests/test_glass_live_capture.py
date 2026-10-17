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
        region_method = method(source, "public void invalidateSourceRegion(RectF dirty)")
        harness = r'''
import java.util.*;
public class GlassLiveCaptureHarness {
    static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    static class NekoConfig { static int blurStrength = 15; static boolean useAdvancedLiquidGlass = true, usePrismGlass; }
    static class Build { static class VERSION { static int SDK_INT = 33; } }
    static class Canvas { void save() {} void restore() {} void translate(float x, float y) {} }
    static class Rect { int left, top, right = 100, bottom = 100; }
    static class RectF {
        float left, top, right, bottom;
        void set(Rect rect) { left = rect.left; top = rect.top; right = rect.right; bottom = rect.bottom; }
        RectF() {}
        RectF(float l, float t, float r, float b) { left = l; top = t; right = r; bottom = b; }
    }
    static class RenderNode { boolean recorded; boolean hasDisplayList() { return recorded; } }
    static class SourcePart {
        Rect position = new Rect(); RenderNode renderNode = new RenderNode(); long lastHash, contentVersion;
        int materialBlurStrength, materialUpdates; boolean materialAdvanced;
        boolean materialChanged() { return materialBlurStrength != NekoConfig.blurStrength || materialAdvanced != NekoConfig.useAdvancedLiquidGlass; }
        void invalidate() { materialBlurStrength = NekoConfig.blurStrength; materialAdvanced = NekoConfig.useAdvancedLiquidGlass; materialUpdates++; }
    }
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
        int k, rectRenderNodesCount = 1, compositionChecks, recordingIndex;
        boolean isLiquidGlassEnabled = true, allowNoiseSuppress;
        RectF tmpRectF = new RectF(); Builder builder = new Builder();
        List<SourcePart> rectRenderNodes = new ArrayList<>(List.of(new SourcePart()));
        static int captureScale(boolean glass, boolean noise, int strength) { return 1; }
        Canvas beginRecordingRect(int index) { recordingIndex = index; return new Canvas(); }
        void endRecordingRect() { rectRenderNodes.get(recordingIndex).renderNode.recorded = true; rectRenderNodes.get(recordingIndex).invalidate(); }
        // Composition needs rebuilding only once: the same child node receives new content.
        boolean invalidateResultRenderNodes(int width, int height) { return compositionChecks++ == 0; }
        CAPTURE_METHOD
        REGION_METHOD
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
        NekoConfig.usePrismGlass = true;
        check(s.invalidateResultRenderNodes(capture, 100, 100), "Switch to Prism");
        int captures = capture.captures;
        for (int strength : new int[]{0, 1, 15, 100, 0}) {
            NekoConfig.blurStrength = strength;
            check(s.invalidateResultRenderNodes(capture, 100, 100), "Prism material change must notify");
            check(capture.captures == captures, "Prism recaptured unchanged scene for material change");
            check(!s.invalidateResultRenderNodes(capture, 100, 100), "Prism material must settle");
        }
        NekoConfig.useAdvancedLiquidGlass = false;
        check(s.invalidateResultRenderNodes(capture, 100, 100), "Prism saturation change must notify");
        check(capture.captures == captures, "Saturation change recaptured content");
        capture.generation++;
        check(s.invalidateResultRenderNodes(capture, 100, 100), "Prism must keep live content");
        check(capture.captures == captures + 1, "Prism lost new source pixels");
        capture.hashed = false;
        for (int frame = 0; frame < 3; frame++) {
            check(s.invalidateResultRenderNodes(capture, 100, 100), "Prism unhashed animation");
        }
        check(capture.captures == captures + 4, "Prism froze unhashed animation");
        capture.hashed = true;
        check(s.invalidateResultRenderNodes(capture, 100, 100), "Restore scene caching");
        captures = capture.captures;
        // A source version alone is insufficient: moving a region must sample its new pixels.
        s.rectRenderNodes.get(0).position.left += 16;
        s.rectRenderNodes.get(0).position.right += 16;
        check(s.invalidateResultRenderNodes(capture, 100, 100), "Moved region reused old pixels");
        check(capture.captures == captures + 1, "Moved region must recapture once");
        check(!s.invalidateResultRenderNodes(capture, 100, 100), "Stationary region must settle");
        s.rectRenderNodesCount = 0;
        check(!s.invalidateResultRenderNodes(capture, 100, 100), "No visible regions must not notify");

        // A local animation must refresh the affected capture and retain the other.
        Suppressor regions = new Suppressor();
        regions.rectRenderNodes.add(new SourcePart());
        regions.rectRenderNodesCount = 2;
        regions.rectRenderNodes.get(1).position.top = 900;
        regions.rectRenderNodes.get(1).position.bottom = 1000;
        Capture scene = new Capture();
        check(regions.invalidateResultRenderNodes(scene, 100, 1000), "Initial regions");
        check(scene.captures == 2, "Both initial regions must capture");
        for (int frame = 0; frame < 20; frame++) {
            // Notifications in one frame coalesce into one recording.
            regions.invalidateSourceRegion(new RectF(0, 10, 100, 30));
            regions.invalidateSourceRegion(new RectF(0, 20, 100, 40));
            check(regions.invalidateResultRenderNodes(scene, 100, 1000), "Local animation froze");
            check(scene.captures == frame + 3, "Unchanged region was recaptured");
            check(!regions.invalidateResultRenderNodes(scene, 100, 1000), "Local update did not settle");
        }
        regions.invalidateSourceRegion(new RectF(0, 300, 100, 400));
        check(!regions.invalidateResultRenderNodes(scene, 100, 1000), "Off-region animation recaptured glass");
        regions.invalidateSourceRegion(new RectF(0, 100, 100, 900));
        check(!regions.invalidateResultRenderNodes(scene, 100, 1000), "Touching bounds must not overlap");
        regions.invalidateSourceRegion(new RectF(0, 950, 100, 970));
        check(regions.invalidateResultRenderNodes(scene, 100, 1000), "Bottom animation froze");
        check(scene.captures == 23, "Bottom animation updated wrong regions");
        scene.generation++;
        check(regions.invalidateResultRenderNodes(scene, 100, 1000), "Global scroll froze");
        check(scene.captures == 25, "Global scroll must recapture both regions");
        regions.rectRenderNodes.get(0).position.top = 300;
        regions.rectRenderNodes.get(0).position.bottom = 400;
        check(regions.invalidateResultRenderNodes(scene, 100, 1000), "Moved capture reused stale pixels");
        check(scene.captures == 26, "Moved capture must update independently");
    }
}
'''.replace("CAPTURE_METHOD", capture_method.replace("zxc.iconic.xenon.NekoConfig", "NekoConfig").replace("android.os.Build", "Build")).replace("REGION_METHOD", region_method)
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
