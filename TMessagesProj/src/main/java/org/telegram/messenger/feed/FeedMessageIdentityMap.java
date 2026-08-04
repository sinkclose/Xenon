package org.telegram.messenger.feed;

import org.telegram.messenger.MessageObject;
import org.telegram.tgnet.TLRPC;

import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;

public final class FeedMessageIdentityMap {

    private final HashMap<MessageCompositeID, Integer> generatedIds = new HashMap<>();
    private final ConcurrentHashMap<Integer, MessageCompositeID> realIdsByGeneratedId = new ConcurrentHashMap<>();
    private final HashMap<MessageCompositeID, MessageObject> messagesByRealId = new HashMap<>();
    private final HashMap<GroupKey, MessageObject> primaryByGroup = new HashMap<>();
    private int lastGeneratedId = 2147483637;

    public boolean register(MessageObject messageObject) {
        boolean z;
        messageObject.reactionsLastCheckTime = Long.MAX_VALUE;
        MessageCompositeID messageCompositeID = new MessageCompositeID(messageObject.messageOwner);
        int i = messageObject.messageOwner.id;
        Integer numValueOf = this.generatedIds.get(messageCompositeID);
        if (numValueOf == null) {
            int i2 = this.lastGeneratedId;
            this.lastGeneratedId = i2 - 1;
            numValueOf = Integer.valueOf(i2);
            this.generatedIds.put(messageCompositeID, numValueOf);
        }
        this.realIdsByGeneratedId.put(numValueOf, messageCompositeID);
        if (this.messagesByRealId.containsKey(messageCompositeID)) {
            z = false;
        } else {
            updatePrimaryGroupFlag(messageObject, messageCompositeID.dialog_id, i);
            this.messagesByRealId.put(messageCompositeID, messageObject);
            z = true;
        }
        TLRPC.Message message = messageObject.messageOwner;
        message.realId = i;
        message.id = numValueOf.intValue();
        return z;
    }

    public void replace(MessageObject messageObject) {
        messageObject.reactionsLastCheckTime = Long.MAX_VALUE;
        MessageCompositeID id = new MessageCompositeID(messageObject.getDialogId(), messageObject.getRealId());
        this.generatedIds.put(id, Integer.valueOf(messageObject.getId()));
        this.realIdsByGeneratedId.put(Integer.valueOf(messageObject.getId()), id);
        MessageObject old = this.messagesByRealId.put(id, messageObject);
        if (messageObject.hasValidGroupId()) {
            GroupKey groupKey = new GroupKey(id.dialog_id, messageObject.messageOwner.grouped_id);
            if (old == null || this.primaryByGroup.get(groupKey) != old) {
                return;
            }
            this.primaryByGroup.put(groupKey, messageObject);
        }
    }

    public void releaseRow(MessageObject messageObject) {
        this.messagesByRealId.remove(new MessageCompositeID(messageObject.getDialogId(), messageObject.getRealId()));
        if (messageObject.hasValidGroupId()) {
            GroupKey groupKey = new GroupKey(messageObject.getDialogId(), messageObject.messageOwner.grouped_id);
            if (this.primaryByGroup.get(groupKey) == messageObject) {
                this.primaryByGroup.remove(groupKey);
            }
        }
    }

    public void purge(MessageObject messageObject) {
        MessageCompositeID id = new MessageCompositeID(messageObject.getDialogId(), messageObject.getRealId());
        this.generatedIds.remove(id);
        this.messagesByRealId.remove(id);
        this.realIdsByGeneratedId.remove(Integer.valueOf(messageObject.getId()));
        if (messageObject.hasValidGroupId()) {
            GroupKey key = new GroupKey(id.dialog_id, messageObject.messageOwner.grouped_id);
            if (this.primaryByGroup.get(key) == messageObject) {
                this.primaryByGroup.remove(key);
            }
        }
    }

    public MessageObject getByRealId(long dialogId, int realId) {
        return this.messagesByRealId.get(new MessageCompositeID(dialogId, realId));
    }

    public MessageObject getByAnyId(long dialogId, int id) {
        MessageObject msg = this.messagesByRealId.get(new MessageCompositeID(dialogId, id));
        if (msg != null) return msg;
        int resolved = resolveRealMessageId(dialogId, id);
        if (resolved != id) {
            return this.messagesByRealId.get(new MessageCompositeID(dialogId, resolved));
        }
        return null;
    }

    public int resolveRealMessageId(long dialogId, int id) {
        MessageCompositeID composite = this.realIdsByGeneratedId.get(Integer.valueOf(id));
        return (composite == null || composite.dialog_id != dialogId) ? id : composite.id;
    }

    public long resolveRealDialogId(int id) {
        MessageCompositeID composite = this.realIdsByGeneratedId.get(Integer.valueOf(id));
        return composite != null ? composite.dialog_id : 0L;
    }

    public boolean isEmpty() {
        return this.realIdsByGeneratedId.isEmpty();
    }

    public void clear() {
        this.generatedIds.clear();
        this.realIdsByGeneratedId.clear();
        this.messagesByRealId.clear();
        this.primaryByGroup.clear();
        this.lastGeneratedId = 2147483637;
    }

    private void updatePrimaryGroupFlag(MessageObject messageObject, long l, int i) {
        if (!messageObject.hasValidGroupId()) {
            messageObject.isPrimaryGroupMessage = false;
            return;
        }
        GroupKey groupKey = new GroupKey(l, messageObject.messageOwner.grouped_id);
        MessageObject existing = this.primaryByGroup.get(groupKey);
        if (existing == null || i > existing.getRealId()) {
            messageObject.isPrimaryGroupMessage = true;
            if (existing != null) {
                existing.isPrimaryGroupMessage = false;
            }
            this.primaryByGroup.put(groupKey, messageObject);
            return;
        }
        messageObject.isPrimaryGroupMessage = false;
    }

    public static final class GroupKey {
        final long dialog_id;
        final long groupedId;

        public GroupKey(long dialog_id, long groupedId) {
            this.dialog_id = dialog_id;
            this.groupedId = groupedId;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (obj instanceof GroupKey) {
                GroupKey other = (GroupKey) obj;
                return this.dialog_id == other.dialog_id && this.groupedId == other.groupedId;
            }
            return false;
        }

        @Override
        public int hashCode() {
            return (Long.hashCode(this.dialog_id) * 31) + Long.hashCode(this.groupedId);
        }
    }

    public static final class MessageCompositeID {
        final long dialog_id;
        final int id;

        MessageCompositeID(TLRPC.Message message) {
            this.dialog_id = MessageObject.getDialogId(message);
            this.id = message.id;
        }

        MessageCompositeID(long dialog_id, int id) {
            this.dialog_id = dialog_id;
            this.id = id;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (obj instanceof MessageCompositeID) {
                MessageCompositeID other = (MessageCompositeID) obj;
                return this.dialog_id == other.dialog_id && this.id == other.id;
            }
            return false;
        }

        @Override
        public int hashCode() {
            return (Long.hashCode(this.dialog_id) * 31) + this.id;
        }
    }
}