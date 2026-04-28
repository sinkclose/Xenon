package zxc.iconic.xenon.proxy;

import android.content.Context;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;

import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import go.Seq;
import libv2ray.CoreCallbackHandler;
import libv2ray.CoreController;
import libv2ray.Libv2ray;

/**
 * Canonical Xray core engine for app-only proxy mode.
 *
 * Lifecycle is aligned with v2rayNG patterns:
 * 1) Seq context initialization
 * 2) Libv2ray environment initialization
 * 3) single CoreController instance with callback-driven state updates
 */
final class XrayCoreEngine {

    private static final String TAG = "XrayCoreEngine";
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final AtomicBoolean STARTING_OR_STOPPING = new AtomicBoolean(false);
    private static final Object CORE_LOCK = new Object();
    private static final Object LOG_LOCK = new Object();
    private static final int MAX_RECENT_LOG_LINES = 200;
    private static final String DEFAULT_DELAY_TEST_URL = "https://www.gstatic.com/generate_204";
    private static final SimpleDateFormat LOG_TIME_FORMAT = new SimpleDateFormat("HH:mm:ss", Locale.US);
    private static final ArrayList<String> RECENT_LOGS = new ArrayList<>();
    private static final CoreCallbackHandler CORE_CALLBACK = new CoreCallback();

    private static volatile boolean coreEnvInitialized;
    private static volatile CoreController coreController;
    private static volatile boolean running;
    private static volatile Method startLoopMethod;

    private XrayCoreEngine() {
    }

    /**
     * Checks whether libv2ray Java bindings are present in classpath.
     */
    static boolean isLibraryAvailable() {
        try {
            Class.forName("go.Seq");
            Class.forName("libv2ray.Libv2ray");
            return true;
        } catch (Throwable ignore) {
            return false;
        }
    }

    /**
     * Starts embedded Xray core with runtime config and verifies local SOCKS endpoint availability.
     * Edge cases: empty config, malformed JSON, duplicate start requests, stale state after failures.
     */
    static void start(String configJson, XrayAppProxyManager.StartCallback callback) {
        addLog("start requested");
        if (TextUtils.isEmpty(configJson)) {
            addLog("start rejected: empty config");
            notifyStart(callback, false, "Empty config");
            return;
        }

        String configShapeError = validateConfigShape(configJson);
        if (configShapeError != null) {
            addLog("start rejected: " + configShapeError);
            notifyStart(callback, false, configShapeError);
            return;
        }

        if (!STARTING_OR_STOPPING.compareAndSet(false, true)) {
            addLog("start rejected: busy");
            notifyStart(callback, false, "Busy");
            return;
        }

        EXECUTOR.execute(() -> {
            try {
                ensureCoreInitialized();
                CoreController controller = coreController;
                if (controller == null) {
                    throw new Exception("Core controller is not initialized");
                }

                if (isControllerRunning(controller)) {
                    running = true;
                    addLog("start skipped: already running");
                    notifyStart(callback, true, "Already running");
                    return;
                }

                startCoreLoop(controller, configJson);
                if (!isControllerRunning(controller)) {
                    throw new Exception("Core did not switch to running state");
                }

                verifyLocalSocksReachable(configJson);
                running = true;
                addLog("start success");
                notifyStart(callback, true, "Started");
            } catch (Throwable t) {
                running = false;
                tryStopCoreAfterFailedStart();
                String error = extractErrorMessage(t, "Start failed");
                addLog("start failed: " + error);
                FileLog.e(TAG + ": start failed", t);
                notifyStart(callback, false, error);
            } finally {
                STARTING_OR_STOPPING.set(false);
            }
        });
    }

    /**
     * Stops embedded Xray core if running.
     */
    static void stop(XrayAppProxyManager.StopCallback callback) {
        addLog("stop requested");
        if (!STARTING_OR_STOPPING.compareAndSet(false, true)) {
            addLog("stop rejected: busy");
            notifyStop(callback, false, "Busy");
            return;
        }

        EXECUTOR.execute(() -> {
            try {
                CoreController controller = coreController;
                if (controller == null || !isControllerRunning(controller)) {
                    running = false;
                    addLog("stop skipped: already stopped");
                    notifyStop(callback, true, "Already stopped");
                    return;
                }

                controller.stopLoop();
                running = false;
                addLog("stop success");
                notifyStop(callback, true, "Stopped");
            } catch (Throwable t) {
                String error = extractErrorMessage(t, "Stop failed");
                addLog("stop failed: " + error);
                FileLog.e(TAG + ": stop failed", t);
                notifyStop(callback, false, error);
            } finally {
                STARTING_OR_STOPPING.set(false);
            }
        });
    }

    /**
     * Returns effective running state based on controller state and callback signals.
     */
    static boolean isRunning() {
        CoreController controller = coreController;
        if (controller != null) {
            try {
                running = controller.getIsRunning();
            } catch (Throwable t) {
                FileLog.e(TAG + ": failed to query running state", t);
            }
        }
        return running;
    }

    /**
     * Measures delay for provided outbound config using Libv2ray stateless API.
     */
    static void measureDelay(String configJson, String testUrl, XrayAppProxyManager.DelayCallback callback) {
        addLog("delay check requested");
        if (TextUtils.isEmpty(configJson)) {
            addLog("delay check rejected: empty config");
            notifyDelay(callback, false, -1, "Config is empty");
            return;
        }

        final String safeTestUrl;
        {
            String trimmed = TextUtils.isEmpty(testUrl) ? DEFAULT_DELAY_TEST_URL : testUrl.trim();
            safeTestUrl = TextUtils.isEmpty(trimmed) ? DEFAULT_DELAY_TEST_URL : trimmed;
        }

        EXECUTOR.execute(() -> {
            try {
                ensureCoreEnvInitialized();
                long delay = Libv2ray.measureOutboundDelay(configJson, safeTestUrl);
                if (delay < 0) {
                    addLog("delay check failed");
                    notifyDelay(callback, false, -1, "Delay check failed");
                    return;
                }

                addLog("delay check success: " + delay + "ms");
                notifyDelay(callback, true, delay, "OK");
            } catch (Throwable t) {
                String error = extractErrorMessage(t, "Delay check failed");
                addLog("delay check error: " + error);
                FileLog.e(TAG + ": delay check failed", t);
                notifyDelay(callback, false, -1, error);
            }
        });
    }

    static ArrayList<String> getRecentLogs() {
        synchronized (LOG_LOCK) {
            return new ArrayList<>(RECENT_LOGS);
        }
    }

    static void clearRecentLogs() {
        synchronized (LOG_LOCK) {
            RECENT_LOGS.clear();
        }
        addLog("logs cleared");
    }

    private static void ensureCoreInitialized() throws Exception {
        if (coreController != null) {
            return;
        }

        synchronized (CORE_LOCK) {
            if (coreController != null) {
                return;
            }
            ensureCoreEnvInitialized();
            coreController = Libv2ray.newCoreController(CORE_CALLBACK);
            addLog("core controller created");
        }
    }

    private static void ensureCoreEnvInitialized() throws Exception {
        if (coreEnvInitialized) {
            return;
        }

        synchronized (CORE_LOCK) {
            if (coreEnvInitialized) {
                return;
            }

            Context context = ApplicationLoader.applicationContext;
            if (context == null) {
                throw new Exception("Application context is not initialized");
            }

            Context appContext = context.getApplicationContext();
            Seq.setContext(appContext);
            String envPath = appContext.getFilesDir().getAbsolutePath();
            Libv2ray.initCoreEnv(envPath, "");
            coreEnvInitialized = true;

            addLog("core env initialized: " + envPath);
            addLog("lib version: " + safeCoreVersion());
        }
    }

    private static String safeCoreVersion() {
        try {
            return Libv2ray.checkVersionX();
        } catch (Throwable t) {
            return "unknown";
        }
    }

    private static boolean isControllerRunning(CoreController controller) {
        if (controller == null) {
            return false;
        }
        try {
            return controller.getIsRunning();
        } catch (Throwable t) {
            return running;
        }
    }

    private static void startCoreLoop(CoreController controller, String configJson) throws Exception {
        Method method = resolveStartLoopMethod(controller);
        try {
            if (method.getParameterTypes().length == 2) {
                method.invoke(controller, configJson, 0);
            } else {
                method.invoke(controller, configJson);
            }
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            throw new Exception(extractErrorMessage(cause, "startLoop failed"), cause);
        }
    }

    private static Method resolveStartLoopMethod(CoreController controller) throws Exception {
        Method cached = startLoopMethod;
        if (cached != null) {
            return cached;
        }

        synchronized (CORE_LOCK) {
            if (startLoopMethod != null) {
                return startLoopMethod;
            }

            Method method;
            try {
                method = controller.getClass().getMethod("startLoop", String.class, int.class);
            } catch (NoSuchMethodException ignored) {
                method = controller.getClass().getMethod("startLoop", String.class);
            }
            method.setAccessible(true);
            startLoopMethod = method;
            addLog("startLoop signature resolved: " + method.toGenericString());
            return method;
        }
    }

    private static void tryStopCoreAfterFailedStart() {
        CoreController controller = coreController;
        if (controller == null) {
            return;
        }
        try {
            controller.stopLoop();
        } catch (Throwable ignore) {
        }
    }

    private static void verifyLocalSocksReachable(String configJson) throws Exception {
        LocalSocksEndpoint endpoint = extractLocalSocksEndpoint(configJson);
        if (endpoint == null || endpoint.port <= 0) {
            return;
        }

        long deadline = System.currentTimeMillis() + 4500L;
        Throwable lastError = null;
        while (System.currentTimeMillis() < deadline) {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", endpoint.port), 450);
                socket.setSoTimeout(800);

                if (TextUtils.isEmpty(endpoint.username) || TextUtils.isEmpty(endpoint.password)) {
                    return;
                }

                if (performSocks5Auth(socket, endpoint.username, endpoint.password)) {
                    return;
                }

                lastError = new Exception("SOCKS auth rejected by local inbound");
            } catch (Throwable t) {
                lastError = t;
            }

            try {
                Thread.sleep(120L);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new Exception("Interrupted while waiting local SOCKS startup", ie);
            }
        }

        String reason = extractErrorMessage(lastError, "unknown");
        throw new Exception("Local SOCKS inbound is not reachable on 127.0.0.1:" + endpoint.port + " (" + reason + ")");
    }

    private static LocalSocksEndpoint extractLocalSocksEndpoint(String configJson) {
        try {
            JSONObject root = new JSONObject(configJson);
            JSONArray inbounds = root.optJSONArray("inbounds");
            if (inbounds == null || inbounds.length() == 0) {
                return null;
            }

            JSONObject preferred = null;
            for (int i = 0; i < inbounds.length(); i++) {
                JSONObject inbound = inbounds.optJSONObject(i);
                if (inbound == null || !"socks".equalsIgnoreCase(inbound.optString("protocol", ""))) {
                    continue;
                }
                String listen = inbound.optString("listen", "");
                if (TextUtils.isEmpty(listen) || "127.0.0.1".equals(listen) || "localhost".equalsIgnoreCase(listen)) {
                    preferred = inbound;
                    break;
                }
                if (preferred == null) {
                    preferred = inbound;
                }
            }

            if (preferred == null) {
                return null;
            }

            int port = parseIntFlexible(preferred.opt("port"), -1);
            if (port <= 0) {
                return null;
            }

            JSONObject settings = preferred.optJSONObject("settings");
            if (settings == null) {
                return new LocalSocksEndpoint(port, "", "");
            }

            JSONArray accounts = settings.optJSONArray("accounts");
            if (accounts == null || accounts.length() == 0) {
                return new LocalSocksEndpoint(port, "", "");
            }

            JSONObject account = accounts.optJSONObject(0);
            if (account == null) {
                return new LocalSocksEndpoint(port, "", "");
            }

            String username = account.optString("user", "");
            String password = account.optString("pass", "");
            return new LocalSocksEndpoint(port, username, password);
        } catch (Throwable ignore) {
            return null;
        }
    }

    private static boolean performSocks5Auth(Socket socket, String username, String password) throws Exception {
        byte[] userBytes = username.getBytes("UTF-8");
        byte[] passBytes = password.getBytes("UTF-8");
        if (userBytes.length == 0 || userBytes.length > 255 || passBytes.length == 0 || passBytes.length > 255) {
            return false;
        }

        OutputStream out = socket.getOutputStream();
        InputStream in = socket.getInputStream();

        out.write(new byte[]{0x05, 0x01, 0x02});
        out.flush();

        byte[] methodSelect = readExact(in, 2);
        if (methodSelect[0] != 0x05 || methodSelect[1] != 0x02) {
            return false;
        }

        byte[] auth = new byte[3 + userBytes.length + passBytes.length];
        auth[0] = 0x01;
        auth[1] = (byte) userBytes.length;
        System.arraycopy(userBytes, 0, auth, 2, userBytes.length);
        auth[2 + userBytes.length] = (byte) passBytes.length;
        System.arraycopy(passBytes, 0, auth, 3 + userBytes.length, passBytes.length);

        out.write(auth);
        out.flush();

        byte[] authResponse = readExact(in, 2);
        return authResponse[0] == 0x01 && authResponse[1] == 0x00;
    }

    private static byte[] readExact(InputStream in, int size) throws Exception {
        byte[] data = new byte[size];
        int offset = 0;
        while (offset < size) {
            int count = in.read(data, offset, size - offset);
            if (count < 0) {
                throw new Exception("Unexpected EOF while reading local SOCKS response");
            }
            offset += count;
        }
        return data;
    }

    private static int parseIntFlexible(Object value, int fallback) {
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        if (value instanceof String) {
            try {
                return Integer.parseInt(((String) value).trim());
            } catch (Throwable ignore) {
                return fallback;
            }
        }
        return fallback;
    }

    private static String extractErrorMessage(Throwable error, String fallback) {
        if (error == null) {
            return fallback;
        }

        Throwable cursor = error;
        while (cursor.getCause() != null && cursor.getCause() != cursor) {
            cursor = cursor.getCause();
        }

        String message = cursor.getMessage();
        if (!TextUtils.isEmpty(message)) {
            return message;
        }

        message = error.getMessage();
        if (!TextUtils.isEmpty(message)) {
            return message;
        }

        return fallback;
    }

    private static void notifyStart(XrayAppProxyManager.StartCallback callback, boolean success, String message) {
        if (callback != null) {
            callback.onComplete(success, message);
        }
    }

    private static void notifyStop(XrayAppProxyManager.StopCallback callback, boolean success, String message) {
        if (callback != null) {
            callback.onComplete(success, message);
        }
    }

    private static void notifyDelay(XrayAppProxyManager.DelayCallback callback, boolean success, long delayMs, String message) {
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

    private static String validateConfigShape(String configJson) {
        try {
            JSONObject root = new JSONObject(configJson);
            JSONArray inbounds = root.optJSONArray("inbounds");
            if (inbounds == null || inbounds.length() == 0) {
                return "Config must contain inbounds";
            }

            JSONArray outbounds = root.optJSONArray("outbounds");
            if (outbounds == null || outbounds.length() == 0) {
                return "Config must contain outbounds";
            }
            return null;
        } catch (Throwable t) {
            return "Invalid JSON config";
        }
    }

    private static void updateRunningStateFromStatus(String status) {
        if (TextUtils.isEmpty(status)) {
            return;
        }

        String lower = status.toLowerCase(Locale.US);
        if (lower.contains("stopped") || lower.contains("shutdown") || lower.contains("failed") || lower.contains("error")) {
            running = false;
            return;
        }
        if (lower.contains("running") || lower.contains("started")) {
            running = true;
        }
    }

    private static final class CoreCallback implements CoreCallbackHandler {
        @Override
        public long startup() {
            running = true;
            addLog("callback: startup");
            return 0L;
        }

        @Override
        public long shutdown() {
            running = false;
            addLog("callback: shutdown");
            return 0L;
        }

        @Override
        public long onEmitStatus(long code, String status) {
            if (!TextUtils.isEmpty(status)) {
                addLog("status[" + code + "]: " + status);
                updateRunningStateFromStatus(status);
            }
            return 0L;
        }
    }

    private static final class LocalSocksEndpoint {
        final int port;
        final String username;
        final String password;

        LocalSocksEndpoint(int port, String username, String password) {
            this.port = port;
            this.username = username;
            this.password = password;
        }
    }
}
