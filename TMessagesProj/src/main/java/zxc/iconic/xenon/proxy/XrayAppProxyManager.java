package zxc.iconic.xenon.proxy;

import android.os.Build;
import android.text.TextUtils;

import org.telegram.messenger.FileLog;

import java.util.ArrayList;
import java.util.Locale;

/**
 * Public facade for the canonical app-only Xray core engine.
 */
public final class XrayAppProxyManager {

    private static final String TAG = "XrayAppProxyManager";
    private static final String UNSUPPORTED_ABI_MESSAGE = "AndroidLibXrayLite is unavailable on this CPU ABI";

    private static volatile boolean unsupportedAbiLogged;

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

    /**
     * Listener for asynchronous state transitions (callback shutdowns, status emissions,
     * explicit start/stop completions). Mirrors v2rayNG's {@code MSG_STATE_*} broadcasts.
     */
    public interface StateListener {
        /**
         * @param running whether the core is currently running
         * @param reason  short tag identifying the source of the transition
         */
        void onStateChanged(boolean running, String reason);
    }

    /**
     * Returns whether bundled Xray Java/native bindings can be used on this CPU ABI.
     */
    public static boolean isLibraryAvailable() {
        if (!isCpuAbiSupported()) {
            logUnsupportedAbiOnce();
            return false;
        }
        return XrayCoreEngine.isLibraryAvailable();
    }

    /**
     * Starts embedded Xray core with provided runtime JSON config and an optional tun fd.
     * When {@code tunFd} is > 0, the fd is forwarded to {@code CoreController.startLoop(config, fd)}
     * as a separate argument (Xray core reads the fd from the call site, not from the JSON),
     * matching v2rayNG's {@code doStartCoreLoop}.
     */
    public static void start(String configJson, int tunFd, StartCallback callback) {
        if (!isLibraryAvailable()) {
            notifyStart(callback, false, UNSUPPORTED_ABI_MESSAGE);
            return;
        }
        XrayCoreEngine.start(configJson, tunFd, callback);
    }

    /**
     * Starts embedded Xray core with provided runtime JSON config (no tun fd).
     */
    public static void start(String configJson, StartCallback callback) {
        start(configJson, 0, callback);
    }

    /**
     * Stops embedded Xray core if it is running.
     */
    public static void stop(StopCallback callback) {
        if (!isCpuAbiSupported()) {
            notifyStop(callback, true, "Already stopped");
            return;
        }
        XrayCoreEngine.stop(callback);
    }

    /**
     * Stops then starts the core with the supplied runtime config. Mirrors v2rayNG's
     * {@code MSG_STATE_RESTART} which performs stop + small delay + start. The callback fires
     * once the second start completes (success message reflects the start outcome).
     */
    public static void restart(String configJson, int tunFd, StartCallback callback) {
        if (!isLibraryAvailable()) {
            notifyStart(callback, false, UNSUPPORTED_ABI_MESSAGE);
            return;
        }
        XrayCoreEngine.restart(configJson, tunFd, callback);
    }

    public static void restart(String configJson, StartCallback callback) {
        restart(configJson, 0, callback);
    }

    /**
     * Queries traffic bytes since the previous query for the given outbound tag. Returns 0 when the core is
     * not running or libv2ray is unavailable. Mirrors v2rayNG's {@code V2RayServiceManager.queryStats}.
     *
     * @param tag  outbound tag, typically {@code "proxy"} or {@code "direct"}
     * @param link stat name, typically {@code "uplink"} or {@code "downlink"}
     */
    public static long queryStats(String tag, String link) {
        if (!isCpuAbiSupported()) {
            return 0L;
        }
        return XrayCoreEngine.queryStats(tag, link);
    }

    /**
     * Registers a state listener for async lifecycle transitions. Listeners are dispatched on the
     * UI thread. Idempotent — registering the same listener twice has no effect.
     */
    public static void addStateListener(StateListener listener) {
        if (listener == null || !isCpuAbiSupported()) {
            return;
        }
        XrayCoreEngine.addStateListener(listener);
    }

    /**
     * Removes a previously registered state listener.
     */
    public static void removeStateListener(StateListener listener) {
        if (listener == null || !isCpuAbiSupported()) {
            return;
        }
        XrayCoreEngine.removeStateListener(listener);
    }

    /**
     * Returns the effective Xray running state.
     */
    public static boolean isRunning() {
        if (!isCpuAbiSupported()) {
            return false;
        }
        return XrayCoreEngine.isRunning();
    }

    /**
     * Measures outbound delay with the provided runtime config and test URL.
     */
    public static void measureDelay(String configJson, String testUrl, DelayCallback callback) {
        if (!isLibraryAvailable()) {
            notifyDelay(callback, false, -1, UNSUPPORTED_ABI_MESSAGE);
            return;
        }
        XrayCoreEngine.measureDelay(configJson, testUrl, callback);
    }

    /**
     * Returns recent in-memory Xray lifecycle logs.
     */
    public static ArrayList<String> getRecentLogs() {
        if (!isCpuAbiSupported()) {
            return new ArrayList<>();
        }
        return XrayCoreEngine.getRecentLogs();
    }

    /**
     * Clears recent in-memory Xray lifecycle logs.
     */
    public static void clearRecentLogs() {
        if (!isCpuAbiSupported()) {
            return;
        }
        XrayCoreEngine.clearRecentLogs();
    }

    private static void logUnsupportedAbiOnce() {
        if (unsupportedAbiLogged) {
            return;
        }
        unsupportedAbiLogged = true;
        FileLog.d(TAG + " xray disabled on unsupported ABI: " + getPrimaryAbi());
    }

    private static boolean isCpuAbiSupported() {
        String primaryAbi = getPrimaryAbi();
        if (TextUtils.isEmpty(primaryAbi) || "unknown".equals(primaryAbi)) {
            return false;
        }
        String abi = primaryAbi.toLowerCase(Locale.US);
        if (abi.startsWith("riscv") || abi.startsWith("mips")) {
            return false;
        }
        return abi.startsWith("arm64") || abi.startsWith("armeabi") || abi.startsWith("x86");
    }

    private static String getPrimaryAbi() {
        String[] abis = Build.SUPPORTED_ABIS;
        if (abis == null || abis.length == 0 || TextUtils.isEmpty(abis[0])) {
            return "unknown";
        }
        return abis[0];
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
}
