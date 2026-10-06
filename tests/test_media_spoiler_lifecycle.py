"""Exercise production spoiler cache and thread lifecycle without an Android SDK."""
import subprocess
import tempfile
import unittest
from pathlib import Path

from test_glass_blur import ROOT, method


class MediaSpoilerLifecycleTest(unittest.TestCase):
    def test_windows_preview_removal_resume_and_paused_shutdown(self):
        source = (ROOT / 'org/telegram/ui/Components/spoilers/SpoilerEffect2.java').read_text()
        cache_methods = '\n'.join(method(source, signature) for signature in (
            'public static SpoilerEffect2 getInstance(int type, View view, ViewGroup rootView)',
            'public static void pause(boolean pause)',
            'public static void pause(int type, boolean pause)',
            'public void attach(View view)',
            'public void detach(View view)',
            'public int getAttachIndex(View view)',
            'private void destroy()'))
        thread_source = source[source.index('private class SpoilerThread extends Thread'):]
        thread_methods = '\n'.join(method(thread_source, signature) for signature in (
            'public void halt()', 'public void pause(boolean paused)', 'public void run()'))
        harness = r'''
import java.util.*;
public class MediaSpoilerLifecycleHarness {
    static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
    static class View {}
    static class ViewGroup extends View {
        ViewGroup parent;
        Object getParent() { return parent; }
        void removeView(View child) {
            if (child instanceof ViewGroup) ((ViewGroup) child).parent = null;
        }
    }
    static class AndroidUtilities {
        static final Set<Runnable> scheduled = new HashSet<>();
        static synchronized void cancelRunOnUIThread(Runnable r) { scheduled.remove(r); }
        static synchronized void runOnUIThread(Runnable r) { scheduled.add(r); }
        static synchronized void runOnUIThread(Runnable r, long delay) { scheduled.add(r); }
    }
    static class SpoilerEffect2 {
        static final ArrayList<SpoilerEffect2> instances = new ArrayList<>();
        final int type;
        final ViewGroup textureViewContainer;
        final View textureView = new View();
        SpoilerThread thread;
        boolean destroyed;
        final ArrayList<View> holders = new ArrayList<>();
        final HashMap<View, Integer> holdersToIndex = new HashMap<>();
        int holdersIndex;
        final Runnable checkDestroy = () -> { if (holders.isEmpty()) destroy(); };
        SpoilerEffect2(int type, ViewGroup container, int width, int height) {
            this.type = type;
            textureViewContainer = container;
            thread = new SpoilerThread();
        }
        static boolean supports() { return true; }
        static int getSize() { return 720; }
        static ViewGroup makeTextureViewContainer(ViewGroup root) {
            ViewGroup container = new ViewGroup();
            container.parent = root;
            return container;
        }
        CACHE_METHODS
        private class SpoilerThread extends Thread {
            volatile boolean running = true, paused;
            volatile boolean initialized, cleaned;
            volatile int frames;
            final double MIN_DELTA = 1. / 60, MAX_DELTA = MIN_DELTA * 4;
            final Runnable invalidate = () -> {};
            void init() { initialized = true; }
            void die() { cleaned = true; }
            void checkResize() {}
            void drawFrame(float dt) { frames++; }
            THREAD_METHODS
        }
    }
    public static void main(String[] args) throws Exception {
        ViewGroup chatRoot = new ViewGroup(), newRoot = new ViewGroup();
        View photo = new View(), video = new View(), previewView = new View();
        SpoilerEffect2 chat = SpoilerEffect2.getInstance(0, photo, chatRoot);
        check(chat == SpoilerEffect2.getInstance(0, video, chatRoot), "share within a window");
        check(chat.getAttachIndex(photo) != chat.getAttachIndex(video), "distinct holder indices");
        SpoilerEffect2 preview = SpoilerEffect2.getInstance(1, previewView, chatRoot);
        SpoilerEffect2 replacement = SpoilerEffect2.getInstance(0, new View(), newRoot);
        check(replacement != chat, "a new window needs a new surface");
        check(SpoilerEffect2.getInstance(0, new View(), null) == null, "no root means no effect");

        SpoilerEffect2.pause(0, true);
        check(chat.thread.paused && replacement.thread.paused && !preview.thread.paused,
              "type-specific pause across windows");
        preview.detach(previewView);
        preview.checkDestroy.run();
        check(preview.destroyed && preview.thread == null, "destroy unused preview");
        check(SpoilerEffect2.instances.size() == 2, "keep both chat surfaces registered");
        SpoilerEffect2.pause(false);
        check(!chat.thread.paused && !replacement.thread.paused, "resume after preview removal");
        check(chat == SpoilerEffect2.getInstance(0, photo, chatRoot), "preview keeps chat cache");

        chat.detach(photo);
        chat.checkDestroy.run();
        check(!chat.destroyed, "video still holds chat renderer");
        chat.detach(video);
        chat.attach(photo);
        chat.checkDestroy.run();
        check(!chat.destroyed, "reattach before delayed cleanup preserves surface");
        chat.detach(photo);
        chat.checkDestroy.run();
        check(chat.destroyed, "last holder releases surface");
        chat.destroy();
        check(SpoilerEffect2.instances.size() == 1, "repeated cleanup keeps replacement");
        check(replacement == SpoilerEffect2.getInstance(0, new View(), newRoot),
              "old window cleanup keeps replacement cached");

        var worker = replacement.thread;
        worker.pause(true);
        worker.start();
        long deadline = System.nanoTime() + 2_000_000_000L;
        while (!worker.initialized && System.nanoTime() < deadline) Thread.sleep(1);
        worker.halt();
        worker.join(1500);
        check(!worker.isAlive() && worker.cleaned, "paused worker exits and releases GL resources");
        check(worker.frames == 0, "halt must not draw on a destroyed surface");
    }
}
'''.replace('CACHE_METHODS', cache_methods).replace('THREAD_METHODS', thread_methods)
        with tempfile.TemporaryDirectory() as directory:
            java = Path(directory) / 'MediaSpoilerLifecycleHarness.java'
            java.write_text(harness)
            result = subprocess.run(['java', '-m', 'jdk.compiler/com.sun.tools.javac.Main', str(java)],
                                    capture_output=True, text=True)
            self.assertEqual(0, result.returncode, result.stderr)
            result = subprocess.run(['java', '-cp', directory, java.stem],
                                    capture_output=True, text=True, timeout=8)
            self.assertEqual(0, result.returncode, result.stderr)


if __name__ == '__main__':
    unittest.main()
