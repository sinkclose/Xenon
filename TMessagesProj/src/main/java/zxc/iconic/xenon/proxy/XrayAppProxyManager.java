package zxc.iconic.xenon.proxy;

import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;

import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.net.Socket;
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
    private static final String DEFAULT_DELAY_TEST_URL = "https://www.gstatic.com/generate_204";
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
        return XrayCoreEngine.isLibraryAvailable();
    }

    /**
     * Starts embedded Xray core with provided JSON config.
     * Edge cases handled: missing library, duplicate starts, malformed config.
     */
    public static void start(String configJson, StartCallback callback) {
        XrayCoreEngine.start(configJson, callback);
    }

    /**
     * Stops embedded Xray core if running.
     */
    public static void stop(StopCallback callback) {
        XrayCoreEngine.stop(callback);
    }

    public static boolean isRunning() {
        return XrayCoreEngine.isRunning();
    }

    /**
     * Measures outbound delay using provided config and test URL.
     */
    public static void measureDelay(String configJson, String testUrl, DelayCallback callback) {
        XrayCoreEngine.measureDelay(configJson, testUrl, callback);
    }

    /**
     * Returns recent in-memory core lifecycle logs for UI.
     */
    public static ArrayList<String> getRecentLogs() {
        return XrayCoreEngine.getRecentLogs();
    }

    /**
     * Clears in-memory log buffer.
     */
    public static void clearRecentLogs() {
        XrayCoreEngine.clearRecentLogs();
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
        invokeInitCoreEnv(libClass, envPath);
        coreController = invokeNewCoreController(libClass);
    }

    private static Object invokeMeasureOutboundDelay(Class<?> libClass, String configJson, String testUrl) throws Exception {
        Method method;
        try {
            method = libClass.getMethod("measureOutboundDelay", String.class, String.class);
            return method.invoke(null, configJson, testUrl);
        } catch (NoSuchMethodException ignored) {
        }

        try {
            method = libClass.getMethod("measureOutboundDelay", String.class);
            return method.invoke(null, configJson);
        } catch (NoSuchMethodException ignored) {
        }

        for (Method candidate : libClass.getMethods()) {
            if (!"measureOutboundDelay".equals(candidate.getName())) {
                continue;
            }
            Class<?>[] params = candidate.getParameterTypes();
            if (params.length == 0 || params[0] != String.class) {
                continue;
            }
            Object[] args = new Object[params.length];
            args[0] = configJson;
            for (int i = 1; i < params.length; i++) {
                Class<?> type = params[i];
                if (type == String.class) {
                    args[i] = testUrl;
                } else if (type == int.class || type == Integer.class) {
                    args[i] = 0;
                } else if (type == long.class || type == Long.class) {
                    args[i] = 0L;
                } else if (type == boolean.class || type == Boolean.class) {
                    args[i] = false;
                } else {
                    args[i] = null;
                }
            }
            return candidate.invoke(null, args);
        }

        throw new NoSuchMethodException(libClass.getName() + ".measureOutboundDelay compatible signature not found");
    }

    private static void invokeInitCoreEnv(Class<?> libClass, String envPath) throws Exception {
        Method initCoreEnv;
        try {
            initCoreEnv = libClass.getMethod("initCoreEnv", String.class, String.class);
            initCoreEnv.invoke(null, envPath, "");
            return;
        } catch (NoSuchMethodException ignored) {
        }

        try {
            initCoreEnv = libClass.getMethod("initCoreEnv", String.class);
            initCoreEnv.invoke(null, envPath);
            return;
        } catch (NoSuchMethodException ignored) {
        }

        throw new NoSuchMethodException(libClass.getName() + ".initCoreEnv compatible signature not found");
    }

    private static Object invokeNewCoreController(Class<?> libClass) throws Exception {
        try {
            Class<?> callbackInterface = Class.forName("libv2ray.CoreCallbackHandler");
            Object callback = Proxy.newProxyInstance(
                    callbackInterface.getClassLoader(),
                    new Class[]{callbackInterface},
                    new CoreCallbackInvocationHandler()
            );
            Method newCoreController = libClass.getMethod("newCoreController", callbackInterface);
            return newCoreController.invoke(null, callback);
        } catch (NoSuchMethodException ignored) {
        } catch (ClassNotFoundException ignored) {
        }

        Method newCoreController = libClass.getMethod("newCoreController");
        return newCoreController.invoke(null);
    }

    private static void invokeStartLoop(Object controller, String configJson) throws Exception {
        Method startLoop;
        try {
            startLoop = controller.getClass().getMethod("startLoop", String.class);
            Object startResult = startLoop.invoke(controller, configJson);
            ensureNoInvocationError("startLoop", startResult);
            return;
        } catch (NoSuchMethodException ignored) {
        }

        try {
            startLoop = controller.getClass().getMethod("startLoop", String.class, int.class);
            Object startResult = startLoop.invoke(controller, configJson, 0);
            ensureNoInvocationError("startLoop", startResult);
            return;
        } catch (NoSuchMethodException ignored) {
        }

        try {
            startLoop = controller.getClass().getMethod("startLoop", String.class, long.class);
            Object startResult = startLoop.invoke(controller, configJson, 0L);
            ensureNoInvocationError("startLoop", startResult);
            return;
        } catch (NoSuchMethodException ignored) {
        }

        Method[] methods = controller.getClass().getMethods();
        for (Method method : methods) {
            if (!"startLoop".equals(method.getName())) {
                continue;
            }
            Class<?>[] params = method.getParameterTypes();
            if (params.length == 0 || params[0] != String.class) {
                continue;
            }
            Object[] args = new Object[params.length];
            args[0] = configJson;
            for (int i = 1; i < params.length; i++) {
                Class<?> type = params[i];
                if (type == int.class || type == Integer.class) {
                    args[i] = 0;
                } else if (type == long.class || type == Long.class) {
                    args[i] = 0L;
                } else if (type == boolean.class || type == Boolean.class) {
                    args[i] = false;
                } else if (type == String.class) {
                    args[i] = "";
                } else {
                    args[i] = null;
                }
            }
            Object startResult = method.invoke(controller, args);
            ensureNoInvocationError("startLoop", startResult);
            return;
        }

        throw new NoSuchMethodException(controller.getClass().getName() + ".startLoop compatible signature not found");
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

    private static void tryStopCoreAfterFailedStart() {
        if (coreController == null) {
            return;
        }
        try {
            Method stopLoop = coreController.getClass().getMethod("stopLoop");
            stopLoop.invoke(coreController);
        } catch (Throwable ignore) {
        }
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

    private static void ensureNoInvocationError(String methodName, Object result) throws Exception {
        if (result == null) {
            return;
        }
        if (result instanceof Throwable) {
            Throwable throwable = (Throwable) result;
            throw new Exception(throwable.getMessage() == null ? methodName + " failed" : throwable.getMessage(), throwable);
        }
        if (result instanceof Boolean) {
            if (!((Boolean) result)) {
                throw new Exception(methodName + " returned false");
            }
            return;
        }
        if (result instanceof Number) {
            long value = ((Number) result).longValue();
            if (value == 0L) {
                return;
            }
            if (value < 0L) {
                throw new Exception(methodName + " returned " + value);
            }
            return;
        }
        if (result instanceof CharSequence) {
            String message = result.toString().trim();
            if (TextUtils.isEmpty(message)
                    || "null".equalsIgnoreCase(message)
                    || "<nil>".equalsIgnoreCase(message)
                    || "ok".equalsIgnoreCase(message)
                    || "success".equalsIgnoreCase(message)
                    || "started".equalsIgnoreCase(message)
                    || "stopped".equalsIgnoreCase(message)) {
                return;
            }
            throw new Exception(message);
        }
    }

    private static Boolean queryControllerRunningState(Object controller) {
        if (controller == null) {
            return false;
        }

        try {
            Method method = controller.getClass().getMethod("isRunning");
            Object value = method.invoke(controller);
            if (value instanceof Boolean) {
                return (Boolean) value;
            }
        } catch (Throwable ignored) {
        }

        try {
            Method method = controller.getClass().getMethod("getIsRunning");
            Object value = method.invoke(controller);
            if (value instanceof Boolean) {
                return (Boolean) value;
            }
        } catch (Throwable ignored) {
        }

        try {
            Field field = controller.getClass().getField("isRunning");
            Object value = field.get(controller);
            if (value instanceof Boolean) {
                return (Boolean) value;
            }
        } catch (Throwable ignored) {
        }

        try {
            Field field = controller.getClass().getField("IsRunning");
            Object value = field.get(controller);
            if (value instanceof Boolean) {
                return (Boolean) value;
            }
        } catch (Throwable ignored) {
        }

        return null;
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

    private static final class CoreCallbackInvocationHandler implements InvocationHandler {
        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            try {
                String name = method.getName();
                if ("onEmitStatus".equals(name)) {
                    if (args != null && args.length > 1 && args[1] != null) {
                        String status = String.valueOf(args[1]);
                        FileLog.d(TAG + " status: " + status);
                        addLog("status: " + status);
                        updateRunningStateFromStatus(status);
                    }
                } else if ("startup".equals(name)) {
                    running = true;
                    FileLog.d(TAG + " callback: " + name);
                    addLog("callback: " + name);
                } else if ("shutdown".equals(name)) {
                    running = false;
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
