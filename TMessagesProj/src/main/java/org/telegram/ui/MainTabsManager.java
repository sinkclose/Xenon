package org.telegram.ui;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.glass.GlassTabView;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

public final class MainTabsManager {
    private static final String PREFS_NAME = "mainconfig";
    private static final String KEY_MAIN_TABS_ORDER = "XN_MainTabsOrder";
    private static final String DEFAULT_ORDER = "CHATS,CALLS,SETTINGS,PROFILE,!CONTACTS";

    public enum TabType {
        CHATS,
        CONTACTS,
        CALLS,
        SETTINGS,
        PROFILE
    }

    public static class Tab {
        public TabType type;
        public boolean enabled;

        public Tab(TabType type, boolean enabled) {
            this.type = type;
            this.enabled = enabled;
        }
    }

    private MainTabsManager() {
    }

    public static List<Tab> getAllTabs() {
        return loadTabs();
    }

    public static List<Tab> getEnabledTabs() {
        List<Tab> all = loadTabs();
        List<Tab> enabled = new ArrayList<>();
        for (Tab tab : all) {
            if (tab.enabled) {
                enabled.add(tab);
            }
        }
        return enabled;
    }

    public static int getPosition(TabType type) {
        List<Tab> tabs = getEnabledTabs();
        for (int i = 0; i < tabs.size(); i++) {
            if (tabs.get(i).type == type) {
                return i;
            }
        }
        return -1;
    }

    public static boolean hasTab(TabType type) {
        return getPosition(type) != -1;
    }

    public static void saveTabs(List<Tab> tabs) {
        StringBuilder order = new StringBuilder();
        for (int i = 0; i < tabs.size(); i++) {
            Tab tab = tabs.get(i);
            if (i > 0) {
                order.append(",");
            }
            if (!tab.enabled) {
                order.append("!");
            }
            order.append(tab.type.name());
        }
        prefs().edit().putString(KEY_MAIN_TABS_ORDER, order.toString()).apply();
    }

    public static void resetTabs() {
        prefs().edit().putString(KEY_MAIN_TABS_ORDER, DEFAULT_ORDER).apply();
    }

    public static GlassTabView createTabView(Context context, Theme.ResourcesProvider resourceProvider, int currentAccount, TabType type) {
        switch (type) {
            case CHATS:
                return GlassTabView.createMainTab(context, resourceProvider, GlassTabView.TabAnimation.CHATS, R.string.MainTabsChats);
            case CONTACTS:
                return GlassTabView.createMainTab(context, resourceProvider, GlassTabView.TabAnimation.CONTACTS, R.string.MainTabsContacts);
            case CALLS:
                return GlassTabView.createMainTab(context, resourceProvider, GlassTabView.TabAnimation.CALLS, R.string.Calls);
            case SETTINGS:
                return GlassTabView.createMainTab(context, resourceProvider, GlassTabView.TabAnimation.SETTINGS, R.string.Settings);
            case PROFILE:
            default:
                return GlassTabView.createAvatar(context, resourceProvider, currentAccount, R.string.MainTabsProfile);
        }
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS_NAME, Activity.MODE_PRIVATE);
    }

    private static List<Tab> loadTabs() {
        String raw = prefs().getString(KEY_MAIN_TABS_ORDER, DEFAULT_ORDER);
        EnumSet<TabType> remaining = EnumSet.allOf(TabType.class);
        List<Tab> result = new ArrayList<>();
        if (raw != null && !raw.isEmpty()) {
            String[] parts = raw.split(",");
            for (String part : parts) {
                boolean enabled = !part.startsWith("!");
                String name = enabled ? part : part.substring(1);
                try {
                    TabType type = TabType.valueOf(name);
                    result.add(new Tab(type, enabled));
                    remaining.remove(type);
                } catch (Throwable ignore) {
                }
            }
        }
        for (TabType type : remaining) {
            result.add(new Tab(type, true));
        }
        return result;
    }
}
