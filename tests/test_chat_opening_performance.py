"""Cold-cache compilation and lazy interaction allocation regressions."""
import re
import unittest

from test_glass_blur import ROOT, method
from test_glass_chat_refresh import run_java


class ChatOpeningPerformanceTest(unittest.TestCase):
    def test_shader_cache_misses_never_compile_on_the_caller(self):
        source = (ROOT / 'org/telegram/ui/Components/blur3/GlassShaderCache.java').read_text()
        imports = '\n'.join(re.findall(r'^import java\..*;', source, re.M))
        source = re.sub(r'^(?:package|import) .*;\n', '', source, flags=re.M)
        source = re.sub(r'@RequiresApi\([^\n]*\)\n', '', source)
        source = source.replace('public final class GlassShaderCache', 'static final class GlassShaderCache')
        harness = r'''
IMPORTS
public class CacheHarness {
    static void check(boolean v) { if (!v) throw new AssertionError(); }
    static final java.util.concurrent.ConcurrentLinkedQueue<Runnable> ui = new java.util.concurrent.ConcurrentLinkedQueue<>();
    static final java.util.concurrent.CountDownLatch compileGate = new java.util.concurrent.CountDownLatch(1);
    static class RuntimeShader {
        final String code;
        RuntimeShader(String code) {
            check(Thread.currentThread().getName().equals("GlassShaderWarmup"));
            this.code = code;
            if (code.equals("bad")) throw new IllegalArgumentException();
            if (code.equals("blocked")) try { compileGate.await(); } catch (InterruptedException e) { throw new RuntimeException(e); }
        }
    }
    static class Process { static int THREAD_PRIORITY_BACKGROUND; static void setThreadPriority(int p) {} }
    static class AndroidUtilities {
        static String readRes(int id) { return "resource:" + id; }
        static void runOnUIThread(Runnable r) { ui.add(r); }
    }
    static class FileLog { static void e(Throwable t) {} }
    static class R { static class raw {
        static int liquid_glass_shader=1, liquid_glass_shader_advanced=2,
            prism_glass_shader=3, prism_glass_shader_advanced=4, liquid_glass_highlight=5;
    } }
    CACHE
    interface Condition { boolean get(); }
    static void await(Condition c) throws Exception {
        long until = System.nanoTime() + 5_000_000_000L;
        while (!c.get()) {
            if (System.nanoTime() > until) throw new AssertionError("Worker stalled");
            Thread.sleep(1);
        }
    }
    public static void main(String[] args) throws Exception {
        try {
            GlassShaderCache.Pool p = new GlassShaderCache.Pool(() -> "blocked", 2);
            int[] callbacks = {0}; Runnable ready = () -> callbacks[0]++;
            check(p.acquire(ready) == null);
            check(p.acquire(ready) == null); // one callback per consumer
            check(ui.isEmpty());
            compileGate.countDown();
            await(() -> { synchronized (p) { return p.ready.size() == 2; } });
            Runnable r; while ((r = ui.poll()) != null) r.run();
            check(callbacks[0] == 1);
            RuntimeShader a = p.acquire(null), b = p.acquire(null);
            check(a != null && b != null && a != b); // mutable uniforms never shared
            GlassShaderCache.Pool failed = new GlassShaderCache.Pool(() -> "bad", 1);
            check(failed.acquire(ready) == null);
            await(failed::hasFailed);
            await(() -> !ui.isEmpty());
            while ((r = ui.poll()) != null) r.run();
            check(callbacks[0] == 2 && failed.acquire(ready) == null);
            synchronized (failed) { check(failed.waiting.isEmpty()); }
        } finally { compileGate.countDown(); GlassShaderCache.worker.shutdownNow(); }
    }
}
'''.replace('IMPORTS', imports).replace('CACHE', source)
        run_java(self, 'CacheHarness', harness)

    def test_untouched_liquid_controls_allocate_no_springs(self):
        source = (ROOT / 'org/telegram/ui/Components/LiquidTouchEffect.java').read_text()
        methods = '\n'.join(method(source, s) for s in (
            'public LiquidTouchEffect(View host)', 'private void ensureSprings()',
            'private SpringAnimation spring(Update update)', 'public void reset()'))
        methods = methods.replace('public LiquidTouchEffect(View host)', 'Effect(View host)')
        harness = r'''
public class SpringHarness {
    static void check(boolean v) { if (!v) throw new AssertionError(); }
    static class View { int invalidations; void invalidate() { invalidations++; } }
    static class AndroidUtilities { static void cancelRunOnUIThread(Runnable r) {} }
    static class PorterDuff { static class Mode { static int ADD; } }
    static class PorterDuffXfermode { PorterDuffXfermode(int mode) {} }
    static class Paint { void setXfermode(Object mode) {} }
    static class FloatValueHolder {}
    static class SpringForce {
        SpringForce(float v) {} SpringForce setDampingRatio(float v) { return this; }
        SpringForce setStiffness(float v) { return this; }
    }
    interface Listener { void update(Object a, float v, float velocity); }
    static class SpringAnimation {
        static int creations;
        SpringAnimation(FloatValueHolder h) { creations++; }
        void setSpring(SpringForce f) {} void setMinimumVisibleChange(float v) {}
        void addUpdateListener(Listener l) {} void cancel() {} void setStartValue(float v) {}
    }
    static class Effect {
        View host; Paint glowPaint = new Paint();
        SpringAnimation press, returnX, returnY;
        float progress, offsetX, offsetY; int pointerId=-1;
        boolean releasePending; long minimumPressDuration;
        Runnable releasePress = () -> {};
        interface Update { void set(float v); }
        void invalidateHost() { host.invalidate(); }
        METHODS
    }
    public static void main(String[] args) {
        View host = new View(); Effect[] effects = new Effect[100];
        for (int i=0; i<effects.length; i++) { effects[i]=new Effect(host); effects[i].reset(); }
        check(SpringAnimation.creations == 0 && host.invalidations == 0);
        Effect active=effects[0]; active.ensureSprings(); active.ensureSprings();
        check(SpringAnimation.creations == 3);
        active.progress=.5f; active.reset();
        check(host.invalidations == 1 && active.progress == 0);
        active.reset(); check(host.invalidations == 1);
    }
}
'''.replace('METHODS', methods)
        run_java(self, 'SpringHarness', harness)


if __name__ == '__main__':
    unittest.main()
