package zxc.iconic.xenon.proxy;

import android.content.SharedPreferences;
import android.text.TextUtils;

import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.SharedConfig;
import org.telegram.tgnet.ConnectionsManager;

/**
 * Synchronizes Telegram networking proxy state with the local app-only Xray SOCKS endpoint.
 */
public final class XrayTelegramProxyBridge {

    public static final String LOCAL_PROXY_HOST = "127.0.0.1";
    public static final int DEFAULT_PROXY_PORT = 1080;

    private static final String KEY_XRAY_PROXY_OWNED = "xray_proxy_owned";
    private static final String KEY_XRAY_PROXY_OWNED_PORT = "xray_proxy_owned_port";
    private static final String KEY_XRAY_PROXY_OWNED_USER = "xray_proxy_owned_user";
    private static final String KEY_XRAY_PROXY_OWNED_PASS = "xray_proxy_owned_pass";

    private XrayTelegramProxyBridge() {
    }

    /**
     * Enables Telegram proxy settings for the local Xray SOCKS endpoint.
     */
    public static void enableLocalProxy(int localPort, XrayLocalSocksAuth.Credentials credentials) {
        String proxyUser = credentials == null ? "" : safe(credentials.username);
        String proxyPass = credentials == null ? "" : safe(credentials.password);
        SharedPreferences preferences = MessagesController.getGlobalMainSettings();
        preferences.edit()
                .putBoolean("proxy_enabled", true)
                .putString("proxy_ip", LOCAL_PROXY_HOST)
                .putInt("proxy_port", localPort)
                .putString("proxy_user", proxyUser)
                .putString("proxy_pass", proxyPass)
                .putString("proxy_secret", "")
                .putBoolean(KEY_XRAY_PROXY_OWNED, true)
                .putInt(KEY_XRAY_PROXY_OWNED_PORT, localPort)
                .putString(KEY_XRAY_PROXY_OWNED_USER, proxyUser)
                .putString(KEY_XRAY_PROXY_OWNED_PASS, proxyPass)
                .apply();

        SharedConfig.currentProxy = new SharedConfig.ProxyInfo(LOCAL_PROXY_HOST, localPort, proxyUser, proxyPass, "");
        ConnectionsManager.setProxySettings(true, LOCAL_PROXY_HOST, localPort, proxyUser, proxyPass, "");
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxySettingsChanged);
    }

    /**
     * Disables Telegram proxy only when it points to a local Xray SOCKS endpoint.
     */
    public static boolean disableLocalProxyIfOwned() {
        SharedPreferences preferences = MessagesController.getGlobalMainSettings();
        if (!isLocalProxyEnabled(preferences) || !isOwnedLocalProxy(preferences)) {
            return false;
        }

        preferences.edit()
                .putBoolean("proxy_enabled", false)
                .putString("proxy_ip", "")
                .putInt("proxy_port", DEFAULT_PROXY_PORT)
                .putString("proxy_user", "")
                .putString("proxy_pass", "")
                .putString("proxy_secret", "")
                .putBoolean(KEY_XRAY_PROXY_OWNED, false)
                .remove(KEY_XRAY_PROXY_OWNED_PORT)
                .remove(KEY_XRAY_PROXY_OWNED_USER)
                .remove(KEY_XRAY_PROXY_OWNED_PASS)
                .apply();

        ConnectionsManager.setProxySettings(false, "", 0, "", "", "");
        SharedConfig.currentProxy = null;
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxySettingsChanged);
        return true;
    }

    /**
     * Returns whether Telegram currently points to a local Xray SOCKS endpoint.
     */
    public static boolean isLocalProxyEnabled() {
        return isLocalProxyEnabled(MessagesController.getGlobalMainSettings());
    }

    /**
     * Returns whether the provided address is a loopback endpoint managed by Xray app proxy.
     */
    public static boolean isLocalProxyAddress(String address) {
        return LOCAL_PROXY_HOST.equals(address) || "localhost".equalsIgnoreCase(address);
    }

    private static boolean isLocalProxyEnabled(SharedPreferences preferences) {
        return preferences.getBoolean("proxy_enabled", false)
                && isLocalProxyAddress(preferences.getString("proxy_ip", ""))
                && preferences.getInt("proxy_port", 0) > 0;
    }

    private static boolean isOwnedLocalProxy(SharedPreferences preferences) {
        int port = preferences.getInt("proxy_port", 0);
        String user = safe(preferences.getString("proxy_user", ""));
        String pass = safe(preferences.getString("proxy_pass", ""));
        if (preferences.getBoolean(KEY_XRAY_PROXY_OWNED, false)) {
            return port == preferences.getInt(KEY_XRAY_PROXY_OWNED_PORT, -1)
                    && TextUtils.equals(user, safe(preferences.getString(KEY_XRAY_PROXY_OWNED_USER, "")))
                    && TextUtils.equals(pass, safe(preferences.getString(KEY_XRAY_PROXY_OWNED_PASS, "")));
        }
        return TextUtils.isEmpty(preferences.getString("proxy_secret", ""))
                && !TextUtils.isEmpty(user)
                && user.startsWith("xray_");
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
