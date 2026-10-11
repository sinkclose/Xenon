package zxc.iconic.xenon.helpers;

import org.telegram.messenger.AndroidUtilities;
import zxc.iconic.xenon.NekoConfig;

/** iOS keeps one continuous card per section, with its heading outside the card. */
public final class IosListItemsHelper {
    private IosListItemsHelper() {}
    public static boolean isEnabled() { return NekoConfig.iosListItems; }
    public static float radius(float original) {
        return isEnabled() ? Math.max(original, AndroidUtilities.dp(24)) : original;
    }
    public static boolean isSection(boolean original, boolean header) {
        return original && !(isEnabled() && header);
    }
}
