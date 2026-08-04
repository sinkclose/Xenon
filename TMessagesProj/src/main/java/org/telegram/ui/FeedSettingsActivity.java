/*
 * This is the source code of Xenon Feed for Telegram Android.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * FeedSettingsActivity: production-grade settings screen for the Feed tab.
 */

package org.telegram.ui;

import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.feed.FeedConfig;
import org.telegram.messenger.feed.FeedController;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.BulletinFactory;

import java.util.ArrayList;

import zxc.iconic.xenon.settings.BaseNekoSettingsActivity;

public class FeedSettingsActivity extends BaseNekoSettingsActivity {

    private final int headerChannelsRow = rowId++;
    private final int manageChannelsRow = rowId++;
    private final int markAllReadRow = rowId++;
    private final int shadowRow = rowId++;

    private final int headerInfoRow = rowId++;
    private final int infoShadowRow = rowId++;

    @Override
    protected String getActionBarTitle() {
        return LocaleController.getString(R.string.FeedSettingsTitle);
    }

    @Override
    protected String getKey() {
        return "feedSettings";
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asHeader(LocaleController.getString(R.string.FeedChannelsHeader)));
        items.add(UItem.asButton(manageChannelsRow, R.drawable.msg_channel,
                LocaleController.getString(R.string.FeedManageChannels)).accent());
        items.add(UItem.asButton(markAllReadRow, R.drawable.msg_markread,
                LocaleController.getString(R.string.FeedMarkAllRead)).accent());
        items.add(UItem.asShadow(LocaleController.getString(R.string.FeedChannelsInfo)));

        items.add(UItem.asHeader(LocaleController.getString(R.string.FeedAboutHeader)));
        items.add(UItem.asShadow(LocaleController.getString(R.string.FeedAboutText)));
    }

    @Override
    protected void onItemClick(UItem item, View view, int position, float x, float y) {
        if (!item.enabled) return;
        int id = item.id;
        if (id == manageChannelsRow) {
            presentFragment(new FeedChannelsActivity());
        } else if (id == markAllReadRow) {
            FeedController.getInstance(currentAccount).markAllRead();
            BulletinFactory.of(this).createSimpleBulletin(R.raw.contact_check,
                    LocaleController.getString(R.string.FeedMarkAllReadDone)).show();
        }
    }

    @Override
    protected boolean onItemLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }
}
