package org.telegram.messenger.feed;

import org.telegram.messenger.MessageObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;

public final class FeedStore {

    private int count;
    private boolean endReached;
    private final ArrayList<MessageObject> messages = new ArrayList<>();
    private final FeedMessageIdentityMap identityMap = new FeedMessageIdentityMap();
    private final HashSet<Long> hiddenDialogIds = new HashSet<>();
    private final FeedTimelineLoader.Cursor oldestCursor = new FeedTimelineLoader.Cursor();
    private final FeedTimelineLoader.Cursor newestCursor = new FeedTimelineLoader.Cursor();

    public ArrayList<MessageObject> getMessages() {
        return messages;
    }

    public ArrayList<MessageObject> getVisibleMessages() {
        if (hiddenDialogIds.isEmpty()) {
            return new ArrayList<>(messages);
        }
        ArrayList<MessageObject> result = new ArrayList<>(messages.size());
        for (int i = 0; i < messages.size(); i++) {
            MessageObject msg = messages.get(i);
            if (msg != null && !hiddenDialogIds.contains(msg.getDialogId())) {
                result.add(msg);
            }
        }
        return result;
    }

    public boolean isEmpty() {
        return messages.isEmpty();
    }

    public int getVisibleCount() {
        if (hiddenDialogIds.isEmpty()) return messages.size();
        int cnt = 0;
        for (int i = 0; i < messages.size(); i++) {
            MessageObject msg = messages.get(i);
            if (msg != null && !hiddenDialogIds.contains(msg.getDialogId())) {
                cnt++;
            }
        }
        return cnt;
    }

    public boolean hasMessagesForDialog(long dialogId) {
        for (int i = 0; i < messages.size(); i++) {
            MessageObject msg = messages.get(i);
            if (msg != null && msg.getDialogId() == dialogId) return true;
        }
        return false;
    }

    public HashSet<Long> getLoadedDialogIds() {
        HashSet<Long> set = new HashSet<>();
        for (int i = 0; i < messages.size(); i++) {
            MessageObject msg = messages.get(i);
            if (msg != null) {
                set.add(msg.getDialogId());
            }
        }
        return set;
    }

    public HashSet<Long> getHiddenSnapshot() {
        return new HashSet<>(hiddenDialogIds);
    }

    public boolean setHidden(long dialogId, boolean hidden) {
        Long key = dialogId;
        boolean changed = hidden ? hiddenDialogIds.add(key) : hiddenDialogIds.remove(key);
        if (changed) updateCount();
        return changed;
    }

    public boolean applyIncludedDialogs(HashSet<Long> included) {
        HashSet<Long> loaded = getLoadedDialogIds();
        boolean changed = false;
        for (Long id : loaded) {
            if (!included.contains(id)) {
                changed |= hiddenDialogIds.add(id);
            }
        }
        Iterator<Long> it = hiddenDialogIds.iterator();
        while (it.hasNext()) {
            Long next = it.next();
            if (included.contains(next) || !loaded.contains(next)) {
                it.remove();
                changed = true;
            }
        }
        if (changed) updateCount();
        return changed;
    }

    public FeedTimelineLoader.Cursor getOldestCursor() { return oldestCursor; }
    public FeedTimelineLoader.Cursor getNewestCursor() { return newestCursor; }

    public boolean isEndReached() { return endReached; }

    public void setEndReached(boolean reached) {
        this.endReached = reached;
        updateCount();
    }

    public int getCount() { return count; }

    private void updateCount() {
        count = messages.isEmpty() ? 0 : getVisibleCount() + (endReached ? 0 : 3);
    }

    public void clear() {
        messages.clear();
        identityMap.clear();
        hiddenDialogIds.clear();
        endReached = false;
        count = 0;
        oldestCursor.set(0, 0L, 0);
        newestCursor.set(0, 0L, 0);
    }

    public ArrayList<MessageObject> appendMessages(ArrayList<MessageObject> list, boolean prepend) {
        ArrayList<MessageObject> added = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            MessageObject msg = list.get(i);
            if (identityMap.register(msg)) {
                added.add(msg);
            }
        }
        if (prepend) {
            ArrayList<MessageObject> reversed = new ArrayList<>(added);
            Collections.reverse(reversed);
            messages.addAll(0, reversed);
        } else {
            messages.addAll(added);
        }
        updateCount();
        return added;
    }

    public ArrayList<MessageObject> mergeRows(ArrayList<MessageObject> list) {
        ArrayList<MessageObject> added = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            MessageObject msg = list.get(i);
            if (identityMap.register(msg)) {
                added.add(msg);
            }
        }
        int mergeIdx = 0;
        for (int i = 0; i < added.size(); ) {
            MessageObject msg = added.get(i);
            int groupEnd = i + 1;
            long gid = msg.getGroupId();
            while (gid != 0 && groupEnd < added.size() && added.get(groupEnd).getGroupId() == gid && added.get(groupEnd).getDialogId() == msg.getDialogId()) {
                groupEnd++;
            }
            mergeIdx = findMergeIndex(msg, mergeIdx);
            while (i < groupEnd) {
                messages.add(mergeIdx, added.get(i));
                i++;
                mergeIdx++;
            }
            i = groupEnd;
        }
        updateCount();
        return added;
    }

    private int findMergeIndex(MessageObject msg, int start) {
        while (start < messages.size()) {
            MessageObject cur = messages.get(start);
            if (cur == null || compareTimeline(cur.messageOwner.date, cur.getDialogId(), cur.getRealId(), msg.messageOwner.date, msg.getDialogId(), msg.getRealId()) >= 0) {
                start++;
            } else {
                break;
            }
        }
        while (start > 0 && start < messages.size()) {
            MessageObject prev = messages.get(start - 1);
            MessageObject next = messages.get(start);
            if (prev == null || next == null || prev.getGroupId() == 0 || prev.getGroupId() != next.getGroupId() || prev.getDialogId() != next.getDialogId()) break;
            start++;
        }
        return start;
    }

    public void replaceMessage(MessageObject oldMsg, MessageObject newMsg) {
        if (oldMsg == null || newMsg == null) return;
        int idx = messages.indexOf(oldMsg);
        if (idx >= 0) messages.set(idx, newMsg);
        identityMap.replace(newMsg);
    }

    public ArrayList<Integer> deleteMessages(long dialogId, ArrayList<Integer> realIds, boolean[] outChanged) {
        ArrayList<Integer> deletedIds = new ArrayList<>();
        if (realIds == null) return deletedIds;
        HashSet<Integer> realSet = new HashSet<>(realIds);
        HashSet<Integer> visitedGenIds = new HashSet<>();
        boolean changed = false;
        for (int i = 0; i < realIds.size(); i++) {
            MessageObject msg = identityMap.getByRealId(dialogId, realIds.get(i));
            if (msg != null) {
                changed |= messages.remove(msg);
                purgeRow(msg, deletedIds, visitedGenIds);
            }
        }
        for (int i = messages.size() - 1; i >= 0; i--) {
            MessageObject msg = messages.get(i);
            if (msg != null && msg.getDialogId() == dialogId && realSet.contains(msg.getRealId())) {
                messages.remove(i);
                purgeRow(msg, deletedIds, visitedGenIds);
                changed = true;
            }
        }
        if (changed) onRowsRemoved();
        outChanged[0] = changed;
        return deletedIds;
    }

    public ArrayList<Integer> deleteHistory(long dialogId, int maxId, boolean[] outChanged) {
        ArrayList<Integer> deletedIds = new ArrayList<>();
        HashSet<Integer> seenGenIds = new HashSet<>();
        boolean changed = false;
        for (int i = messages.size() - 1; i >= 0; i--) {
            MessageObject msg = messages.get(i);
            if (msg != null && msg.getDialogId() == dialogId && msg.getRealId() > 0 && msg.getRealId() <= maxId) {
                messages.remove(i);
                purgeRow(msg, deletedIds, seenGenIds);
                changed = true;
            }
        }
        if (changed) {
            if (!hasMessagesForDialog(dialogId)) {
                hiddenDialogIds.remove(dialogId);
            }
            onRowsRemoved();
        }
        outChanged[0] = changed;
        return deletedIds;
    }

    public boolean trim(int maxCount) {
        if (messages.size() <= maxCount) return false;
        MessageObject pivot = messages.get(maxCount - 1);
        int pivotDate = pivot.messageOwner.date;
        long pivotUid = pivot.getDialogId();
        int pivotMid = pivot.getRealId();
        boolean changed = false;
        for (int i = messages.size() - 1; i >= 0; i--) {
            MessageObject msg = messages.get(i);
            if (msg != null && compareTimeline(msg.messageOwner.date, msg.getDialogId(), msg.getRealId(), pivotDate, pivotUid, pivotMid) < 0) {
                messages.remove(i);
                identityMap.releaseRow(msg);
                changed = true;
            }
        }
        if (!changed) return false;
        if (messages.isEmpty()) {
            oldestCursor.set(0, 0L, 0);
        } else {
            int minDate = 0;
            int minMid = 0;
            long minUid = 0;
            for (int i = 0; i < messages.size(); i++) {
                MessageObject msg = messages.get(i);
                if (msg != null && (minDate == 0 || compareTimeline(msg.messageOwner.date, msg.getDialogId(), msg.getRealId(), minDate, minUid, minMid) < 0)) {
                    minDate = msg.messageOwner.date;
                    minUid = msg.getDialogId();
                    minMid = msg.getRealId();
                }
            }
            oldestCursor.set(minDate, minUid, minMid);
        }
        endReached = false;
        updateCount();
        return true;
    }

    private void onRowsRemoved() {
        if (!rebuildPagingCursorsFromLoadedRows()) {
            endReached = false;
        }
        updateCount();
    }

    private boolean rebuildPagingCursorsFromLoadedRows() {
        int newestDate = 0, oldestDate = 0;
        long newestUid = 0, oldestUid = 0;
        int newestMid = 0, oldestMid = 0;
        boolean oldEmpty = oldestCursor.isEmpty();
        for (int i = 0; i < messages.size(); i++) {
            MessageObject msg = messages.get(i);
            if (!isPagingRow(msg)) continue;
            int date = msg.messageOwner.date;
            long uid = msg.getDialogId();
            int mid = msg.getRealId();
            if (newestDate == 0 || compareTimeline(date, uid, mid, newestDate, newestUid, newestMid) > 0) {
                newestDate = date;
                newestUid = uid;
                newestMid = mid;
            }
            if (oldestDate == 0 || compareTimeline(date, uid, mid, oldestDate, oldestUid, oldestMid) < 0) {
                oldestDate = date;
                oldestUid = uid;
                oldestMid = mid;
            }
        }
        if (newestDate == 0) {
            oldestCursor.set(0, 0L, 0);
            newestCursor.set(0, 0L, 0);
            return false;
        }
        if (!oldEmpty && compareTimeline(oldestDate, oldestUid, oldestMid, oldestCursor.date, oldestCursor.uid, oldestCursor.mid) > 0) {
            endReached = false;
        }
        newestCursor.set(newestDate, newestUid, newestMid);
        oldestCursor.set(oldestDate, oldestUid, oldestMid);
        return true;
    }

    private static boolean isPagingRow(MessageObject msg) {
        return msg != null && !msg.isDateObject && msg.messageOwner != null && msg.getRealId() > 0;
    }

    public static int compareTimeline(int d1, long u1, int m1, int d2, long u2, int m2) {
        if (d1 != d2) return Integer.compare(d1, d2);
        if (u1 != u2) return Long.compare(u1, u2);
        return Integer.compare(m1, m2);
    }

    private void purgeRow(MessageObject msg, ArrayList<Integer> deletedIds, HashSet<Integer> seenIds) {
        identityMap.purge(msg);
        if (seenIds.add(msg.getId())) {
            deletedIds.add(msg.getId());
        }
    }

    public boolean hasNoSyntheticIds() {
        return identityMap.isEmpty();
    }

    public MessageObject getMessage(long dialogId, int id) {
        return identityMap.getByAnyId(dialogId, id);
    }

    public int resolveRealMessageId(long dialogId, int id) {
        return identityMap.resolveRealMessageId(dialogId, id);
    }

    public long resolveRealDialogId(int id) {
        return identityMap.resolveRealDialogId(id);
    }
}