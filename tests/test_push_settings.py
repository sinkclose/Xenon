"""Exercise account settings and reversible optimized-push mode with production Java."""

import unittest

from test_glass_blur import ROOT, method
from test_glass_chat_refresh import run_java


class PushSettingsTest(unittest.TestCase):
    def test_accounts_and_optimized_mode_preserve_user_choices(self):
        get = method((ROOT / "org/telegram/tgnet/ConnectionsManager.java").read_text(),
                     "public boolean isPushConnectionEnabled()")
        start = method((ROOT / "org/telegram/messenger/ApplicationLoader.java").read_text(),
                       "public static void startPushService()")
        toggle = method((ROOT / "zxc/iconic/xenon/NekoConfig.java").read_text(),
                        "public static void toggleOptimizedPushService()")
        run_java(self, "PushSettingsHarness", r"""
import java.util.*;
public class PushSettingsHarness {
    static class Activity { static final int MODE_PRIVATE = 0; }
    static class SharedPreferences {
        Map<String, Boolean> values = new HashMap<>();
        boolean contains(String key) { return values.containsKey(key); }
        boolean getBoolean(String key, boolean fallback) { return values.getOrDefault(key, fallback); }
        Editor edit() { return new Editor(); }
        class Editor {
            Editor putBoolean(String key, boolean value) { values.put(key, value); return this; }
            void apply() {}
        }
    }
    static class MessagesController {
        static SharedPreferences[] notifications = {new SharedPreferences(), new SharedPreferences(), new SharedPreferences()};
        static SharedPreferences[] main = {new SharedPreferences(), new SharedPreferences(), new SharedPreferences()};
        static SharedPreferences getNotificationsSettings(int a) { return notifications[a]; }
        static SharedPreferences getGlobalNotificationsSettings() { return notifications[0]; }
        static SharedPreferences getMainSettings(int a) { return main[a]; }
    }
    static class UserConfig {
        static final int MAX_ACCOUNT_COUNT = 3;
        static int selectedAccount = 1;
        static UserConfig getInstance(int a) { UserConfig u = new UserConfig(); u.active = a != 1; return u; }
        boolean active;
        boolean isClientActivated() { return active; }
    }
    static class ConnectionsManager {
        static int constructed;
        static ConnectionsManager[] instances = new ConnectionsManager[3];
        int currentAccount, changes;
        boolean enabled;
        ConnectionsManager(int a) { currentAccount = a; ++constructed; }
        static ConnectionsManager getInstance(int a) {
            if (instances[a] == null) instances[a] = new ConnectionsManager(a);
            return instances[a];
        }
        void setPushConnectionEnabled(boolean value) { enabled = value; ++changes; }
        GET
    }
    static class BuildVars { static boolean LOGS_ENABLED; }
    static class FileLog { static void d(String s) {} }
    static class NotificationsService {}
    static class Intent { Intent(Object context, Class<?> service) {} }
    static class Context {
        int starts, stops;
        SharedPreferences neko = new SharedPreferences();
        void startService(Intent i) { ++starts; }
        void stopService(Intent i) { ++stops; }
        SharedPreferences getSharedPreferences(String name, int mode) { return neko; }
    }
    static class ApplicationLoader {
        static Context applicationContext = new Context();
        START
    }
    static class NekoConfig {
        static boolean optimizedPushService;
        TOGGLE
    }
    static void check(boolean b, String reason) { if (!b) throw new AssertionError(reason); }
    public static void main(String[] args) {
        MessagesController.main[2].values.put("backgroundConnection", true);
        // Starting the application must not construct transports just to stop a service.
        NekoConfig.optimizedPushService = true;
        ApplicationLoader.startPushService();
        check(ConnectionsManager.constructed == 0, "service startup initialized transports early");
        check(MessagesController.notifications[2].values.isEmpty(), "mode rewrote user choice");
        NekoConfig.optimizedPushService = false;
        ConnectionsManager zero = ConnectionsManager.getInstance(0);
        ConnectionsManager two = ConnectionsManager.getInstance(2);
        check(!zero.isPushConnectionEnabled() && two.isPushConnectionEnabled(), "wrong account server defaults");
        MessagesController.notifications[0].values.put("pushConnection", false);
        MessagesController.notifications[2].values.put("pushConnection", true);
        check(!zero.isPushConnectionEnabled() && two.isPushConnectionEnabled(), "account 0 overrides another account");
        NekoConfig.toggleOptimizedPushService();
        check(!zero.enabled && two.enabled, "optimized mode ignored explicit choice");
        check(!MessagesController.notifications[0].values.get("pushConnection"), "explicit off overwritten");
        check(ConnectionsManager.instances[1] == null, "inactive account initialized");
        NekoConfig.toggleOptimizedPushService();
        check(!zero.enabled && two.enabled, "mode off failed to restore account policy");
        MessagesController.notifications[0].values.remove("pushConnection");
        NekoConfig.toggleOptimizedPushService();
        check(zero.enabled && !MessagesController.notifications[0].contains("pushConnection"), "temporary default persisted");
        NekoConfig.toggleOptimizedPushService();
        check(!zero.enabled && two.enabled, "temporary push connection remained enabled");
        check(!ApplicationLoader.applicationContext.neko.getBoolean("optimizedPushService", true), "mode not saved");
        MessagesController.notifications[0].values.put("pushService", true);
        ApplicationLoader.startPushService();
        check(ApplicationLoader.applicationContext.starts > 0, "explicit keep-alive setting lost");
    }
}
""".replace("GET", get).replace("START", start).replace("TOGGLE", toggle))


if __name__ == "__main__":
    unittest.main()
