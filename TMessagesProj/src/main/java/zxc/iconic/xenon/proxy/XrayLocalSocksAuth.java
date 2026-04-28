package zxc.iconic.xenon.proxy;

import android.app.Activity;
import android.content.SharedPreferences;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.ApplicationLoader;

import java.security.SecureRandom;

/**
 * Manages local SOCKS auth credentials for app-only Xray proxy and injects them into runtime config.
 */
public final class XrayLocalSocksAuth {

    private static final String PREFS_NAME = "nekoconfig";
    private static final String KEY_USERNAME = "xrayAppProxySocksUser";
    private static final String KEY_PASSWORD = "xrayAppProxySocksPass";
    private static final String TOKEN_CHARS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private XrayLocalSocksAuth() {
    }

    /**
     * Returns persisted credentials or creates random ones when missing.
     */
    public static Credentials getOrCreateCredentials() {
        SharedPreferences preferences = ApplicationLoader.applicationContext
                .getSharedPreferences(PREFS_NAME, Activity.MODE_PRIVATE);
        String username = safe(preferences.getString(KEY_USERNAME, ""));
        String password = safe(preferences.getString(KEY_PASSWORD, ""));
        if (!TextUtils.isEmpty(username) && !TextUtils.isEmpty(password)) {
            return new Credentials(username, password);
        }
        return saveNewCredentials(preferences);
    }

    /**
     * Replaces existing credentials with a new random pair.
     */
    public static Credentials resetCredentials() {
        SharedPreferences preferences = ApplicationLoader.applicationContext
                .getSharedPreferences(PREFS_NAME, Activity.MODE_PRIVATE);
        return saveNewCredentials(preferences);
    }

    /**
     * Injects username/password auth into local SOCKS inbound for the selected port.
     */
    public static String applyCredentials(String rawConfig, int localPort, Credentials credentials) throws Exception {
        if (TextUtils.isEmpty(rawConfig)) {
            throw new Exception("Config is empty");
        }
        if (credentials == null || TextUtils.isEmpty(credentials.username) || TextUtils.isEmpty(credentials.password)) {
            throw new Exception("SOCKS auth credentials are missing");
        }

        JSONObject root = new JSONObject(rawConfig);
        JSONArray inbounds = root.optJSONArray("inbounds");
        if (inbounds == null) {
            inbounds = new JSONArray();
            root.put("inbounds", inbounds);
        }

        JSONObject targetInbound = null;
        for (int i = 0; i < inbounds.length(); i++) {
            JSONObject inbound = inbounds.optJSONObject(i);
            if (inbound == null) {
                continue;
            }
            if ("socks".equalsIgnoreCase(inbound.optString("protocol", "")) && inbound.optInt("port", -1) == localPort) {
                targetInbound = inbound;
                break;
            }
        }

        if (targetInbound == null) {
            targetInbound = new JSONObject();
            targetInbound.put("listen", "127.0.0.1");
            targetInbound.put("port", localPort);
            targetInbound.put("protocol", "socks");
            inbounds.put(targetInbound);
        }

        JSONObject settings = targetInbound.optJSONObject("settings");
        if (settings == null) {
            settings = new JSONObject();
            targetInbound.put("settings", settings);
        }
        settings.put("udp", true);
        settings.put("auth", "password");

        JSONObject account = new JSONObject();
        account.put("user", credentials.username);
        account.put("pass", credentials.password);
        settings.put("accounts", new JSONArray().put(account));

        return root.toString();
    }

    private static Credentials saveNewCredentials(SharedPreferences preferences) {
        String username = "xray_" + randomToken(8);
        String password = randomToken(24);
        preferences.edit()
                .putString(KEY_USERNAME, username)
                .putString(KEY_PASSWORD, password)
                .apply();
        return new Credentials(username, password);
    }

    private static String randomToken(int size) {
        StringBuilder builder = new StringBuilder(size);
        for (int i = 0; i < size; i++) {
            builder.append(TOKEN_CHARS.charAt(RANDOM.nextInt(TOKEN_CHARS.length())));
        }
        return builder.toString();
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    public static final class Credentials {
        public final String username;
        public final String password;

        public Credentials(String username, String password) {
            this.username = username;
            this.password = password;
        }
    }
}
