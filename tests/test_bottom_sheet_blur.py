"""Exercise the production sheet blur with an asynchronous PixelCopy/looper harness.

Run: python3 -m unittest discover -s tests -p 'test_bottom_sheet_blur.py' -v
Requires Java 17+, without an Android SDK or device.
"""

import re
import subprocess
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / 'TMessagesProj/src/main/java'


class BottomSheetBlurTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.directory = tempfile.TemporaryDirectory(prefix='bottom-sheet-blur-')
        cls.addClassCleanup(cls.directory.cleanup)
        production = (SOURCE / 'zxc/iconic/xenon/helpers/BottomSheetBlurView.java').read_text()
        production = re.sub(r'^(?:package |import |@RequiresApi).*\n', '', production, flags=re.M)
        production = production.replace('public final class BottomSheetBlurView',
                                        'static final class BottomSheetBlurView')
        harness = r'''
import java.util.*;
import javax.tools.*;
import com.sun.source.util.JavacTask;

public class SheetBlurHarness {
    static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    static void near(float value, float expected) {
        check(Math.abs(value - expected) < .001f, value + " != " + expected);
    }
    static class Context {}
    static class Looper { static Looper getMainLooper() { return new Looper(); } }
    static class Handler {
        Handler(Looper looper) {}
    }
    static class Choreographer {
        interface FrameCallback { void doFrame(long frameTimeNanos); }
        static final Choreographer instance = new Choreographer();
        static final List<FrameCallback> callbacks = new ArrayList<>();
        static int refreshRate = 120;
        static long frameTimeNanos;
        static Choreographer getInstance() { return instance; }
        void postFrameCallback(FrameCallback callback) {
            check(!callbacks.contains(callback), "only one callback per display frame");
            callbacks.add(callback);
        }
        void removeFrameCallback(FrameCallback callback) { callbacks.remove(callback); }
        static void frame() {
            frameTimeNanos += Math.round(1e9 / refreshRate);
            List<FrameCallback> current = List.copyOf(callbacks);
            callbacks.clear();
            for (FrameCallback callback : current) callback.doFrame(frameTimeNanos);
        }
    }
    static class NekoConfig {
        static int blurPixelation, blurOverlayRadius = 10, blurOverlayRefreshInterval = 2;
        static boolean disableBlurBs, blurOverlayRefresh = true;
    }
    static class FileLog { static int errors; static void e(Exception e) { errors++; } }
    static class Paint { static final int FILTER_BITMAP_FLAG = 2; Paint(int flags) {} }
    static class Rect { void set(int l, int t, int r, int b) {} }
    static class Shader { enum TileMode { CLAMP } }
    static class Bitmap {
        enum Config { ARGB_8888 }
        static int allocations;
        final int width, height;
        Bitmap(int width, int height) { this.width = width; this.height = height; allocations++; }
        static Bitmap createBitmap(int width, int height, Config config) { return new Bitmap(width, height); }
        int getWidth() { return width; } int getHeight() { return height; }
    }
    static class RenderEffect {
        static int creations;
        float x, y;
        static RenderEffect createBlurEffect(float x, float y, Shader.TileMode mode) {
            check(x > 0 && y > 0, "positive effect radii");
            RenderEffect effect = new RenderEffect(); effect.x = x; effect.y = y; creations++;
            return effect;
        }
    }
    static class Canvas {
        boolean hardware = true;
        Bitmap drawnBitmap;
        boolean isHardwareAccelerated() { return hardware; }
        void drawRenderNode(RenderNode node) { drawnBitmap = node.recorded; }
        void drawBitmap(Bitmap bitmap, Rect src, Rect dst, Paint paint) { drawnBitmap = bitmap; }
        void drawBitmap(Bitmap bitmap, float x, float y, Paint paint) { drawnBitmap = bitmap; }
    }
    static class RecordingCanvas extends Canvas {}
    static class RenderNode {
        int width, height, recordings;
        float scaleX, scaleY;
        boolean layer;
        Bitmap recorded;
        RenderEffect effect;
        RecordingCanvas canvas;
        RenderNode(String name) {}
        void setUseCompositingLayer(boolean value, Paint paint) { layer = value; }
        void setPivotX(float x) {} void setPivotY(float y) {}
        void setPosition(int l, int t, int r, int b) { width = r - l; height = b - t; }
        RecordingCanvas beginRecording() {
            check(width > 0 && height > 0, "valid layer bounds");
            recordings++; return canvas = new RecordingCanvas();
        }
        void endRecording() { recorded = canvas.drawnBitmap; }
        void setScaleX(float scale) { scaleX = scale; } void setScaleY(float scale) { scaleY = scale; }
        void setRenderEffect(RenderEffect effect) { this.effect = effect; }
        void discardDisplayList() { recorded = null; }
    }
    static class ViewTreeObserver {
        interface OnDrawListener { void onDraw(); }
        boolean alive = true;
        List<OnDrawListener> listeners = new ArrayList<>();
        void addOnDrawListener(OnDrawListener listener) { listeners.add(listener); }
        void removeOnDrawListener(OnDrawListener listener) { listeners.remove(listener); }
        boolean isAlive() { return alive; }
        void draw() { for (OnDrawListener listener : List.copyOf(listeners)) listener.onDraw(); }
    }
    static class View {
        static final int IMPORTANT_FOR_ACCESSIBILITY_NO = 2;
        int width = 1080, height = 2400;
        boolean attached = true, shown = true;
        ViewTreeObserver observer = new ViewTreeObserver();
        ViewGroup.LayoutParams params = new ViewGroup.LayoutParams();
        View(Context context) { params.height = height; }
        int getWidth() { return width; } int getHeight() { return height; }
        boolean isAttachedToWindow() { return attached; } boolean isShown() { return shown; }
        ViewTreeObserver getViewTreeObserver() { return observer; }
        ViewGroup.LayoutParams getLayoutParams() { return params; }
        void setLayoutParams(ViewGroup.LayoutParams params) { this.params = params; }
        void setImportantForAccessibility(int mode) {} void invalidate() {}
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {}
        protected void onDraw(Canvas canvas) {}
        protected void onDetachedFromWindow() {}
    }
    static class ViewGroup { static class LayoutParams { int height; } }
    static class Window {
        View decor = new View(new Context());
        View getDecorView() { return decor; }
    }
    static class PixelCopy {
        interface OnPixelCopyFinishedListener { void onPixelCopyFinished(int result); }
        static final int SUCCESS = 0, ERROR_TIMEOUT = 2, ERROR_SOURCE_INVALID = 4,
                ERROR_DESTINATION_INVALID = 5;
        static int requests;
        static boolean failRequest;
        static Bitmap destination;
        static OnPixelCopyFinishedListener callback;
        static void request(Window window, Bitmap bitmap, OnPixelCopyFinishedListener listener, Handler handler) {
            check(callback == null, "only one copy in flight");
            if (failRequest) throw new IllegalArgumentException("No window surface");
            requests++; destination = bitmap; callback = listener;
        }
        static void finish(int result) {
            check(callback != null, "copy must be pending");
            OnPixelCopyFinishedListener listener = callback;
            callback = null; destination = null; listener.onPixelCopyFinished(result);
        }
    }
    PRODUCTION
    static BottomSheetBlurView create(Window window) {
        BottomSheetBlurView view = new BottomSheetBlurView(new Context(), window);
        view.setBlurRadius(80);
        view.start(() -> {});
        return view;
    }
    static void next(Window window) { window.decor.observer.draw(); Choreographer.frame(); }

    static void performance() {
        Window window = new Window(); BottomSheetBlurView view = create(window);
        check(PixelCopy.destination.getWidth() == 216 && PixelCopy.destination.getHeight() == 480,
                "25x fewer captured pixels at 1080x2400");
        PixelCopy.finish(PixelCopy.SUCCESS);
        check(view.blurNode.layer && view.blurNode.width == 216 && view.blurNode.height == 480,
                "blur operates on a small compositing layer");
        near(view.blurNode.effect.x * view.blurNode.scaleX, 80);
        near(view.blurNode.effect.y * view.blurNode.scaleY, 80);
        for (int i = 0; i < 100; i++) { view.onDraw(new Canvas()); Choreographer.frame(); }
        check(PixelCopy.requests == 1 && Choreographer.callbacks.isEmpty(), "idle sheet schedules no work");
        for (int i = 0; i < 12; i++) {
            next(window);
            check(PixelCopy.destination != view.frontBitmap, "copy never writes into displayed bitmap");
            PixelCopy.finish(PixelCopy.SUCCESS);
            view.setBlurRadius(80);
        }
        check(Bitmap.allocations == 2, "steady state reuses two buffers");
        check(RenderEffect.creations == 1, "unchanged radius keeps cached effect");
        view.setBlurRadius(0); check(view.blurNode.effect == null, "zero radius removes effect");
        view.setBlurRadius(40);
        near(view.blurNode.effect.x * view.blurNode.scaleX, 40);
        Canvas software = new Canvas(); software.hardware = false; view.onDraw(software);
        check(software.drawnBitmap == view.frontBitmap, "software canvas fallback");
    }
    static void trailingFrame() {
        Window window = new Window(); BottomSheetBlurView view = create(window);
        PixelCopy.finish(PixelCopy.SUCCESS); next(window);
        for (int i = 0; i < 20; i++) window.decor.observer.draw();
        check(Choreographer.callbacks.isEmpty(), "changes coalesce while copy is in flight");
        for (int i = 0; i < 4; i++) Choreographer.frame();
        check(PixelCopy.requests == 2, "slow copy cannot start overlapping requests");
        PixelCopy.finish(PixelCopy.SUCCESS);
        check(Choreographer.callbacks.size() == 1, "final source change survives outstanding copy");
        check(PixelCopy.requests == 2, "capture waits for vsync");
        Choreographer.frame(); check(PixelCopy.requests == 3, "trailing frame captured on next vsync");
        PixelCopy.finish(PixelCopy.SUCCESS);
        check(Choreographer.callbacks.isEmpty(), "returns to idle after final frame");
        for (int i = 0; i < 20; i++) window.decor.observer.draw();
        check(Choreographer.callbacks.size() == 1, "multiple draws coalesce before vsync");
        Choreographer.frame(); check(PixelCopy.requests == 4, "one capture per display frame");
        PixelCopy.finish(PixelCopy.SUCCESS);
    }
    static void displaySync() {
        Window window = new Window(); BottomSheetBlurView view = create(window);
        PixelCopy.finish(PixelCopy.SUCCESS);
        // Change the display mode while the same sheet is open. The legacy
        // frame-skipping preference must not halve its update frequency.
        NekoConfig.blurOverlayRefreshInterval = 10;
        int requests = PixelCopy.requests;
        for (int hz : new int[]{60, 90, 120, 144, 60}) {
            Choreographer.refreshRate = hz;
            for (int i = 0; i < 24; i++) {
                window.decor.observer.draw();
                check(PixelCopy.requests == requests, "source draw waits for display frame");
                Choreographer.frame();
                check(PixelCopy.requests == ++requests, "every display frame can refresh at " + hz + "Hz");
                PixelCopy.finish(PixelCopy.SUCCESS);
            }
        }
        check(Bitmap.allocations == 2, "full display-rate updates still reuse buffers");
        check(Choreographer.callbacks.isEmpty(), "no callbacks when display-rate animation stops");
        view.release();
    }
    static void lifecycle() {
        Window window = new Window(); BottomSheetBlurView view = create(window);
        PixelCopy.finish(PixelCopy.SUCCESS); next(window);
        window.decor.observer.draw();
        view.release(); PixelCopy.finish(PixelCopy.SUCCESS);
        Choreographer.frame();
        check(Choreographer.callbacks.isEmpty() && window.decor.observer.listeners.isEmpty(),
                "cleanup removes frame callbacks/listeners");
        check(view.frontBitmap == null && view.spareBitmap == null && view.blurNode.recorded == null,
                "late result cannot restore released resources");
        view = create(window); PixelCopy.finish(PixelCopy.SUCCESS);
        window.decor.observer.draw(); view.stopUpdates();
        Choreographer.frame(); check(PixelCopy.requests == 3, "dismiss cancels pending capture");
        view.setBlurRadius(20); near(view.blurNode.effect.x * view.blurNode.scaleX, 20);
        check(view.frontBitmap != null, "dismiss animation retains its captured frame");
        view.release();
        final int[] ready = {0};
        view = new BottomSheetBlurView(new Context(), window); view.start(() -> ready[0]++);
        view.stopUpdates(); PixelCopy.finish(PixelCopy.SUCCESS);
        check(ready[0] == 0 && view.frontBitmap == null, "dismiss before first copy cannot attach overlay");
        view.release();
    }
    static void resizeAndErrors() {
        Window window = new Window(); BottomSheetBlurView view = create(window);
        PixelCopy.finish(PixelCopy.SUCCESS);
        window.decor.width = 1440; window.decor.height = 3200;
        next(window); PixelCopy.finish(PixelCopy.SUCCESS);
        view.width = 1440; view.height = 3200; view.onSizeChanged(1440, 3200, 1080, 2400);
        check(view.getLayoutParams().height == 3200, "capture height tracks resized window");
        near(view.blurNode.effect.x * view.blurNode.scaleX, 80);
        near(view.blurNode.effect.y * view.blurNode.scaleY, 80);
        next(window); PixelCopy.finish(PixelCopy.SUCCESS);
        int allocations = Bitmap.allocations;
        next(window); PixelCopy.finish(PixelCopy.ERROR_TIMEOUT);
        next(window); PixelCopy.finish(PixelCopy.SUCCESS);
        check(Bitmap.allocations == allocations, "retry reuses spare buffer after resize");
        NekoConfig.blurOverlayRefresh = false; next(window);
        check(PixelCopy.callback == null && Choreographer.callbacks.isEmpty(), "static blur option stops refreshes");
        NekoConfig.blurOverlayRefresh = true; PixelCopy.failRequest = true; next(window);
        check(FileLog.errors == 1 && window.decor.observer.listeners.isEmpty(), "invalid surface handled safely");
        view.release(); PixelCopy.failRequest = false;
        window.decor.attached = false; NekoConfig.blurOverlayRefresh = false;
        view = create(window); check(PixelCopy.callback == null, "wait for usable decor");
        window.decor.attached = true; window.decor.observer.draw(); Choreographer.frame();
        PixelCopy.finish(PixelCopy.SUCCESS);
        check(view.frontBitmap != null, "initial capture retries on first draw even with static blur");
        view.release();
    }
    static void downscale() {
        for (int[] size : new int[][]{{1080, 2400}, {2400, 1080}, {1440, 3200}, {3840, 2160}, {1, 1}}) {
            int scale = BottomSheetBlurView.captureDownscale(size[0], size[1]);
            check(scale >= 4, "automatic minimum downscale");
            check((Math.max(size[0], size[1]) + scale - 1) / scale <= 512, "bounded blur layer");
        }
        NekoConfig.blurPixelation = 100;
        check(BottomSheetBlurView.captureDownscale(1080, 2400) == 21, "manual pixelation preserved");
        NekoConfig.blurPixelation = 0; NekoConfig.disableBlurBs = true;
        check(BottomSheetBlurView.captureDownscale(1080, 2400) == 1, "sharp capture when blur disabled");
        NekoConfig.disableBlurBs = false; NekoConfig.blurOverlayRadius = 0;
        check(BottomSheetBlurView.captureDownscale(1080, 2400) == 1, "zero intensity remains sharp");
    }
    static void syntax(String path) throws Exception {
        var compiler = ToolProvider.getSystemJavaCompiler();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        try (var files = compiler.getStandardFileManager(diagnostics, null, null)) {
            var task = (JavacTask) compiler.getTask(null, files, diagnostics, null, null,
                    files.getJavaFileObjects(path));
            task.parse();
            for (var diagnostic : diagnostics.getDiagnostics()) {
                check(diagnostic.getKind() != Diagnostic.Kind.ERROR, diagnostic.toString());
            }
        }
    }
    public static void main(String[] args) throws Exception {
        switch (args[0]) {
            case "performance": performance(); break;
            case "trailing": trailingFrame(); break;
            case "display": displaySync(); break;
            case "lifecycle": lifecycle(); break;
            case "resize": resizeAndErrors(); break;
            case "downscale": downscale(); break;
            case "syntax": syntax(args[1]); break;
            default: throw new AssertionError(args[0]);
        }
    }
}
'''.replace('    PRODUCTION', production)
        path = Path(cls.directory.name) / 'SheetBlurHarness.java'
        path.write_text(harness)
        result = subprocess.run(['java', '-m', 'jdk.compiler/com.sun.tools.javac.Main', str(path)],
                                capture_output=True, text=True)
        if result.returncode:
            raise AssertionError(result.stderr)

    def run_harness(self, *args):
        result = subprocess.run(['java', '-cp', self.directory.name, 'SheetBlurHarness', *args],
                                capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_small_layer_idle_buffer_and_effect_reuse(self):
        self.run_harness('performance')

    def test_coalescing_vsync_and_final_frame(self):
        self.run_harness('trailing')

    def test_display_sync_and_refresh_rate_changes(self):
        self.run_harness('display')

    def test_dismissal_and_late_pixelcopy_completion(self):
        self.run_harness('lifecycle')

    def test_resize_static_mode_and_copy_failures(self):
        self.run_harness('resize')

    def test_automatic_and_manual_downscale(self):
        self.run_harness('downscale')

    def test_bottom_sheet_java_syntax(self):
        self.run_harness('syntax', str(SOURCE / 'org/telegram/ui/ActionBar/BottomSheet.java'))
