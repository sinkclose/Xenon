package org.telegram.messenger.feed;

import org.telegram.ui.Components.UItem;

public class SettingsRegistry {
    public static UItem markAsNewFeature(String key) {
        return UItem.asCheck(Integer.MAX_VALUE, "");
    }
}
