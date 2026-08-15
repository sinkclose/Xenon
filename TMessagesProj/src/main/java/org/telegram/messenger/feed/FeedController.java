package org.telegram.messenger.feed;

import android.util.SparseArray;
import android.util.SparseIntArray;
import androidx.collection.LongSparseArray;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ChatActivity;

public class FeedController implements NotificationCenter.NotificationCenterDelegate {
    private static final FeedController[] Instance = new FeedController[UserConfig.MAX_ACCOUNT_COUNT];
    private static final Object[] lockObjects = new Object[UserConfig.MAX_ACCOUNT_COUNT];
    private int attemptRounds;
    private final FeedBackfillCoordinator backfill;
    private int cachedIncludedChannelCount;
    private final int closedRefreshGuid;
    private final Runnable closedRefreshRunnable;
    private boolean closedRefreshScheduled;
    public final int currentAccount;
    private SavedScrollPosition drawerScrollPosition;
    private boolean hasChannels;
    private boolean hasIncludedChannels;
    private int heldGuid;
    private int heldLoadIndex;
    private final ArrayList<int[]> initialLoadWaiters;
    private boolean initialUnreadScrollPending;
    private final FeedTimelineLoader loader;
    private boolean loading;
    private boolean loadingNewer;
    private boolean newerPagingBoundsDirty;
    private boolean olderPagingBoundsDirty;
    private int resumedUiClients;
    private int sessionGeneration;
    private int staleEnumerationRetries;
    private final FeedStore store;
    private int uiActiveClients;
    private final FeedUnreadTracker unreadTracker;

    public interface ChannelsCallback {
        void onChannels(ArrayList<TLRPC.Chat> chats, int count, boolean failed);
    }

    static {
        for (int i = 0; i < lockObjects.length; i++) {
            lockObjects[i] = new Object();
        }
    }

    public static FeedController peekInstance(int account) {
        return Instance[account];
    }

    public static FeedController getInstance(int account) {
        FeedController result = Instance[account];
        if (result != null) {
            return result;
        }
        synchronized (lockObjects[account]) {
            result = Instance[account];
            if (result == null) {
                result = new FeedController(account);
                Instance[account] = result;
            }
            return result;
        }
    }

    public static final class SavedScrollPosition {
        public final long dialogId;
        public final int messageId;
        public final int offsetTop;

        private SavedScrollPosition(long dialogId, int messageId, int offsetTop) {
            this.dialogId = dialogId;
            this.messageId = messageId;
            this.offsetTop = offsetTop;
        }
    }

    private FeedController(int account) {
        FeedStore feedStore = new FeedStore();
        this.store = feedStore;
        this.initialUnreadScrollPending = true;
        this.initialLoadWaiters = new ArrayList<>();
        this.closedRefreshGuid = ConnectionsManager.generateClassGuid();
        this.closedRefreshRunnable = this::runClosedRefresh;
        this.currentAccount = account;
        this.unreadTracker = new FeedUnreadTracker(account, feedStore.getMessages());
        this.loader = new FeedTimelineLoader(account);
        this.backfill = new FeedBackfillCoordinator(account, this::onBackfillRoundFinished);
        AndroidUtilities.runOnUIThread(() -> lambdaNew0(account));
    }

    private void lambdaNew0(int account) {
        NotificationCenter nc = NotificationCenter.getInstance(account);
        nc.addObserver(this, NotificationCenter.messagesDidLoad);
        nc.addObserver(this, NotificationCenter.loadingMessagesFailed);
        nc.addObserver(this, NotificationCenter.messagesDeleted);
        nc.addObserver(this, NotificationCenter.historyCleared);
        nc.addObserver(this, NotificationCenter.didReceiveNewMessages);
        FeedChannelRegistry.getInstance(account).addListener(this::onFeedChannelsChanged);
    }

    private void onFeedChannelsChanged(HashSet<Long> added, HashSet<Long> removed) {
        this.loader.invalidateChannelCache();
        for (Long id : removed) {
            deleteHistory(id, Integer.MAX_VALUE);
        }
        if (added.isEmpty()) {
            NotificationCenter.getInstance(this.currentAccount).postNotificationNameOnUIThread(NotificationCenter.feedNeedReload, Boolean.FALSE);
        } else {
            reconcileChannelSet(willBeFull ->
                    NotificationCenter.getInstance(this.currentAccount).postNotificationNameOnUIThread(NotificationCenter.feedNeedReload, willBeFull));
        }
    }

    public FeedStore getStore() {
        return this.store;
    }

    public ArrayList<MessageObject> getMessages() {
        return this.store.getMessages();
    }

    public boolean isLoading() {
        return this.loading || this.loadingNewer;
    }

    public boolean hasMessagesForDialog(long dialogId) {
        return this.store.hasMessagesForDialog(dialogId);
    }

    public boolean hasChannels() {
        return this.hasChannels;
    }

    public boolean hasIncludedChannels() {
        return this.hasIncludedChannels;
    }

    public int getIncludedChannelCount() {
        return this.cachedIncludedChannelCount;
    }

    public boolean hasNoSyntheticIds() {
        return this.store.hasNoSyntheticIds();
    }

    public void setUiActive(boolean active) {
        int current = this.uiActiveClients;
        if (!active) {
            if (current == 0) {
                return;
            }
            int next = current - 1;
            this.uiActiveClients = next;
            if (next == 0) {
                cancelLoads();
                trimForInactiveCache();
            }
            return;
        }
        int next = current + 1;
        this.uiActiveClients = next;
        if (next > 1) {
            return;
        }
        if (this.closedRefreshScheduled) {
            AndroidUtilities.cancelRunOnUIThread(this.closedRefreshRunnable);
            this.closedRefreshScheduled = false;
        }
        if (this.loadingNewer) {
            cancelLoads();
        }
    }

    private boolean isUiActive() {
        return this.uiActiveClients > 0;
    }

    public void setUiResumed(boolean resumed) {
        int current = this.resumedUiClients;
        if (resumed) {
            this.resumedUiClients = current + 1;
        } else if (current > 0) {
            this.resumedUiClients = current - 1;
        }
    }

    public void clear() {
        this.sessionGeneration++;
        this.unreadTracker.clear();
        this.drawerScrollPosition = null;
        this.store.clear();
        this.loading = false;
        this.loadingNewer = false;
        this.olderPagingBoundsDirty = false;
        this.newerPagingBoundsDirty = false;
        this.attemptRounds = 0;
        this.staleEnumerationRetries = 0;
        this.initialLoadWaiters.clear();
        this.backfill.cancel();
        this.backfill.clearExhausted();
        if (this.closedRefreshScheduled) {
            AndroidUtilities.cancelRunOnUIThread(this.closedRefreshRunnable);
            this.closedRefreshScheduled = false;
        }
    }

    public void cancelLoads() {
        this.sessionGeneration++;
        this.loading = false;
        this.loadingNewer = false;
        this.olderPagingBoundsDirty = false;
        this.newerPagingBoundsDirty = false;
        this.attemptRounds = 0;
        this.staleEnumerationRetries = 0;
        this.initialLoadWaiters.clear();
        this.backfill.cancel();
    }

    private static int getInactiveCacheCap() {
        int perf = SharedConfig.getDevicePerformanceClass();
        if (perf == 0) {
            return 300;
        }
        if (perf == 2) {
            return 1000;
        }
        return 700;
    }

    public void trimForInactiveCache() {
        if (isUiActive() || this.store.isEmpty()) {
            return;
        }
        this.store.trim(getInactiveCacheCap());
    }

    public boolean isIncludedChannelPost(long dialogId) {
        if (!DialogObject.isChatDialog(dialogId)) {
            return false;
        }
        return isEligibleChannel(MessagesController.getInstance(this.currentAccount).getChat(-dialogId));
    }

    public static boolean isEligibleChannel(TLRPC.Chat chat) {
        return chat != null && ChatObject.isChannelAndNotMegaGroup(chat)
                && !ChatObject.isCommunity(chat) && !ChatObject.isNotInChat(chat);
    }

    public boolean consumeInitialUnreadScroll() {
        boolean v = this.initialUnreadScrollPending;
        this.initialUnreadScrollPending = false;
        return v;
    }

    public int getUnreadCount() {
        if (ExtraConfig.getShowFeedUnreadCounter()) {
            return this.unreadTracker.getUnreadCount();
        }
        return 0;
    }

    public void onPostSeen(long dialogId, int maxReadId) {
        this.unreadTracker.onPostSeen(dialogId, maxReadId);
    }

    public void markAllRead() {
        this.unreadTracker.markAllRead();
    }

    public int findFirstUnreadIndex(ArrayList<MessageObject> messages) {
        return this.unreadTracker.findFirstUnreadIndex(messages);
    }

    public int countUnreadBelow(ArrayList<MessageObject> messages, int fromIndex) {
        return this.unreadTracker.countUnreadBelow(messages, fromIndex);
    }

    public void saveDrawerScrollPosition(long dialogId, int messageId, int offsetTop) {
        if (dialogId == 0 || messageId <= 0) {
            return;
        }
        this.drawerScrollPosition = new SavedScrollPosition(dialogId, messageId, offsetTop);
    }

    public SavedScrollPosition getDrawerScrollPosition() {
        return this.drawerScrollPosition;
    }

    public MessageObject getMessage(long dialogId, int id) {
        return this.store.getMessage(dialogId, id);
    }

    public int resolveRealMessageId(long dialogId, int id) {
        return this.store.resolveRealMessageId(dialogId, id);
    }

    public long resolveRealDialogId(int id) {
        return this.store.resolveRealDialogId(id);
    }

    public boolean loadInitial(int guid, int loadIndex) {
        int channelCacheEpoch = this.loader.getChannelCacheEpoch();
        if (this.store.isEmpty()) {
            if (!loadMore(guid, loadIndex)) {
                this.initialLoadWaiters.add(new int[]{guid, loadIndex});
            }
            return false;
        }
        ArrayList<MessageObject> visibleMessages = this.store.getVisibleMessages();
        for (int i = 0; i < visibleMessages.size(); i++) {
            visibleMessages.get(i).viewsReloaded = false;
        }
        if (visibleMessages.isEmpty() && !this.store.isEndReached()) {
            if (!loadMore(guid, loadIndex)) {
                this.initialLoadWaiters.add(new int[]{guid, loadIndex});
            }
            return false;
        }
        final int sessionGen = this.sessionGeneration;
        final ArrayList<MessageObject> finalVisible = visibleMessages;
        MessagesStorage.getInstance(this.currentAccount).getStorageQueue().postRunnable(() -> {
            final FeedTimelineLoader.ChannelEnumeration enumeration = this.loader.enumerateChannels(sessionGen, true);
            AndroidUtilities.runOnUIThread(() -> lambdaLoadInitial2(sessionGen, enumeration, channelCacheEpoch, guid, loadIndex, finalVisible));
        });
        return true;
    }

    private void lambdaLoadInitial2(int sessionGen, FeedTimelineLoader.ChannelEnumeration enumeration, int epoch, int guid, int loadIndex, ArrayList<MessageObject> visibleMessages) {
        if (sessionGen != this.sessionGeneration) {
            return;
        }
        if (!isEnumerationCurrent(enumeration, epoch)) {
            postFeedResults(guid, loadIndex, new ArrayList<>(), 0, false, true);
            postFeedCount(guid);
        } else {
            applyEnumeration(enumeration);
            postFeedResults(guid, loadIndex, visibleMessages, 0, false, enumeration.failed);
            postFeedCount(guid);
        }
    }

    private void reconcileChannelSet(final Utilities.Callback<Boolean> callback) {
        final int sessionGen = this.sessionGeneration;
        final int epoch = this.loader.getChannelCacheEpoch();
        if (this.store.isEmpty()) {
            loadChannels((chats, count, failed) -> {
                if (callback != null) {
                    callback.run(false);
                }
            });
            return;
        }
        final HashSet<Long> loadedDialogIds = this.store.getLoadedDialogIds();
        final HashSet<Long> hiddenSnapshot = this.store.getHiddenSnapshot();
        final FeedTimelineLoader.Cursor newest = new FeedTimelineLoader.Cursor();
        final FeedTimelineLoader.Cursor oldest = new FeedTimelineLoader.Cursor();
        newest.set(this.store.getNewestCursor().date, this.store.getNewestCursor().uid, this.store.getNewestCursor().mid);
        oldest.set(this.store.getOldestCursor().date, this.store.getOldestCursor().uid, this.store.getOldestCursor().mid);
        MessagesStorage.getInstance(this.currentAccount).getStorageQueue().postRunnable(() ->
                lambdaReconcileChannelSet9(sessionGen, epoch, callback, loadedDialogIds, hiddenSnapshot, newest, oldest));
    }

    private void lambdaReconcileChannelSet9(int sessionGen, int epoch, Utilities.Callback<Boolean> callback, HashSet<Long> loadedDialogIds, HashSet<Long> hiddenSnapshot, FeedTimelineLoader.Cursor newest, FeedTimelineLoader.Cursor oldest) {
        final FeedTimelineLoader.ChannelEnumeration enumeration = this.loader.enumerateChannels(sessionGen, true);
        if (enumeration.failed) {
            AndroidUtilities.runOnUIThread(() -> lambdaReconcileChannelSet6(sessionGen, enumeration, epoch, callback));
            return;
        }
        ArrayList<Long> needWindow = new ArrayList<>();
        for (int i = 0; i < enumeration.included.size(); i++) {
            FeedTimelineLoader.ChannelSnapshot snapshot = enumeration.included.get(i);
            if (!loadedDialogIds.contains(snapshot.dialogId) || hiddenSnapshot.contains(snapshot.dialogId)) {
                needWindow.add(snapshot.dialogId);
            }
        }
        final FeedTimelineLoader.WindowPage windowPage = needWindow.isEmpty() ? null : this.loader.loadChannelWindow(needWindow, newest, oldest);
        if (windowPage != null && windowPage.failed) {
            AndroidUtilities.runOnUIThread(() -> lambdaReconcileChannelSet7(sessionGen, enumeration, epoch, callback));
            return;
        }
        final ArrayList<MessageObject> created = windowPage != null
                ? createMessageObjects(windowPage.messages, windowPage.users, windowPage.chats) : null;
        final boolean hasNewChannels = !needWindow.isEmpty();
        AndroidUtilities.runOnUIThread(() -> lambdaReconcileChannelSet8(sessionGen, enumeration, epoch, callback, windowPage, created, hasNewChannels));
    }

    private void lambdaReconcileChannelSet6(int sessionGen, FeedTimelineLoader.ChannelEnumeration enumeration, int epoch, Utilities.Callback<Boolean> callback) {
        if (sessionGen != this.sessionGeneration) {
            return;
        }
        if (!isEnumerationCurrent(enumeration, epoch) && canRetryStaleEnumeration()) {
            reconcileChannelSet(callback);
        } else if (callback != null) {
            callback.run(false);
        }
    }

    private void lambdaReconcileChannelSet7(int sessionGen, FeedTimelineLoader.ChannelEnumeration enumeration, int epoch, Utilities.Callback<Boolean> callback) {
        if (sessionGen != this.sessionGeneration) {
            return;
        }
        if (!isEnumerationCurrent(enumeration, epoch) && canRetryStaleEnumeration()) {
            reconcileChannelSet(callback);
        } else if (callback != null) {
            callback.run(false);
        }
    }

    private void lambdaReconcileChannelSet8(int sessionGen, FeedTimelineLoader.ChannelEnumeration enumeration, int epoch, Utilities.Callback<Boolean> callback, FeedTimelineLoader.WindowPage windowPage, ArrayList<MessageObject> created, boolean hasNewChannels) {
        if (sessionGen != this.sessionGeneration) {
            return;
        }
        if (!isEnumerationCurrent(enumeration, epoch) && canRetryStaleEnumeration()) {
            reconcileChannelSet(callback);
            return;
        }
        applyEnumeration(enumeration);
        HashSet<Long> includedIds = new HashSet<>();
        for (int i = 0; i < enumeration.included.size(); i++) {
            includedIds.add(enumeration.included.get(i).dialogId);
        }
        this.store.applyIncludedDialogs(includedIds);
        boolean truncated = windowPage != null && windowPage.truncated;
        if (windowPage != null && !truncated && !created.isEmpty()) {
            MessagesController controller = MessagesController.getInstance(this.currentAccount);
            controller.putUsers(windowPage.users, true);
            controller.putChats(windowPage.chats, true);
            this.store.mergeRows(created);
        }
        if (hasNewChannels) {
            this.store.setEndReached(false);
            if (this.loading) {
                this.olderPagingBoundsDirty = true;
            }
            if (this.loadingNewer) {
                this.newerPagingBoundsDirty = true;
            }
        }
        if (callback != null) {
            callback.run(truncated);
        }
    }

    public void refreshReadState(final Runnable callback) {
        final int sessionGen = this.sessionGeneration;
        final int epoch = this.loader.getChannelCacheEpoch();
        MessagesStorage.getInstance(this.currentAccount).getStorageQueue().postRunnable(() -> {
            final FeedTimelineLoader.ChannelEnumeration enumeration = this.loader.enumerateChannels(sessionGen, true);
            AndroidUtilities.runOnUIThread(() -> lambdaRefreshReadState10(sessionGen, enumeration, epoch, callback));
        });
    }

    private void lambdaRefreshReadState10(int sessionGen, FeedTimelineLoader.ChannelEnumeration enumeration, int epoch, Runnable callback) {
        if (sessionGen != this.sessionGeneration) {
            return;
        }
        if (isEnumerationCurrent(enumeration, epoch)) {
            applyEnumeration(enumeration);
        }
        if (callback != null) {
            callback.run();
        }
    }

    public boolean loadMore(int guid, int loadIndex) {
        if (this.loading || (this.store.isEndReached() && !this.store.getOldestCursor().isEmpty())) {
            return false;
        }
        this.loading = true;
        this.heldGuid = guid;
        this.heldLoadIndex = loadIndex;
        this.attemptRounds = 0;
        runAttempt();
        return true;
    }

    /** Alias kept for callers that historically used loadOlder (ChatActivity paging). */
    public boolean loadOlder(int guid, int loadIndex) {
        return loadMore(guid, loadIndex);
    }

    private void runAttempt() {
        final int guid = this.heldGuid;
        final int loadIndex = this.heldLoadIndex;
        final int sessionGen = this.sessionGeneration;
        final int epoch = this.loader.getChannelCacheEpoch();
        final boolean cursorEmpty = this.store.getOldestCursor().isEmpty();
        final FeedTimelineLoader.Cursor cursor = new FeedTimelineLoader.Cursor();
        cursor.set(this.store.getOldestCursor().date, this.store.getOldestCursor().uid, this.store.getOldestCursor().mid);
        final HashSet<Long> exhaustedSnapshot = this.backfill.getExhaustedSnapshot();
        MessagesStorage.getInstance(this.currentAccount).getStorageQueue().postRunnable(() ->
                lambdaRunAttempt15(sessionGen, epoch, guid, loadIndex, cursor, exhaustedSnapshot, cursorEmpty));
    }

    private void lambdaRunAttempt15(int sessionGen, int epoch, int guid, int loadIndex, FeedTimelineLoader.Cursor cursor, HashSet<Long> exhausted, boolean cursorEmpty) {
        final FeedTimelineLoader.ChannelEnumeration enumeration = this.loader.enumerateChannels(sessionGen, false);
        ArrayList<FeedTimelineLoader.ChannelSnapshot> included = enumeration.included;
        if (enumeration.failed) {
            AndroidUtilities.runOnUIThread(() -> lambdaRunAttempt12(sessionGen, enumeration, epoch, guid, loadIndex));
        } else {
            if (included.isEmpty()) {
                AndroidUtilities.runOnUIThread(() -> lambdaRunAttempt13(sessionGen, enumeration, epoch, guid, loadIndex));
                return;
            }
            final FeedTimelineLoader.OlderPage olderPage = this.loader.loadOlderPage(included, cursor, exhausted);
            final ArrayList<MessageObject> created = createMessageObjects(olderPage.messages, olderPage.users, olderPage.chats);
            AndroidUtilities.runOnUIThread(() -> lambdaRunAttempt14(sessionGen, enumeration, epoch, olderPage, guid, loadIndex, cursorEmpty, created));
        }
    }

    private void lambdaRunAttempt12(int sessionGen, FeedTimelineLoader.ChannelEnumeration enumeration, int epoch, int guid, int loadIndex) {
        if (sessionGen != this.sessionGeneration) {
            return;
        }
        if (!isEnumerationCurrent(enumeration, epoch) && canRetryStaleEnumeration()) {
            this.attemptRounds = 0;
            runAttempt();
        } else {
            this.loading = false;
            postFeedResults(guid, loadIndex, new ArrayList<>(), 2, false, true);
            postFeedCount(guid);
            flushInitialLoadWaiters(true);
        }
    }

    private void lambdaRunAttempt13(int sessionGen, FeedTimelineLoader.ChannelEnumeration enumeration, int epoch, int guid, int loadIndex) {
        if (sessionGen != this.sessionGeneration) {
            return;
        }
        if (!isEnumerationCurrent(enumeration, epoch)) {
            if (canRetryStaleEnumeration()) {
                this.olderPagingBoundsDirty = false;
                this.attemptRounds = 0;
                runAttempt();
                return;
            } else {
                this.loading = false;
                postFeedResults(guid, loadIndex, new ArrayList<>(), 2, false, true);
                postFeedCount(guid);
                flushInitialLoadWaiters(true);
                return;
            }
        }
        applyEnumeration(enumeration);
        this.olderPagingBoundsDirty = false;
        this.unreadTracker.clear();
        this.loading = false;
        this.store.setEndReached(true);
        postFeedResults(guid, loadIndex, new ArrayList<>(), 2);
        postFeedCount(guid);
        flushInitialLoadWaiters(false);
    }

    private void lambdaRunAttempt14(int sessionGen, FeedTimelineLoader.ChannelEnumeration enumeration, int epoch, FeedTimelineLoader.OlderPage olderPage, int guid, int loadIndex, boolean cursorEmpty, ArrayList<MessageObject> created) {
        if (sessionGen != this.sessionGeneration) {
            return;
        }
        if (!isEnumerationCurrent(enumeration, epoch) && canRetryStaleEnumeration()) {
            this.olderPagingBoundsDirty = false;
            this.attemptRounds = 0;
            runAttempt();
            return;
        }
        if (this.olderPagingBoundsDirty) {
            this.olderPagingBoundsDirty = false;
            this.attemptRounds = 0;
            runAttempt();
            return;
        }
        applyEnumeration(enumeration);
        MessagesController controller = MessagesController.getInstance(this.currentAccount);
        if (olderPage.failed) {
            this.loading = false;
            postFeedResults(guid, loadIndex, new ArrayList<>(), 2, false, true);
            postFeedCount(guid);
            flushInitialLoadWaiters(true);
            return;
        }
        FeedTimelineLoader.Cursor oldestCursor = this.store.getOldestCursor();
        FeedTimelineLoader.Cursor last = olderPage.last;
        oldestCursor.set(last.date, last.uid, last.mid);
        if (cursorEmpty && !olderPage.first.isEmpty()) {
            FeedTimelineLoader.Cursor newestCursor = this.store.getNewestCursor();
            FeedTimelineLoader.Cursor first = olderPage.first;
            newestCursor.set(first.date, first.uid, first.mid);
        }
        controller.putUsers(olderPage.users, true);
        controller.putChats(olderPage.chats, true);
        ArrayList<MessageObject> appended = this.store.appendMessages(created, false);
        if (appended.isEmpty() && olderPage.lastChunkRowCount == 30) {
            runAttempt();
            return;
        }
        boolean endReached = !olderPage.hasIncomplete && olderPage.lastChunkRowCount < 30;
        if (!appended.isEmpty() || endReached || olderPage.backfillCandidates.isEmpty() || this.attemptRounds >= 3) {
            this.loading = false;
            this.store.setEndReached(endReached);
            postFeedResults(guid, loadIndex, appended, 2);
            postFeedCount(guid);
            flushInitialLoadWaiters(false);
            return;
        }
        this.attemptRounds++;
        this.backfill.startRound(olderPage.backfillCandidates);
    }

    private void postFeedResults(int guid, int loadIndex, ArrayList<MessageObject> messages, int loadType) {
        postFeedResults(guid, loadIndex, messages, loadType, false, false);
    }

    private void postFeedResults(int guid, int loadIndex, ArrayList<MessageObject> messages, int loadType, boolean hasMore, boolean failed) {
        NotificationCenter.getInstance(this.currentAccount).postNotificationNameOnUIThread(NotificationCenter.messagesDidLoad,
                0L, messages.size(), messages, false, 0, 0, 0, 0, loadType, true, guid, loadIndex, 0, 0, ChatActivity.MODE_SEARCH, hasMore, failed);
    }

    private void postFeedCount(int guid) {
        NotificationCenter.getInstance(this.currentAccount).postNotificationNameOnUIThread(NotificationCenter.hashtagSearchUpdated,
                guid, this.store.getCount(), this.store.isEndReached(), 0, 0, 0);
    }

    private boolean isEnumerationCurrent(FeedTimelineLoader.ChannelEnumeration enumeration, int epoch) {
        boolean current = this.loader.isEnumerationCurrent(enumeration)
                && enumeration.cacheEpoch == epoch;
        if (current) {
            this.staleEnumerationRetries = 0;
        }
        return current;
    }

    private boolean canRetryStaleEnumeration() {
        int retries = this.staleEnumerationRetries;
        if (retries >= 3) {
            this.staleEnumerationRetries = 0;
            return false;
        }
        this.staleEnumerationRetries = retries + 1;
        return true;
    }

    private void applyEnumeration(FeedTimelineLoader.ChannelEnumeration enumeration) {
        if (enumeration.failed) {
            return;
        }
        this.hasChannels = enumeration.hasChannels;
        this.hasIncludedChannels = !enumeration.included.isEmpty();
        this.cachedIncludedChannelCount = enumeration.included.size();
        for (int i = 0; i < enumeration.included.size(); i++) {
            FeedTimelineLoader.ChannelSnapshot snapshot = enumeration.included.get(i);
            int readInboxMax = snapshot.readInboxMax;
            if (readInboxMax <= 0 && snapshot.unreadCount <= 0) {
                readInboxMax = snapshot.topMessage;
            }
            this.unreadTracker.applyReadInboxMax(snapshot.dialogId, readInboxMax);
        }
    }

    private void flushInitialLoadWaiters(boolean failed) {
        if (this.initialLoadWaiters.isEmpty()) {
            return;
        }
        ArrayList<int[]> waiters = new ArrayList<>(this.initialLoadWaiters);
        this.initialLoadWaiters.clear();
        ArrayList<MessageObject> visibleMessages = this.store.getVisibleMessages();
        for (int i = 0; i < waiters.size(); i++) {
            int[] entry = waiters.get(i);
            postFeedResults(entry[0], entry[1], visibleMessages, 0, false, failed);
            postFeedCount(entry[0]);
        }
    }

    private void onBackfillRoundFinished() {
        if (this.loading) {
            runAttempt();
        }
    }

    public boolean loadNewer(int guid, int loadIndex) {
        if (this.loadingNewer || this.store.getNewestCursor().isEmpty()) {
            return false;
        }
        this.loadingNewer = true;
        runLoadNewer(guid, loadIndex);
        return true;
    }

    private void runLoadNewer(final int guid, final int loadIndex) {
        final int sessionGen = this.sessionGeneration;
        final int epoch = this.loader.getChannelCacheEpoch();
        final FeedTimelineLoader.Cursor cursor = new FeedTimelineLoader.Cursor();
        cursor.set(this.store.getNewestCursor().date, this.store.getNewestCursor().uid, this.store.getNewestCursor().mid);
        MessagesStorage.getInstance(this.currentAccount).getStorageQueue().postRunnable(() ->
                lambdaRunLoadNewer19(sessionGen, epoch, guid, loadIndex, cursor));
    }

    private void lambdaRunLoadNewer19(int sessionGen, int epoch, int guid, int loadIndex, FeedTimelineLoader.Cursor cursor) {
        final FeedTimelineLoader.ChannelEnumeration enumeration = this.loader.enumerateChannels(sessionGen, false);
        ArrayList<FeedTimelineLoader.ChannelSnapshot> included = enumeration.included;
        if (enumeration.failed) {
            AndroidUtilities.runOnUIThread(() -> lambdaRunLoadNewer16(sessionGen, enumeration, epoch, guid, loadIndex));
        } else {
            if (included.isEmpty()) {
                AndroidUtilities.runOnUIThread(() -> lambdaRunLoadNewer17(sessionGen, enumeration, epoch, guid, loadIndex));
                return;
            }
            final FeedTimelineLoader.NewerPage newerPage = this.loader.loadNewerPage(included, cursor);
            final ArrayList<MessageObject> created = createMessageObjects(newerPage.messages, newerPage.users, newerPage.chats);
            AndroidUtilities.runOnUIThread(() -> lambdaRunLoadNewer18(sessionGen, enumeration, epoch, guid, loadIndex, newerPage, created));
        }
    }

    private void lambdaRunLoadNewer16(int sessionGen, FeedTimelineLoader.ChannelEnumeration enumeration, int epoch, int guid, int loadIndex) {
        if (sessionGen != this.sessionGeneration) {
            return;
        }
        if (!isEnumerationCurrent(enumeration, epoch) && canRetryStaleEnumeration()) {
            runLoadNewer(guid, loadIndex);
        } else {
            this.loadingNewer = false;
            postNewerMessagesLoaded(guid, loadIndex, null, false, true);
        }
    }

    private void lambdaRunLoadNewer17(int sessionGen, FeedTimelineLoader.ChannelEnumeration enumeration, int epoch, int guid, int loadIndex) {
        if (sessionGen != this.sessionGeneration) {
            return;
        }
        if (!isEnumerationCurrent(enumeration, epoch) && canRetryStaleEnumeration()) {
            this.newerPagingBoundsDirty = false;
            runLoadNewer(guid, loadIndex);
        } else {
            this.newerPagingBoundsDirty = false;
            this.loadingNewer = false;
            postNewerMessagesLoaded(guid, loadIndex, null, false);
            postFeedCount(guid);
        }
    }

    private void lambdaRunLoadNewer18(int sessionGen, FeedTimelineLoader.ChannelEnumeration enumeration, int epoch, int guid, int loadIndex, FeedTimelineLoader.NewerPage newerPage, ArrayList<MessageObject> created) {
        if (sessionGen != this.sessionGeneration) {
            return;
        }
        if (!isEnumerationCurrent(enumeration, epoch) && canRetryStaleEnumeration()) {
            this.newerPagingBoundsDirty = false;
            runLoadNewer(guid, loadIndex);
            return;
        }
        if (this.newerPagingBoundsDirty) {
            this.newerPagingBoundsDirty = false;
            if (this.store.getNewestCursor().isEmpty()) {
                this.loadingNewer = false;
                postNewerMessagesLoaded(guid, loadIndex, null, false);
                postFeedCount(guid);
                return;
            }
            runLoadNewer(guid, loadIndex);
            return;
        }
        this.loadingNewer = false;
        applyEnumeration(enumeration);
        if (newerPage.failed) {
            postNewerMessagesLoaded(guid, loadIndex, null, false, true);
            return;
        }
        FeedTimelineLoader.Cursor newestCursor = this.store.getNewestCursor();
        FeedTimelineLoader.Cursor first = newerPage.first;
        newestCursor.set(first.date, first.uid, first.mid);
        if (newerPage.messages.isEmpty()) {
            postNewerMessagesLoaded(guid, loadIndex, null, newerPage.hasMore);
            if (!newerPage.hasMore) {
                postFeedCount(guid);
            }
            return;
        }
        MessagesController controller = MessagesController.getInstance(this.currentAccount);
        controller.putUsers(newerPage.users, true);
        controller.putChats(newerPage.chats, true);
        postNewerMessagesLoaded(guid, loadIndex, this.store.appendMessages(created, true), newerPage.hasMore);
        if (!newerPage.hasMore) {
            postFeedCount(guid);
        }
        trimForInactiveCache();
    }

    private void postNewerMessagesLoaded(int guid, int loadIndex, ArrayList<MessageObject> messages, boolean hasMore) {
        postNewerMessagesLoaded(guid, loadIndex, messages, hasMore, false);
    }

    private void postNewerMessagesLoaded(int guid, int loadIndex, ArrayList<MessageObject> messages, boolean hasMore, boolean failed) {
        ArrayList<MessageObject> reversed = new ArrayList<>();
        int loadType;
        if (messages == null || messages.isEmpty()) {
            loadType = 0;
        } else {
            reversed.addAll(messages);
            Collections.reverse(reversed);
            loadType = 1;
        }
        postFeedResults(guid, loadIndex, reversed, loadType, hasMore, failed);
    }

    private ArrayList<MessageObject> createMessageObjects(ArrayList<TLRPC.Message> messages, ArrayList<TLRPC.User> users, ArrayList<TLRPC.Chat> chats) {
        HashMap<Long, TLRPC.User> userMap = new HashMap<>();
        HashMap<Long, TLRPC.Chat> chatMap = new HashMap<>();
        for (int i = 0; i < users.size(); i++) {
            TLRPC.User user = users.get(i);
            userMap.put(user.id, user);
        }
        for (int i = 0; i < chats.size(); i++) {
            TLRPC.Chat chat = chats.get(i);
            chatMap.put(chat.id, chat);
        }
        ArrayList<MessageObject> result = new ArrayList<>(messages.size());
        for (int i = 0; i < messages.size(); i++) {
            result.add(new MessageObject(this.currentAccount, messages.get(i), null, userMap, chatMap, null, null, true, true, 0L, false, false, false, ChatActivity.SEARCH_FEED));
        }
        return result;
    }

    public void loadChannels(ChannelsCallback callback) {
        loadChannels(false, callback);
    }

    public void loadChannels(final boolean force, final ChannelsCallback callback) {
        final int sessionGen = this.sessionGeneration;
        final int epoch = this.loader.getChannelCacheEpoch();
        MessagesStorage.getInstance(this.currentAccount).getStorageQueue().postRunnable(() -> {
            final FeedTimelineLoader.ChannelEnumeration enumeration = this.loader.enumerateChannels(sessionGen, force);
            AndroidUtilities.runOnUIThread(() -> lambdaLoadChannels20(sessionGen, enumeration, epoch, callback));
        });
    }

    private void lambdaLoadChannels20(int sessionGen, FeedTimelineLoader.ChannelEnumeration enumeration, int epoch, ChannelsCallback callback) {
        if (sessionGen != this.sessionGeneration) {
            return;
        }
        if (!isEnumerationCurrent(enumeration, epoch)) {
            if (callback != null) {
                callback.onChannels(new ArrayList<>(), 0, true);
            }
        } else {
            applyEnumeration(enumeration);
            if (!enumeration.failed) {
                MessagesController.getInstance(this.currentAccount).putChats(enumeration.channels, true);
            }
            if (callback != null) {
                callback.onChannels(enumeration.channels, enumeration.included.size(), enumeration.failed);
            }
        }
    }

    public void replaceMessage(MessageObject oldMsg, MessageObject newMsg) {
        this.store.replaceMessage(oldMsg, newMsg);
    }

    public ArrayList<Integer> deleteMessages(long dialogId, ArrayList<Integer> messageIds) {
        boolean[] changed = new boolean[1];
        ArrayList<Integer> deleted = this.store.deleteMessages(dialogId, messageIds, changed);
        if (changed[0]) {
            onFeedRowsRemoved();
        }
        return deleted;
    }

    public ArrayList<Integer> deleteHistory(long dialogId, int maxId) {
        boolean[] changed = new boolean[1];
        ArrayList<Integer> deleted = this.store.deleteHistory(dialogId, maxId, changed);
        if (changed[0]) {
            onFeedRowsRemoved();
        }
        return deleted;
    }

    private void onFeedRowsRemoved() {
        if (this.loading) {
            this.olderPagingBoundsDirty = true;
        }
        if (this.loadingNewer) {
            this.newerPagingBoundsDirty = true;
        }
    }

    public ArrayList<MessageObject> updateViews(LongSparseArray<SparseIntArray> views, LongSparseArray<SparseIntArray> forwards,
                                                LongSparseArray<SparseArray<TLRPC.MessageReplies>> replies, boolean incremental) {
        ArrayList<MessageObject> updated = new ArrayList<>();
        updateCounters(views, true, updated);
        updateCounters(forwards, false, updated);
        updateReplies(replies, incremental, updated);
        return updated;
    }

    private void updateCounters(LongSparseArray<SparseIntArray> map, boolean views, ArrayList<MessageObject> out) {
        if (map == null) {
            return;
        }
        for (int i = 0; i < map.size(); i++) {
            long dialogId = map.keyAt(i);
            SparseIntArray byId = map.valueAt(i);
            for (int j = 0; j < byId.size(); j++) {
                MessageObject message = getMessage(dialogId, byId.keyAt(j));
                if (message != null) {
                    int value = byId.valueAt(j);
                    TLRPC.Message owner = message.messageOwner;
                    if (views) {
                        if (value > owner.views) {
                            owner.views = value;
                            addUpdated(out, message);
                        }
                    } else if (value > owner.forwards) {
                        owner.forwards = value;
                        addUpdated(out, message);
                    }
                }
            }
        }
    }

    private void updateReplies(LongSparseArray<SparseArray<TLRPC.MessageReplies>> map, boolean incremental, ArrayList<MessageObject> out) {
        if (map == null) {
            return;
        }
        for (int i = 0; i < map.size(); i++) {
            long dialogId = map.keyAt(i);
            SparseArray<TLRPC.MessageReplies> byId = map.valueAt(i);
            for (int j = 0; j < byId.size(); j++) {
                MessageObject message = getMessage(dialogId, byId.keyAt(j));
                TLRPC.MessageReplies replyUpdate = byId.valueAt(j);
                if (message == null || replyUpdate == null) {
                    continue;
                }
                TLRPC.Message owner = message.messageOwner;
                if (incremental) {
                    if (owner.replies == null) {
                        owner.replies = new TLRPC.TL_messageReplies();
                    }
                    owner.replies.replies += replyUpdate.replies;
                    for (int k = 0; k < replyUpdate.recent_repliers.size(); k++) {
                        owner.replies.recent_repliers.remove(replyUpdate.recent_repliers.get(k));
                    }
                    owner.replies.recent_repliers.addAll(0, replyUpdate.recent_repliers);
                    while (owner.replies.recent_repliers.size() > 3) {
                        owner.replies.recent_repliers.remove(0);
                    }
                } else {
                    TLRPC.MessageReplies existing = owner.replies;
                    if (existing == null || replyUpdate.replies_pts > existing.replies_pts
                            || replyUpdate.read_max_id > existing.read_max_id
                            || replyUpdate.max_id > existing.max_id) {
                        owner.replies = replyUpdate;
                    }
                }
                message.animateComments = true;
                addUpdated(out, message);
            }
        }
    }

    private static void addUpdated(ArrayList<MessageObject> out, MessageObject message) {
        if (!out.contains(message)) {
            out.add(message);
        }
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (account != this.currentAccount) {
            return;
        }
        if (id == NotificationCenter.messagesDidLoad) {
            this.backfill.onMessagesDidLoad(args);
        } else if (id == NotificationCenter.loadingMessagesFailed) {
            this.backfill.onLoadingMessagesFailed(args);
        } else if (id == NotificationCenter.messagesDeleted) {
            if (isUiActive() || ((Boolean) args[2])) {
                return;
            }
            long dialogId = ((Long) args[1]);
            if (dialogId == 0) {
                return;
            }
            if (dialogId > 0) {
                dialogId = -dialogId;
            }
            deleteMessages(dialogId, (ArrayList<Integer>) args[0]);
        } else if (id == NotificationCenter.historyCleared) {
            if (isUiActive()) {
                return;
            }
            long dialogId = ((Long) args[0]);
            if (DialogObject.isChatDialog(dialogId)) {
                deleteHistory(dialogId, ((Integer) args[1]));
            }
        } else if (id == NotificationCenter.didReceiveNewMessages) {
            if (isUiActive() || ((Boolean) args[2]) || this.store.isEmpty()
                    || this.store.getNewestCursor().isEmpty()
                    || !isIncludedChannelPost(((Long) args[0]))) {
                return;
            }
            scheduleClosedRefresh();
        }
    }

    private void scheduleClosedRefresh() {
        if (this.closedRefreshScheduled) {
            return;
        }
        this.closedRefreshScheduled = true;
        AndroidUtilities.runOnUIThread(this.closedRefreshRunnable, 1000L);
    }

    private void runClosedRefresh() {
        this.closedRefreshScheduled = false;
        if (isUiActive() || this.loadingNewer || this.store.isEmpty() || this.store.getNewestCursor().isEmpty()) {
            return;
        }
        loadNewer(this.closedRefreshGuid, 0);
    }
}
