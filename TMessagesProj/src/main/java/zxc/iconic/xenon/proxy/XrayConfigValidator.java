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
            int port = inbound.optInt("port", -1);
            if ("socks".equals(protocol) && port == localPort) {
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

        return ValidationResult.valid();
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
