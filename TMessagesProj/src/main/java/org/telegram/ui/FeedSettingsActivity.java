package org.telegram.ui;

import android.view.View;
import android.content.Context;
import android.view.Gravity;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.text.InputFilter;
import java.text.Collator;
import java.util.Locale;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.messenger.feed.FeedFolders;
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
    private String searchQuery = "";
    private final int folderId;
    private final boolean channelsOnly;
    private int foldersOrderSection;
    private static final int ADD_FOLDER = -10;

    public FeedSettingsActivity() {
        folderId = FeedFolders.ALL;
        channelsOnly = false;
    }

    public FeedSettingsActivity(int folderId) {
        this.folderId = folderId;
        channelsOnly = true;
    }

    @Override
    public View createView(Context context) {
        View view = super.createView(context);
        createSearchItem(actionBar.createMenu(), new ActionBarMenuItem.ActionBarMenuItemSearchListener() {
            @Override
            public void onSearchCollapse() {
                searchQuery = "";
                updateRows();
            }

            @Override
            public void onTextChanged(EditText editText) {
                searchQuery = editText.getText().toString().trim().toLowerCase(Locale.ROOT);
                updateRows();
            }
        });
        ActionBarMenuItem more = actionBar.createMenu().addItem(1, R.drawable.ic_ab_other);
        more.addSubItem(2, R.drawable.msg_select, LocaleController.getString(R.string.SelectAll));
        more.addSubItem(3, R.drawable.msg_clear, LocaleController.getString(R.string.FeedDeselectAll));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) finishFragment();
                else if (id == 2 || id == 3) {
                    ArrayList<Long> ids = new ArrayList<>();
                    for (TLRPC.Chat channel : channels) ids.add(-channel.id);
                    FeedController.getInstance(currentAccount).setFolderChannels(folderId, ids, id == 2);
                    updateRows();
                }
            }
        });
        if (!channelsOnly) {
            listView.listenReorder((section, rows) -> {
                if (section != foldersOrderSection) return;
                ArrayList<Integer> order = new ArrayList<>();
                for (UItem row : rows) if (row.object instanceof FeedFolders.Folder) {
                    order.add(((FeedFolders.Folder) row.object).id);
                }
                FeedFolders.getInstance(currentAccount).reorder(order);
                FeedController.getInstance(currentAccount).onFoldersChanged(false);
            });
            listView.allowReorder(true);
        }
        return view;
    }

    @Override
    public void onResume() {
        super.onResume();
        if (channelsOnly && FeedFolders.getInstance(currentAccount).getFolder(folderId) == null) {
            finishFragment();
            return;
        }
        if (listView != null) updateRows();
    }

    @Override
    public boolean onFragmentCreate() {
        if (!super.onFragmentCreate()) return false;
        FeedController.getInstance(currentAccount).loadChannels(true, (loaded, count, failed) -> {
            if (isFinished || failed) return;
            channels.clear();
            channels.addAll(loaded);
            Collator collator = Collator.getInstance();
            collator.setStrength(Collator.PRIMARY);
            channels.sort((a, b) -> {
                int result = collator.compare(a.title == null ? "" : a.title, b.title == null ? "" : b.title);
                return result != 0 ? result : Long.compare(a.id, b.id);
            });
            if (listView != null) updateRows();
        });
        return true;
    }

    @Override
    protected String getActionBarTitle() {
        if (channelsOnly) {
            FeedFolders.Folder folder = FeedFolders.getInstance(currentAccount).getFolder(folderId);
            return LocaleController.getString(R.string.Feed) + " · " + LocaleController.getString(R.string.FeedFolder)
                    + " " + (folder == null ? "" : folder.name);
        }
        return LocaleController.getString(R.string.Feed) + " · " + LocaleController.getString(R.string.Settings);
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        if (!channelsOnly && searchQuery.isEmpty()) {
            FeedFolders folders = FeedFolders.getInstance(currentAccount);
            items.add(UItem.asHeader(LocaleController.getString(R.string.FeedFolders)));
            ArrayList<FeedFolders.Folder> all = folders.getFolders();
            items.add(folderItem(all.get(0), folders));
            if (all.size() > 1) {
                foldersOrderSection = adapter.reorderSectionStart();
                for (int i = 1; i < all.size(); i++) items.add(folderItem(all.get(i), folders));
                adapter.reorderSectionEnd();
            }
            items.add(UItem.asButton(ADD_FOLDER, R.drawable.msg_add, LocaleController.getString(R.string.FeedAddFolder)).accent());
            items.add(UItem.asShadow(LocaleController.getString(R.string.FeedFoldersReorderHint)));
        }
        items.add(UItem.asHeader(LocaleController.getString(R.string.FeedChannelsList)));
        addChannels(items, false);
        boolean hasDisabled = false;
        for (TLRPC.Chat channel : channels) {
            if (!FeedFolders.getInstance(currentAccount).isIncluded(folderId, -channel.id)) {
                hasDisabled = true;
                break;
            }
        }
        if (hasDisabled) {
            items.add(UItem.asShadow(null));
            items.add(UItem.asHeader(LocaleController.getString(R.string.FeedDisabledChannels)));
            addChannels(items, true);
        }
        items.add(UItem.asShadow(channels.isEmpty() ? LocaleController.getString(R.string.FeedChannelsEmpty) : null));
    }

    private void addChannels(ArrayList<UItem> items, boolean disabled) {
        for (int i = 0; i < channels.size(); i++) {
            TLRPC.Chat channel = channels.get(i);
            if (!searchQuery.isEmpty() && (channel.title == null
                    || !channel.title.toLowerCase(Locale.ROOT).contains(searchQuery))) continue;
            if (!FeedFolders.getInstance(currentAccount).isIncluded(folderId, -channel.id) == disabled) {
                items.add(ChannelCellFactory.of(CHANNEL_ROW_START + i, channel, !disabled));
            }
        }
    }

    @Override
    protected void onItemClick(UItem item, View view, int position, float x, float y) {
        if (item.id == ADD_FOLDER) {
            showFolderSheet(null);
            return;
        }
        if (item.object instanceof FeedFolders.Folder) {
            showFolderSheet((FeedFolders.Folder) item.object);
            return;
        }
        FeedController controller = FeedController.getInstance(currentAccount);
        int index = item.id - CHANNEL_ROW_START;
        if (index < 0 || index >= channels.size()) return;
        long dialogId = -channels.get(index).id;
        ArrayList<Long> ids = new ArrayList<>();
        ids.add(dialogId);
        controller.setFolderChannels(folderId, ids, !FeedFolders.getInstance(currentAccount).isIncluded(folderId, dialogId));
        updateRows();
    }

    private UItem folderItem(FeedFolders.Folder folder, FeedFolders folders) {
        String status = folder.id == folders.getDefaultId() ? LocaleController.getString(R.string.FeedMainFolder) : "";
        if (folder.id == FeedFolders.ALL && folders.isAllHidden()) {
            status = status.isEmpty() ? LocaleController.getString(R.string.Hide) : status + " · " + LocaleController.getString(R.string.Hide);
        }
        UItem item = UItem.asButton(-1000 - folder.id, R.drawable.msg_folders, folder.name, status);
        item.object = folder;
        return item;
    }

    private void showFolderSheet(FeedFolders.Folder folder) {
        if (getParentActivity() == null) return;
        Context context = getParentActivity();
        FeedFolders folders = FeedFolders.getInstance(currentAccount);
        BottomSheet sheet = new BottomSheet(context, true, getResourceProvider());
        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        EditTextBoldCursor name = new EditTextBoldCursor(context);
        name.setSingleLine(true);
        name.setTextSize(18);
        name.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
        name.setHintTextColor(getThemedColor(Theme.key_windowBackgroundWhiteHintText));
        name.setHint(LocaleController.getString(R.string.FeedFolderName));
        name.setBackground(Theme.createEditTextDrawable(context,
                getThemedColor(Theme.key_windowBackgroundWhiteInputField),
                getThemedColor(Theme.key_windowBackgroundWhiteInputFieldActivated)));
        name.setFilters(new InputFilter[]{new InputFilter.LengthFilter(64)});
        if (folder != null) name.setText(folder.name);
        content.addView(name, LayoutHelper.createLinear(-1, 48, 20, 12, 20, 12));
        if (folder == null) {
            addSheetButton(content, LocaleController.getString(R.string.FeedAddFolder), () -> {
                String title = name.getText().toString().trim();
                if (title.isEmpty()) { name.setError(LocaleController.getString(R.string.FeedFolderName)); return; }
                folders.add(title);
                FeedController.getInstance(currentAccount).onFoldersChanged(false);
                updateRows();
                sheet.dismiss();
            });
        }
        if (folder != null) {
            sheet.setOnDismissListener((Runnable) () -> {
                String title = name.getText().toString().trim();
                FeedFolders.Folder current = folders.getFolder(folder.id);
                if (current != null && !title.isEmpty() && !title.equals(current.name)) {
                    folders.rename(folder.id, title);
                    FeedController.getInstance(currentAccount).onFoldersChanged(false);
                    if (!isFinished) updateRows();
                }
            });
            addSheetButton(content, LocaleController.getString(R.string.FeedManageChannels), () -> {
                sheet.dismiss();
                presentFragment(new FeedSettingsActivity(folder.id));
            });
            addSheetButton(content, LocaleController.getString(folder.id == folders.getDefaultId()
                    ? R.string.FeedMainFolder : R.string.FeedMakeMain), () -> {
                folders.setDefault(folder.id);
                FeedController.getInstance(currentAccount).onFoldersChanged(false);
                updateRows();
                sheet.dismiss();
            });
            if (folder.id == FeedFolders.ALL) {
                addSheetButton(content, LocaleController.getString(folders.isAllHidden() ? R.string.Show : R.string.Hide), () -> {
                    int previousActive = folders.getActiveId();
                    folders.setAllHidden(!folders.isAllHidden());
                    FeedController.getInstance(currentAccount).onFoldersChanged(previousActive != folders.getActiveId());
                    updateRows();
                    sheet.dismiss();
                });
            } else {
                addSheetButton(content, LocaleController.getString(R.string.Delete), () -> {
                    int previousActive = folders.getActiveId();
                    folders.delete(folder.id);
                    FeedController.getInstance(currentAccount).onFoldersChanged(previousActive != folders.getActiveId());
                    updateRows();
                    sheet.dismiss();
                });
            }
        }
        sheet.setCustomView(content);
        showDialog(sheet);
    }

    private void addSheetButton(LinearLayout content, String title, Runnable action) {
        TextSettingsCell cell = new TextSettingsCell(content.getContext(), getResourceProvider());
        cell.setText(title, true);
        cell.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlueText));
        cell.setBackground(Theme.getSelectorDrawable(false));
        cell.setOnClickListener(v -> action.run());
        content.addView(cell, LayoutHelper.createLinear(-1, -2));
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
