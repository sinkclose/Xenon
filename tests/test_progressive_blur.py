"""Exercise production blur methods with Java stubs, without an Android SDK."""
import re
import subprocess
import tempfile
import unittest
from pathlib import Path

from test_glass_blur import ROOT, method


class ProgressiveBlurTest(unittest.TestCase):
    def test_kernel_effect_cache_and_mode_switch(self):
        source = (ROOT / 'org/telegram/ui/Components/blur3/source/BlurredBackgroundSourceRenderNode.java').read_text()
        fields = source[source.index('    private RuntimeShader progressiveShader;'):source.index('    // Two materialized')]
        methods = '\n'.join(method(source, signature) for signature in (
            'private static String buildProgressiveBlurShader(int samples)',
            'public void setProgressiveBlur(',
            'private static void updateProgressiveUniforms(',
            'public void setBlur(float radius)',
            'public void setBlur(float radius, RenderEffect effect)'))
        methods = re.sub(r'\s*@RequiresApi\([^\n]*\)', '', methods)
        harness = '''
import java.util.*;
import java.util.regex.*;
public class ProgressiveBlurHarness {
    static void check(boolean value) { if (!value) throw new AssertionError(); }
    static class Shader { enum TileMode { CLAMP } }
    static class FileLog { static void e(Exception e) {} }
    static class RuntimeShader {
        static int creations;
        static boolean fail;
        String code;
        Map<String, float[]> uniforms = new HashMap<>();
        RuntimeShader(String code) {
            creations++;
            if (fail) throw new IllegalArgumentException("Unsupported shader");
            this.code = code;
        }
        void setFloatUniform(String name, float... values) {
            check(code.contains(" " + name + ";"));
            uniforms.put(name, values);
        }
    }
    static class RenderEffect {
        RenderEffect outer, inner;
        RuntimeShader shader;
        float radius;
        static RenderEffect createRuntimeShaderEffect(RuntimeShader shader, String input) {
            check(input.equals("inputTexture"));
            RenderEffect effect = new RenderEffect(); effect.shader = shader; return effect;
        }
        static RenderEffect createChainEffect(RenderEffect outer, RenderEffect inner) {
            RenderEffect effect = new RenderEffect(); effect.outer = outer; effect.inner = inner; return effect;
        }
        static RenderEffect createBlurEffect(float x, float y, Shader.TileMode mode) {
            check(x > 0 && y > 0);
            RenderEffect effect = new RenderEffect(); effect.radius = x; return effect;
        }
    }
    static class RenderNode {
        int updates;
        RenderEffect effect;
        void setRenderEffect(RenderEffect effect) { updates++; this.effect = effect; }
    }
    static class GlassShaderCache {
        static int available = Integer.MAX_VALUE;
        static RuntimeShader acquireProgressive(int samples, java.util.function.Supplier<String> code, Runnable ready) {
            if (available == 0) return null;
            available--; return new RuntimeShader(code.get());
        }
    }
    static class Source {
        RenderNode renderNode = new RenderNode();
        float lastBlurRadius = -1;
        RenderEffect lastBlurEffect;
        FIELDS
        METHODS
    }
    public static void main(String[] args) {
        for (int samples = 3; samples <= 25; samples += 2) {
            String code = Source.buildProgressiveBlurShader(samples);
            check(!code.contains("exp("));
            check(!code.contains("for ("));
            Matcher weights = Pattern.compile("\\\\) \\* ([0-9.E-]+);").matcher(code);
            double sum = 0; int taps = 0;
            List<Double> values = new ArrayList<>();
            while (weights.find()) { double weight = Double.parseDouble(weights.group(1)); sum += weight; taps++; values.add(weight); }
            check(taps == samples && Math.abs(sum - 1) < 1e-6);
            for (int i = 0; i < taps; i++) check(values.get(i).equals(values.get(taps - 1 - i)));
        }
        Source s = new Source();
        s.setProgressiveBlur(12, 101, 203, .2f, .1f, 11);
        check(RuntimeShader.creations == 2 && s.renderNode.updates == 1);
        check(s.renderNode.effect.inner.shader.uniforms.get("direction")[0] == 1);
        check(s.renderNode.effect.outer.shader.uniforms.get("direction")[1] == 1);
        s.setProgressiveBlur(12, 101, 203, .2f, .1f, 11);
        check(s.renderNode.updates == 1);
        s.setProgressiveBlur(13, 101, 203, .2f, .1f, 11);
        check(RuntimeShader.creations == 2 && s.renderNode.updates == 2);
        s.setBlur(4);
        check(!s.progressiveEffectApplied && s.renderNode.effect.radius == 4);
        s.setProgressiveBlur(13, 101, 203, .2f, .1f, 11);
        check(s.progressiveEffectApplied && s.renderNode.effect.inner != null);
        s.setProgressiveBlur(13, 101, 203, 0, 2, 24);
        check(s.progressiveShaderSamples == 25);
        check(s.progressiveShader.uniforms.get("fadeZoneTopFraction")[0] == 0);
        check(s.progressiveShader.uniforms.get("fadeZoneBottomFraction")[0] == 1);
        int updates = s.renderNode.updates;
        s.setProgressiveBlur(13, 0, 203, .2f, .1f, 11);
        check(s.renderNode.updates == updates);
        s.setProgressiveBlur(0, 101, 203, .2f, .1f, 11);
        check(s.renderNode.effect == null && !s.progressiveEffectApplied);
        s.setProgressiveBlur(13, 101, 203, 0, 0, 11);
        check(s.renderNode.effect == null);
        RuntimeShader.fail = true;
        Source fallback = new Source();
        fallback.setProgressiveBlur(7, 100, 200, .2f, .1f, 11);
        check(fallback.renderNode.effect.radius == 7);
        int creations = RuntimeShader.creations;
        fallback.setProgressiveBlur(8, 100, 200, .2f, .1f, 11);
        check(RuntimeShader.creations == creations && fallback.renderNode.effect.radius == 8);
        RuntimeShader.fail = false; GlassShaderCache.available = 0;
        Source cold = new Source(); creations = RuntimeShader.creations;
        cold.setProgressiveBlur(9, 100, 200, .2f, .1f, 11);
        check(RuntimeShader.creations == creations && cold.renderNode.effect.radius == 9);
        GlassShaderCache.available = 1;
        cold.setProgressiveBlur(9, 100, 200, .2f, .1f, 11);
        RuntimeShader first = cold.progressiveShader;
        check(first != null && cold.progressiveVerticalShader == null);
        GlassShaderCache.available = 1; cold.onProgressiveShadersReady.run();
        cold.setProgressiveBlur(9, 100, 200, .2f, .1f, 11);
        check(cold.progressiveShader == first && cold.progressiveVerticalShader != first && cold.progressiveEffectApplied);
    }
}
'''.replace('FIELDS', fields).replace('METHODS', methods)
        # Java regex literals need two backslashes, separately from Python escaping.
        harness = harness.replace('Pattern.compile("\\\\) \\*', 'Pattern.compile("\\\\) \\\\*')
        with tempfile.TemporaryDirectory(prefix='progressive-blur-') as directory:
            java = Path(directory) / 'ProgressiveBlurHarness.java'
            java.write_text(harness)
            result = subprocess.run(['java', '-m', 'jdk.compiler/com.sun.tools.javac.Main', str(java)], capture_output=True, text=True)
            self.assertEqual(0, result.returncode, result.stderr)
            result = subprocess.run(['java', '-cp', directory, 'ProgressiveBlurHarness'], capture_output=True, text=True)
            self.assertEqual(0, result.returncode, result.stderr)

    def test_capture_scheduling_idle_and_property_animation(self):
        source = (ROOT / 'org/telegram/ui/Components/ProgressiveFadeBlurController.java').read_text()
        methods = '\n'.join(method(source, signature) for signature in (
            'private void scheduleUpdate(long delay)', 'public void startContinuousUpdates()',
            'public void stopContinuousUpdates()', 'public void invalidate()',
            'private long captureTransform()', 'private static long viewTransform(View view)',
            'private void drawCapturedView(Canvas c, View view)',
            'private boolean hasActiveEdgeEffects()',
            'private static boolean hasActiveEdgeEffects(View view)'))
        runnable = source[source.index('    private final Runnable updateRunnable'):source.index('    private void scheduleUpdate')]
        listener = source[source.index('    private final ViewTreeObserver.OnPreDrawListener'):source.index('    private boolean updatePending;')]
        harness = r"""
import java.util.*;
import java.util.function.IntSupplier;
public class CaptureHarness {
    static class ViewTreeObserver { interface OnPreDrawListener { boolean onPreDraw(); } }
    static void check(boolean condition) { if (!condition) throw new AssertionError(); }
    static class Build {
        static class VERSION { static int SDK_INT = 33; }
        static class VERSION_CODES { static final int TIRAMISU = 33; }
    }
    static class SystemClock { static long now = 1000; static long uptimeMillis() { return now; } }
    static class AndroidUtilities {
        static float screenRefreshTime = 8.33f;
        static int dp(int n) { return n; }
        static float dpf2(float n) { return n; }
    }
    static class NekoConfig {
        static int progressiveFadeBlurRefreshRate = 60, progressiveFadeBlurMaxRadius = 20;
        static int progressiveFadeBlurSamples = 11, blurredFadePixelation = 3, blurredFadeDimStrength = 20;
        static boolean blurredFadeDimming = true;
    }
    static class SizeNotifierFrameLayout { static boolean drawingBlur; }
    static class FileLog { static void e(Exception e) { throw new AssertionError(e); } }
    static class Canvas {
        boolean isHardwareAccelerated() { return true; }
        void drawColor(int c) {} void save() {} void restore() {}
        void translate(float x, float y) {} void scale(float x, float y, float px, float py) {}
    }
    static class View {
        static final int OVER_SCROLL_NEVER = 2;
        boolean dirty, attached = true;
        float x, y, sx = 1, sy = 1;
        int redraws, posts;
        Runnable pending;
        long delay;
        int getWidth() { return 101; } int getHeight() { return 203; }
        int getLeft() { return 0; } int getTop() { return 0; }
        int getOverScrollMode() { return 0; }
        float getTranslationX() { return x; } float getTranslationY() { return y; }
        float getScaleX() { return sx; } float getScaleY() { return sy; }
        float getPivotX() { return 0; } float getPivotY() { return 0; }
        boolean isDirty() { return dirty; } boolean isAttachedToWindow() { return attached; }
        void draw(Canvas c) { dirty = false; }
        void invalidate() { redraws++; }
        void postDelayed(Runnable r, long delay) { check(pending == null); posts++; pending = r; this.delay = delay; }
        void removeCallbacks(Runnable r) { if (pending == r) pending = null; }
        void runPending() { Runnable r = pending; pending = null; if (r != null) r.run(); }
        void setDim(int dim) {} void setDimColor(int color) {}
    }
    static class ViewGroup extends View {
        int getChildCount() { return 0; }
        View getChildAt(int index) { throw new AssertionError(); }
    }
    static class RecyclerListView extends ViewGroup {
        boolean hasActiveEdgeEffects() { return false; }
    }
    static class CaptureLayout {
        void capture(Canvas c, View v) { v.draw(c); }
    }
    static class Source {
        int records;
        boolean inRecording() { return false; }
        void setPixelation(int p) {}
        void setProgressiveBlur(float r, int w, int h, float t, float b, int n) { check(w == 34 && h == 68); }
        Canvas beginRecording(int w, int h) { records++; return new Canvas(); }
        void endRecording() {}
    }
    static class ColorSource { void setColor(int c) {} }
    static class Controller {
        Source source = new Source(); ColorSource underSource = new ColorSource();
        CaptureLayout captureLayout = new CaptureLayout();
        View fadeView = new View(), captureView = new View();
        List<View> additionalCaptureViews = new ArrayList<>();
        boolean continuousUpdating, updateAtScreenRefreshRate, updatePending, capturePending, lastCaptureHadEdgeEffects, dimEnabled = true;
        long lastCaptureTransform, lastUpdateTime;
        int drawCount, lastProcessedDrawCount = -1, background, lastBackgroundColor;
        int fadeZoneTop = 96, fadeZoneBottom = 48;
        IntSupplier backgroundColorProvider;
        boolean enabled = true;
        boolean isEnabled() { return enabled; }
        void syncState() { if (!enabled) stopContinuousUpdates(); }
        LISTENER
        RUNNABLE
        METHODS
    }
    public static void main(String[] args) {
        Controller c = new Controller();
        c.invalidate(); check(c.source.records == 1);
        c.drawCount++; c.invalidate(); check(c.source.records == 1); // own redraw
        SystemClock.now += 20;
        c.captureView.x = 30; c.invalidate(); check(c.source.records == 2); // property animation
        c.drawCount++; c.invalidate(); check(c.source.records == 2);
        c.captureView.dirty = true; c.invalidate(); check(c.capturePending && c.fadeView.pending != null);
        c.captureView.dirty = false; // real tree drew before trailing callback
        c.invalidate(); check(c.fadeView.posts == 1); // coalesced
        SystemClock.now += 20; c.fadeView.runPending();
        check(c.source.records == 3 && !c.capturePending && !c.updatePending);
        c.drawCount++; c.invalidate(); check(c.source.records == 3);
        View extra = new View(); c.additionalCaptureViews.add(extra);
        SystemClock.now += 20; c.invalidate(); check(c.source.records == 4);
        c.drawCount++; SystemClock.now += 20; extra.x = -50;
        c.invalidate(); check(c.source.records == 5);
        c.drawCount++; c.drawCount++; c.invalidate();
        check(c.updatePending); c.stopContinuousUpdates(); check(c.fadeView.pending == null && !c.capturePending);
        c.enabled = false; SystemClock.now += 20; c.invalidate(); check(c.source.records == 5);
        // HWUI may update only a child display list, bypassing the parent's
        // dispatchDraw (and its call to Controller.invalidate).
        c.enabled = true; c.startContinuousUpdates(); check(c.updatePending);
        SystemClock.now += 20; c.fadeView.runPending();
        int records = c.source.records;
        c.captureView.dirty = true;
        c.drawCountListener.onPreDraw();
        c.captureView.dirty = false; // normal HWUI draw clears the dirty bit
        check(c.updatePending && c.capturePending);
        SystemClock.now += 20; c.fadeView.runPending();
        check(c.source.records == records + 1);
        c.drawCountListener.onPreDraw(); // fade's own redraw must stay idle
        check(!c.updatePending && !c.capturePending);
        extra.x = -75; // property animation also bypasses parent dispatchDraw
        c.drawCountListener.onPreDraw();
        check(c.updatePending && c.capturePending);
        SystemClock.now += 20; c.fadeView.runPending();
        check(c.source.records == records + 2);
        c.drawCountListener.onPreDraw();
        check(!c.updatePending);
        c.stopContinuousUpdates();
        c.captureView.dirty = true; c.drawCountListener.onPreDraw();
        check(!c.updatePending);
        c.startContinuousUpdates(); check(c.updatePending);
        c.fadeView.attached = false; c.fadeView.runPending(); check(c.source.records == records + 2);
    }
}
""".replace('LISTENER', listener).replace('RUNNABLE', runnable).replace('METHODS', methods)
        with tempfile.TemporaryDirectory(prefix='progressive-capture-') as directory:
            java = Path(directory) / 'CaptureHarness.java'
            java.write_text(harness)
            result = subprocess.run(['java', '-m', 'jdk.compiler/com.sun.tools.javac.Main', str(java)], capture_output=True, text=True)
            self.assertEqual(0, result.returncode, result.stderr)
            result = subprocess.run(['java', '-cp', directory, 'CaptureHarness'], capture_output=True, text=True)
            self.assertEqual(0, result.returncode, result.stderr)

    def test_chat_idle_and_final_throttled_frame(self):
        source = (ROOT / 'org/telegram/ui/ChatActivity.java').read_text()
        code = source[source.index('    private long lastFadeBlurUpdateTime;'):source.index('    private boolean fadeBlurStockApplied;')]
        capture = "\n".join(method(source, signature) for signature in (
            'private void invalidateFadeBlurImpl()', 'private void captureFadeBlur()'))
        harness = r"""
public class ChatCaptureHarness {
    static void check(boolean condition) { if (!condition) throw new AssertionError(); }
    static class Build {
        static class VERSION { static int SDK_INT = 33; }
        static class VERSION_CODES { static final int TIRAMISU = 33; }
    }
    static class NekoConfig {
        static boolean enabled = true, progressive;
        static int progressiveFadeBlurRefreshRate = 60;
        static int blurredFadePixelation = 3, progressiveFadeBlurMaxRadius = 20;
        static int progressiveFadeBlurSamples = 11, blurredFadeBlurStrength = 10, blurredFadeDimStrength = 20;
        static boolean blurredFadeDimming = true;
        static boolean blurredFadeViewEnabled() { return enabled; }
        static boolean progressiveFadeBlurEnabled() { return progressive; }
    }
    static class SystemClock { static long now = 1000; static long uptimeMillis() { return now; } }
    static class Choreographer {
        interface FrameCallback { void doFrame(long time); }
        static Choreographer singleton = new Choreographer();
        static Choreographer getInstance() { return singleton; }
        void postFrameCallback(FrameCallback c) {} void removeFrameCallback(FrameCallback c) {}
    }
    static class CaptureView {
        int draws, requests;
        boolean pending;
        int getPreDrawCount() { return draws; }
        void invalidate(int flags) { requests++; pending = true; }
    }
    static class RectF { void set(int l, int t, int r, int b) {} }
    static class Canvas {
        int pixels;
        void drawColor(int color) {} void save() {} void restore() {} void translate(float x, float y) {}
    }
    static class Color { static int alpha(int color) { return color >>> 24; } }
    static class ColorUtils { static int setAlphaComponent(int color, int alpha) { return color | alpha << 24; } }
    static class Theme { static final int key_chat_wallpaper = 1; }
    static class AndroidUtilities { static float dpf2(float dp) { return dp; } }
    interface BlurredBackgroundSource { void draw(Canvas c, int l, int t, int r, int b); }
    static class Source implements BlurredBackgroundSource {
        int records, pixels;
        Canvas canvas;
        BlurredBackgroundSource underSource;
        boolean inRecording() { return canvas != null; }
        void setPixelation(int value) {} void setBlur(float value) {}
        void setProgressiveBlur(float radius, int w, int h, float top, float bottom, int samples) {}
        void setUnderSource(BlurredBackgroundSource value) { underSource = value; }
        void invalidateDisplayListForDrawables() {}
        Canvas beginRecording(int w, int h) { records++; return canvas = new Canvas(); }
        void endRecording() { pixels = canvas.pixels; canvas = null; }
        public void draw(Canvas c, int l, int t, int r, int b) {}
    }
    static class Chat {
        CaptureView fadeBlurCaptureView = new CaptureView();
        Source fadeBlurSource = new Source(), fadeWallpaperSource = new Source();
        Source navbarContentSourceWallpaper = new Source(), navbarContentSourceWallpaperSharp = new Source();
        RectF fadeBlurCaptureRect = new RectF();
        int glassSourceCaptureDepth, generation = 1;
        boolean failCapture;
        boolean openAnimationEnded = true, inPreviewMode, isInsideContainer, inBubbleMode;
        class Background {
            int getWidth() { return 360; }
            float getX() { return 0; } float getY() { return 0; }
            void draw(Canvas c) { invalidateFadeBlur(); } // invalidation during recording
        }
        class Content {
            Background backgroundView = new Background();
            int width = 360;
            int getWidth() { return width; } int getHeight() { return 800; }
            void drawList(Canvas c, RectF rect) {
                invalidateFadeBlur();
                if (failCapture) throw new IllegalStateException();
                c.pixels = generation;
            }
        }
        class Fade {
            int getFadeZoneTop() { return 96; } int getFadeZoneBottom() { return 48; }
            void setDimColor(int c) {} void setDim(int dim) {}
            void invalidate() { invalidateFadeBlur(); } // output must not feed back
        }
        Content contentView = new Content(); Fade chatActivityFadeView = new Fade();
        int dp(int value) { return value; }
        int getThemedColor(int key) { return 0xFF123456; }
        void syncFadeBlurEnabledState() {}
        void frame(long elapsed) {
            SystemClock.now += elapsed;
            // Android runs animation callbacks BEFORE traversal/pre-draw.
            fadeBlurFrameCallback.doFrame(0);
            fadeBlurCaptureView.draws++;
            if (fadeBlurCaptureView.pending) {
                fadeBlurCaptureView.pending = false;
                invalidateFadeBlurImpl();
            }
        }
        CODE
        CAPTURE
    }
    public static void main(String[] args) {
        for (int api : new int[]{31, 33}) for (boolean progressive : new boolean[]{false, true}) {
            Build.VERSION.SDK_INT = api; NekoConfig.progressive = progressive; NekoConfig.enabled = true;
            Chat c = new Chat(); c.startFadeBlurContinuousUpdates(); c.frame(17);
            check(c.fadeBlurSource.records == 1 && c.fadeWallpaperSource.records == 1);
            for (int i = 0; i < 120; i++) c.frame(17);
            check(c.fadeBlurCaptureView.requests == 1 && c.fadeBlurSource.records == 1);
            // All changes before a traversal share one capture of the latest state.
            for (int change = 2; change <= 1000; change++) {
                c.generation = change; c.invalidateFadeBlur();
            }
            c.frame(1);
            check(c.fadeBlurSource.records == 2 && c.fadeBlurSource.pixels == 1000);
            c.generation = 1001; c.invalidateFadeBlur();
            c.generation = 1002; c.invalidateFadeBlur();
            if (api >= 33 && progressive) {
                check(c.fadeBlurCapturePending && c.fadeBlurCaptureView.requests == 2);
                c.frame(1); check(c.fadeBlurSource.records == 2);
            }
            // No more source events: the trailing callback must still capture the final state.
            c.frame(20);
            check(c.fadeBlurSource.records == 3 && c.fadeBlurSource.pixels == 1002);
            check(!c.fadeBlurCapturePending && !c.fadeBlurCaptureScheduled && c.glassSourceCaptureDepth == 0);
            for (int i = 0; i < 120; i++) c.frame(17);
            check(c.fadeBlurSource.records == 3 && c.fadeBlurCaptureView.requests == 3);
            if (api >= 33 && progressive) {
                c.generation++; c.invalidateFadeBlur(); c.frame(1);
                c.generation++; c.invalidateFadeBlur(); check(c.fadeBlurCapturePending);
                NekoConfig.enabled = false; c.frame(20);
                check(!c.fadeBlurContinuousUpdating && !c.fadeBlurCapturePending);
                check(c.fadeBlurSource.records == 4);
            }
            NekoConfig.enabled = true;
            c.failCapture = true;
            try { c.invalidateFadeBlurImpl(); throw new AssertionError(); }
            catch (IllegalStateException expected) {
                check(c.glassSourceCaptureDepth == 0 && !c.fadeBlurCaptureScheduled && !c.fadeBlurSource.inRecording());
            }
        }
        NekoConfig.enabled = true;
        Chat opening = new Chat(); opening.openAnimationEnded = false;
        opening.startFadeBlurContinuousUpdates(); opening.frame(17);
        check(opening.fadeBlurSource.records == 1 && !opening.fadeBlurCapturePending);
        opening.openAnimationEnded = true; opening.invalidateFadeBlur(); opening.frame(17);
        check(opening.fadeBlurSource.records == 2 && opening.fadeWallpaperSource.records == 1);
        opening.generation++; opening.invalidateFadeBlur(); opening.frame(17);
        check(opening.fadeBlurSource.records == 3 && opening.fadeWallpaperSource.records == 1);
        opening.fadeWallpaperDirty = true; opening.invalidateFadeBlur(); opening.frame(17);
        check(opening.fadeWallpaperSource.records == 2);
        opening.stopFadeBlurContinuousUpdates();
        check(!opening.fadeBlurContinuousUpdating && !opening.fadeBlurCapturePending);
        Chat beforeLayout = new Chat(); beforeLayout.contentView.width = 0;
        beforeLayout.startFadeBlurContinuousUpdates(); beforeLayout.frame(17);
        for (int i = 0; i < 60; i++) beforeLayout.frame(17);
        check(beforeLayout.fadeBlurCapturePending && beforeLayout.fadeBlurSource.records == 0);
        beforeLayout.contentView.width = 360; beforeLayout.invalidateFadeBlur(); beforeLayout.frame(17);
        check(beforeLayout.fadeBlurSource.records == 1);
    }
}
""".replace('        CODE\n', code).replace('        CAPTURE\n', capture)
        with tempfile.TemporaryDirectory(prefix='chat-capture-') as directory:
            java = Path(directory) / 'ChatCaptureHarness.java'
            java.write_text(harness)
            result = subprocess.run(['java', '-m', 'jdk.compiler/com.sun.tools.javac.Main', str(java)], capture_output=True, text=True)
            self.assertEqual(0, result.returncode, result.stderr)
            result = subprocess.run(['java', '-cp', directory, 'ChatCaptureHarness'], capture_output=True, text=True)
            self.assertEqual(0, result.returncode, result.stderr)


if __name__ == '__main__':
    unittest.main()
