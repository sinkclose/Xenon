package org.telegram.messenger.feed;

import java.util.ArrayList;
import java.util.HashSet;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MessagesController;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;

final class FeedBackfillCoordinator {
    private final int currentAccount;
    private int loadIndex;
    private final Runnable onRoundFinished;
    private int roundId;
    private boolean running;
    private final int guid = ConnectionsManager.generateClassGuid();
    private final HashSet<Long> pending = new HashSet<>();
    private final HashSet<Long> exhausted = new HashSet<>();

    public FeedBackfillCoordinator(int account, Runnable onRoundFinished) {
        this.currentAccount = account;
        this.onRoundFinished = onRoundFinished;
    }

    public HashSet<Long> getExhaustedSnapshot() {
        return new HashSet<>(this.exhausted);
    }

    public void clearExhausted() {
        this.exhausted.clear();
    }

    public void cancel() {
        this.running = false;
        this.roundId++;
        this.pending.clear();
        ConnectionsManager.getInstance(this.currentAccount).cancelRequestsForGuid(this.guid);
    }

    public void startRound(ArrayList<long[]> candidates) {
        this.running = true;
        final int roundId = this.roundId + 1;
        this.roundId = roundId;
        this.pending.clear();
        int count = Math.min(4, candidates.size());
        for (int i = 0; i < count; i++) {
            this.pending.add(candidates.get(i)[0]);
        }
        MessagesController controller = MessagesController.getInstance(this.currentAccount);
        for (int i = 0; i < count; i++) {
            long dialogId = candidates.get(i)[0];
            int maxId = (int) candidates.get(i)[1];
            controller.loadMessages(dialogId, 0L, false, 20, maxId, 0, false, 0, this.guid, 0, 0, 0, 0L, 0, this.loadIndex++, false);
        }
        AndroidUtilities.runOnUIThread(() -> {
            if (roundId == this.roundId && this.running) {
                this.exhausted.addAll(this.pending);
                finishRound();
            }
        }, 10000L);
    }

    public void onMessagesDidLoad(Object... args) {
        if (((Integer) args[10]) != this.guid) {
            return;
        }
        Long dialogId = (Long) args[0];
        long did = dialogId;
        if (((ArrayList<?>) args[2]).size() < 20) {
            this.exhausted.add(dialogId);
        }
        onResult(did);
    }

    public void onLoadingMessagesFailed(Object... args) {
        if (((Integer) args[0]) != this.guid) {
            return;
        }
        long dialogId = 0;
        Object request = args[1];
        if (request instanceof TLRPC.TL_messages_getHistory) {
            TLRPC.InputPeer peer = ((TLRPC.TL_messages_getHistory) request).peer;
            if (peer != null) {
                long channelId = peer.channel_id;
                if (channelId == 0) {
                    channelId = peer.chat_id;
                }
                dialogId = -channelId;
            }
        }
        if (dialogId != 0) {
            this.exhausted.add(dialogId);
        }
        onResult(dialogId);
    }

    private void onResult(long dialogId) {
        if (this.running && this.pending.remove(dialogId) && this.pending.isEmpty()) {
            finishRound();
        }
    }

    private void finishRound() {
        this.running = false;
        this.roundId++;
        this.pending.clear();
        this.onRoundFinished.run();
    }
}
