package org.telegram.ui.Components.blur3;

import android.graphics.RuntimeShader;
import android.os.Build;
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
import java.util.function.Function;
import java.util.function.Supplier;

/** Prepared shaders are handed off once; active surfaces never share mutable uniforms. */
@RequiresApi(api = 33)
public final class GlassShaderCache {
    private static final Pool<RuntimeShader> standard = shaderPool(R.raw.liquid_glass_shader, 12);
    private static final Pool<RuntimeShader> advanced = shaderPool(R.raw.liquid_glass_shader_advanced, 12);
    private static final Pool<RuntimeShader> prismStandard = shaderPool(R.raw.prism_glass_shader, 12);
    private static final Pool<RuntimeShader> prismAdvanced = shaderPool(R.raw.prism_glass_shader_advanced, 12);
    private static final Pool<RuntimeShader> ios27 = shaderPool(() -> ios27Source(Build.VERSION.SDK_INT < 36), 12);
    private static final Pool<RuntimeShader> ios27Menu = shaderPool(() -> menuSource(ios27Source(false))
            .replace("return nativeGlassRoundedField(local, extent, float2(r), 0.0); // MENU_DISTANCE",
                    "return liquidFieldGradient(coord);"), 2);
    private static final Pool<RuntimeShader> ios26Menu = shaderPool(() -> menuSource(AndroidUtilities.readRes(R.raw.glasmio_menu_26)), 2);
    private static final Pool<RuntimeShader> menuPortal = shaderPool(R.raw.glasmio_menu_portal, 4);
    private static final Pool<RuntimeShader> menuHighlight26 = shaderPool(R.raw.glasmio_menu_highlight26, 2);
    private static final Pool<RuntimeShader> highlight = shaderPool(R.raw.liquid_glass_highlight, 16);
    private static final Pool<GlasmioPressEffect> ios27PressOverlay = new Pool<>(
            () -> AndroidUtilities.readRes(R.raw.glasmio_press_mask), GlasmioPressEffect::new, 3);
    private static final ConcurrentHashMap<Integer, Pool<RuntimeShader>> unions = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Integer, Pool<RuntimeShader>> progressive = new ConcurrentHashMap<>();

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
        if (zxc.iconic.xenon.NekoConfig.ios27LiquidGlass) {
            if (refractionEnabled) {
                if (Build.VERSION.SDK_INT >= 36) ios27PressOverlay.refill();
                ios27Menu.refill();
            }
        } else {
            highlight.refill();
            if (refractionEnabled) ios26Menu.refill();
        }
        if (zxc.iconic.xenon.NekoConfig.iosPopups) {
            menuPortal.refill();
            if (refractionEnabled && !zxc.iconic.xenon.NekoConfig.ios27LiquidGlass) menuHighlight26.refill();
        }
        // Navigation capsules use fixed advanced refraction even in standard mode.
        if (refractionEnabled && !useAdvanced && !zxc.iconic.xenon.NekoConfig.ios27LiquidGlass) {
            refractionPool(true, prism).refill();
        }
    }

    private static Pool<RuntimeShader> refractionPool(boolean useAdvanced, boolean prism) {
        if (zxc.iconic.xenon.NekoConfig.ios27LiquidGlass) return ios27;
        return prism ? (useAdvanced ? prismAdvanced : prismStandard) : (useAdvanced ? advanced : standard);
    }

    static RuntimeShader acquireRefraction(boolean useAdvanced, boolean prism) {
        return acquireRefraction(useAdvanced, prism, null);
    }

    static RuntimeShader acquireRefraction(boolean useAdvanced, boolean prism, Runnable onReady) {
        Pool<RuntimeShader> pool = refractionPool(useAdvanced, prism);
        if (prism && pool.hasFailed()) pool = refractionPool(useAdvanced, false);
        return pool.acquire(onReady);
    }

    static RuntimeShader acquireIos26Refraction(Runnable onReady) {
        return advanced.acquire(onReady);
    }

    static RuntimeShader acquireIos26Highlight(Runnable onReady) {
        return highlight.acquire(onReady);
    }

    static RuntimeShader acquireHighlight() {
        return acquireHighlight(null);
    }

    static RuntimeShader acquireHighlight(Runnable onReady) {
        return highlight.acquire(onReady);
    }

    private static String ios27Source(boolean sampledPress) {
        String source = withIos27Optics(AndroidUtilities.readRes(R.raw.ios27_liquid_glass));
        if (!sampledPress) {
            return source.replace("return applyGlassPress(color, coord); // PRESS_APPLY", "return color;");
        }
        String press = AndroidUtilities.readRes(R.raw.glasmio_press);
        return source.replace("// PRESS_EFFECT", press.substring(0, press.indexOf("uniform shader content;")));
    }

    @RequiresApi(36)
    public static GlasmioPressEffect acquireIos27PressOverlay(Runnable onReady) {
        return ios27PressOverlay.acquire(onReady);
    }

    public static String withIos27Optics(String source) {
        return withRoundedField(source).replace("// BLUR_PLANE",
                AndroidUtilities.readRes(R.raw.liquid_glass_blur27));
    }

    public static String withIos27PressTransfer(String source) {
        String press = AndroidUtilities.readRes(R.raw.glasmio_press);
        return source.replace("// PRESS_TRANSFER", press.substring(0, press.indexOf("uniform float2 pressCenter;")));
    }

    private static String withRoundedField(String source) {
        return source.replace("// ROUNDED_FIELD", AndroidUtilities.readRes(R.raw.liquid_rounded_field));
    }

    private static String menuSource(String source) {
        return withRoundedField(source).replace("// MENU_FIELD", AndroidUtilities.readRes(R.raw.liquid_menu_field));
    }

    public static RuntimeShader acquireMenuRefraction(boolean ios27, Runnable onReady) {
        return (ios27 ? ios27Menu : ios26Menu).acquire(onReady);
    }

    public static RuntimeShader acquireMenuPortal(Runnable onReady) {
        return menuPortal.acquire(onReady);
    }

    public static RuntimeShader acquireMenuHighlight26(Runnable onReady) {
        return menuHighlight26.acquire(onReady);
    }

    public static RuntimeShader acquireUnion(int shapeCount, Supplier<String> source, Runnable onReady) {
        return unions.computeIfAbsent(shapeCount, key -> shaderPool(source, 2)).acquire(onReady);
    }

    public static RuntimeShader acquireProgressive(int samples, Supplier<String> source, Runnable onReady) {
        return progressive.computeIfAbsent(samples, key -> shaderPool(source, 4)).acquire(onReady);
    }

    private static Pool<RuntimeShader> shaderPool(int resource, int capacity) {
        return shaderPool(() -> AndroidUtilities.readRes(resource), capacity);
    }

    private static Pool<RuntimeShader> shaderPool(Supplier<String> source, int capacity) {
        return new Pool<>(source, RuntimeShader::new, capacity);
    }

    private static final class Pool<T> {
        private final Supplier<String> sourceFactory;
        private final Function<String, T> compiler;
        private final int capacity;
        private final ArrayDeque<T> ready = new ArrayDeque<>();
        private volatile String source;
        private boolean refillPending;
        private boolean warmupFailed;
        private final HashSet<Runnable> waiting = new HashSet<>();

        Pool(Supplier<String> sourceFactory, Function<String, T> compiler, int capacity) {
            this.sourceFactory = sourceFactory;
            this.compiler = compiler;
            this.capacity = capacity;
        }

        synchronized boolean hasFailed() {
            return warmupFailed;
        }

        T acquire(Runnable onReady) {
            final T shader;
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
                T shader = compiler.apply(source());
                boolean more;
                synchronized (this) {
                    ready.addLast(shader);
                    more = ready.size() < capacity;
                    if (!more) refillPending = false;
                }
                notifyWaiting();
                // Interleave programs so a pool of refraction shaders cannot delay the press mask.
                if (more) worker.execute(this::fill);
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
