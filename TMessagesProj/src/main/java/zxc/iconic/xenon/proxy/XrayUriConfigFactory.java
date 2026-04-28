package zxc.iconic.xenon.proxy;

import android.text.TextUtils;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URI;
import java.net.URLDecoder;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class XrayUriConfigFactory {

    private static final Pattern LINK_PATTERN = Pattern.compile("(?i)(vless|vmess|trojan|ss|socks)://[^\\s]+", Pattern.CASE_INSENSITIVE);

    private XrayUriConfigFactory() {
    }

    /**
     * Parses clipboard content and extracts first supported proxy URI.
     * Edge cases handled: plain text around link, base64 subscription body, malformed entries.
     */
    public static ParseResult fromClipboardText(String clipboardText, int localPort) {
        if (TextUtils.isEmpty(clipboardText)) {
            return ParseResult.invalid("Clipboard is empty");
        }
        String trimmed = clipboardText.trim();

        String link = extractFirstLink(trimmed);
        if (TextUtils.isEmpty(link)) {
            String decoded = tryDecodeBase64(trimmed);
            if (!TextUtils.isEmpty(decoded)) {
                link = extractFirstLink(decoded);
            }
        }

        if (TextUtils.isEmpty(link)) {
            return ParseResult.invalid("No supported proxy URI found in clipboard");
        }

        return fromLink(link, localPort);
    }

    /**
     * Converts a single share URI into full Xray JSON config with local socks inbound.
     * Supports vless, vmess, trojan, shadowsocks, socks and http links.
     */
    public static ParseResult fromLink(String rawLink, int localPort) {
        if (localPort < 1024 || localPort > 65535) {
            return ParseResult.invalid("Local port must be in range 1024..65535");
        }
        if (TextUtils.isEmpty(rawLink)) {
            return ParseResult.invalid("URI is empty");
        }

        String link = cleanupLink(rawLink);
        try {
            String lower = link.toLowerCase(Locale.US);
            if (lower.startsWith("vless://")) {
                return parseVless(link, localPort);
            }
            if (lower.startsWith("vmess://")) {
                return parseVmess(link, localPort);
            }
            if (lower.startsWith("trojan://")) {
                return parseTrojan(link, localPort);
            }
            if (lower.startsWith("ss://")) {
                return parseShadowsocks(link, localPort);
            }
            if (lower.startsWith("socks://")) {
                return parseSocksOrHttp(link, localPort, "socks");
            }
            if (lower.startsWith("http://")) {
                return parseSocksOrHttp(link, localPort, "http");
            }
            return ParseResult.invalid("Unsupported URI scheme");
        } catch (Throwable t) {
            return ParseResult.invalid(t.getMessage() == null ? "Failed to parse URI" : t.getMessage());
        }
    }

    private static ParseResult parseVless(String link, int localPort) throws Exception {
        URI uri = URI.create(link);
        String host = requireHost(uri);
        int port = resolvePort(uri, 443);
        String id = requireText(uri.getUserInfo(), "VLESS UUID is missing");
        Map<String, String> q = parseQuery(uri.getRawQuery());

        JSONObject user = new JSONObject();
        user.put("id", id);
        user.put("encryption", defaultIfEmpty(q.get("encryption"), "none"));
        putIfNotEmpty(user, "flow", q.get("flow"));

        JSONObject server = new JSONObject();
        server.put("address", host);
        server.put("port", port);
        server.put("users", new JSONArray().put(user));

        JSONObject settings = new JSONObject();
        settings.put("vnext", new JSONArray().put(server));

        JSONObject outbound = new JSONObject();
        outbound.put("tag", "proxy");
        outbound.put("protocol", "vless");
        outbound.put("settings", settings);
        outbound.put("streamSettings", buildStreamSettings(q, host, "tcp"));

        return ParseResult.valid("vless", host, port, getNodeName(uri, q), toConfig(localPort, outbound));
    }

    private static ParseResult parseVmess(String link, int localPort) throws Exception {
        String payload = link.substring("vmess://".length()).trim();
        String decoded = tryDecodeBase64(payload);
        if (TextUtils.isEmpty(decoded)) {
            return ParseResult.invalid("Invalid VMess base64 payload");
        }
        JSONObject node = new JSONObject(decoded);

        String host = requireText(node.optString("add", ""), "VMess host is missing");
        int port = parsePort(node.optString("port", ""), 443);
        String id = requireText(node.optString("id", ""), "VMess UUID is missing");

        JSONObject user = new JSONObject();
        user.put("id", id);
        user.put("security", defaultIfEmpty(node.optString("scy", ""), "auto"));
        int alterId = parsePort(node.optString("aid", "0"), 0);
        user.put("alterId", Math.max(0, alterId));

        JSONObject server = new JSONObject();
        server.put("address", host);
        server.put("port", port);
        server.put("users", new JSONArray().put(user));

        JSONObject settings = new JSONObject();
        settings.put("vnext", new JSONArray().put(server));

        Map<String, String> q = new HashMap<>();
        q.put("type", node.optString("net", node.optString("type", "tcp")));
        q.put("security", node.optString("tls", node.optString("security", "none")));
        q.put("sni", node.optString("sni", ""));
        q.put("fp", node.optString("fp", ""));
        q.put("alpn", node.optString("alpn", ""));
        q.put("host", node.optString("host", ""));
        q.put("path", node.optString("path", ""));
        q.put("serviceName", node.optString("serviceName", ""));

        JSONObject outbound = new JSONObject();
        outbound.put("tag", "proxy");
        outbound.put("protocol", "vmess");
        outbound.put("settings", settings);
        outbound.put("streamSettings", buildStreamSettings(q, host, "tcp"));

        String name = defaultIfEmpty(node.optString("ps", ""), "vmess");
        return ParseResult.valid("vmess", host, port, name, toConfig(localPort, outbound));
    }

    private static ParseResult parseTrojan(String link, int localPort) throws Exception {
        URI uri = URI.create(link);
        String host = requireHost(uri);
        int port = resolvePort(uri, 443);
        String password = requireText(uri.getUserInfo(), "Trojan password is missing");
        Map<String, String> q = parseQuery(uri.getRawQuery());
        if (TextUtils.isEmpty(q.get("security"))) {
            q.put("security", "tls");
        }

        JSONObject server = new JSONObject();
        server.put("address", host);
        server.put("port", port);
        server.put("password", password);

        JSONObject settings = new JSONObject();
        settings.put("servers", new JSONArray().put(server));

        JSONObject outbound = new JSONObject();
        outbound.put("tag", "proxy");
        outbound.put("protocol", "trojan");
        outbound.put("settings", settings);
        outbound.put("streamSettings", buildStreamSettings(q, host, "tcp"));

        return ParseResult.valid("trojan", host, port, getNodeName(uri, q), toConfig(localPort, outbound));
    }

    private static ParseResult parseShadowsocks(String link, int localPort) throws Exception {
        String body = link.substring("ss://".length());
        String noTag = splitByFirst(body, '#')[0];
        String beforeQuery = splitByFirst(noTag, '?')[0];

        String credsAndEndpoint;
        if (beforeQuery.contains("@")) {
            String[] parts = splitByFirst(beforeQuery, '@');
            String creds = parts[0];
            String endpoint = parts.length > 1 ? parts[1] : "";
            if (creds.contains(":")) {
                credsAndEndpoint = creds + "@" + endpoint;
            } else {
                String decodedCreds = tryDecodeBase64(creds);
                credsAndEndpoint = (decodedCreds == null ? "" : decodedCreds) + "@" + endpoint;
            }
        } else {
            String decoded = tryDecodeBase64(beforeQuery);
            credsAndEndpoint = defaultIfEmpty(decoded, "");
        }

        String[] parts = splitByFirst(credsAndEndpoint, '@');
        if (parts.length != 2) {
            return ParseResult.invalid("Invalid Shadowsocks URI");
        }

        String[] methodPass = splitByFirst(parts[0], ':');
        if (methodPass.length != 2) {
            return ParseResult.invalid("Shadowsocks method/password are missing");
        }

        String[] hostPort = splitHostPort(parts[1]);
        String host = requireText(hostPort[0], "Shadowsocks host is missing");
        int port = parsePort(hostPort[1], 8388);

        JSONObject server = new JSONObject();
        server.put("address", host);
        server.put("port", port);
        server.put("method", methodPass[0]);
        server.put("password", methodPass[1]);

        JSONObject settings = new JSONObject();
        settings.put("servers", new JSONArray().put(server));

        JSONObject outbound = new JSONObject();
        outbound.put("tag", "proxy");
        outbound.put("protocol", "shadowsocks");
        outbound.put("settings", settings);

        return ParseResult.valid("shadowsocks", host, port, "ss", toConfig(localPort, outbound));
    }

    private static ParseResult parseSocksOrHttp(String link, int localPort, String protocol) throws Exception {
        URI uri = URI.create(link);
        String host = requireHost(uri);
        int port = resolvePort(uri, "http".equals(protocol) ? 8080 : 1080);

        JSONObject server = new JSONObject();
        server.put("address", host);
        server.put("port", port);

        String userInfo = uri.getUserInfo();
        if (!TextUtils.isEmpty(userInfo) && userInfo.contains(":")) {
            String[] creds = splitByFirst(userInfo, ':');
            JSONObject user = new JSONObject();
            user.put("user", decode(creds[0]));
            user.put("pass", decode(creds.length > 1 ? creds[1] : ""));
            server.put("users", new JSONArray().put(user));
        }

        JSONObject settings = new JSONObject();
        settings.put("servers", new JSONArray().put(server));

        JSONObject outbound = new JSONObject();
        outbound.put("tag", "proxy");
        outbound.put("protocol", protocol);
        outbound.put("settings", settings);

        return ParseResult.valid(protocol, host, port, protocol, toConfig(localPort, outbound));
    }

    private static JSONObject toConfig(int localPort, JSONObject mainOutbound) throws Exception {
        JSONObject inbound = new JSONObject();
        inbound.put("listen", "127.0.0.1");
        inbound.put("port", localPort);
        inbound.put("protocol", "socks");
        JSONObject inboundSettings = new JSONObject();
        inboundSettings.put("udp", true);
        inbound.put("settings", inboundSettings);

        JSONObject direct = new JSONObject();
        direct.put("tag", "direct");
        direct.put("protocol", "freedom");

        JSONObject block = new JSONObject();
        block.put("tag", "block");
        block.put("protocol", "blackhole");

        JSONObject root = new JSONObject();
        root.put("inbounds", new JSONArray().put(inbound));
        root.put("outbounds", new JSONArray().put(mainOutbound).put(direct).put(block));
        return root;
    }

    private static JSONObject buildStreamSettings(Map<String, String> q, String hostFallback, String defaultNetwork) throws Exception {
        JSONObject stream = new JSONObject();
        String network = defaultIfEmpty(q.get("type"), defaultNetwork).toLowerCase(Locale.US);
        stream.put("network", network);

        String security = normalizeTransportSecurity(defaultIfEmpty(q.get("security"), "none"));
        if (!"none".equals(security)) {
            stream.put("security", security);
            if ("tls".equals(security)) {
                JSONObject tls = new JSONObject();
                putIfNotEmpty(tls, "serverName", defaultIfEmpty(q.get("sni"), hostFallback));
                putIfNotEmpty(tls, "fingerprint", q.get("fp"));
                if (isTruthy(q.get("allowinsecure"))
                        || isTruthy(q.get("allow_insecure"))
                        || isTruthy(q.get("insecure"))
                        || isTruthy(q.get("skip-cert-verify"))
                        || isTruthy(q.get("skipcertverify"))) {
                    tls.put("allowInsecure", true);
                }
                if (!TextUtils.isEmpty(q.get("alpn"))) {
                    JSONArray alpn = new JSONArray();
                    for (String item : q.get("alpn").split(",")) {
                        if (!TextUtils.isEmpty(item)) {
                            alpn.put(item.trim());
                        }
                    }
                    if (alpn.length() > 0) {
                        tls.put("alpn", alpn);
                    }
                }
                stream.put("tlsSettings", tls);
            } else if ("reality".equals(security)) {
                JSONObject reality = new JSONObject();
                putIfNotEmpty(reality, "serverName", defaultIfEmpty(q.get("sni"), hostFallback));
                putIfNotEmpty(reality, "fingerprint", q.get("fp"));
                putIfNotEmpty(reality, "publicKey", q.get("pbk"));
                putIfNotEmpty(reality, "shortId", q.get("sid"));
                putIfNotEmpty(reality, "spiderX", q.get("spx"));
                stream.put("realitySettings", reality);
            }
        }

        if ("ws".equals(network)) {
            JSONObject ws = new JSONObject();
            putIfNotEmpty(ws, "path", q.get("path"));
            if (!TextUtils.isEmpty(q.get("host"))) {
                JSONObject headers = new JSONObject();
                headers.put("Host", q.get("host"));
                ws.put("headers", headers);
            }
            stream.put("wsSettings", ws);
        } else if ("grpc".equals(network)) {
            JSONObject grpc = new JSONObject();
            putIfNotEmpty(grpc, "serviceName", q.get("serviceName"));
            stream.put("grpcSettings", grpc);
        } else if ("httpupgrade".equals(network)) {
            JSONObject hu = new JSONObject();
            putIfNotEmpty(hu, "path", q.get("path"));
            putIfNotEmpty(hu, "host", q.get("host"));
            stream.put("httpupgradeSettings", hu);
        } else if ("splithttp".equals(network)) {
            JSONObject sh = new JSONObject();
            putIfNotEmpty(sh, "path", q.get("path"));
            putIfNotEmpty(sh, "host", q.get("host"));
            stream.put("splithttpSettings", sh);
        }

        return stream;
    }

    private static String extractFirstLink(String text) {
        Matcher matcher = LINK_PATTERN.matcher(text);
        if (!matcher.find()) {
            return null;
        }
        return cleanupLink(matcher.group());
    }

    private static String cleanupLink(String raw) {
        String link = raw == null ? "" : raw.trim();
        while (!TextUtils.isEmpty(link) && ").,;\"'".indexOf(link.charAt(link.length() - 1)) >= 0) {
            link = link.substring(0, link.length() - 1);
        }
        return link;
    }

    private static Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> map = new HashMap<>();
        if (TextUtils.isEmpty(rawQuery)) {
            return map;
        }
        String[] parts = rawQuery.split("&");
        for (String part : parts) {
            if (TextUtils.isEmpty(part)) {
                continue;
            }
            String[] kv = splitByFirst(part, '=');
            String key = decode(kv[0]).toLowerCase(Locale.US);
            String value = kv.length > 1 ? decode(kv[1]) : "";
            map.put(key, value);
        }
        return map;
    }

    private static String getNodeName(URI uri, Map<String, String> q) {
        String fragment = decode(uri.getRawFragment());
        if (!TextUtils.isEmpty(fragment)) {
            return fragment;
        }
        String ps = q.get("ps");
        if (!TextUtils.isEmpty(ps)) {
            return ps;
        }
        return "node";
    }

    private static String requireHost(URI uri) throws Exception {
        String host = uri.getHost();
        if (TextUtils.isEmpty(host)) {
            String authority = uri.getRawAuthority();
            if (!TextUtils.isEmpty(authority)) {
                String withoutUser = authority.contains("@") ? authority.substring(authority.lastIndexOf('@') + 1) : authority;
                String[] hostPort = splitHostPort(withoutUser);
                host = hostPort[0];
            }
        }
        if (TextUtils.isEmpty(host)) {
            throw new Exception("Host is missing");
        }
        return host;
    }

    private static int resolvePort(URI uri, int fallback) {
        int port = uri.getPort();
        return port > 0 ? port : fallback;
    }

    private static String requireText(String value, String error) throws Exception {
        if (TextUtils.isEmpty(value)) {
            throw new Exception(error);
        }
        return decode(value);
    }

    private static int parsePort(String value, int fallback) {
        try {
            int parsed = Integer.parseInt(value.trim());
            return parsed > 0 && parsed <= 65535 ? parsed : fallback;
        } catch (Throwable ignore) {
            return fallback;
        }
    }

    private static String defaultIfEmpty(String value, String fallback) {
        return TextUtils.isEmpty(value) ? fallback : value;
    }

    private static String normalizeTransportSecurity(String value) {
        if (TextUtils.isEmpty(value)) {
            return "none";
        }
        String normalized = value.trim().toLowerCase(Locale.US);
        if ("1".equals(normalized) || "true".equals(normalized)) {
            return "tls";
        }
        if ("0".equals(normalized) || "false".equals(normalized)) {
            return "none";
        }
        if ("xtls".equals(normalized)) {
            return "tls";
        }
        return normalized;
    }

    private static boolean isTruthy(String value) {
        if (TextUtils.isEmpty(value)) {
            return false;
        }
        String normalized = value.trim().toLowerCase(Locale.US);
        return "1".equals(normalized)
                || "true".equals(normalized)
                || "yes".equals(normalized)
                || "on".equals(normalized);
    }

    private static String[] splitByFirst(String value, char separator) {
        int index = value.indexOf(separator);
        if (index < 0) {
            return new String[]{value};
        }
        return new String[]{value.substring(0, index), value.substring(index + 1)};
    }

    private static String[] splitHostPort(String hostPort) {
        int idx = hostPort.lastIndexOf(':');
        if (idx <= 0 || idx >= hostPort.length() - 1) {
            return new String[]{hostPort, ""};
        }
        return new String[]{hostPort.substring(0, idx), hostPort.substring(idx + 1)};
    }

    private static String decode(String value) {
        if (value == null) {
            return null;
        }
        try {
            return URLDecoder.decode(value, "UTF-8");
        } catch (Throwable ignore) {
            return value;
        }
    }

    private static String tryDecodeBase64(String raw) {
        if (TextUtils.isEmpty(raw)) {
            return null;
        }
        String normalized = raw.trim().replace('\n', ' ').replace('\r', ' ');
        String compact = normalized.replaceAll("\\s+", "");

        String[] candidates = new String[]{compact, padBase64(compact)};
        for (String candidate : candidates) {
            if (TextUtils.isEmpty(candidate)) {
                continue;
            }
            try {
                byte[] bytes = Base64.decode(candidate, Base64.DEFAULT);
                return new String(bytes);
            } catch (Throwable ignore) {
            }
            try {
                byte[] bytes = Base64.decode(candidate, Base64.URL_SAFE | Base64.NO_WRAP);
                return new String(bytes);
            } catch (Throwable ignore) {
            }
        }
        return null;
    }

    private static String padBase64(String value) {
        int mod = value.length() % 4;
        if (mod == 0) {
            return value;
        }
        StringBuilder builder = new StringBuilder(value);
        for (int i = 0; i < 4 - mod; i++) {
            builder.append('=');
        }
        return builder.toString();
    }

    private static void putIfNotEmpty(JSONObject object, String key, String value) throws Exception {
        if (!TextUtils.isEmpty(value)) {
            object.put(key, value);
        }
    }

    public static final class ParseResult {
        public final boolean valid;
        public final String message;
        public final String protocol;
        public final String host;
        public final int port;
        public final String nodeName;
        public final JSONObject config;

        private ParseResult(boolean valid, String message, String protocol, String host, int port, String nodeName, JSONObject config) {
            this.valid = valid;
            this.message = message;
            this.protocol = protocol;
            this.host = host;
            this.port = port;
            this.nodeName = nodeName;
            this.config = config;
        }

        public static ParseResult valid(String protocol, String host, int port, String nodeName, JSONObject config) {
            return new ParseResult(true, "OK", protocol, host, port, nodeName, config);
        }

        public static ParseResult invalid(String message) {
            return new ParseResult(false, message, "", "", 0, "", null);
        }
    }
}
