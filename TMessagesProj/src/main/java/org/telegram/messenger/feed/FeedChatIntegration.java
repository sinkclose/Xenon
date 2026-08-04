package org.telegram.messenger.feed;

import androidx.collection.LongSparseArray;
import java.util.ArrayList;
import java.util.HashSet;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MediaDataController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.RequestDelegate;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_update;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.BulletinFactory;

public class FeedChatIntegration {
    private Runnable channelsChangedCallback;
    private final int currentAccount;
    private boolean destroyed;
    private final Host host;
    private boolean initialScrollApplied;
    private boolean pagedownShownByScroll;
    private boolean pendingDividerScroll;
    private long pendingHideDialogId;
    private ScrollAnchor pendingInitialScrollRestore;
    private boolean reactionsRefreshScheduled;
    private boolean readyToMarkAsRead;
    private final boolean restoreDrawerScrollPosition;
    private boolean scrollPreservedNewerToUnread;
    private boolean settleAtNewestScheduled;
    private int totalScrollDy;
    private MessageObject unreadDivider;
    private boolean viewportActive;
    private static final int PAGEDOWN_SCROLL_THRESHOLD = AndroidUtilities.dp(100.0f);
    private static final int NEAR_NEWEST_THRESHOLD = AndroidUtilities.dp(160.0f);
    private int preserveScrollLoadIndex = -1;
    private int lastPagedownCount = -1;
    private final Runnable settleAtNewestRunnable = new Runnable() {
        @Override // java.lang.Runnable
        public final void run() {
            FeedChatIntegration.this.settleAtNewestNow();
        }
    };
    private final int reactionsRequestGuid = ConnectionsManager.generateClassGuid();
    private final LongSparseArray<Long> reactionsLastCheckTimes = new LongSparseArray<>();
    private final LongSparseArray<ArrayList<Integer>> pendingReactionIds = new LongSparseArray<>();
    private final Runnable reactionsRefreshRunnable = new Runnable() {
        @Override // java.lang.Runnable
        public final void run() {
            FeedChatIntegration.this.flushReactionsRefresh();
        }
    };

    public interface Host {
        boolean canScrollToNewer();

        ScrollAnchor captureScrollAnchor();

        void deleteRows(ArrayList<Integer> arrayList);

        int getDistanceToNewerPx();

        BaseFragment getFragment();

        int getLastVisibleMessageIndex();

        ArrayList<MessageObject> getMessages();

        int getNewestVisibleMessageIndex();

        void invalidateVisiblePart();

        boolean isFirstLoadComplete();

        boolean isListReady();

        boolean isListScrollIdle();

        boolean isPagedownButtonVisible();

        boolean isScrollAnimationRunning();

        void materializeRow(MessageObject messageObject);

        int nextStableId();

        void notifyAllMessagesChanged();

        void notifyMessageInserted(int i);

        void notifyMessageRemoved(int i);

        void onFeedListChanged();

        void reloadFeed();

        void requestOlderFeedPage();

        void restoreScrollAnchor(ScrollAnchor scrollAnchor);

        void scrollToMessage(int i, int i2);

        void scrollToMessageAnimated(int i, int i2);

        void setPagedownButtonVisible(boolean z);

        void setPagedownCount(int i);

        void showEmptyFeedProgress();

        void showEmptyFeedState();

        int stableIdForDateHeader(int i);
    }

    public static final class ScrollAnchor {
        public final int offsetTop;
        public final MessageObject row;

        public ScrollAnchor(MessageObject messageObject, int i) {
            this.row = messageObject;
            this.offsetTop = i;
        }
    }

    public void settleAtNewestNow() {
        this.settleAtNewestScheduled = false;
        if (this.destroyed || !this.viewportActive || !this.host.isListReady() || this.host.isScrollAnimationRunning() || this.host.canScrollToNewer()) {
            return;
        }
        settleUnreadDivider();
    }

    public FeedChatIntegration(int i, Host host, boolean z) {
        this.currentAccount = i;
        this.host = host;
        this.restoreDrawerScrollPosition = z;
    }

    public void refreshAds() {
        requestPendingInitialPosition();
    }

    public void setChannelsChangedCallback(Runnable runnable) {
        this.channelsChangedCallback = runnable;
    }

    public void notifyChannelsChanged() {
        Runnable runnable = this.channelsChangedCallback;
        if (runnable != null) {
            runnable.run();
        }
    }

    public void resetUiState() {
        resetMetadataRefresh();
        this.initialScrollApplied = false;
        this.readyToMarkAsRead = false;
        this.pendingDividerScroll = false;
        this.pendingInitialScrollRestore = null;
        this.scrollPreservedNewerToUnread = false;
        this.preserveScrollLoadIndex = -1;
        this.unreadDivider = null;
        this.lastPagedownCount = -1;
        this.pagedownShownByScroll = false;
        this.totalScrollDy = 0;
        this.pendingHideDialogId = 0L;
        if (this.settleAtNewestScheduled) {
            AndroidUtilities.cancelRunOnUIThread(this.settleAtNewestRunnable);
            this.settleAtNewestScheduled = false;
        }
    }

    private boolean hasMaterializedPostRows() {
        ArrayList<MessageObject> messages = this.host.getMessages();
        for (int i = 0; i < messages.size(); i++) {
            if (FeedMessageUtils.isPostRow(messages.get(i))) {
                return true;
            }
        }
        return false;
    }

    public void onMessagesLoaded() {
        if (!this.host.getFragment().isPaused() && this.host.isListReady() && hasMaterializedPostRows()) {
            if (!this.initialScrollApplied) {
                this.initialScrollApplied = true;
                FeedController feedController = FeedController.getInstance(this.currentAccount);
                boolean zConsumeInitialUnreadScroll = feedController.consumeInitialUnreadScroll();
                FeedController.SavedScrollPosition drawerScrollPosition = this.restoreDrawerScrollPosition ? feedController.getDrawerScrollPosition() : null;
                MessageObject message = drawerScrollPosition != null ? feedController.getMessage(drawerScrollPosition.dialogId, drawerScrollPosition.messageId) : null;
                if (message != null && this.host.getMessages().contains(message)) {
                    applyUnreadDivider(false);
                    refreshAds();
                    this.pendingInitialScrollRestore = new ScrollAnchor(message, drawerScrollPosition.offsetTop);
                    requestPendingInitialPosition();
                } else {
                    applyUnreadDivider((zConsumeInitialUnreadScroll || this.host.getDistanceToNewerPx() > NEAR_NEWEST_THRESHOLD) ? zConsumeInitialUnreadScroll : true);
                }
            }
            refreshAds();
        }
    }

    public void onHostResumed() {
        if (!this.host.getMessages().isEmpty()) {
            onMessagesLoaded();
        }
        requestPendingInitialPosition();
    }

    private boolean hasPendingInitialPosition() {
        return this.pendingInitialScrollRestore != null || this.pendingDividerScroll;
    }

    private void requestPendingInitialPosition() {
        if (this.host.getFragment().isPaused() || !this.host.isListReady()) {
            return;
        }
        ScrollAnchor scrollAnchor = this.pendingInitialScrollRestore;
        if (scrollAnchor != null) {
            this.host.restoreScrollAnchor(scrollAnchor);
            return;
        }
        if (this.pendingDividerScroll) {
            int iIndexOf = this.unreadDivider == null ? -1 : this.host.getMessages().indexOf(this.unreadDivider);
            if (iIndexOf < 0) {
                this.pendingDividerScroll = false;
                this.readyToMarkAsRead = true;
            } else {
                this.host.scrollToMessage(iIndexOf, AndroidUtilities.dp(48.0f));
            }
        }
    }

    public void setViewportActive(boolean z) {
        if (this.viewportActive == z) {
            return;
        }
        this.viewportActive = z;
        if (!z) {
            if (this.settleAtNewestScheduled) {
                AndroidUtilities.cancelRunOnUIThread(this.settleAtNewestRunnable);
                this.settleAtNewestScheduled = false;
            }
            cancelPendingReactionsRefresh();
            return;
        }
        onHostResumed();
        if (this.host.getMessages().isEmpty()) {
            return;
        }
        onVisiblePartInvalidated();
    }

    public boolean canMarkVisibleAsRead() {
        return this.viewportActive && !this.host.getFragment().isPaused() && this.initialScrollApplied && this.readyToMarkAsRead && !this.pendingDividerScroll && this.pendingInitialScrollRestore == null && !BaseFragment.hasSheets(this.host.getFragment());
    }

    public void onPostCellVisible(MessageObject messageObject, boolean z, boolean z2) {
        if (messageObject == null || messageObject.isSponsored()) {
            return;
        }
        requestReactionsRefresh(messageObject);
        if (canMarkVisibleAsRead()) {
            if (z || z2) {
                FeedController.getInstance(this.currentAccount).onPostSeen(messageObject.getDialogId(), messageObject.getRealId());
            }
        }
    }

    private void requestReactionsRefresh(MessageObject messageObject) {
        if (this.destroyed || !this.viewportActive || messageObject.messageOwner == null) {
            return;
        }
        int realId = messageObject.getRealId();
        long dialogId = messageObject.getDialogId();
        if (realId <= 0 || dialogId == 0) {
            return;
        }
        int id = messageObject.getId();
        if (messageObject.messageOwner.action == null || messageObject.canSetReaction()) {
            long jCurrentTimeMillis = System.currentTimeMillis();
            long j = id;
            if (jCurrentTimeMillis - this.reactionsLastCheckTimes.get(j, 0L).longValue() <= 15000) {
                return;
            }
            this.reactionsLastCheckTimes.put(j, Long.valueOf(jCurrentTimeMillis));
            ArrayList<Integer> arrayList = this.pendingReactionIds.get(dialogId);
            if (arrayList == null) {
                arrayList = new ArrayList<>();
                this.pendingReactionIds.put(dialogId, arrayList);
            }
            arrayList.add(Integer.valueOf(realId));
            if (this.reactionsRefreshScheduled) {
                return;
            }
            this.reactionsRefreshScheduled = true;
            AndroidUtilities.runOnUIThread(this.reactionsRefreshRunnable);
        }
    }

    public void flushReactionsRefresh() {
        this.reactionsRefreshScheduled = false;
        if (this.destroyed || !this.viewportActive) {
            this.pendingReactionIds.clear();
            return;
        }
        for (int i = 0; i < this.pendingReactionIds.size(); i++) {
            TLRPC.TL_messages_getMessagesReactions tL_messages_getMessagesReactions = new TLRPC.TL_messages_getMessagesReactions();
            tL_messages_getMessagesReactions.peer = MessagesController.getInstance(this.currentAccount).getInputPeer(this.pendingReactionIds.keyAt(i));
            tL_messages_getMessagesReactions.id.addAll(this.pendingReactionIds.valueAt(i));
            ConnectionsManager.getInstance(this.currentAccount).bindRequestToGuid(ConnectionsManager.getInstance(this.currentAccount).sendRequest(tL_messages_getMessagesReactions, new RequestDelegate() {
                @Override // org.telegram.tgnet.RequestDelegate
                public final void run(TLObject tLObject, TLRPC.TL_error tL_error) {
                    FeedChatIntegration.this.lambda$flushReactionsRefresh$0(tLObject, tL_error);
                }
            }), this.reactionsRequestGuid);
        }
        this.pendingReactionIds.clear();
    }

    public /* synthetic */ void lambda$flushReactionsRefresh$0(TLObject tLObject, TLRPC.TL_error tL_error) {
        if (tLObject instanceof TLRPC.Updates) {
            TLRPC.Updates updates = (TLRPC.Updates) tLObject;
            for (int i = 0; i < updates.updates.size(); i++) {
                TLRPC.Update update = updates.updates.get(i);
                if (update instanceof TL_update.TL_updateMessageReactions) {
                    ((TL_update.TL_updateMessageReactions) update).updateUnreadState = false;
                }
            }
            MessagesController.getInstance(this.currentAccount).processUpdates(updates, false);
        }
    }

    private void cancelPendingReactionsRefresh() {
        AndroidUtilities.cancelRunOnUIThread(this.reactionsRefreshRunnable);
        this.reactionsRefreshScheduled = false;
        this.pendingReactionIds.clear();
    }

    private void resetMetadataRefresh() {
        cancelPendingReactionsRefresh();
        this.reactionsLastCheckTimes.clear();
        ConnectionsManager.getInstance(this.currentAccount).cancelRequestsForGuid(this.reactionsRequestGuid);
    }

    public void markAllRead() {
        FeedController.getInstance(this.currentAccount).markAllRead();
        applyUnreadDivider(false);
        refreshAds();
        this.host.invalidateVisiblePart();
    }

    public void settleUnreadDivider() {
        int lastVisibleMessageIndex;
        if (canMarkVisibleAsRead() && this.host.isListReady() && (lastVisibleMessageIndex = this.host.getLastVisibleMessageIndex()) != Integer.MIN_VALUE) {
            ArrayList<MessageObject> messages = this.host.getMessages();
            int iMin = this.host.canScrollToNewer() ? Math.min(lastVisibleMessageIndex, messages.size() - 1) : messages.size() - 1;
            if (iMin >= 0) {
                FeedController feedController = FeedController.getInstance(this.currentAccount);
                for (int i = 0; i <= iMin; i++) {
                    MessageObject messageObject = messages.get(i);
                    if (messageObject != null && !messageObject.isDateObject && messageObject.type != 6 && !messageObject.isSponsored()) {
                        feedController.onPostSeen(messageObject.getDialogId(), messageObject.getRealId());
                    }
                }
            }
            applyUnreadDivider(false);
            refreshAds();
            updatePagedownCounter();
        }
    }

    public void onScrollAnimationFinished() {
        if (this.destroyed || !this.viewportActive || this.settleAtNewestScheduled) {
            return;
        }
        this.settleAtNewestScheduled = true;
        AndroidUtilities.runOnUIThread(this.settleAtNewestRunnable);
    }

    public void destroy() {
        this.destroyed = true;
        resetMetadataRefresh();
        if (this.settleAtNewestScheduled) {
            AndroidUtilities.cancelRunOnUIThread(this.settleAtNewestRunnable);
            this.settleAtNewestScheduled = false;
        }
        this.pendingInitialScrollRestore = null;
    }

    public void saveDrawerScrollPosition() {
        ScrollAnchor scrollAnchorCaptureScrollAnchor = this.host.captureScrollAnchor();
        if (scrollAnchorCaptureScrollAnchor == null || scrollAnchorCaptureScrollAnchor.row == null) {
            return;
        }
        FeedController.getInstance(this.currentAccount).saveDrawerScrollPosition(scrollAnchorCaptureScrollAnchor.row.getDialogId(), scrollAnchorCaptureScrollAnchor.row.getRealId(), scrollAnchorCaptureScrollAnchor.offsetTop);
    }

    public void onReadStateRefreshed() {
        ScrollAnchor scrollAnchorCaptureScrollAnchor = this.host.captureScrollAnchor();
        boolean zHasPendingInitialPosition = hasPendingInitialPosition();
        applyUnreadDivider(false);
        refreshAds();
        if (!zHasPendingInitialPosition || !hasPendingInitialPosition()) {
            this.host.restoreScrollAnchor(scrollAnchorCaptureScrollAnchor);
        }
        this.lastPagedownCount = -1;
        updatePagedownCounter();
        this.host.invalidateVisiblePart();
    }

    public void onPreserveScrollLoadStarted(int i) {
        this.preserveScrollLoadIndex = i;
    }

    public boolean consumePreserveScrollLoad(int i) {
        if (this.preserveScrollLoadIndex != i) {
            return false;
        }
        this.preserveScrollLoadIndex = -1;
        return true;
    }

    public void beforePreservedNewerMessagesInserted() {
        this.scrollPreservedNewerToUnread = this.host.isListScrollIdle() && !this.host.isScrollAnimationRunning() && this.host.getDistanceToNewerPx() <= NEAR_NEWEST_THRESHOLD;
    }

    public boolean afterPreservedNewerMessagesInserted() {
        boolean z = this.scrollPreservedNewerToUnread;
        applyUnreadDivider(z, true);
        this.scrollPreservedNewerToUnread = false;
        return z;
    }

    public void applyUnreadDivider(boolean z) {
        applyUnreadDivider(z, false);
    }

    private void applyUnreadDivider(boolean z, boolean z2) {
        if (this.host.isListReady()) {
            ArrayList<MessageObject> messages = this.host.getMessages();
            if (messages.isEmpty()) {
                this.unreadDivider = null;
                this.readyToMarkAsRead = false;
                return;
            }
            MessageObject messageObject = this.unreadDivider;
            int iIndexOf = messageObject == null ? -1 : messages.indexOf(messageObject);
            MessageObject messageObjectCreateUnreadDivider = iIndexOf >= 0 ? this.unreadDivider : null;
            if (iIndexOf >= 0) {
                messages.remove(iIndexOf);
            }
            this.unreadDivider = null;
            int iFindFirstUnreadIndex = FeedController.getInstance(this.currentAccount).findFirstUnreadIndex(messages);
            if (iFindFirstUnreadIndex < 0) {
                this.pendingDividerScroll = false;
                if (iIndexOf >= 0) {
                    if (z && !z2) {
                        this.host.notifyAllMessagesChanged();
                    } else {
                        this.host.notifyMessageRemoved(iIndexOf);
                        this.host.invalidateVisiblePart();
                    }
                }
                this.readyToMarkAsRead = true;
                return;
            }
            int iFindDividerInsertIndex = findDividerInsertIndex(messages, iFindFirstUnreadIndex);
            if (messageObjectCreateUnreadDivider == null) {
                messageObjectCreateUnreadDivider = FeedMessageUtils.createUnreadDivider(this.currentAccount, this.host.nextStableId());
            }
            messages.add(iFindDividerInsertIndex, messageObjectCreateUnreadDivider);
            this.unreadDivider = messageObjectCreateUnreadDivider;
            if (z) {
                this.readyToMarkAsRead = false;
                if (!z2) {
                    this.host.notifyAllMessagesChanged();
                } else if (iIndexOf < 0) {
                    this.host.notifyMessageInserted(iFindDividerInsertIndex);
                } else if (iIndexOf != iFindDividerInsertIndex) {
                    this.host.notifyMessageRemoved(iIndexOf);
                    this.host.notifyMessageInserted(iFindDividerInsertIndex);
                }
                this.pendingDividerScroll = true;
                requestPendingInitialPosition();
                this.host.invalidateVisiblePart();
                return;
            }
            this.readyToMarkAsRead = true;
            if (iIndexOf < 0) {
                this.host.notifyMessageInserted(iFindDividerInsertIndex);
                this.host.invalidateVisiblePart();
            } else if (iIndexOf != iFindDividerInsertIndex) {
                this.host.notifyMessageRemoved(iIndexOf);
                this.host.notifyMessageInserted(iFindDividerInsertIndex);
                this.host.invalidateVisiblePart();
            }
        }
    }

    public boolean scrollToUnreadDividerIfAbove() {
        int newestVisibleMessageIndex;
        if (!this.host.isListReady()) {
            return false;
        }
        ArrayList<MessageObject> messages = this.host.getMessages();
        MessageObject messageObject = this.unreadDivider;
        if (messageObject == null || !messages.contains(messageObject)) {
            applyUnreadDivider(false);
            refreshAds();
            messages = this.host.getMessages();
        }
        int iIndexOf = messages.indexOf(this.unreadDivider);
        if (iIndexOf < 0 || (newestVisibleMessageIndex = this.host.getNewestVisibleMessageIndex()) == Integer.MIN_VALUE || iIndexOf >= newestVisibleMessageIndex) {
            return false;
        }
        this.host.scrollToMessageAnimated(iIndexOf, AndroidUtilities.dp(48.0f));
        this.host.invalidateVisiblePart();
        return true;
    }

    private static int findDividerInsertIndex(ArrayList<MessageObject> arrayList, int i) {
        MessageObject messageObject = arrayList.get(i);
        long groupId = messageObject.getGroupId();
        if (groupId == 0) {
            return i + 1;
        }
        long dialogId = messageObject.getDialogId();
        int iMax = i + 1;
        for (int i2 = 0; i2 < arrayList.size(); i2++) {
            MessageObject messageObject2 = arrayList.get(i2);
            if (messageObject2 != null && messageObject2.getGroupId() == groupId && messageObject2.getDialogId() == dialogId) {
                iMax = Math.max(iMax, i2 + 1);
            }
        }
        return iMax;
    }

    public void onVisiblePartInvalidated() {
        if (!this.viewportActive || this.host.getFragment().isPaused()) {
            return;
        }
        ScrollAnchor scrollAnchor = this.pendingInitialScrollRestore;
        if (scrollAnchor != null) {
            this.host.restoreScrollAnchor(scrollAnchor);
            this.pendingInitialScrollRestore = null;
        }
        maybeScrollToDivider();
        updatePagedownCounter();
    }

    private void maybeScrollToDivider() {
        if (this.pendingDividerScroll) {
            if (!this.host.isListReady() || this.unreadDivider == null) {
                this.pendingDividerScroll = false;
                this.readyToMarkAsRead = true;
                return;
            }
            int lastVisibleMessageIndex = this.host.getLastVisibleMessageIndex();
            if (lastVisibleMessageIndex == Integer.MIN_VALUE) {
                return;
            }
            int iIndexOf = this.host.getMessages().indexOf(this.unreadDivider);
            if (iIndexOf >= 0 && iIndexOf > lastVisibleMessageIndex) {
                this.host.scrollToMessage(iIndexOf, AndroidUtilities.dp(48.0f));
            }
            this.pendingDividerScroll = false;
            this.readyToMarkAsRead = true;
        }
    }

    private void updatePagedownCounter() {
        if (this.host.isListReady() && !this.host.isScrollAnimationRunning()) {
            int newestVisibleMessageIndex = this.host.getNewestVisibleMessageIndex();
            int iCountUnreadBelow = newestVisibleMessageIndex == Integer.MIN_VALUE ? 0 : FeedController.getInstance(this.currentAccount).countUnreadBelow(this.host.getMessages(), newestVisibleMessageIndex);
            if (iCountUnreadBelow != this.lastPagedownCount) {
                this.lastPagedownCount = iCountUnreadBelow;
                this.host.setPagedownCount(iCountUnreadBelow);
            }
            if (iCountUnreadBelow > 0) {
                this.pagedownShownByScroll = false;
                this.host.setPagedownButtonVisible(true);
            } else {
                if (this.host.canScrollToNewer()) {
                    return;
                }
                this.pagedownShownByScroll = false;
                this.host.setPagedownButtonVisible(false);
            }
        }
    }

    public void onScrolled(int i) {
        if (this.viewportActive && this.host.isListReady() && !this.host.isScrollAnimationRunning()) {
            if (!this.host.canScrollToNewer()) {
                this.totalScrollDy = 0;
                this.pagedownShownByScroll = false;
                this.host.setPagedownButtonVisible(false);
                if (i <= 0 || this.settleAtNewestScheduled) {
                    return;
                }
                this.settleAtNewestScheduled = true;
                AndroidUtilities.runOnUIThread(this.settleAtNewestRunnable);
                return;
            }
            if (this.lastPagedownCount > 0) {
                return;
            }
            boolean zIsPagedownButtonVisible = this.host.isPagedownButtonVisible();
            if (i > 0) {
                if (zIsPagedownButtonVisible) {
                    return;
                }
                int i2 = this.totalScrollDy + i;
                this.totalScrollDy = i2;
                if (i2 > PAGEDOWN_SCROLL_THRESHOLD) {
                    this.totalScrollDy = 0;
                    this.pagedownShownByScroll = true;
                    this.host.setPagedownButtonVisible(true);
                    return;
                }
                return;
            }
            if (i < 0 && this.pagedownShownByScroll && zIsPagedownButtonVisible) {
                int i3 = this.totalScrollDy + i;
                this.totalScrollDy = i3;
                if (i3 < (-PAGEDOWN_SCROLL_THRESHOLD)) {
                    this.totalScrollDy = 0;
                    this.host.setPagedownButtonVisible(false);
                }
            }
        }
    }

    public void onMessagesDeleted() {
        if (this.unreadDivider == null) {
            return;
        }
        ArrayList<MessageObject> messages = this.host.getMessages();
        int iIndexOf = messages.indexOf(this.unreadDivider);
        for (int i = 0; i < iIndexOf; i++) {
            if (FeedMessageUtils.isPostRow(messages.get(i))) {
                return;
            }
        }
        this.unreadDivider = null;
        if (iIndexOf < 0 || !this.host.isListReady()) {
            return;
        }
        messages.remove(iIndexOf);
        this.host.notifyMessageRemoved(iIndexOf);
    }

    public void loadReplyMessages(ArrayList<MessageObject> arrayList, int i, int i2) {
        if (arrayList == null || arrayList.isEmpty()) {
            return;
        }
        LongSparseArray longSparseArray = new LongSparseArray();
        for (int i3 = 0; i3 < arrayList.size(); i3++) {
            MessageObject messageObject = arrayList.get(i3);
            if (messageObject != null && !messageObject.isDateObject) {
                long dialogId = messageObject.getDialogId();
                if (dialogId != 0) {
                    ArrayList arrayList2 = (ArrayList) longSparseArray.get(dialogId);
                    if (arrayList2 == null) {
                        arrayList2 = new ArrayList();
                        longSparseArray.put(dialogId, arrayList2);
                    }
                    arrayList2.add(messageObject);
                }
            }
        }
        for (int i4 = 0; i4 < longSparseArray.size(); i4++) {
            MediaDataController.getInstance(this.currentAccount).loadReplyMessagesForMessages((ArrayList) longSparseArray.valueAt(i4), longSparseArray.keyAt(i4), i, 0L, null, i2, null);
        }
    }

    public void reconcileWithStore() {
        boolean z;
        if (this.host.isListReady()) {
            FeedStore store = FeedController.getInstance(this.currentAccount).getStore();
            ArrayList<MessageObject> messages = this.host.getMessages();
            int i = 0;
            for (int i2 = 0; i2 < messages.size(); i2++) {
                if (FeedMessageUtils.isPostRow(messages.get(i2))) {
                    i++;
                }
            }
            ArrayList<MessageObject> visibleMessages = store.getVisibleMessages();
            if (i == 0) {
                if (!visibleMessages.isEmpty() && this.host.isFirstLoadComplete()) {
                    this.host.reloadFeed();
                    return;
                } else {
                    if (store.isEmpty() || store.isEndReached()) {
                        return;
                    }
                    this.host.requestOlderFeedPage();
                    return;
                }
            }
            if (store.isEmpty()) {
                this.host.reloadFeed();
                return;
            }
            HashSet hashSet = new HashSet(visibleMessages);
            ArrayList<Integer> arrayList = null;
            ArrayList arrayList2 = null;
            for (int i3 = 0; i3 < messages.size(); i3++) {
                MessageObject messageObject = messages.get(i3);
                if (FeedMessageUtils.isPostRow(messageObject) && !hashSet.contains(messageObject)) {
                    if (store.getMessage(messageObject.getDialogId(), messageObject.getId()) == messageObject) {
                        if (arrayList2 == null) {
                            arrayList2 = new ArrayList();
                        }
                        arrayList2.add(messageObject);
                    } else {
                        if (arrayList == null) {
                            arrayList = new ArrayList<>();
                        }
                        arrayList.add(Integer.valueOf(messageObject.getId()));
                    }
                }
            }
            int size = (arrayList == null ? 0 : arrayList.size()) + (arrayList2 == null ? 0 : arrayList2.size());
            if (size == 0 && i == visibleMessages.size()) {
                return;
            }
            ScrollAnchor scrollAnchorCaptureScrollAnchor = this.host.captureScrollAnchor();
            boolean zHasPendingInitialPosition = hasPendingInitialPosition();
            if (arrayList != null) {
                this.host.deleteRows(arrayList);
            }
            if (arrayList2 != null) {
                for (int i4 = 0; i4 < arrayList2.size(); i4++) {
                    int iIndexOf = messages.indexOf(arrayList2.get(i4));
                    if (iIndexOf >= 0) {
                        messages.remove(iIndexOf);
                        this.host.notifyMessageRemoved(iIndexOf);
                    }
                }
            }
            HashSet hashSet2 = new HashSet();
            for (int i5 = 0; i5 < messages.size(); i5++) {
                if (FeedMessageUtils.isPostRow(messages.get(i5))) {
                    hashSet2.add(messages.get(i5));
                }
            }
            int i6 = 0;
            boolean z2 = false;
            int i7 = 0;
            while (true) {
                z = true;
                if (i6 >= visibleMessages.size()) {
                    break;
                }
                MessageObject messageObject2 = visibleMessages.get(i6);
                if (hashSet2.contains(messageObject2)) {
                    while (i7 < messages.size() && messages.get(i7) != messageObject2) {
                        i7++;
                    }
                    if (i7 < messages.size()) {
                        i7++;
                    }
                } else {
                    this.host.materializeRow(messageObject2);
                    int insertIndex = getInsertIndex(messages, messageObject2, visibleMessages, i6, i7);
                    messages.add(insertIndex, messageObject2);
                    this.host.notifyMessageInserted(insertIndex);
                    i7 = insertIndex + 1;
                    z2 = true;
                }
                i6++;
            }
            if (size <= 0 && !z2) {
                z = false;
            }
            if (!normalizeDateHeaders(messages) && !z) {
                return;
            }
            this.host.onFeedListChanged();
            applyUnreadDivider(false);
            refreshAds();
            onFeedExclusionsChanged();
            if (!zHasPendingInitialPosition || !hasPendingInitialPosition()) {
                this.host.restoreScrollAnchor(scrollAnchorCaptureScrollAnchor);
            }
            this.host.invalidateVisiblePart();
            if (store.getVisibleCount() == 0) {
                boolean zIsEndReached = store.isEndReached();
                Host host = this.host;
                if (zIsEndReached) {
                    host.showEmptyFeedState();
                } else {
                    host.showEmptyFeedProgress();
                    this.host.requestOlderFeedPage();
                }
            }
        }
    }

    private static int getInsertIndex(ArrayList<MessageObject> arrayList, MessageObject messageObject, ArrayList<MessageObject> arrayList2, int i, int i2) {
        int i3;
        int iMin = Math.min(i2, arrayList.size());
        if (i <= 0 || iMin >= arrayList.size()) {
            return iMin;
        }
        MessageObject messageObject2 = arrayList2.get(i - 1);
        MessageObject messageObject3 = arrayList.get(iMin);
        return (messageObject2 == null || messageObject3 == null || !messageObject3.isDateObject || (i3 = messageObject2.dateKeyInt) == messageObject.dateKeyInt || messageObject3.dateKeyInt != i3) ? iMin : iMin + 1;
    }

    private boolean normalizeDateHeaders(ArrayList<MessageObject> arrayList) {
        MessageObject lastReal = null;
        int i = 0;
        boolean haveReal = false;
        boolean changed = false;
        while (i < arrayList.size()) {
            MessageObject cur = arrayList.get(i);
            if (cur == null || cur.type == 6 || cur.isSponsored()) {
                i++;
                continue;
            }
            if (cur.isDateObject) {
                if (lastReal == null) {
                    arrayList.remove(i);
                    this.host.notifyMessageRemoved(i);
                } else if (cur.dateKeyInt != lastReal.dateKeyInt) {
                    arrayList.add(i, createDateHeader(lastReal));
                    this.host.notifyMessageInserted(i);
                    i++;
                }
                changed = true;
            } else if (lastReal == null || lastReal.dateKeyInt == cur.dateKeyInt) {
                lastReal = cur;
                haveReal = true;
                i++;
            } else {
                arrayList.add(i, createDateHeader(lastReal));
                this.host.notifyMessageInserted(i);
                i++;
            }
            // reset lastReal to detect a new group on next iteration
            lastReal = null;
            changed = true;
            i++;
        }
        if (haveReal && lastReal != null) {
            arrayList.add(createDateHeader(lastReal));
            this.host.notifyMessageInserted(arrayList.size() - 1);
            return true;
        }
        return changed;
    }

    private MessageObject createDateHeader(MessageObject messageObject) {
        return FeedMessageUtils.createDateHeader(this.currentAccount, messageObject, this.host.stableIdForDateHeader(messageObject.dateKeyInt));
    }

    public ArrayList<Integer> collectLocalRowIds(long j, ArrayList<Integer> arrayList, int i) {
        boolean zContains;
        ArrayList<Integer> arrayList2 = new ArrayList<>();
        HashSet hashSet = arrayList != null ? new HashSet(arrayList) : null;
        ArrayList<MessageObject> messages = this.host.getMessages();
        for (int i2 = 0; i2 < messages.size(); i2++) {
            MessageObject messageObject = messages.get(i2);
            if (FeedMessageUtils.isPostRow(messageObject) && messageObject.getDialogId() == j) {
                int realId = messageObject.getRealId();
                if (hashSet != null) {
                    zContains = hashSet.contains(Integer.valueOf(realId));
                } else {
                    zContains = realId > 0 && realId <= i;
                }
                if (zContains) {
                    arrayList2.add(Integer.valueOf(messageObject.getId()));
                }
            }
        }
        return arrayList2;
    }

    public static void mergeDeletedIds(ArrayList<Integer> arrayList, ArrayList<Integer> arrayList2) {
        for (int i = 0; i < arrayList2.size(); i++) {
            if (!arrayList.contains(arrayList2.get(i))) {
                arrayList.add(arrayList2.get(i));
            }
        }
    }

    public void hideChannelWithUndo(final long j, CharSequence charSequence) {
        FeedConfig feedConfig = FeedConfig.getInstance(this.currentAccount);
        FeedController feedController = FeedController.getInstance(this.currentAccount);
        feedConfig.setExcluded(j, true);
        feedController.markConfigApplied();
        feedController.getStore().setHidden(j, true);
        this.pendingHideDialogId = j;
        reconcileWithStore();
        onFeedExclusionsChanged();
        notifyChannelsChanged();
        BulletinFactory.of(this.host.getFragment()).createUndoBulletin(AndroidUtilities.replaceTags(LocaleController.formatString(R.string.FeedChannelHidden, charSequence)), new Runnable() {
            @Override // java.lang.Runnable
            public final void run() {
                FeedChatIntegration.this.undoHideChannel();
            }
        }, new Runnable() {
            @Override // java.lang.Runnable
            public final void run() {
                FeedChatIntegration.this.lambda$hideChannelWithUndo$1(j);
            }
        }).show();
    }

    public /* synthetic */ void lambda$hideChannelWithUndo$1(long j) {
        if (this.pendingHideDialogId == j) {
            this.pendingHideDialogId = 0L;
        }
    }

    public void undoHideChannel() {
        long j = this.pendingHideDialogId;
        if (j == 0) {
            return;
        }
        this.pendingHideDialogId = 0L;
        FeedController feedController = FeedController.getInstance(this.currentAccount);
        FeedConfig.getInstance(this.currentAccount).setExcluded(j, false);
        feedController.markConfigApplied();
        feedController.getStore().setHidden(j, false);
        reconcileWithStore();
        onFeedExclusionsChanged();
        notifyChannelsChanged();
    }

    public void onFeedExclusionsChanged() {
        this.lastPagedownCount = -1;
        updatePagedownCounter();
        NotificationCenter.getInstance(this.currentAccount).postNotificationNameOnUIThread(NotificationCenter.updateInterfaces, Integer.valueOf(MessagesController.UPDATE_MASK_READ_DIALOG_MESSAGE));
    }
}
