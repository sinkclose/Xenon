package org.telegram.ui;

import android.view.View;
import android.content.Context;
import android.view.Gravity;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.UniversalRecyclerView;
import java.util.ArrayList;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.feed.FeedController;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import zxc.iconic.xenon.settings.BaseNekoSettingsActivity;

public class FeedSettingsActivity extends BaseNekoSettingsActivity {
    private static final int CHANNEL_ROW_START = 100;
    private final ArrayList<TLRPC.Chat> channels = new ArrayList<>();

    @Override
    public boolean onFragmentCreate() {
        if (!super.onFragmentCreate()) return false;
        FeedController.getInstance(currentAccount).loadChannels(true, (loaded, count, failed) -> {
            if (isFinished || failed) return;
            channels.clear();
            channels.addAll(loaded);
            if (listView != null) updateRows();
        });
        return true;
    }

    @Override
    protected String getActionBarTitle() {
        return LocaleController.getString(R.string.Feed) + " · " + LocaleController.getString(R.string.Settings);
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asHeader(LocaleController.getString(R.string.FeedChannelsList)));
        addChannels(items, false);
        boolean hasDisabled = false;
        for (TLRPC.Chat channel : channels) {
            if (FeedController.isChannelExcluded(currentAccount, -channel.id)) {
                hasDisabled = true;
                break;
            }
        }
        if (hasDisabled) {
            items.add(UItem.asShadow(null));
            items.add(UItem.asHeader("Disabled channels"));
            addChannels(items, true);
        }
        items.add(UItem.asShadow(channels.isEmpty() ? LocaleController.getString(R.string.FeedChannelsEmpty) : null));
    }

    private void addChannels(ArrayList<UItem> items, boolean disabled) {
        for (int i = 0; i < channels.size(); i++) {
            TLRPC.Chat channel = channels.get(i);
            if (FeedController.isChannelExcluded(currentAccount, -channel.id) == disabled) {
                items.add(ChannelCellFactory.of(CHANNEL_ROW_START + i, channel, !disabled));
            }
        }
    }

    @Override
    protected void onItemClick(UItem item, View view, int position, float x, float y) {
        FeedController controller = FeedController.getInstance(currentAccount);
        int index = item.id - CHANNEL_ROW_START;
        if (index < 0 || index >= channels.size()) return;
        long dialogId = -channels.get(index).id;
        controller.setChannelExcluded(dialogId, !FeedController.isChannelExcluded(currentAccount, dialogId));
        updateRows();
    }

    private static class ChannelCell extends TextCheckCell {
        final BackupImageView avatar;
        final AvatarDrawable avatarDrawable = new AvatarDrawable();
        final int account;

        ChannelCell(Context context, int account, Theme.ResourcesProvider resourcesProvider) {
            super(context, 76, false, resourcesProvider);
            this.account = account;
            avatar = new BackupImageView(context);
            avatar.getImageReceiver().setCurrentAccount(account);
            avatar.setRoundRadius(AndroidUtilities.dp(20));
            addView(avatar, LayoutHelper.createFrame(40, 40,
                    (LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT) | Gravity.CENTER_VERTICAL, 20, 0, 20, 0));
        }
    }

    public static class ChannelCellFactory extends UItem.UItemFactory<ChannelCell> {
        static { setup(new ChannelCellFactory()); }

        static UItem of(int id, TLRPC.Chat channel, boolean checked) {
            UItem item = UItem.ofFactory(ChannelCellFactory.class);
            item.id = id;
            item.object = channel;
            item.text = channel.title;
            return item.setChecked(checked);
        }

        @Override
        public ChannelCell createView(Context context, RecyclerListView listView, int account, int classGuid, Theme.ResourcesProvider provider) {
            return new ChannelCell(context, account, provider);
        }

        @Override
        public void bindView(View view, UItem item, boolean divider, UniversalAdapter adapter, UniversalRecyclerView listView) {
            ChannelCell cell = (ChannelCell) view;
            TLRPC.Chat channel = (TLRPC.Chat) item.object;
            cell.setTextAndCheck(item.text, item.checked, divider);
            cell.avatarDrawable.setInfo(cell.account, channel);
            cell.avatar.setForUserOrChat(channel, cell.avatarDrawable);
        }
    }
}
