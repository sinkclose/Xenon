"""Exercise production chat glass capture and wallpaper lifecycle methods."""

import subprocess
import tempfile
import unittest
from pathlib import Path

from test_glass_blur import ROOT, method


def run_java(test, name, source):
    with tempfile.TemporaryDirectory() as directory:
        path = Path(directory) / (name + ".java")
        path.write_text(source)
        compiled = subprocess.run(["java", "-m", "jdk.compiler/com.sun.tools.javac.Main", str(path)],
                                  capture_output=True, text=True)
        test.assertEqual(0, compiled.returncode, compiled.stderr)
        result = subprocess.run(["java", "-cp", directory, name], capture_output=True, text=True)
        test.assertEqual(0, result.returncode, result.stderr)


class GlassChatRefreshTest(unittest.TestCase):
    def test_wallpaper_updates_with_cached_glass(self):
        source = (ROOT / "org/telegram/ui/Components/blur3/source/BlurredBackgroundSourceRenderNode.java").read_text()
        methods = "\n".join(method(source, signature) for signature in (
            "public void setWallpaperSize(", "public void invalidateWallpaper()",
            "private void recordWallpaperIfNeeded()", "private void drawWallpaper(",
            "public void prepareToDraw()"))
        harness = r'''
public class WallpaperRefreshHarness {
    static void check(boolean value) { if (!value) throw new AssertionError(); }
    static class Build {
        static class VERSION { static int SDK_INT = 33; }
        static class VERSION_CODES { static int S = 31; }
    }
    static class NekoConfig { static int blurStrength = 15; }
    static class Canvas {
        int pixels;
        void save() {} void restore() {} void scale(float x, float y) {}
        void clipRect(float l, float t, float r, float b) {}
        void drawRenderNode(RenderNode node) { pixels = node.pixels; }
    }
    static class RecordingCanvas extends Canvas {}
    static class RenderNode {
        int pixels, width, height, recordings;
        boolean recorded;
        RecordingCanvas canvas;
        boolean hasDisplayList() { return recorded; }
        void setPosition(int l, int t, int r, int b) { width = r - l; height = b - t; }
        RecordingCanvas beginRecording(int w, int h) { recordings++; return canvas = new RecordingCanvas(); }
        void endRecording() { pixels = canvas.pixels; recorded = true; }
    }
    interface BlurredBackgroundSource { void draw(Canvas c, float l, float t, float r, float b); }
    static class Wallpaper implements BlurredBackgroundSource {
        int pixels = 1;
        public void draw(Canvas c, float l, float t, float r, float b) { c.pixels = pixels; }
    }
    static class Source implements BlurredBackgroundSource {
        boolean glassBlurEnabled, wallpaperDirty = true, wallpaperRecording, noClip, raw;
        int wallpaperParentW, wallpaperParentH, invalidatedDrawables;
        RenderNode wallpaperNode = new RenderNode();
        BlurredBackgroundSource underSource;
        void syncGlassBlur() {} void syncWallpaperEffect() {}
        void invalidateDisplayListForDrawables() { invalidatedDrawables++; }
        boolean isUnblurredCanvas(Canvas canvas) { return raw; }
        public void draw(Canvas c, float l, float t, float r, float b) { drawWallpaper(c, l, t, r, b); }
        METHODS
    }
    public static void main(String[] args) {
        Source s = new Source(); Wallpaper wallpaper = new Wallpaper(); s.underSource = wallpaper;
        s.setWallpaperSize(360, 800);
        Canvas canvas = new Canvas();
        s.prepareToDraw(); s.draw(canvas, 0, 0, 360, 800);
        check(canvas.pixels == 1 && s.wallpaperNode.recordings == 1);
        for (int frame = 2; frame <= 20; frame++) {
            wallpaper.pixels = frame;
            s.invalidateWallpaper();
            // Cached glass calls prepareToDraw, without rebuilding source.draw commands.
            s.prepareToDraw(); canvas.drawRenderNode(s.wallpaperNode);
            check(canvas.pixels == frame && s.invalidatedDrawables == frame - 1);
        }
        int recordings = s.wallpaperNode.recordings;
        for (int idle = 0; idle < 100; idle++) s.prepareToDraw();
        check(s.wallpaperNode.recordings == recordings);
        Wallpaper replacement = new Wallpaper(); replacement.pixels = 42;
        s.underSource = replacement; s.invalidateWallpaper(); s.prepareToDraw();
        canvas.drawRenderNode(s.wallpaperNode); check(canvas.pixels == 42);
        s.setWallpaperSize(800, 360); s.prepareToDraw();
        check(s.wallpaperNode.width == 400 && s.wallpaperNode.height == 180);
        NekoConfig.blurStrength = 0; replacement.pixels = 43;
        s.invalidateWallpaper(); s.prepareToDraw(); s.draw(canvas, 0, 0, 800, 360);
        check(canvas.pixels == 43 && s.invalidatedDrawables == 21);
        s.raw = true; NekoConfig.blurStrength = 15; replacement.pixels = 44;
        s.draw(canvas, 0, 0, 800, 360); check(canvas.pixels == 44);
        s.wallpaperRecording = true; s.invalidateWallpaper(); recordings = s.wallpaperNode.recordings;
        s.prepareToDraw(); check(s.wallpaperNode.recordings == recordings && s.wallpaperDirty);
        s.wallpaperRecording = false; s.prepareToDraw(); check(!s.wallpaperDirty);
    }
}
'''.replace("METHODS", methods.replace("zxc.iconic.xenon.NekoConfig", "NekoConfig"))
        run_java(self, "WallpaperRefreshHarness", harness)

    def test_chat_animates_sources_without_capture_feedback(self):
        source = (ROOT / "org/telegram/ui/ChatActivity.java").read_text()
        methods = "\n".join(method(source, signature) for signature in (
            "public void onDescendantInvalidated(",
            "public void drawWallpaperForGlass(", "private void drawWallpaperImpl(",
            "public void drawList("))
        wallpaper_source = method(source[source.index("private final BlurredBackgroundSource glassWallpaperSource"):], "public void draw(")
        callback = "\n".join(method(source, signature) for signature in (
            "private void invalidateMergedVisibleBlurredPositionsAndSourcesImpl(int flags)",
            "private void captureMergedVisibleBlurredPositionsAndSources(int flags)",
            "private void invalidateAllGlassAttachedViews()"))
        invalidate = method(source, "private void invalidateMergedVisibleBlurredPositionsAndSources(int flags)")
        capture = method(source, "private final IBlur3Capture glassListCapture = new IBlur3Capture()") + ";"
        harness = r'''
public class ChatGlassRefreshHarness {
    static void check(boolean value) { if (!value) throw new AssertionError(); }
    static class View {
        static final int VISIBLE = 0;
        int visibility, pixels;
        Runnable duringInvalidate;
        int getVisibility() { return visibility; }
        void invalidate() { if (duringInvalidate != null) duringInvalidate.run(); }
    }
    static class ViewGroup extends View {
        int superclassCalls;
        Runnable duringDraw;
        public void onDescendantInvalidated(View child, View target) { superclassCalls++; }
        boolean drawChild(Canvas c, View v, long time) {
            if (duringDraw != null) duringDraw.run();
            c.pixels = v.pixels; c.childDraws++; return true;
        }
    }
    static class RectF { float left, top, right, bottom; void set(float l, float t, float r, float b) { left = l; top = t; right = r; bottom = b; } }
    static class Canvas {
        int pixels, childDraws, depth;
        int save() { return ++depth; }
        void clipRect(RectF r) {} void restoreToCount(int count) { depth = count - 1; }
    }
    interface Capture { void capture(Canvas c, RectF r); }
    interface IBlur3Capture extends Capture { void captureCalculateHash(IBlur3Hash hash, RectF r); }
    static class IBlur3Hash {
        long value;
        void add(long v) { value = value * 31 + v; }
        void add(Object v) { add(System.identityHashCode(v)); }
    }
    static class Blur3Utils {
        static ViewGroup lastParent;
        static void captureRelativeParent(Capture capture, Canvas c, RectF r, View v, ViewGroup parent) {
            lastParent = parent; capture.capture(c, r);
        }
    }
    static class Build {
        static class VERSION { static int SDK_INT = 33; }
        static class VERSION_CODES { static int S = 31; }
    }
    static class BitwiseUtils { static boolean hasFlag(int flags, int mask) { return (flags & mask) != 0; } }
    static class GlassSource {
        int wallpaperUpdates, contentUpdates;
        void invalidateWallpaper() { wallpaperUpdates++; }
        void invalidateDisplayListForDrawables() { contentUpdates++; }
    }
    static class Suppressor {
        int captures, pixels;
        long lastHash;
        boolean recorded;
        void setupRenderNodes(Object positions, int count) {}
        boolean invalidateResultRenderNodes(IBlur3Capture capture, int w, int h) {
            IBlur3Hash hash = new IBlur3Hash(); capture.captureCalculateHash(hash, new RectF());
            if (recorded && lastHash == hash.value) return false;
            lastHash = hash.value; recorded = true;
            captures++; Canvas c = new Canvas(); capture.capture(c, new RectF()); pixels = c.pixels; return true;
        }
    }
    static class Chat {
        static final int BLUR_INVALIDATE_FLAG_SCROLL = 1, BLUR_INVALIDATE_FLAG_POSITIONS = 2,
            BLUR_INVALIDATE_FLAG_CLIP = 4, BLUR_INVALIDATE_FLAG_WALLPAPER = 8;
        View chatListView = new View(), messagesSearchListContainer = new View(), searchViewPager = new View(), actionBar = new View();
        Content contentView = new Content(); Chat parentChatActivity;
        Suppressor scrollableViewNoiseSuppressor = new Suppressor();
        GlassSource glassBackgroundSourceRenderNode = new GlassSource(), glassBackgroundSourceFrostedRenderNode = new GlassSource();
        int requestedFlags, redraws, glassDrawablesPositionsCount, pendingGlassSourceFlags, glassSourceCaptureDepth;
        long glassListContentVersion;
        CAPTURE_SOURCE
        int fadeInvalidations;
        java.util.List<View> glassAttachedViews = new java.util.ArrayList<>();
        Object glassDrawablesPositionsMerged;
        Runnable feedContentChangedCallback;
        class Observer { void invalidate(int flags) { requestedFlags |= flags; } }
        Observer invalidateBlurredSourcesView = new Observer();
        void invalidateFadeBlur() { fadeInvalidations++; }
        INVALIDATION_METHOD
        void invalidateClipRectForBackgroundAndChatList() {}
        int getMergedVisibleBlurredPositions(Object positions) { return 1; }
        void flush() {
            int flags = requestedFlags; requestedFlags = 0;
            invalidateMergedVisibleBlurredPositionsAndSourcesImpl(flags);
        }
        class Content extends ViewGroup {
            View backgroundView = new View(); RectF glassWallpaperCaptureRect = new RectF();
            int width = 360, height = 800;
            @Override void invalidate() { redraws++; super.invalidate(); }
            int getWidth() { return width; } int getHeight() { return height; }
            long getDrawingTime() { return 1; }
            boolean failCapture;
            void drawListForGlass(Canvas c, RectF r) {
                // Animated children can request redraws while their display lists are recorded.
                onDescendantInvalidated(chatListView, chatListView);
                invalidateMergedVisibleBlurredPositionsAndSources(BLUR_INVALIDATE_FLAG_SCROLL);
                if (failCapture) throw new IllegalStateException();
                c.pixels = chatListView.pixels;
            }
            METHODS
        }
        class LiveWallpaper {
            WALLPAPER_SOURCE
        }
        class SharpWallpaper { void draw(Canvas c, float l, float t, float r, float b) { c.pixels = -1; } }
        SharpWallpaper navbarContentSourceWallpaperSharp = new SharpWallpaper();
        CALLBACK
    }
    public static void main(String[] args) {
        Chat chat = new Chat(); Chat.Content content = chat.contentView;
        View surface = new View(); chat.glassAttachedViews.add(surface);
        surface.duringInvalidate = () -> content.onDescendantInvalidated(chat.chatListView, surface);
        chat.actionBar.duringInvalidate = surface.duringInvalidate;
        content.onDescendantInvalidated(chat.chatListView, new View());
        check(chat.requestedFlags == 1 && chat.pendingGlassSourceFlags == 1 && content.superclassCalls == 1);
        content.onDescendantInvalidated(new View(), new View()); // glass-only redraw
        content.onDescendantInvalidated(chat.searchViewPager, new View()); // nested glass must not create feedback
        check(chat.requestedFlags == 1);
        content.onDescendantInvalidated(chat.messagesSearchListContainer, new View()); check(chat.requestedFlags == 1);
        // Many source mutations before one traversal must coalesce.
        for (int change = 0; change < 1000; change++) {
            content.onDescendantInvalidated(content.backgroundView, content.backgroundView);
            content.onDescendantInvalidated(chat.chatListView, new View());
        }
        check(chat.requestedFlags == 9 && chat.pendingGlassSourceFlags == 9);
        check(chat.scrollableViewNoiseSuppressor.captures == 0 && chat.redraws == 0);
        // Opening before layout must retain dirty sources without capturing empty nodes.
        content.width = 0;
        chat.flush();
        check(chat.pendingGlassSourceFlags == 9 && chat.scrollableViewNoiseSuppressor.captures == 0);
        content.width = 360;
        chat.flush(); // retained changes must survive the pre-layout callback
        check(chat.redraws == 1 && chat.scrollableViewNoiseSuppressor.captures == 1);
        check(chat.pendingGlassSourceFlags == 0 && chat.requestedFlags == 0 && chat.glassSourceCaptureDepth == 0);
        check(chat.glassBackgroundSourceRenderNode.contentUpdates == 0);
        int fadeInvalidations = chat.fadeInvalidations;
        for (int frame = 0; frame < 1000; frame++) chat.flush();
        check(chat.scrollableViewNoiseSuppressor.captures == 1 && chat.redraws == 1);
        check(chat.fadeInvalidations == fadeInvalidations);
        // A stationary list still has to show each new animation frame under glass.
        for (int frame = 1; frame <= 20; frame++) {
            chat.chatListView.pixels = frame;
            content.onDescendantInvalidated(chat.chatListView, chat.chatListView);
            content.onDescendantInvalidated(chat.chatListView, chat.chatListView);
            chat.flush();
            check(chat.scrollableViewNoiseSuppressor.pixels == frame);
            check(chat.scrollableViewNoiseSuppressor.captures == frame + 1);
            check(chat.requestedFlags == 0 && chat.pendingGlassSourceFlags == 0 && chat.glassSourceCaptureDepth == 0);
        }
        chat.invalidateAllGlassAttachedViews(); // output-only redraw outside a capture
        check(chat.requestedFlags == 0 && chat.pendingGlassSourceFlags == 0);
        int captures = chat.scrollableViewNoiseSuppressor.captures;
        int redraws = chat.redraws;
        // A wallpaper-only update must not recapture messages.
        content.onDescendantInvalidated(content.backgroundView, content.backgroundView);
        chat.flush();
        check(chat.redraws == redraws + 1 && chat.scrollableViewNoiseSuppressor.captures == captures);
        check(chat.glassBackgroundSourceRenderNode.wallpaperUpdates == 2 && chat.glassBackgroundSourceFrostedRenderNode.wallpaperUpdates == 2);
        chat.invalidateMergedVisibleBlurredPositionsAndSourcesImpl(1 | 8);
        check(chat.redraws == redraws + 2 && chat.scrollableViewNoiseSuppressor.captures == captures + 1);
        check(chat.glassBackgroundSourceRenderNode.contentUpdates == 0);
        // Repeated geometry notifications must update surfaces without recapturing the same scene.
        chat.invalidateMergedVisibleBlurredPositionsAndSourcesImpl(2);
        check(chat.redraws == redraws + 3 && chat.scrollableViewNoiseSuppressor.captures == captures + 1);
        check(chat.glassBackgroundSourceRenderNode.contentUpdates == 1 && chat.glassBackgroundSourceFrostedRenderNode.contentUpdates == 1);
        for (int frame = 0; frame < 1000; frame++) chat.invalidateMergedVisibleBlurredPositionsAndSourcesImpl(2);
        check(chat.scrollableViewNoiseSuppressor.captures == captures + 1);
        // Keyboard/inset clipping affects both the messages and their wallpaper.
        int wallpaperUpdates = chat.glassBackgroundSourceRenderNode.wallpaperUpdates;
        chat.invalidateMergedVisibleBlurredPositionsAndSources(4);
        chat.flush();
        check(chat.scrollableViewNoiseSuppressor.captures == captures + 2);
        check(chat.glassBackgroundSourceRenderNode.wallpaperUpdates == wallpaperUpdates + 1);
        check(chat.requestedFlags == 0 && chat.glassSourceCaptureDepth == 0);
        Canvas canvas = new Canvas(); Chat.LiveWallpaper wallpaper = chat.new LiveWallpaper();
        content.duringDraw = () -> {
            content.onDescendantInvalidated(content.backgroundView, content.backgroundView);
            chat.invalidateMergedVisibleBlurredPositionsAndSources(8);
        };
        for (int frame = 1; frame <= 20; frame++) {
            content.backgroundView.pixels = frame;
            wallpaper.draw(canvas, 0, 0, 360, 800);
            check(canvas.pixels == frame && canvas.depth == 0 && Blur3Utils.lastParent == content);
            check(chat.requestedFlags == 0 && chat.pendingGlassSourceFlags == 0 && chat.glassSourceCaptureDepth == 0);
        }
        content.failCapture = true;
        try { content.drawList(canvas, new RectF()); throw new AssertionError(); }
        catch (IllegalStateException expected) { check(chat.glassSourceCaptureDepth == 0); }
        content.duringDraw = () -> { throw new IllegalStateException(); };
        try { wallpaper.draw(canvas, 0, 0, 360, 800); throw new AssertionError(); }
        catch (IllegalStateException expected) { check(chat.glassSourceCaptureDepth == 0 && canvas.depth == 0); }
        content.duringDraw = null;
        chat.parentChatActivity = new Chat(); wallpaper.draw(canvas, 0, 0, 360, 800);
        check(Blur3Utils.lastParent == chat.parentChatActivity.contentView);
        content.backgroundView = null; wallpaper.draw(canvas, 0, 0, 360, 800); check(canvas.pixels == -1);
    }
}
'''.replace("METHODS", methods).replace("WALLPAPER_SOURCE", wallpaper_source).replace("CALLBACK", callback).replace("INVALIDATION_METHOD", invalidate).replace("CAPTURE_SOURCE", capture)
        run_java(self, "ChatGlassRefreshHarness", harness)

    def test_invalidations_during_capture_survive_to_next_frame(self):
        source = (ROOT / "org/telegram/messenger/utils/OnPostDrawView.java").read_text()
        methods = "\n".join(method(source, signature) for signature in (
            "public void invalidate(int flags)", "public boolean onPreDraw()", "protected void onDraw("))
        harness = r'''
public class CaptureInvalidationHarness {
    static void check(boolean value) { if (!value) throw new AssertionError(); }
    @interface NonNull {}
    static class Canvas {}
    static class View {
        void invalidate() {}
        public boolean onPreDraw() { return true; }
        protected void onDraw(Canvas c) {}
    }
    interface Callback { void onPostDraw(int flags); }
    static class Observer extends View {
        boolean onPreDrawMode = true;
        int invalidateFlags, preDrawCount;
        Callback callback;
        METHODS
    }
    public static void main(String[] args) {
        for (boolean preDraw : new boolean[]{true, false}) {
            Observer observer = new Observer(); observer.onPreDrawMode = preDraw;
            int[] calls = {0};
            observer.callback = flags -> {
                calls[0]++;
                if (calls[0] == 1) { check(flags == 3); observer.invalidate(8); }
                else check(flags == 8);
            };
            observer.invalidate(1); observer.invalidate(2);
            if (preDraw) observer.onPreDraw(); else observer.onDraw(new Canvas());
            check(observer.invalidateFlags == 8);
            if (preDraw) observer.onPreDraw(); else observer.onDraw(new Canvas());
            check(calls[0] == 2 && observer.invalidateFlags == 0);
            if (preDraw) { observer.onPreDraw(); check(calls[0] == 2); }
        }
    }
}
'''.replace("METHODS", methods)
        run_java(self, "CaptureInvalidationHarness", harness)


if __name__ == "__main__":
    unittest.main()
