package zxc.iconic.xenon.plugins;

/** Permission decisions independent of Android, UI and Lua state. */
public final class PluginPermissionPolicy {
    public interface Flags { boolean get(String scope, boolean fallback); }
    public static boolean allows(String scope, boolean godMode, Flags flags) {
        if ("GENERAL".equals(scope)) return true;
        boolean messaging = flags.get("MESSAGING", false);
        if ("JAVA".equals(scope)) return godMode && flags.get("JAVA", false);
        if ("HOOKS".equals(scope)) return allows("JAVA", godMode, flags) && flags.get("HOOKS", true);
        if ("UI".equals(scope)) return flags.get(scope, true);
        if ("LIMITED_JAVA".equals(scope) || "FILES".equals(scope) || "INTERNET".equals(scope) || "OPEN_LINKS".equals(scope)) return flags.get(scope, false);
        if ("READ_MESSAGES".equals(scope) || "SEND_MESSAGES".equals(scope) || "DELETE_MESSAGES".equals(scope)
                || "REACTIONS".equals(scope) || "READ_RECEIPTS".equals(scope)) return messaging && flags.get(scope, messaging);
        return "MESSAGING".equals(scope) && messaging;
    }
}
