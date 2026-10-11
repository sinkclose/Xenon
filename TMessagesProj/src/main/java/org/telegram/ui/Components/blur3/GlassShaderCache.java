package org.telegram.ui.Components.blur3;

import android.graphics.RuntimeShader;
import android.os.Process;

import androidx.annotation.RequiresApi;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.R;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/** Prepared shaders are handed off once; active surfaces never share mutable uniforms. */
@RequiresApi(api = 33)
public final class GlassShaderCache {
    private static final Pool standard = new Pool(R.raw.liquid_glass_shader, 12);
    private static final Pool advanced = new Pool(R.raw.liquid_glass_shader_advanced, 12);
    private static final Pool prismStandard = new Pool(R.raw.prism_glass_shader, 12);
    private static final Pool prismAdvanced = new Pool(R.raw.prism_glass_shader_advanced, 12);
    private static final Pool highlight = new Pool(R.raw.liquid_glass_highlight, 16);
    private static final ConcurrentHashMap<Integer, Pool> progressive = new ConcurrentHashMap<>();

    private static final ThreadPoolExecutor worker = new ThreadPoolExecutor(
            0, 1, 10, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), task ->
            new Thread(() -> {
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
                task.run();
            }, "GlassShaderWarmup"));

    static void prewarm(boolean refractionEnabled, boolean useAdvanced, boolean prism) {
        // Refraction is the more expensive program; prepare it before the highlights.
        if (refractionEnabled) {
            refractionPool(useAdvanced, prism).refill();
        }
        highlight.refill();
        // Navigation capsules use fixed advanced refraction even in standard mode.
        if (refractionEnabled && !useAdvanced) {
            refractionPool(true, prism).refill();
        }
    }

    private static Pool refractionPool(boolean useAdvanced, boolean prism) {
        return prism ? (useAdvanced ? prismAdvanced : prismStandard) : (useAdvanced ? advanced : standard);
    }

    static RuntimeShader acquireRefraction(boolean useAdvanced, boolean prism) {
        return acquireRefraction(useAdvanced, prism, null);
    }

    static RuntimeShader acquireRefraction(boolean useAdvanced, boolean prism, Runnable onReady) {
        Pool pool = refractionPool(useAdvanced, prism);
        if (prism && pool.hasFailed()) pool = refractionPool(useAdvanced, false);
        return pool.acquire(onReady);
    }

    static RuntimeShader acquireHighlight() {
        return acquireHighlight(null);
    }

    static RuntimeShader acquireHighlight(Runnable onReady) {
        return highlight.acquire(onReady);
    }

    public static RuntimeShader acquireProgressive(int samples, Supplier<String> source, Runnable onReady) {
        return progressive.computeIfAbsent(samples, key -> new Pool(source, 4)).acquire(onReady);
    }

    private static final class Pool {
        private final Supplier<String> sourceFactory;
        private final int capacity;
        private final ArrayDeque<RuntimeShader> ready = new ArrayDeque<>();
        private volatile String source;
        private boolean refillPending;
        private boolean warmupFailed;
        private final HashSet<Runnable> waiting = new HashSet<>();

        Pool(int resource, int capacity) {
            this(() -> AndroidUtilities.readRes(resource), capacity);
        }

        Pool(Supplier<String> sourceFactory, int capacity) {
            this.sourceFactory = sourceFactory;
            this.capacity = capacity;
        }

        synchronized boolean hasFailed() {
            return warmupFailed;
        }

        RuntimeShader acquire(Runnable onReady) {
            final RuntimeShader shader;
            synchronized (this) {
                shader = ready.pollFirst();
                if (shader == null && !warmupFailed && onReady != null) waiting.add(onReady);
            }
            // A cold chat must never compile on the UI thread. Until the worker
            // publishes a shader, the surface keeps its ordinary blurred tint.
            refill();
            return shader;
        }

        private String source() {
            String code = source;
            if (code == null) {
                // Resource I/O and compilation stay outside the queue monitor so the
                // UI can take a prepared shader while the worker is compiling another.
                code = sourceFactory.get();
                if (code != null) {
                    source = code;
                }
            }
            return code;
        }

        void refill() {
            synchronized (this) {
                if (refillPending || warmupFailed || ready.size() >= capacity) {
                    return;
                }
                refillPending = true;
            }
            worker.execute(this::fill);
        }

        private void fill() {
            try {
                while (true) {
                    synchronized (this) {
                        if (ready.size() >= capacity) {
                            refillPending = false;
                            return;
                        }
                    }
                    RuntimeShader shader = new RuntimeShader(source());
                    synchronized (this) {
                        ready.addLast(shader);
                    }
                    notifyWaiting();
                }
            } catch (RuntimeException | LinkageError e) {
                synchronized (this) {
                    warmupFailed = true;
                    refillPending = false;
                }
                // A rejected optional program leaves consumers on the blurred tint.
                FileLog.e(e);
                // Also wake Prism users so they can request the original program.
                notifyWaiting();
            }
        }

        private void notifyWaiting() {
            Runnable[] callbacks;
            synchronized (this) {
                callbacks = waiting.toArray(new Runnable[0]);
                waiting.clear();
            }
            for (Runnable callback : callbacks) AndroidUtilities.runOnUIThread(callback);
        }
    }
}
