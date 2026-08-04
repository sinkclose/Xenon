package org.telegram.ui;

import android.content.Context;
import android.text.TextUtils;
import android.view.View;
import android.widget.EditText;

import org.telegram.messenger.feed.ExtraConfig;
import org.telegram.messenger.feed.FeedConfig;
import org.telegram.messenger.feed.FeedController;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Locale;

import zxc.iconic.xenon.settings.BaseNekoSettingsActivity;

public class FeedChannelsActivity extends BaseNekoSettingsActivity implements NotificationCenter.NotificationCenterDelegate {

    private final ArrayList<TLRPC.Chat> channels = new ArrayList<>();
    private ActionBarMenuItem otherItem;
    private String query;
    private boolean searching;

    private static final int ID_BOTTOM_TAB = 1073741822;
    private static final int ID_UNREAD_COUNTER = 1073741820;
    private static final int ID_INCLUDE_ARCHIVED = 1073741823;

    private static final Comparator<TLRPC.Chat> BY_TITLE = (a, b) -> {
        String ta = a.title != null ? a.title.toLowerCase(Locale.ROOT) : "";
        String tb = b.title != null ? b.title.toLowerCase(Locale.ROOT) : "";
        return ta.compareTo(tb);
    };

    @Override
    public boolean onFragmentCreate() {
        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.feedNeedReload);
        reloadChannels();
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        super.onFragmentDestroy();
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.feedNeedReload);
    }

    private void reloadChannels() {
        channels.clear();
        FeedController.getInstance(currentAccount).loadChannels(false, (list, count, cached, guid) -> {
            channels.clear();
            for (int i = 0; i < list.size(); i++) {
                TLRPC.Chat c = list.get(i);
                TLRPC.Chat fresh = getMessagesController().getChat(c.id);
                channels.add(fresh != null ? fresh : c);
            }
            Collections.sort(channels, BY_TITLE);
            if (listView != null) listView.adapter.update(true);
        });
    }

    private void setAllExcluded(boolean excluded) {
        FeedConfig config = FeedConfig.getInstance(currentAccount);
        if (excluded) {
            for (int i = 0; i < channels.size(); i++) {
                config.setExcluded(-channels.get(i).id, true);
            }
        } else {
            config.clearExcluded();
        }
        if (listView != null) listView.adapter.update(true);
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (account != currentAccount) return;
        if (id == NotificationCenter.feedNeedReload) {
            reloadChannels();
        }
    }

    @Override
    public boolean onBackPressed(boolean force) {
        if (!searching) return super.onBackPressed(force);
        if (!force) return false;
        actionBar.closeSearchField();
        return false;
    }

    @Override
    protected String getActionBarTitle() {
        return LocaleController.getString(R.string.FeedSettings);
    }

    @Override
    protected String getKey() {
        return "feedChannels";
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        String filter = query;
        boolean noFilter = TextUtils.isEmpty(filter);
        FeedConfig config = FeedConfig.getInstance(currentAccount);

        if (noFilter) {
            items.add(UItem.asHeader(LocaleController.getString(R.string.General)));
            UItem bottomTab = UItem.asCheck(ID_BOTTOM_TAB, LocaleController.getString(R.string.FeedBottomTab));
            bottomTab.setChecked(ExtraConfig.getShowFeedTab());
            items.add(bottomTab);
            UItem unread = UItem.asCheck(ID_UNREAD_COUNTER, LocaleController.getString(R.string.FeedUnreadCounter));
            unread.setChecked(ExtraConfig.getShowFeedUnreadCounter());
            items.add(unread);
            UItem archived = UItem.asCheck(ID_INCLUDE_ARCHIVED, LocaleController.getString(R.string.FeedIncludeArchived));
            archived.setChecked(config.isIncludeArchived());
            items.add(archived);
            items.add(UItem.asShadow(LocaleController.getString(R.string.FeedIncludeArchivedInfo)));
        }

        ArrayList<UItem> shown = new ArrayList<>();
        ArrayList<UItem> hidden = new ArrayList<>();

        for (int i = 0; i < channels.size(); i++) {
            TLRPC.Chat chat = channels.get(i);
            if (!noFilter) {
                String t = chat.title != null ? chat.title.toLowerCase() : "";
                if (!t.contains(filter)) continue;
            }
            boolean included = !config.isExcluded(-chat.id);
            UItem item = UItem.asUserCheckbox((int) chat.id, (TLObject) chat);
            item.setChecked(included);
            (included ? shown : hidden).add(item);
        }

        if (!shown.isEmpty()) {
            items.add(UItem.asHeader(LocaleController.getString(R.string.FeedShownChannels)));
            items.addAll(shown);
        }
        if (!hidden.isEmpty()) {
            if (!shown.isEmpty()) {
                items.add(UItem.asShadow(""));
            }
            items.add(UItem.asHeader(LocaleController.getString(R.string.FeedHiddenChannels)));
            items.addAll(hidden);
        }
        if (noFilter && (!shown.isEmpty() || !hidden.isEmpty())) {
            items.add(UItem.asShadow(LocaleController.getString(R.string.FeedChannelsInfo)));
        }
    }

    @Override
    protected void onItemClick(UItem uItem, View view, int position, float x, float y) {
        if (!uItem.enabled) return;
        Object obj = uItem.object;
        if (obj instanceof TLRPC.Chat) {
            TLRPC.Chat chat = (TLRPC.Chat) obj;
            FeedConfig config = FeedConfig.getInstance(currentAccount);
            boolean wasExcluded = config.isExcluded(-chat.id);
            config.setExcluded(-chat.id, !wasExcluded);
            return;
        }
        int id = uItem.id;
        if (id == ID_BOTTOM_TAB) {
            ExtraConfig.setShowFeedTab(!ExtraConfig.getShowFeedTab());
            NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.feedNeedReload, false);
            if (listView != null) listView.adapter.update(true);
        } else if (id == ID_UNREAD_COUNTER) {
            ExtraConfig.setShowFeedUnreadCounter(!ExtraConfig.getShowFeedUnreadCounter());
            NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.updateInterfaces, 0);
            if (listView != null) listView.adapter.update(true);
        } else if (id == ID_INCLUDE_ARCHIVED) {
            FeedConfig config = FeedConfig.getInstance(currentAccount);
            config.setIncludeArchived(!config.isIncludeArchived());
            reloadChannels();
        }
    }

    @Override
    protected boolean onItemLongClick(UItem uItem, View view, int position, float x, float y) {
        Object obj = uItem.object;
        if (!(obj instanceof TLRPC.Chat)) return false;
        presentFragment(ChatActivity.of(-((TLRPC.Chat) obj).id));
        return true;
    }

    @Override
    public View createView(Context context) {
        View view = super.createView(context);

        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                } else if (id == 1) {
                    setAllExcluded(false);
                } else if (id == 2) {
                    setAllExcluded(true);
                }
            }
        });

        ActionBarMenu menu = actionBar.createMenu();
        menu.addItem(0, R.drawable.outline_header_search).setIsSearchField(true)
            .setActionBarMenuItemSearchListener(new ActionBarMenuItem.ActionBarMenuItemSearchListener() {
                @Override
                public void onSearchCollapse() {
                    searching = false;
                    query = null;
                    if (otherItem != null) otherItem.setVisibility(View.VISIBLE);
                    if (listView != null) listView.adapter.update(true);
                }

                @Override
                public void onSearchExpand() {
                    searching = true;
                    if (otherItem != null) otherItem.setVisibility(View.GONE);
                }

                @Override
                public void onTextChanged(EditText editText) {
                    query = editText.getText().toString().trim().toLowerCase();
                    if (listView != null) listView.adapter.update(true);
                }
            }).setSearchFieldHint(LocaleController.getString(R.string.Search));

        otherItem = menu.addItem(3, R.drawable.ic_ab_other);
        otherItem.addSubItem(1, R.drawable.msg_markread, LocaleController.getString(R.string.SelectAll));
        otherItem.addSubItem(2, R.drawable.msg_close, LocaleController.getString(R.string.DeselectAll));

        return view;
    }
}