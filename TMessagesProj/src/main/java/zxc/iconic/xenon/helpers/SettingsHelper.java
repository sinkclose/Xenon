package zxc.iconic.xenon.helpers;

import android.net.Uri;
import android.text.TextUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.browser.Browser;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.LaunchActivity;

import java.util.Locale;
import java.util.function.Consumer;

import zxc.iconic.xenon.helpers.PasscodeHelper;
import zxc.iconic.xenon.plugins.PluginManager;

import zxc.iconic.xenon.settings.BaseNekoSettingsActivity;
import zxc.iconic.xenon.settings.MainTabsSettingsActivity;
import zxc.iconic.xenon.settings.NekoAppearanceSettingsActivity;
import zxc.iconic.xenon.settings.NekoCameraSettingsActivity;
import zxc.iconic.xenon.settings.NekoChatHeaderSettingsActivity;
import zxc.iconic.xenon.settings.NekoChatSettingsActivity;
import zxc.iconic.xenon.settings.NekoDonateActivity;
import zxc.iconic.xenon.settings.NekoEmojiSettingsActivity;
import zxc.iconic.xenon.settings.NekoExperimentalSettingsActivity;
import zxc.iconic.xenon.settings.NekoGeneralSettingsActivity;
import zxc.iconic.xenon.settings.NekoLiquidGlassSettingsActivity;
import zxc.iconic.xenon.settings.NekoMentionSettingsActivity;
import zxc.iconic.xenon.settings.NekoNavigationSettingsActivity;
import zxc.iconic.xenon.settings.NekoPasscodeSettingsActivity;
import zxc.iconic.xenon.settings.NekoPluginsActivity;
import zxc.iconic.xenon.settings.NekoSettingsActivity;
import zxc.iconic.xenon.settings.NekoXrayProxyAdvancedActivity;
import zxc.iconic.xenon.settings.NekoXrayProxyHubActivity;
import zxc.iconic.xenon.settings.NekoXrayProxyProfileEditActivity;
import zxc.iconic.xenon.settings.NekoXrayProxyProfilesActivity;
import zxc.iconic.xenon.settings.NekoXrayProxySubscriptionsActivity;
import zxc.iconic.xenon.settings.PluginSettingsActivity;
import zxc.iconic.xenon.settings.SwipeActionsSettingsActivity;

public class SettingsHelper {

    /** Deep-link key is "plugin_settings_" + pluginId (or fileName as fallback). */
    private static PluginManager.LoadedPlugin findPluginForSettings(String idOrFile) {
        if (TextUtils.isEmpty(idOrFile)) return null;
        try {
            var pm = PluginManager.getInstance();
            var loaded = pm.findByPluginId(idOrFile);
            if (loaded != null) return loaded;
            loaded = pm.findPlugin(idOrFile);
            if (loaded != null) return loaded;
            for (var info : pm.getAllPluginInfos()) {
                if (idOrFile.equalsIgnoreCase(info.pluginId) || idOrFile.equalsIgnoreCase(info.fileName)) {
                    return pm.loadPluginForSettings(info.fileName);
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    public static void processDeepLink(Uri uri, Consumer<BaseFragment> callback, Runnable unknown, Browser.Progress progress) {
        if (uri == null) {
            unknown.run();
            return;
        }
        var segments = uri.getPathSegments();
        if (segments.isEmpty() || segments.size() > 2) {
            unknown.run();
            return;
        }
        BaseNekoSettingsActivity fragment;
        if (segments.size() == 1) {
            fragment = new NekoSettingsActivity();
        } else {
            var segment = segments.get(1);
            if (PasscodeHelper.getSettingsKey().equals(segment)) {
                fragment = new NekoPasscodeSettingsActivity();
            } else if (segment.length() > "plugin_settings_".length() && segment.regionMatches(true, 0, "plugin_settings_", 0, "plugin_settings_".length())) {
                var idOrFile = segment.substring("plugin_settings_".length());
                var loaded = findPluginForSettings(idOrFile);
                if (loaded == null) {
                    unknown.run();
                    return;
                }
                fragment = new PluginSettingsActivity().setPlugin(loaded);
            } else {
                switch (segment.toLowerCase(Locale.US)) {
                    case "appearance":
                    case "a":
                        fragment = new NekoAppearanceSettingsActivity();
                        break;
                    case "chat":
                    case "chats":
                    case "c":
                        fragment = new NekoChatSettingsActivity();
                        break;
                    case "ch":
                    case "chatheader":
                    case "chat_header":
                        fragment = new NekoChatHeaderSettingsActivity();
                        break;
                    case "camera":
                    case "cam":
                        fragment = new NekoCameraSettingsActivity();
                        break;
                    case "donate":
                    case "d":
                        fragment = new NekoDonateActivity();
                        break;
                    case "experimental":
                    case "e":
                        fragment = new NekoExperimentalSettingsActivity();
                        break;
                    case "emoji":
                        fragment = new NekoEmojiSettingsActivity();
                        break;
                    case "general":
                    case "g":
                        fragment = new NekoGeneralSettingsActivity();
                        break;
                    case "liquidglass":
                    case "liquid_glass":
                    case "glass":
                        fragment = new NekoLiquidGlassSettingsActivity();
                        break;
                    case "maintabs":
                    case "tabs":
                        fragment = new MainTabsSettingsActivity();
                        break;
                    case "mention_custom":
                    case "mention":
                    case "mentions":
                        fragment = new NekoMentionSettingsActivity();
                        break;
                    case "nav":
                    case "navigation":
                        fragment = new NekoNavigationSettingsActivity();
                        break;
                    case "plugins":
                        fragment = new NekoPluginsActivity();
                        break;
                    case "swipeactions":
                    case "swipe_actions":
                    case "swipe":
                        fragment = new SwipeActionsSettingsActivity();
                        break;
                    case "xrayhub":
                    case "xray":
                    case "proxy":
                        fragment = new NekoXrayProxyHubActivity();
                        break;
                    case "xrayprofiles":
                    case "profiles":
                        fragment = new NekoXrayProxyProfilesActivity();
                        break;
                    case "xraysubscriptions":
                    case "subscriptions":
                        fragment = new NekoXrayProxySubscriptionsActivity();
                        break;
                    case "xrayadvanced":
                    case "advanced":
                        fragment = new NekoXrayProxyAdvancedActivity();
                        break;
                    case "xrayprofileedit":
                    case "profileedit":
                        fragment = new NekoXrayProxyProfileEditActivity();
                        break;
                    case "reportid":
                        fragment = new NekoSettingsActivity();
                        break;
                    case "update":
                        LaunchActivity.instance.checkAppUpdate(true, progress);
                        return;
                    default:
                        unknown.run();
                        return;
                }
            }
        }
        callback.accept(fragment);
        var row = uri.getQueryParameter("r");
        if (TextUtils.isEmpty(row)) {
            row = uri.getQueryParameter("row");
        }
        if (!TextUtils.isEmpty(row)) {
            fragment.scrollToRow(row, unknown);
        }
    }
}
