package org.telegram.messenger.feed;

import androidx.collection.LongSparseArray;
import java.util.ArrayList;
import java.util.HashSet;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;

public class FeedChannelRegistry implements NotificationCenter.NotificationCenterDelegate {
    private static final FeedChannelRegistry[] instances = new FeedChannelRegistry[UserConfig.MAX_ACCOUNT_COUNT];
    private static final Object[] locks = new Object[UserConfig.MAX_ACCOUNT_COUNT];

    static {
        for (int i = 0; i < locks.length; i++) {
            locks[i] = new Object();
        }
    }

    private boolean built;
    public final int currentAccount;
    private boolean rebuildScheduled;
    private final HashSet<Long> channelIds = new HashSet<>();
    private final ArrayList<Listener> listeners = new ArrayList<>();
    private final Runnable rebuildRunnable = () -> {
        this.rebuildScheduled = false;
        rebuild(true);
    };

    public interface Listener {
        void onFeedChannelsChanged(HashSet<Long> added, HashSet<Long> removed);
    }

    public static FeedChannelRegistry getInstance(int account) {
        FeedChannelRegistry result = instances[account];
        if (result == null) {
            synchronized (locks[account]) {
                result = instances[account];
                if (result == null) {
                    result = new FeedChannelRegistry(account);
                    instances[account] = result;
                }
            }
        }
        return result;
    }

    private FeedChannelRegistry(int account) {
        this.currentAccount = account;
        AndroidUtilities.runOnUIThread(() ->
                NotificationCenter.getInstance(account).addObserver(this, NotificationCenter.dialogsNeedReload));
    }

    public void addListener(Listener listener) {
        ensureBuilt();
        if (this.listeners.contains(listener)) {
            return;
        }
        this.listeners.add(listener);
    }

    private void ensureBuilt() {
        if (this.built) {
            return;
        }
        this.built = true;
        rebuild(false);
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.dialogsNeedReload) {
            ensureBuilt();
            if (this.rebuildScheduled) {
                return;
            }
            this.rebuildScheduled = true;
            AndroidUtilities.runOnUIThread(this.rebuildRunnable, 500L);
        }
    }

    private void rebuild(boolean notify) {
        MessagesController controller = MessagesController.getInstance(this.currentAccount);
        LongSparseArray<TLRPC.Dialog> dialogsDict = controller.dialogs_dict;
        HashSet<Long> current = new HashSet<>();
        for (int i = 0; i < dialogsDict.size(); i++) {
            TLRPC.Dialog dialog = dialogsDict.valueAt(i);
            if (dialog != null && DialogObject.isChatDialog(dialog.id)
                    && FeedController.isEligibleChannel(controller.getChat(-dialog.id))) {
                current.add(dialog.id);
            }
        }
        HashSet<Long> added = null;
        HashSet<Long> removed = null;
        for (Long id : current) {
            if (!this.channelIds.contains(id)) {
                if (added == null) {
                    added = new HashSet<>();
                }
                added.add(id);
            }
        }
        for (Long id : this.channelIds) {
            if (!current.contains(id)) {
                if (removed == null) {
                    removed = new HashSet<>();
                }
                removed.add(id);
            }
        }
        if (added == null && removed == null) {
            return;
        }
        this.channelIds.clear();
        this.channelIds.addAll(current);
        if (notify) {
            if (added == null) {
                added = new HashSet<>();
            }
            if (removed == null) {
                removed = new HashSet<>();
            }
            for (int i = this.listeners.size() - 1; i >= 0; i--) {
                this.listeners.get(i).onFeedChannelsChanged(added, removed);
            }
        }
    }
}
