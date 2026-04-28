package zxc.iconic.xenon.proxy;

import android.text.TextUtils;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Manages embedded Xray core lifecycle in app-only mode.
 *
 * This manager intentionally uses reflection against AndroidLibXrayLite classes
 * so the app still compiles and runs even when the AAR is not yet linked.
 */
public final class XrayAppProxyManager {

    private static final String TAG = "XrayAppProxyManager";

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final AtomicBoolean STARTING_OR_STOPPING = new AtomicBoolean(false);
    private static final int MAX_RECENT_LOG_LINES = 200;
    private static final Object LOG_LOCK = new Object();
    private static final ArrayList<String> RECENT_LOGS = new ArrayList<>();
    private static final SimpleDateFormat LOG_TIME_FORMAT = new SimpleDateFormat("HH:mm:ss", Locale.US);

    private static volatile Object coreController;
    private static volatile boolean running;

    private XrayAppProxyManager() {
    }

    public interface StartCallback {
        void onComplete(boolean success, String message);
    }

    public interface StopCallback {
        void onComplete(boolean success, String message);
    }

    public interface DelayCallback {
        void onComplete(boolean success, long delayMs, String message);
    }

    public static boolean isLibraryAvailable() {
        try {
            Class.forName("libv2ray.Libv2ray");
            return true;
        } catch (Throwable ignore) {
            return false;
        }
    }

    /**
     * Starts embedded Xray core with provided JSON config.
     * Edge cases handled: missing library, duplicate starts, malformed config.
     */
    public static void start(String configJson, StartCallback callback) {
        addLog("start requested");
        if (TextUtils.isEmpty(configJson)) {
            addLog("start rejected: empty config");
            if (callback != null) {
                callback.onComplete(false, "Empty config");
            }
            return;
        }
        if (!STARTING_OR_STOPPING.compareAndSet(false, true)) {
            addLog("start rejected: busy");
            if (callback != null) {
                callback.onComplete(false, "Busy");
            }
            return;
        }

        EXECUTOR.execute(() -> {
            try {
                if (running) {
                    addLog("start skipped: already running");
                    notifyStart(callback, true, "Already running");
                    return;
                }
                ensureCoreInitialized();
                if (coreController == null) {
                    addLog("start failed: library missing");
                    notifyStart(callback, false, "AndroidLibXrayLite is missing");
                    return;
                }
                invokeStartLoop(coreController, configJson);
                running = true;
                addLog("start success");
                notifyStart(callback, true, "Started");
            } catch (Throwable t) {
                FileLog.e(TAG + ": start failed", t);
                running = false;
                addLog("start failed: " + (t.getMessage() == null ? "unknown" : t.getMessage()));
                notifyStart(callback, false, t.getMessage() == null ? "Start failed" : t.getMessage());
            } finally {
                STARTING_OR_STOPPING.set(false);
            }
        });
    }

    /**
     * Stops embedded Xray core if running.
     */
    public static void stop(StopCallback callback) {
        addLog("stop requested");
        if (!STARTING_OR_STOPPING.compareAndSet(false, true)) {
            addLog("stop rejected: busy");
            if (callback != null) {
                callback.onComplete(false, "Busy");
            }
            return;
        }

        EXECUTOR.execute(() -> {
            try {
                if (coreController != null) {
                    Method stopLoop = coreController.getClass().getMethod("stopLoop");
                    stopLoop.invoke(coreController);
                }
                running = false;
                addLog("stop success");
                notifyStop(callback, true, "Stopped");
            } catch (Throwable t) {
                FileLog.e(TAG + ": stop failed", t);
                addLog("stop failed: " + (t.getMessage() == null ? "unknown" : t.getMessage()));
                notifyStop(callback, false, t.getMessage() == null ? "Stop failed" : t.getMessage());
            } finally {
                STARTING_OR_STOPPING.set(false);
            }
        });
    }

    public static boolean isRunning() {
        return running;
    }

    /**
     * Measures outbound delay using provided config and test URL.
     */
    public static void measureDelay(String configJson, String testUrl, DelayCallback callback) {
        addLog("delay check requested");
        if (TextUtils.isEmpty(configJson) || TextUtils.isEmpty(testUrl)) {
            addLog("delay check rejected: empty config or url");
            if (callback != null) {
                callback.onComplete(false, -1, "Config or URL is empty");
            }
            return;
        }
        EXECUTOR.execute(() -> {
            try {
                Class<?> libClass = Class.forName("libv2ray.Libv2ray");
                Method method = libClass.getMethod("measureOutboundDelay", String.class, String.class);
                Object result = method.invoke(null, configJson, testUrl);
                long delay = result instanceof Number ? ((Number) result).longValue() : -1;
                if (delay < 0) {
                    addLog("delay check failed");
                    notifyDelay(callback, false, -1, "Delay check failed");
                } else {
                    addLog("delay check success: " + delay + "ms");
                    notifyDelay(callback, true, delay, "OK");
                }
            } catch (Throwable t) {
                FileLog.e(TAG + ": delay check failed", t);
                addLog("delay check error: " + (t.getMessage() == null ? "unknown" : t.getMessage()));
                notifyDelay(callback, false, -1, t.getMessage() == null ? "Delay check failed" : t.getMessage());
            }
        });
    }

    /**
     * Returns recent in-memory core lifecycle logs for UI.
     */
    public static ArrayList<String> getRecentLogs() {
        synchronized (LOG_LOCK) {
            return new ArrayList<>(RECENT_LOGS);
        }
    }

    /**
     * Clears in-memory log buffer.
     */
    public static void clearRecentLogs() {
        synchronized (LOG_LOCK) {
            RECENT_LOGS.clear();
        }
        addLog("logs cleared");
    }

    private static void ensureCoreInitialized() throws Exception {
        if (coreController != null) {
            return;
        }

        Class<?> libClass;
        try {
            libClass = Class.forName("libv2ray.Libv2ray");
        } catch (Throwable t) {
            return;
        }

        String envPath = ApplicationLoader.applicationContext.getFilesDir().getAbsolutePath();
        Method initCoreEnv = libClass.getMethod("initCoreEnv", String.class, String.class);
        initCoreEnv.invoke(null, envPath, "");

        Class<?> callbackInterface = Class.forName("libv2ray.CoreCallbackHandler");
        Object callback = Proxy.newProxyInstance(
                callbackInterface.getClassLoader(),
                new Class[]{callbackInterface},
                new CoreCallbackInvocationHandler()
        );

        Method newCoreController = libClass.getMethod("newCoreController", callbackInterface);
        coreController = newCoreController.invoke(null, callback);
    }

    private static void invokeStartLoop(Object controller, String configJson) throws Exception {
        Method startLoop;
        try {
            startLoop = controller.getClass().getMethod("startLoop", String.class, int.class);
            startLoop.invoke(controller, configJson, 0);
            return;
        } catch (NoSuchMethodException ignored) {
        }

        startLoop = controller.getClass().getMethod("startLoop", String.class, long.class);
        startLoop.invoke(controller, configJson, 0L);
    }

    private static void notifyStart(StartCallback callback, boolean success, String message) {
        if (callback != null) {
            callback.onComplete(success, message);
        }
    }

    private static void notifyStop(StopCallback callback, boolean success, String message) {
        if (callback != null) {
            callback.onComplete(success, message);
        }
    }

    private static void notifyDelay(DelayCallback callback, boolean success, long delayMs, String message) {
        if (callback != null) {
            callback.onComplete(success, delayMs, message);
        }
    }

    private static void addLog(String text) {
        String line;
        synchronized (LOG_TIME_FORMAT) {
            line = LOG_TIME_FORMAT.format(new Date()) + "  " + text;
        }
        synchronized (LOG_LOCK) {
            RECENT_LOGS.add(line);
            if (RECENT_LOGS.size() > MAX_RECENT_LOG_LINES) {
                RECENT_LOGS.remove(0);
            }
        }
        FileLog.d(TAG + " log: " + text);
    }

    private static final class CoreCallbackInvocationHandler implements InvocationHandler {
        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            try {
                String name = method.getName();
                if ("onEmitStatus".equals(name)) {
                    if (args != null && args.length > 1 && args[1] != null) {
                        FileLog.d(TAG + " status: " + args[1]);
                        addLog("status: " + args[1]);
                    }
                } else if ("startup".equals(name) || "shutdown".equals(name)) {
                    FileLog.d(TAG + " callback: " + name);
                    addLog("callback: " + name);
                } else {
                    if (args != null && args.length > 0) {
                        FileLog.d(TAG + " callback " + name);
                        addLog("callback: " + name);
                    }
                }
            } catch (Throwable t) {
                FileLog.e(TAG + ": callback error", t);
            }
            Class<?> returnType = method.getReturnType();
            if (returnType == boolean.class) {
                return false;
            }
            if (returnType == int.class) {
                return 0;
            }
            if (returnType == long.class) {
                return 0L;
            }
            if (returnType == short.class) {
                return (short) 0;
            }
            if (returnType == byte.class) {
                return (byte) 0;
            }
            if (returnType == float.class) {
                return 0f;
            }
            if (returnType == double.class) {
                return 0d;
            }
            return null;
        }
    }
}
