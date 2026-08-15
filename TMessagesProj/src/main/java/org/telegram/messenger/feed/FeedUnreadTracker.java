package org.telegram.messenger.feed;

import androidx.collection.LongSparseArray;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;

final class FeedUnreadTracker {
    private final int currentAccount;
    private boolean flushScheduled;
    private final ArrayList<MessageObject> timeline;
    private final LongSparseArray<Integer> readInboxMaxByDialog = new LongSparseArray<>();
    private final LongSparseArray<Integer> pendingMaxReadId = new LongSparseArray<>();
    private final Runnable flushRunnable = this::flush;

    public FeedUnreadTracker(int account, ArrayList<MessageObject> timeline) {
        this.currentAccount = account;
        this.timeline = timeline;
    }

    public void clear() {
        if (this.flushScheduled) {
            AndroidUtilities.cancelRunOnUIThread(this.flushRunnable);
            this.flushScheduled = false;
        }
        flush();
        this.readInboxMaxByDialog.clear();
    }

    public void applyReadInboxMax(long dialogId, int maxId) {
        if (maxId > this.readInboxMaxByDialog.get(dialogId, 0)) {
            this.readInboxMaxByDialog.put(dialogId, maxId);
        }
    }

    public boolean isUnread(MessageObject messageObject) {
        return messageObject != null && !messageObject.isSponsored()
                && messageObject.getRealId() > getEffectiveReadInboxMax(messageObject.getDialogId());
    }

    private int getEffectiveReadInboxMax(long dialogId) {
        return Math.max(this.readInboxMaxByDialog.get(dialogId, 0), this.pendingMaxReadId.get(dialogId, 0));
    }

    public int findFirstUnreadIndex(ArrayList<MessageObject> messages) {
        if (messages != null && !this.readInboxMaxByDialog.isEmpty()) {
            for (int i = messages.size() - 1; i >= 0; i--) {
                if (isUnread(messages.get(i))) {
                    return i;
                }
            }
        }
        return -1;
    }

    public int countUnreadBelow(ArrayList<MessageObject> messages, int fromIndex) {
        if (messages == null || this.readInboxMaxByDialog.isEmpty()) {
            return 0;
        }
        int limit = Math.min(fromIndex, messages.size());
        int count = 0;
        for (int i = 0; i < limit; i++) {
            MessageObject messageObject = messages.get(i);
            if (messageObject != null && !messageObject.isDateObject && messageObject.type != 6
                    && !messageObject.isSponsored() && isUnread(messageObject)) {
                count++;
            }
        }
        return count;
    }

    public void onPostSeen(long dialogId, int maxReadId) {
        if (dialogId == 0 || maxReadId <= 0 || maxReadId <= getEffectiveReadInboxMax(dialogId)) {
            return;
        }
        Integer current = this.pendingMaxReadId.get(dialogId);
        if (current == null || current < maxReadId) {
            this.pendingMaxReadId.put(dialogId, maxReadId);
            if (this.flushScheduled) {
                return;
            }
            this.flushScheduled = true;
            AndroidUtilities.runOnUIThread(this.flushRunnable, 1000L);
        }
    }

    private void flush() {
        this.flushScheduled = false;
        if (this.pendingMaxReadId.isEmpty()) {
            return;
        }
        MessagesController controller = MessagesController.getInstance(this.currentAccount);
        int currentTime = ConnectionsManager.getInstance(this.currentAccount).getCurrentTime();
        int i = 0;
        while (true) {
            int size = this.pendingMaxReadId.size();
            LongSparseArray<Integer> pending = this.pendingMaxReadId;
            if (i < size) {
                long dialogId = pending.keyAt(i);
                Integer maxReadId = this.pendingMaxReadId.valueAt(i);
                int maxId = maxReadId;
                int prevRead = this.readInboxMaxByDialog.get(dialogId, 0);
                if (maxId > prevRead) {
                    this.readInboxMaxByDialog.put(dialogId, maxReadId);
                    controller.markDialogAsRead(dialogId, maxId, 0, currentTime, false, 0L,
                            Math.max(countTimelineRows(dialogId, prevRead, maxId), 1), true, 0);
                }
                i++;
            } else {
                pending.clear();
                return;
            }
        }
    }

    private int countTimelineRows(long dialogId, int prevReadId, int maxReadId) {
        int count = 0;
        for (int i = 0; i < this.timeline.size(); i++) {
            MessageObject messageObject = this.timeline.get(i);
            if (messageObject != null && messageObject.getDialogId() == dialogId) {
                int realId = messageObject.getRealId();
                if (realId > prevReadId && realId <= maxReadId) {
                    count++;
                }
            }
        }
        return count;
    }

    public void markAllRead() {
        MessagesController controller = MessagesController.getInstance(this.currentAccount);
        HashSet<Long> dialogsToClear = new HashSet<>();
        ArrayList<TLRPC.Dialog> unreadDialogs = collectUnreadFeedDialogs();
        int size = unreadDialogs.size();
        for (int i = 0; i < size; i++) {
            TLRPC.Dialog dialog = unreadDialogs.get(i);
            controller.markMentionsAsRead(dialog.id, 0L);
            long dialogId = dialog.id;
            int topMessage = dialog.top_message;
            controller.markDialogAsRead(dialogId, topMessage, topMessage, dialog.last_message_date, false, 0L, 0, true, 0);
            this.readInboxMaxByDialog.put(dialog.id, dialog.top_message);
            dialogsToClear.add(dialog.id);
        }
        for (int i = 0; i < this.timeline.size(); i++) {
            MessageObject messageObject = this.timeline.get(i);
            if (messageObject != null) {
                long dialogId = messageObject.getDialogId();
                TLRPC.Dialog dialog = controller.dialogs_dict.get(dialogId);
                if (dialog == null || dialog.folder_id != 1) {
                    dialogsToClear.add(dialogId);
                    int realId = messageObject.getRealId();
                    if (realId > this.readInboxMaxByDialog.get(dialogId, 0)) {
                        this.readInboxMaxByDialog.put(dialogId, realId);
                    }
                }
            }
        }
        Iterator<Long> it = dialogsToClear.iterator();
        while (it.hasNext()) {
            this.pendingMaxReadId.remove(it.next());
        }
        if (this.pendingMaxReadId.isEmpty() && this.flushScheduled) {
            AndroidUtilities.cancelRunOnUIThread(this.flushRunnable);
            this.flushScheduled = false;
        }
    }

    public int getUnreadCount() {
        ArrayList<TLRPC.Dialog> unreadDialogs = collectUnreadFeedDialogs();
        int count = 0;
        for (int i = 0; i < unreadDialogs.size(); i++) {
            count += unreadDialogs.get(i).unread_count;
        }
        return count;
    }

    private ArrayList<TLRPC.Dialog> collectUnreadFeedDialogs() {
        MessagesController controller = MessagesController.getInstance(this.currentAccount);
        LongSparseArray<TLRPC.Dialog> dialogsDict = controller.dialogs_dict;
        ArrayList<TLRPC.Dialog> result = new ArrayList<>();
        for (int i = 0; i < dialogsDict.size(); i++) {
            TLRPC.Dialog dialog = dialogsDict.valueAt(i);
            if (dialog != null && dialog.unread_count > 0) {
                long dialogId = dialog.id;
                if (DialogObject.isChatDialog(dialogId) && dialog.folder_id != 1
                        && FeedController.isEligibleChannel(controller.getChat(-dialogId))) {
                    result.add(dialog);
                }
            }
        }
        return result;
    }
}
