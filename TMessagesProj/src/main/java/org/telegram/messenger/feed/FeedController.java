package org.telegram.messenger.feed;

import androidx.collection.LongSparseArray;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.HashtagSearchController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ChatActivity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;

public class FeedController implements NotificationCenter.NotificationCenterDelegate {

    private static final FeedController[] Instance = new FeedController[UserConfig.MAX_ACCOUNT_COUNT];
    private static final Object[] lockObjects = new Object[UserConfig.MAX_ACCOUNT_COUNT];
    static { for (int i = 0; i < Instance.length; i++) lockObjects[i] = new Object(); }

    public static FeedController peekInstance(int account) { return Instance[account]; }

    public static FeedController getInstance(int account) {
        FeedController c = Instance[account];
        if (c != null) return c;
        synchronized (lockObjects[account]) {
            c = Instance[account];
            if (c == null) { c = new FeedController(account); Instance[account] = c; }
            return c;
        }
    }

    public static class SavedScrollPosition {
        public final long dialogId;
        public final int messageId;
        public final int offsetTop;
        SavedScrollPosition(long d, int m, int o) { dialogId = d; messageId = m; offsetTop = o; }
    }

    public interface ChannelsCallback {
        void onChannels(ArrayList<TLRPC.Chat> chats, int count, boolean cached, int classGuid);
    }

    public final int currentAccount;
    private final FeedStore store;
    private int sessionGeneration;
    private int configGeneration;
    private boolean loading;
    private boolean initialUnreadScrollPending = true;
    private SavedScrollPosition drawerScrollPosition;
    private final HashSet<Long> knownChannelIds = new HashSet<>();

    private final Comparator<MessageObject> dateComparator = (m1, m2) -> {
        long d1 = m1 != null && m1.messageOwner != null ? m1.messageOwner.date : 0;
        long d2 = m2 != null && m2.messageOwner != null ? m2.messageOwner.date : 0;
        return Long.compare(d2, d1);
    };

    private FeedController(int account) {
        this.currentAccount = account;
        this.store = new FeedStore();
        NotificationCenter.getInstance(account).addObserver(this, NotificationCenter.messagesDeleted);
        NotificationCenter.getInstance(account).addObserver(this, NotificationCenter.historyCleared);
        NotificationCenter.getInstance(account).addObserver(this, NotificationCenter.didReceiveNewMessages);
        NotificationCenter.getInstance(account).addObserver(this, NotificationCenter.messagesDidLoad);
    }

    public FeedStore getStore() { return store; }
    public ArrayList<MessageObject> getMessages() { return store.getMessages(); }
    public boolean isLoading() { return loading; }
    public int getIncludedChannelCount() { return knownChannelIds.size(); }
    public boolean hasIncludedChannels() { return !knownChannelIds.isEmpty(); }

    public boolean isIncludedChannelPost(long dialogId) {
        if (!DialogObject.isChatDialog(dialogId) || FeedConfig.getInstance(currentAccount).isExcluded(dialogId))
            return false;
        return isEligibleChannel(MessagesController.getInstance(currentAccount).getChat(-dialogId));
    }

    public static boolean isEligibleChannel(TLRPC.Chat chat) {
        return chat != null && ChatObject.isChannelAndNotMegaGroup(chat)
            && !ChatObject.isCommunity(chat) && !ChatObject.isNotInChat(chat);
    }

    public boolean consumeInitialUnreadScroll() {
        boolean v = initialUnreadScrollPending;
        initialUnreadScrollPending = false;
        return v;
    }

    public void saveDrawerScrollPosition(long dialogId, int messageId, int offsetTop) {
        if (dialogId == 0 || messageId <= 0) return;
        drawerScrollPosition = new SavedScrollPosition(dialogId, messageId, offsetTop);
    }

    public SavedScrollPosition getDrawerScrollPosition() { return drawerScrollPosition; }

    public MessageObject getMessage(long dialogId, int id) { return store.getMessage(dialogId, id); }
    public int resolveRealMessageId(long dialogId, int id) { return store.resolveRealMessageId(dialogId, id); }
    public long resolveRealDialogId(int id) { return store.resolveRealDialogId(id); }

    /* ---- Config management ---- */

    public void markConfigApplied() {
        configGeneration = FeedConfig.getInstance(currentAccount).getGeneration();
    }

    private int uiActiveClients;
    private int resumedUiClients;

    public void setUiActive(boolean active) {
        if (active) uiActiveClients++;
        else if (uiActiveClients > 0) uiActiveClients--;
    }

    public void setUiResumed(boolean resumed) {
        if (resumed) resumedUiClients++;
        else if (resumedUiClients > 0) resumedUiClients--;
    }

    private boolean isUiActive() { return uiActiveClients > 0; }

    public void applyConfigChange(Utilities.Callback<Boolean> callback) {
        clear();
        if (callback != null) callback.run(false);
    }

    public void clear() {
        sessionGeneration++;
        configGeneration = FeedConfig.getInstance(currentAccount).getGeneration();
        knownChannelIds.clear();
        store.clear();
        loading = false;
        drawerScrollPosition = null;
        initialUnreadScrollPending = true;
    }

    /* ---- Core load ---- */

    public boolean loadInitial(int guid, int loadIndex) {
        ensureCurrentConfig();
        loading = true;
        rebuildFromStore();
        loading = false;
        postFeedResults(guid, loadIndex, store.getVisibleMessages(), 0, false, false);
        postFeedCount(guid);
        return true;
    }

    public boolean loadOlder(int guid, int loadIndex) {
        postFeedResults(guid, loadIndex, new ArrayList<>(), 2);
        postFeedCount(guid);
        return true;
    }

    public boolean loadNewer(int guid, int loadIndex) {
        loading = true;
        rebuildFromStore();
        loading = false;
        postFeedResults(guid, loadIndex, store.getVisibleMessages(), 0, false, false);
        postFeedCount(guid);
        return true;
    }

    public void reloadFeed() {
        clear();
        rebuildFromStore();
    }

    public void reconcileFeedList() {
        rebuildFromStore();
        // Sync the search adapter's backing list with the freshly rebuilt store.
        HashtagSearchController.SearchResult result = feedResult();
        result.messages.clear();
        result.messages.addAll(store.getVisibleMessages());
        result.count = result.messages.size();
        result.endReached = true;
        result.loading = false;
    }

    private void rebuildFromStore() {
        store.clear();
        knownChannelIds.clear();
        rebuildMessages(store.getMessages());
    }

    private void rebuildMessages(ArrayList<MessageObject> out) {
        MessagesController mc = MessagesController.getInstance(currentAccount);
        ArrayList<TLRPC.Dialog> allDialogs = mc.getAllDialogs();
        if (allDialogs == null) return;

        long selfId = UserConfig.getInstance(currentAccount).getClientUserId();
        FeedConfig config = FeedConfig.getInstance(currentAccount);
        LongSparseArray<ArrayList<MessageObject>> dm = mc.dialogMessage;
        android.util.SparseArray<MessageObject> dmid = mc.dialogMessagesByIds;

        // Step 1: collect latest message(s) from each eligible channel.
        // For albums (grouped_id != 0) we collect ALL parts of the album so the
        // feed displays the full album rather than just its first/last piece.
        ArrayList<MessageObject> collected = new ArrayList<>();
        for (int i = 0, n = allDialogs.size(); i < n; i++) {
            TLRPC.Dialog dialog = allDialogs.get(i);
            if (dialog == null) continue;
            long did = dialog.id;
            if (DialogObject.isFolderDialogId(did)) continue;
            if (did == selfId) continue;
            if (config.isExcluded(did)) continue;
            if (!isEligibleChannel(mc.getChat(-did))) continue;

            knownChannelIds.add(did);

            // Find the latest message (top_message) for this channel.
            MessageObject top = null;
            int topId = dialog.top_message;
            ArrayList<MessageObject> dml = dm.get(did);
            if (topId > 0) {
                if (dml != null) {
                    for (int a = 0, sz = dml.size(); a < sz; a++) {
                        MessageObject m = dml.get(a);
                        if (m != null && m.getId() == topId) { top = m; break; }
                    }
                }
                if (top == null) top = dmid.get(topId);
            }
            if (top == null && dml != null && !dml.isEmpty()) top = dml.get(0);
            if (top == null || isServiceMessage(top)) continue;

            // If the latest message is part of an album (grouped_id), collect all parts
            // of that album that are available in the cached dialog messages.
            long groupId = top.getGroupId();
            if (groupId != 0 && dml != null) {
                boolean added = false;
                for (int a = 0, sz = dml.size(); a < sz; a++) {
                    MessageObject m = dml.get(a);
                    if (m == null || isServiceMessage(m)) continue;
                    if (m.getGroupId() == groupId && m.getDialogId() == did) {
                        collected.add(m);
                        added = true;
                    }
                }
                if (added) continue;
            }
            // Single (non-album) message
            collected.add(top);
        }

        // Mark all collected messages with searchType=SEARCH_FEED so ChatMessageCell
        // draws avatars (MessageObject.needDrawAvatar() returns true when searchType != 0)
        // and the feed behaves like a channel timeline with channel avatars visible.
        for (int i = 0, sz = collected.size(); i < sz; i++) {
            collected.get(i).searchType = ChatActivity.SEARCH_FEED;
        }

        // Step 2: sort by date DESC, then dialogId, then messageId — newest first.
        // Using FeedStore.compareTimeline (same ordering the store uses) ensures
        // consistent timeline order regardless of insertion order.
        Collections.sort(collected, (m1, m2) -> {
            int d1 = m1.messageOwner != null ? m1.messageOwner.date : 0;
            int d2 = m2.messageOwner != null ? m2.messageOwner.date : 0;
            long id1 = m1.getDialogId();
            long id2 = m2.getDialogId();
            int mid1 = m1.getRealId();
            int mid2 = m2.getRealId();
            // compareTimeline returns natural order (asc); we want desc → negate.
            return -FeedStore.compareTimeline(d1, id1, mid1, d2, id2, mid2);
        });

        // Step 3: register all collected messages through the store so synthetic
        // IDs are assigned and identity map is populated. Using mergeRows keeps
        // the timeline order consistent with the store's own ordering.
        store.mergeRows(collected);
    }

    private boolean isServiceMessage(MessageObject obj) {
        if (obj == null || obj.messageOwner == null) return true;
        if (obj.messageOwner instanceof TLRPC.TL_messageEmpty) return true;
        if (obj.type == MessageObject.TYPE_LOADING) return true;
        if (obj.type == 1000) return true;
        return obj.messageOwner instanceof TLRPC.TL_messageService;
    }

    /* ---- Read state ---- */

    public void markAllRead() {
        for (MessageObject msg : store.getMessages()) {
            if (msg == null) continue;
            MessagesController.getInstance(currentAccount).markDialogAsRead(
                msg.getDialogId(), msg.getId(), 0,
                msg.messageOwner != null ? msg.messageOwner.date : 0,
                false, 0, 1, true, 0);
        }
    }

    public void onPostSeen(long dialogId, int realId) { }
    public int findFirstUnreadIndex(ArrayList<MessageObject> list) { return -1; }
    public int countUnreadBelow(ArrayList<MessageObject> list, int index) { return 0; }

    public void refreshReadState(Runnable callback) {
        if (callback != null) callback.run();
    }

    /* ---- Channels ---- */

    public void loadChannels(ChannelsCallback callback) {
        loadChannels(false, callback);
    }

    public void loadChannels(boolean force, ChannelsCallback callback) {
        ArrayList<TLRPC.Chat> list = new ArrayList<>();
        MessagesController mc = MessagesController.getInstance(currentAccount);
        ArrayList<TLRPC.Dialog> allDialogs = mc.getAllDialogs();
        long selfId = UserConfig.getInstance(currentAccount).getClientUserId();
        FeedConfig config = FeedConfig.getInstance(currentAccount);
        int count = 0;
        for (int i = 0, n = allDialogs != null ? allDialogs.size() : 0; i < n; i++) {
            TLRPC.Dialog d = allDialogs.get(i);
            if (d == null || d.id == selfId || d.id >= 0) continue;
            if (config.isExcluded(d.id)) continue;
            if (!isEligibleChannel(mc.getChat(-d.id))) continue;
            list.add(mc.getChat(-d.id));
            count++;
        }
        if (callback != null) callback.onChannels(list, count, false, 0);
    }

    public void replaceMessage(MessageObject oldMsg, MessageObject newMsg) {
        store.replaceMessage(oldMsg, newMsg);
    }

    /* ---- NotificationCenter ---- */

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (account != currentAccount) return;
        if (id == NotificationCenter.messagesDeleted) {
            if (args != null && args.length > 1 && args[1] instanceof Long) {
                long dialogId = (Long) args[1];
                if (DialogObject.isChatDialog(dialogId)) {
                    @SuppressWarnings("unchecked")
                    ArrayList<Integer> msgIds = (ArrayList<Integer>) args[0];
                    boolean[] changed = new boolean[1];
                    store.deleteMessages(dialogId, msgIds, changed);
                }
            }
        } else if (id == NotificationCenter.historyCleared) {
            if (args != null && args.length > 1) {
                long dialogId = (Long) args[0];
                int maxId = (Integer) args[1];
                if (DialogObject.isChatDialog(dialogId)) {
                    boolean[] changed = new boolean[1];
                    store.deleteHistory(dialogId, maxId, changed);
                }
            }
        }
    }

    /* ---- internal: post results to HashtagSearchController ---- */

    private HashtagSearchController.SearchResult feedResult() {
        return HashtagSearchController.getInstance(currentAccount).getSearchResult(ChatActivity.SEARCH_FEED);
    }

    private void ensureCurrentConfig() {
        if (configGeneration != FeedConfig.getInstance(currentAccount).getGeneration()) {
            applyConfigChange(null);
        }
    }

    private void postFeedResults(int guid, int loadIndex, ArrayList<MessageObject> msgs, int loadType) {
        postFeedResults(guid, loadIndex, msgs, loadType, false, false);
    }

    private void postFeedResults(int guid, int loadIndex, ArrayList<MessageObject> msgs,
                                  int loadType, boolean hasMore, boolean failed) {
        HashtagSearchController.SearchResult result = feedResult();
        if (loadType == 0) {
            // Initial load: replace entirely. Store keeps messages in ascending
            // timeline order (oldest first) — same as channels: old at top, new at bottom.
            result.messages.clear();
            result.messages.addAll(msgs);
        } else if (loadType == 1) {
            // Newer messages: append to the END (they are newer than what's shown).
            result.messages.addAll(msgs);
        } else {
            // Older messages: prepend to the TOP (they are older than what's shown).
            result.messages.addAll(0, msgs);
        }
        result.count = result.messages.size();
        result.endReached = !hasMore;
        result.loading = loading;

        NotificationCenter.getInstance(currentAccount).postNotificationName(
                NotificationCenter.messagesDidLoad,
                0L,
                result.messages.size(),
                new ArrayList<>(result.messages),
                false,
                0, 0, 0, 0,
                loadType,
                true,
                guid,
                loadIndex,
                0, 0,
                ChatActivity.MODE_SEARCH,
                hasMore,
                failed);

        AndroidUtilities.runOnUIThread(() -> NotificationCenter.getInstance(currentAccount).postNotificationName(
                NotificationCenter.hashtagSearchUpdated,
                guid,
                result.messages.size(),
                result.endReached,
                0, 0, 0));
    }

    private void postFeedCount(int guid) {
        HashtagSearchController.SearchResult result = feedResult();
        AndroidUtilities.runOnUIThread(() -> NotificationCenter.getInstance(currentAccount).postNotificationName(
                NotificationCenter.hashtagSearchUpdated,
                guid,
                result.messages.size(),
                result.endReached,
                0, 0, 0));
    }
}