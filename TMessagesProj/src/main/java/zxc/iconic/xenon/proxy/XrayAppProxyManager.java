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
     * Starts embedded Xray core with provided runtime JSON config.
     */
    public static void start(String configJson, StartCallback callback) {
        if (!isLibraryAvailable()) {
            notifyStart(callback, false, UNSUPPORTED_ABI_MESSAGE);
            return;
        }
        XrayCoreEngine.start(configJson, callback);
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
        if (abi.startsWith("x86") || abi.startsWith("riscv") || abi.startsWith("mips")) {
            return false;
        }
        return abi.startsWith("arm64") || abi.startsWith("armeabi");
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
