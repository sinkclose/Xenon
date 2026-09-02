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

import zxc.iconic.xenon.settings.BaseNekoSettingsActivity;
import zxc.iconic.xenon.settings.NekoAppearanceSettingsActivity;
import zxc.iconic.xenon.settings.NekoChatSettingsActivity;
import zxc.iconic.xenon.settings.NekoDonateActivity;
import zxc.iconic.xenon.settings.NekoEmojiSettingsActivity;
import zxc.iconic.xenon.settings.NekoExperimentalSettingsActivity;
import zxc.iconic.xenon.settings.NekoGeneralSettingsActivity;
import zxc.iconic.xenon.settings.NekoPasscodeSettingsActivity;
import zxc.iconic.xenon.settings.NekoSettingsActivity;

public class SettingsHelper {

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
