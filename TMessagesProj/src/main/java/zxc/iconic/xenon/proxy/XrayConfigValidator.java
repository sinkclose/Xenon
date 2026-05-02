package zxc.iconic.xenon.proxy;

import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Validates user-provided Xray JSON config before passing it to native core.
 */
public final class XrayConfigValidator {

    private XrayConfigValidator() {
    }

    public static ValidationResult validate(String rawConfig, int localPort) {
        if (localPort < 1024 || localPort > 65535) {
            return ValidationResult.invalid("Local port must be in range 1024..65535");
        }
        if (TextUtils.isEmpty(rawConfig)) {
            return ValidationResult.invalid("Config is empty");
        }

        JSONObject root;
        try {
            root = new JSONObject(rawConfig);
        } catch (Throwable t) {
            return ValidationResult.invalid("Invalid JSON config");
        }

        JSONArray inbounds = root.optJSONArray("inbounds");
        if (inbounds == null || inbounds.length() == 0) {
            return ValidationResult.invalid("Config must contain inbounds");
        }

        boolean hasLocalSocksInbound = false;
        for (int i = 0; i < inbounds.length(); i++) {
            JSONObject inbound = inbounds.optJSONObject(i);
            if (inbound == null) {
                continue;
            }
            String protocol = inbound.optString("protocol", "");
            if (TextUtils.isEmpty(protocol)) {
                return ValidationResult.invalid("Inbound protocol is empty");
            }
            int port = parseIntFlexible(inbound.opt("port"), -1);
            if ("socks".equalsIgnoreCase(protocol) && port == localPort) {
                JSONObject settings = inbound.optJSONObject("settings");
                if (settings != null && "password".equalsIgnoreCase(settings.optString("auth", ""))) {
                    JSONArray accounts = settings.optJSONArray("accounts");
                    if (accounts == null || accounts.length() == 0) {
                        return ValidationResult.invalid("SOCKS password auth requires non-empty accounts");
                    }
                    for (int j = 0; j < accounts.length(); j++) {
                        JSONObject account = accounts.optJSONObject(j);
                        if (account == null) {
                            return ValidationResult.invalid("SOCKS account item is invalid");
                        }
                        if (TextUtils.isEmpty(account.optString("user", "")) || TextUtils.isEmpty(account.optString("pass", ""))) {
                            return ValidationResult.invalid("SOCKS account user/pass must be non-empty");
                        }
                    }
                }
                hasLocalSocksInbound = true;
                break;
            }
        }

        if (!hasLocalSocksInbound) {
            return ValidationResult.invalid("Config must contain socks inbound on selected local port");
        }

        JSONArray outbounds = root.optJSONArray("outbounds");
        if (outbounds == null || outbounds.length() == 0) {
            return ValidationResult.invalid("Config must contain outbounds");
        }

        for (int i = 0; i < outbounds.length(); i++) {
            JSONObject outbound = outbounds.optJSONObject(i);
            if (outbound == null) {
                return ValidationResult.invalid("Outbound item is invalid");
            }
            if (TextUtils.isEmpty(outbound.optString("protocol", ""))) {
                return ValidationResult.invalid("Outbound protocol is empty");
            }
        }

        return ValidationResult.valid();
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

    public static final class ValidationResult {
        public final boolean valid;
        public final String message;

        private ValidationResult(boolean valid, String message) {
            this.valid = valid;
            this.message = message;
        }

        public static ValidationResult valid() {
            return new ValidationResult(true, "OK");
        }

        public static ValidationResult invalid(String message) {
            return new ValidationResult(false, message);
        }
    }
}
