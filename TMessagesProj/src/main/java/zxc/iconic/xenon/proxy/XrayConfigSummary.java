package zxc.iconic.xenon.proxy;

import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Builds compact endpoint summaries from Xray JSON config.
 */
public final class XrayConfigSummary {

    private XrayConfigSummary() {
    }

    /**
     * Returns a short endpoint representation like "vless • host:443".
     * Falls back to {@code fallbackValue} when summary cannot be extracted.
     */
    public static String endpoint(String configJson, String fallbackValue) {
        if (TextUtils.isEmpty(configJson)) {
            return fallbackValue;
        }
        try {
            JSONObject root = new JSONObject(configJson);
            JSONArray outbounds = root.optJSONArray("outbounds");
            if (outbounds == null || outbounds.length() == 0) {
                return fallbackValue;
            }

            JSONObject outbound = outbounds.optJSONObject(0);
            if (outbound == null) {
                return fallbackValue;
            }

            String protocol = outbound.optString("protocol", "proxy");
            JSONObject settings = outbound.optJSONObject("settings");
            if (settings == null) {
                return protocol;
            }

            String host = "";
            int port = 0;
            JSONArray vnext = settings.optJSONArray("vnext");
            if (vnext != null && vnext.length() > 0) {
                JSONObject server = vnext.optJSONObject(0);
                if (server != null) {
                    host = server.optString("address", "");
                    port = server.optInt("port", 0);
                }
            } else {
                JSONArray servers = settings.optJSONArray("servers");
                if (servers != null && servers.length() > 0) {
                    JSONObject server = servers.optJSONObject(0);
                    if (server != null) {
                        host = server.optString("address", "");
                        port = server.optInt("port", 0);
                    }
                }
            }

            if (TextUtils.isEmpty(host) || port <= 0) {
                return protocol;
            }
            return protocol + " • " + host + ":" + port;
        } catch (Throwable ignore) {
            return fallbackValue;
        }
    }
}
