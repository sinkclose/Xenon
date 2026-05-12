package zxc.iconic.xenon.proxy;

import android.text.TextUtils;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URI;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class XrayUriConfigFactory {

    /**
     * Single source of truth for supported share-URI protocols.
     * All dispatch in {@link #fromLink(String, int)}, the clipboard {@link #LINK_PATTERN}
     * and the public {@link #getSupportedProtocols()} are derived from this list.
     */
    private static final List<ProtocolInfo> SUPPORTED_PROTOCOLS = Collections.unmodifiableList(Arrays.asList(
            new ProtocolInfo("VLESS", "vless"),
            new ProtocolInfo("VMess", "vmess"),
            new ProtocolInfo("Trojan", "trojan"),
            new ProtocolInfo("Shadowsocks", "ss"),
            new ProtocolInfo("SOCKS", "socks", "socks5"),
            new ProtocolInfo("HTTP", "http"),
            new ProtocolInfo("Hysteria2", "hysteria2", "hy2"),
            new ProtocolInfo("WireGuard", "wireguard", "wg")
    ));

    private static final Pattern LINK_PATTERN = buildLinkPattern();

    private XrayUriConfigFactory() {
    }

    /**
     * Returns a read-only list of protocols that can be imported as share-URI.
     * Intended for UI surfaces (e.g. About dialog) so they don't hardcode entries.
     */
    public static List<ProtocolInfo> getSupportedProtocols() {
        return SUPPORTED_PROTOCOLS;
    }

    private static Pattern buildLinkPattern() {
        StringBuilder alt = new StringBuilder();
        for (ProtocolInfo info : SUPPORTED_PROTOCOLS) {
            for (String scheme : info.uriSchemes) {
                if (alt.length() > 0) {
                    alt.append('|');
                }
                alt.append(Pattern.quote(scheme));
            }
        }
        return Pattern.compile("(?i)(" + alt + ")://[^\\s]+", Pattern.CASE_INSENSITIVE);
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
            if (lower.startsWith("socks://") || lower.startsWith("socks5://")) {
                return parseSocksOrHttp(link, localPort, "socks");
            }
            if (lower.startsWith("http://")) {
                return parseSocksOrHttp(link, localPort, "http");
            }
            if (lower.startsWith("hysteria2://") || lower.startsWith("hy2://")) {
                return parseHysteria2(link, localPort);
            }
            if (lower.startsWith("wireguard://") || lower.startsWith("wg://")) {
                return parseWireGuard(link, localPort);
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
        q.put("headertype", node.optString("type", "none"));
        q.put("security", node.optString("tls", node.optString("security", "none")));
        q.put("sni", node.optString("sni", ""));
        q.put("fp", node.optString("fp", ""));
        q.put("alpn", node.optString("alpn", ""));
        q.put("host", node.optString("host", ""));
        q.put("path", node.optString("path", ""));
        q.put("servicename", node.optString("serviceName", ""));
        q.put("mode", node.optString("mode", ""));
        q.put("authority", node.optString("authority", ""));

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
        String host = requireText(stripIpv6Brackets(hostPort[0]), "Shadowsocks host is missing");
        int port = parsePort(hostPort[1], 8388);

        JSONObject server = new JSONObject();
        server.put("address", host);
        server.put("port", port);
        server.put("method", decode(methodPass[0]));
        server.put("password", decode(methodPass[1]));

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

    /**
     * Parses hysteria2:// / hy2:// share links into an Xray outbound.
     * URI shape: hysteria2://&lt;password&gt;@host:port/?sni=&obfs=salamander&obfs-password=&insecure=&alpn=h3&mport=#remarks
     *
     * Produces JSON compatible with AndroidLibXrayLite (v2rayNG's fork of Xray-core):
     * - outbound.protocol   = "hysteria"   (shared with hysteria v1; version switches via settings)
     * - settings.{address, port, version: 2}
     * - streamSettings.network = "hysteria"
     * - streamSettings.hysteriaSettings = {version: 2, auth: password}
     * - salamander obfs: streamSettings.finalmask.udp[0].{type, settings.password}
     */
    private static ParseResult parseHysteria2(String link, int localPort) throws Exception {
        URI uri = URI.create(link);
        String host = requireHost(uri);
        int port = resolvePort(uri, 443);
        String password = requireText(uri.getUserInfo(), "Hysteria2 password is missing");
        Map<String, String> q = parseQuery(uri.getRawQuery());

        JSONObject settings = new JSONObject();
        settings.put("address", host);
        settings.put("port", port);
        settings.put("version", 2);

        // Stream: always TLS, default alpn h3.
        JSONObject stream = new JSONObject();
        stream.put("network", "hysteria");
        stream.put("security", "tls");

        JSONObject tls = new JSONObject();
        String sni = defaultIfEmpty(q.get("sni"), host);
        putIfNotEmpty(tls, "serverName", sni);
        putIfNotEmpty(tls, "fingerprint", q.get("fp"));
        if (isTruthy(q.get("allowinsecure"))
                || isTruthy(q.get("allow_insecure"))
                || isTruthy(q.get("insecure"))
                || isTruthy(q.get("skip-cert-verify"))
                || isTruthy(q.get("skipcertverify"))) {
            tls.put("allowInsecure", true);
        }
        JSONArray alpnArr = new JSONArray();
        String alpnParam = defaultIfEmpty(q.get("alpn"), "h3");
        for (String item : alpnParam.split(",")) {
            String trimmed = item.trim();
            if (!TextUtils.isEmpty(trimmed)) {
                alpnArr.put(trimmed);
            }
        }
        if (alpnArr.length() == 0) {
            alpnArr.put("h3");
        }
        tls.put("alpn", alpnArr);
        stream.put("tlsSettings", tls);

        JSONObject hysteriaSettings = new JSONObject();
        hysteriaSettings.put("version", 2);
        hysteriaSettings.put("auth", password);
        stream.put("hysteriaSettings", hysteriaSettings);

        String obfsType = q.get("obfs");
        String obfsPassword = q.get("obfs-password");
        if (TextUtils.isEmpty(obfsPassword)) {
            obfsPassword = q.get("obfspassword");
        }
        if (!TextUtils.isEmpty(obfsPassword)) {
            JSONObject mask = new JSONObject();
            mask.put("type", defaultIfEmpty(obfsType, "salamander"));
            JSONObject maskSettings = new JSONObject();
            maskSettings.put("password", obfsPassword);
            mask.put("settings", maskSettings);
            JSONObject finalmask = new JSONObject();
            finalmask.put("udp", new JSONArray().put(mask));
            stream.put("finalmask", finalmask);
        }

        JSONObject outbound = new JSONObject();
        outbound.put("tag", "proxy");
        outbound.put("protocol", "hysteria");
        outbound.put("settings", settings);
        outbound.put("streamSettings", stream);

        return ParseResult.valid("hysteria2", host, port, getNodeName(uri, q), toConfig(localPort, outbound));
    }

    /**
     * Parses wireguard:// / wg:// share links into an Xray WireGuard outbound.
     * URI shape: wireguard://<secretKey>@host:port/?publickey=&address=&mtu=&reserved=&presharedkey=#remarks
     */
    private static ParseResult parseWireGuard(String link, int localPort) throws Exception {
        URI uri = URI.create(link);
        String host = requireHost(uri);
        int port = resolvePort(uri, 51820);
        String secretKey = requireText(uri.getUserInfo(), "WireGuard private key is missing");
        Map<String, String> q = parseQuery(uri.getRawQuery());

        String publicKey = q.get("publickey");
        if (TextUtils.isEmpty(publicKey)) {
            return ParseResult.invalid("WireGuard peer public key is missing");
        }

        JSONObject settings = new JSONObject();
        settings.put("secretKey", secretKey);

        JSONArray addresses = new JSONArray();
        String addressParam = defaultIfEmpty(q.get("address"), "172.16.0.2/32");
        for (String a : addressParam.split(",")) {
            String trimmed = a.trim();
            if (!TextUtils.isEmpty(trimmed)) {
                addresses.put(trimmed);
            }
        }
        if (addresses.length() == 0) {
            addresses.put("172.16.0.2/32");
        }
        settings.put("address", addresses);

        JSONObject peer = new JSONObject();
        peer.put("publicKey", publicKey);
        String preSharedKey = q.get("presharedkey");
        if (!TextUtils.isEmpty(preSharedKey)) {
            peer.put("preSharedKey", preSharedKey);
        }
        peer.put("endpoint", formatEndpoint(host, port));
        settings.put("peers", new JSONArray().put(peer));

        int mtu = parsePort(defaultIfEmpty(q.get("mtu"), ""), 1420);
        settings.put("mtu", mtu);

        JSONArray reserved = new JSONArray();
        String reservedParam = defaultIfEmpty(q.get("reserved"), "0,0,0");
        for (String part : reservedParam.split(",")) {
            String trimmed = part.trim();
            if (TextUtils.isEmpty(trimmed)) {
                continue;
            }
            try {
                reserved.put(Integer.parseInt(trimmed));
            } catch (NumberFormatException ignore) {
                // skip non-numeric tokens silently
            }
        }
        if (reserved.length() > 0) {
            settings.put("reserved", reserved);
        }

        JSONObject outbound = new JSONObject();
        outbound.put("tag", "proxy");
        outbound.put("protocol", "wireguard");
        outbound.put("settings", settings);

        return ParseResult.valid("wireguard", host, port, getNodeName(uri, q), toConfig(localPort, outbound));
    }

    private static String formatEndpoint(String host, int port) {
        if (!TextUtils.isEmpty(host) && host.indexOf(':') >= 0 && host.charAt(0) != '[') {
            return "[" + host + "]:" + port;
        }
        return host + ":" + port;
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

        JSONObject log = new JSONObject();
        log.put("loglevel", "warning");

        JSONObject dnsObj = new JSONObject();
        dnsObj.put("servers", new JSONArray()
                .put("https+local://1.1.1.1/dns-query")
                .put("localhost"));

        JSONObject root = new JSONObject();
        root.put("log", log);
        root.put("dns", dnsObj);
        root.put("inbounds", new JSONArray().put(inbound));
        root.put("outbounds", new JSONArray().put(mainOutbound).put(direct).put(block));
        return root;
    }

    private static JSONObject buildStreamSettings(Map<String, String> q, String hostFallback, String defaultNetwork) throws Exception {
        JSONObject stream = new JSONObject();
        String network = defaultIfEmpty(q.get("type"), defaultNetwork).toLowerCase(Locale.US);
        stream.put("network", network);

        String security = normalizeTransportSecurity(defaultIfEmpty(q.get("security"), "none"));
        stream.put("security", security);
        if (!"none".equals(security)) {
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

        if ("tcp".equals(network)) {
            String headerType = q.get("headertype");
            if ("http".equalsIgnoreCase(headerType)) {
                JSONObject tcpSettings = new JSONObject();
                JSONObject header = new JSONObject();
                header.put("type", "http");
                JSONObject request = new JSONObject();
                if (!TextUtils.isEmpty(q.get("host"))) {
                    JSONObject headers = new JSONObject();
                    JSONArray hostArr = new JSONArray();
                    for (String h : q.get("host").split(",")) {
                        if (!TextUtils.isEmpty(h.trim())) hostArr.put(h.trim());
                    }
                    headers.put("Host", hostArr);
                    request.put("headers", headers);
                }
                if (!TextUtils.isEmpty(q.get("path"))) {
                    JSONArray pathArr = new JSONArray();
                    for (String p : q.get("path").split(",")) {
                        if (!TextUtils.isEmpty(p.trim())) pathArr.put(p.trim());
                    }
                    request.put("path", pathArr);
                }
                header.put("request", request);
                tcpSettings.put("header", header);
                stream.put("tcpSettings", tcpSettings);
            }
        } else if ("ws".equals(network)) {
            JSONObject ws = new JSONObject();
            putIfNotEmpty(ws, "path", q.get("path"));
            if (!TextUtils.isEmpty(q.get("host"))) {
                JSONObject headers = new JSONObject();
                headers.put("Host", q.get("host"));
                ws.put("headers", headers);
            }
            stream.put("wsSettings", ws);
        } else if ("h2".equals(network) || "http".equals(network)) {
            stream.put("network", "h2");
            JSONObject h2 = new JSONObject();
            if (!TextUtils.isEmpty(q.get("host"))) {
                JSONArray hostArr = new JSONArray();
                for (String h : q.get("host").split(",")) {
                    if (!TextUtils.isEmpty(h.trim())) hostArr.put(h.trim());
                }
                h2.put("host", hostArr);
            }
            putIfNotEmpty(h2, "path", defaultIfEmpty(q.get("path"), "/"));
            stream.put("httpSettings", h2);
        } else if ("grpc".equals(network)) {
            JSONObject grpc = new JSONObject();
            putIfNotEmpty(grpc, "serviceName", queryValue(q, "serviceName"));
            String mode = q.get("mode");
            if ("multi".equalsIgnoreCase(mode)) {
                grpc.put("multiMode", true);
            }
            putIfNotEmpty(grpc, "authority", q.get("authority"));
            grpc.put("idle_timeout", 60);
            grpc.put("health_check_timeout", 20);
            stream.put("grpcSettings", grpc);
        } else if ("httpupgrade".equals(network)) {
            JSONObject hu = new JSONObject();
            putIfNotEmpty(hu, "path", q.get("path"));
            putIfNotEmpty(hu, "host", q.get("host"));
            stream.put("httpupgradeSettings", hu);
        } else if ("splithttp".equals(network) || "xhttp".equals(network)) {
            JSONObject sh = new JSONObject();
            putIfNotEmpty(sh, "path", q.get("path"));
            putIfNotEmpty(sh, "host", q.get("host"));
            putIfNotEmpty(sh, "mode", q.get("mode"));
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

    private static String queryValue(Map<String, String> q, String key) {
        if (q == null || TextUtils.isEmpty(key)) {
            return "";
        }
        String value = q.get(key);
        if (!TextUtils.isEmpty(value)) {
            return value;
        }
        return q.get(key.toLowerCase(Locale.US));
    }

    private static String stripIpv6Brackets(String host) {
        if (TextUtils.isEmpty(host) || host.length() < 2) {
            return host;
        }
        if (host.charAt(0) == '[' && host.charAt(host.length() - 1) == ']') {
            return host.substring(1, host.length() - 1);
        }
        return host;
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

    /**
     * Describes a single supported share-URI protocol family.
     * {@link #displayName} is a non-localized technical identifier (e.g. "VLESS").
     * {@link #uriSchemes} holds all accepted URI scheme prefixes without "://".
     */
    public static final class ProtocolInfo {
        public final String displayName;
        public final List<String> uriSchemes;

        public ProtocolInfo(String displayName, String... uriSchemes) {
            this.displayName = displayName;
            List<String> schemes = new ArrayList<>(uriSchemes.length);
            for (String scheme : uriSchemes) {
                if (!TextUtils.isEmpty(scheme)) {
                    schemes.add(scheme.toLowerCase(Locale.US));
                }
            }
            this.uriSchemes = Collections.unmodifiableList(schemes);
        }
    }
}
