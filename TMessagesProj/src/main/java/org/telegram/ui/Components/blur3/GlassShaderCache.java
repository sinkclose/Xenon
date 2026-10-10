package org.telegram.ui.Components.blur3;

import android.graphics.RuntimeShader;
import android.os.Process;

import androidx.annotation.RequiresApi;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.R;

import java.util.ArrayDeque;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Prepared shaders are handed off once; active surfaces never share mutable uniforms. */
@RequiresApi(api = 33)
final class GlassShaderCache {
    private static final Pool standard = new Pool(R.raw.liquid_glass_shader, 12);
    private static final Pool advanced = new Pool(R.raw.liquid_glass_shader_advanced, 12);
    private static final Pool prismStandard = new Pool(R.raw.prism_glass_shader, 12);
    private static final Pool prismAdvanced = new Pool(R.raw.prism_glass_shader_advanced, 12);
    private static final Pool highlight = new Pool(R.raw.liquid_glass_highlight, 16);

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
        try {
            return refractionPool(useAdvanced, prism).acquire();
        } catch (RuntimeException e) {
            if (!prism) throw e;
            // A driver rejecting the optimized program keeps the original optics.
            FileLog.e(e);
            return refractionPool(useAdvanced, false).acquire();
        }
    }

    static RuntimeShader acquireHighlight() {
        return highlight.acquire();
    }

    private static final class Pool {
        private final int resource;
        private final int capacity;
        private final ArrayDeque<RuntimeShader> ready = new ArrayDeque<>();
        private volatile String source;
        private boolean refillPending;
        private boolean warmupFailed;

        Pool(int resource, int capacity) {
            this.resource = resource;
            this.capacity = capacity;
        }

        RuntimeShader acquire() {
            final RuntimeShader shader;
            synchronized (this) {
                shader = ready.pollFirst();
            }
            if (shader != null) {
                refill();
                return shader;
            }
            // A miss keeps the existing rendering path. Never wait for the worker.
            RuntimeShader created = new RuntimeShader(source());
            refill();
            return created;
        }

        private String source() {
            String code = source;
            if (code == null) {
                // Resource I/O and compilation stay outside the queue monitor so the
                // UI can take a prepared shader while the worker is compiling another.
                code = AndroidUtilities.readRes(resource);
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
                }
            } catch (RuntimeException | LinkageError e) {
                synchronized (this) {
                    warmupFailed = true;
                    refillPending = false;
                }
                // Optional warmup must not crash the process. Demand compilation
                // still uses the original constructor and its normal error handling.
                FileLog.e(e);
            }
        }
    }
}
