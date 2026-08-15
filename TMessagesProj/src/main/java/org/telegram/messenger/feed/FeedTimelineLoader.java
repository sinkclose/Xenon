package org.telegram.messenger.feed;

import android.text.TextUtils;
import androidx.collection.LongSparseArray;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.concurrent.atomic.AtomicInteger;
import org.telegram.SQLite.SQLiteCursor;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.NativeByteBuffer;
import org.telegram.tgnet.TLRPC;

final class FeedTimelineLoader {
    private final AtomicInteger channelCacheEpoch = new AtomicInteger();
    private volatile ChannelSet channelSetCache;
    private final int currentAccount;

    public static final class ChannelEnumeration {
        int cacheEpoch;
        boolean failed;
        boolean hasChannels;
        final ArrayList<ChannelSnapshot> included = new ArrayList<>();
        final ArrayList<TLRPC.Chat> channels = new ArrayList<>();
    }

    public static final class NewerPage {
        boolean failed;
        boolean hasMore;
        final ArrayList<TLRPC.Message> messages = new ArrayList<>();
        final ArrayList<TLRPC.User> users = new ArrayList<>();
        final ArrayList<TLRPC.Chat> chats = new ArrayList<>();
        final Cursor first = new Cursor();
    }

    public static final class OlderPage {
        boolean failed;
        boolean hasIncomplete;
        int lastChunkRowCount;
        final ArrayList<TLRPC.Message> messages = new ArrayList<>();
        final ArrayList<TLRPC.User> users = new ArrayList<>();
        final ArrayList<TLRPC.Chat> chats = new ArrayList<>();
        final ArrayList<long[]> backfillCandidates = new ArrayList<>();
        final Cursor last = new Cursor();
        final Cursor first = new Cursor();
    }

    public static final class WindowPage {
        boolean failed;
        boolean truncated;
        final ArrayList<TLRPC.Message> messages = new ArrayList<>();
        final ArrayList<TLRPC.User> users = new ArrayList<>();
        final ArrayList<TLRPC.Chat> chats = new ArrayList<>();
    }

    public static final class Cursor {
        public int date;
        public int mid;
        public long uid;

        public boolean isEmpty() {
            return date == 0;
        }

        public void set(int date, long uid, int mid) {
            this.date = date;
            this.uid = uid;
            this.mid = mid;
        }
    }

    public static final class ChannelSnapshot {
        int depthDate;
        int depthMid;
        final long dialogId;
        boolean hasCached;
        boolean hasHole;
        int holeEnd;
        boolean incomplete;
        boolean localStartReached;
        final int readInboxMax;
        final int topMessage;
        final int unreadCount;

        public ChannelSnapshot(long dialogId, int readInboxMax, int unreadCount, int topMessage) {
            this.dialogId = dialogId;
            this.readInboxMax = readInboxMax;
            this.unreadCount = unreadCount;
            this.topMessage = topMessage;
        }
    }

    public static final class ChannelSet {
        boolean failed;
        boolean hasChannels;
        final int sessionGen;
        final ArrayList<long[]> includedRows = new ArrayList<>();
        final ArrayList<TLRPC.Chat> channels = new ArrayList<>();

        public ChannelSet(int sessionGen) {
            this.sessionGen = sessionGen;
        }
    }

    public FeedTimelineLoader(int account) {
        this.currentAccount = account;
    }

    public synchronized void invalidateChannelCache() {
        this.channelCacheEpoch.incrementAndGet();
        this.channelSetCache = null;
    }

    public synchronized int getChannelCacheEpoch() {
        return this.channelCacheEpoch.get();
    }

    public synchronized boolean isEnumerationCurrent(ChannelEnumeration enumeration) {
        return enumeration != null && enumeration.cacheEpoch == this.channelCacheEpoch.get();
    }

    /* Enumerate all candidates from the dialogs table; only channels (uid < 0).
     * Columns read: did(0), inbox_max(1), unread_count(2), last_mid(3), folder_id(4). */
    public ChannelEnumeration enumerateChannels(int sessionGen, boolean forceRefresh) {
        ChannelSet channelSet;
        int epoch;
        boolean refresh = forceRefresh;
        int attempts = 0;
        while (true) {
            synchronized (this) {
                channelSet = this.channelSetCache;
                epoch = this.channelCacheEpoch.get();
            }
            if (refresh || channelSet == null || channelSet.sessionGen != sessionGen) {
                channelSet = buildChannelSet(sessionGen);
                if (!channelSet.failed) {
                    synchronized (this) {
                        if (epoch == this.channelCacheEpoch.get()) {
                            this.channelSetCache = channelSet;
                        }
                    }
                }
            }
            if (!channelSet.failed && attempts < 3) {
                synchronized (this) {
                    if (epoch == this.channelCacheEpoch.get()) {
                        break;
                    }
                }
                attempts++;
                refresh = true;
                continue;
            }
            break;
        }
        ChannelEnumeration enumeration = new ChannelEnumeration();
        enumeration.hasChannels = channelSet.hasChannels;
        enumeration.failed = channelSet.failed;
        enumeration.cacheEpoch = epoch;
        enumeration.channels.addAll(channelSet.channels);
        for (int i = 0; i < channelSet.includedRows.size(); i++) {
            long[] row = channelSet.includedRows.get(i);
            enumeration.included.add(new ChannelSnapshot(row[0], (int) row[1], (int) row[2], (int) row[3]));
        }
        return enumeration;
    }

    private ChannelSet buildChannelSet(int sessionGen) {
        MessagesStorage storage = MessagesStorage.getInstance(this.currentAccount);
        ChannelSet channelSet = new ChannelSet(sessionGen);
        ArrayList<long[]> rows = new ArrayList<>();
        ArrayList<Long> chatIds = new ArrayList<>();
        try {
            SQLiteCursor cursor = storage.getDatabase().queryFinalized(
                    "SELECT did, inbox_max, unread_count, last_mid, folder_id FROM dialogs WHERE did < 0", new Object[0]);
            while (cursor.next()) {
                long did = cursor.longValue(0);
                if (DialogObject.isChatDialog(did)) {
                    rows.add(new long[]{did, cursor.intValue(1), cursor.intValue(2), cursor.intValue(3), cursor.intValue(4)});
                    chatIds.add(-did);
                }
            }
            cursor.dispose();
            if (!rows.isEmpty()) {
                ArrayList<TLRPC.Chat> chats = new ArrayList<>();
                storage.getChatsInternal(TextUtils.join(",", chatIds), chats);
                LongSparseArray<TLRPC.Chat> byId = new LongSparseArray<>();
                for (int i = 0; i < chats.size(); i++) {
                    byId.put(chats.get(i).id, chats.get(i));
                }
                for (int i = 0; i < rows.size(); i++) {
                    long[] row = rows.get(i);
                    long did = row[0];
                    TLRPC.Chat chat = byId.get(-did);
                    if (FeedController.isEligibleChannel(chat) && row[4] != 1) {
                        channelSet.hasChannels = true;
                        channelSet.channels.add(chat);
                        channelSet.includedRows.add(new long[]{did, row[1], row[2], row[3]});
                    }
                }
            }
            return channelSet;
        } catch (Exception e) {
            FileLog.e(e);
            channelSet.failed = true;
            return channelSet;
        }
    }

    public OlderPage loadOlderPage(ArrayList<ChannelSnapshot> channels, Cursor cursor, HashSet<Long> exhausted) {
        OlderPage page = new OlderPage();
        boolean cursorEmpty = cursor.isEmpty();
        page.last.set(cursor.date, cursor.uid, cursor.mid);
        try {
            ArrayList<Long> dialogIds = new ArrayList<>(channels.size());
            for (int i = 0; i < channels.size(); i++) {
                dialogIds.add(channels.get(i).dialogId);
            }
            String join = TextUtils.join(",", dialogIds);
            MessagesStorage storage = MessagesStorage.getInstance(this.currentAccount);

            // Detect which channels have a history gap (a row in messages_holes).
            HashMap<Long, Integer> holeEndByDialog = new HashMap<>();
            SQLiteCursor holes = storage.getDatabase().queryFinalized(
                    "SELECT uid, MAX(end) FROM messages_holes WHERE uid IN (" + join + ") GROUP BY uid", new Object[0]);
            while (holes.next()) {
                holeEndByDialog.put(holes.longValue(0), holes.intValue(1));
            }
            holes.dispose();
            for (int i = 0; i < channels.size(); i++) {
                ChannelSnapshot snapshot = channels.get(i);
                Integer holeEnd = holeEndByDialog.get(snapshot.dialogId);
                snapshot.hasHole = holeEnd != null;
                snapshot.holeEnd = holeEnd != null ? holeEnd : 0;
            }

            loadChannelDepths(storage, channels);

            int maxDepthDate = 0;
            for (int i = 0; i < channels.size(); i++) {
                ChannelSnapshot snapshot = channels.get(i);
                boolean incomplete = !snapshot.localStartReached && !exhausted.contains(snapshot.dialogId);
                snapshot.incomplete = incomplete;
                if (incomplete) {
                    page.hasIncomplete = true;
                    maxDepthDate = Math.max(maxDepthDate, snapshot.depthDate);
                    long fromMid;
                    if (snapshot.hasCached) {
                        fromMid = snapshot.depthMid;
                    } else {
                        int v = Math.max(snapshot.holeEnd, snapshot.topMessage);
                        fromMid = v > 0 ? v + 1 : 0;
                    }
                    page.backfillCandidates.add(new long[]{snapshot.dialogId, fromMid, snapshot.depthDate});
                }
            }
            page.backfillCandidates.sort((a, b) -> Long.compare(b[2], a[2]));
            if (maxDepthDate == Integer.MAX_VALUE) {
                return page;
            }

            Cursor unreadBoundary = cursorEmpty ? findUnreadBoundary(storage, channels, maxDepthDate) : null;
            ArrayList<Long> usersToLoad = new ArrayList<>();
            ArrayList<Long> chatsToLoad = new ArrayList<>();
            int loaded = 0;
            do {
                int chunk = loadChunk(storage, join, maxDepthDate, page, usersToLoad, chatsToLoad);
                page.lastChunkRowCount = chunk;
                loaded += chunk;
                if (chunk < 30 || unreadBoundary == null || loaded >= 200) {
                    break;
                }
            } while (compareDesc(page.last, unreadBoundary) < 0);

            completeTrailingAlbum(storage, page, usersToLoad, chatsToLoad);

            for (int i = 0; i < dialogIds.size(); i++) {
                long chatId = -dialogIds.get(i);
                if (!chatsToLoad.contains(chatId)) {
                    chatsToLoad.add(chatId);
                }
            }
            if (!usersToLoad.isEmpty()) {
                storage.getUsersInternal(usersToLoad, page.users);
            }
            if (!chatsToLoad.isEmpty()) {
                storage.getChatsInternal(TextUtils.join(",", chatsToLoad), page.chats);
            }
            clusterGroupedMessages(page.messages);
            return page;
        } catch (Exception e) {
            FileLog.e(e);
            page.failed = true;
            clusterGroupedMessages(page.messages);
            return page;
        }
    }

    /* Load a 30-row page of channel messages ordered by (date DESC, uid DESC, mid DESC). */
    private int loadChunk(MessagesStorage storage, String join, int maxDate, OlderPage page,
                          ArrayList<Long> usersToLoad, ArrayList<Long> chatsToLoad) throws Exception {
        StringBuilder sb = new StringBuilder("SELECT data, mid, date, uid FROM messages_v2 WHERE uid IN (");
        sb.append(join);
        sb.append(") AND mid > 0");
        if (maxDate > 0) {
            sb.append(" AND date >= ");
            sb.append(maxDate);
        }
        if (!page.last.isEmpty()) {
            appendCursorBound(sb, page.last, true, false);
        }
        sb.append(" ORDER BY date DESC, uid DESC, mid DESC LIMIT ");
        sb.append(30);
        SQLiteCursor cursor = storage.getDatabase().queryFinalized(sb.toString(), new Object[0]);
        int count = 0;
        while (cursor.next()) {
            count++;
            page.last.set(cursor.intValue(2), cursor.longValue(3), cursor.intValue(1));
            if (page.first.isEmpty()) {
                page.first.set(page.last.date, page.last.uid, page.last.mid);
            }
            TLRPC.Message message = readMessage(cursor);
            if (message != null) {
                page.messages.add(message);
                MessagesStorage.addUsersAndChatsFromMessage(message, usersToLoad, chatsToLoad, null);
            }
        }
        cursor.dispose();
        return count;
    }

    private Cursor findUnreadBoundary(MessagesStorage storage, ArrayList<ChannelSnapshot> channels, int maxDate) throws Exception {
        StringBuilder sb = new StringBuilder();
        Cursor boundary = null;
        int added = 0;
        for (int i = 0; i < channels.size(); i++) {
            ChannelSnapshot snapshot = channels.get(i);
            if (snapshot.topMessage > snapshot.readInboxMax || snapshot.unreadCount > 0) {
                if (sb.length() > 0) {
                    sb.append(" OR ");
                }
                sb.append("uid = ");
                sb.append(snapshot.dialogId);
                sb.append(" AND mid > ");
                sb.append(snapshot.readInboxMax);
                added++;
            }
            if (added > 0 && (added == 64 || i == channels.size() - 1)) {
                Cursor c = queryUnreadBoundary(storage, sb, maxDate);
                if (c != null && (boundary == null || compareDesc(c, boundary) > 0)) {
                    boundary = c;
                }
                sb.setLength(0);
                added = 0;
            }
        }
        return boundary;
    }

    private Cursor queryUnreadBoundary(MessagesStorage storage, StringBuilder sb, int maxDate) {
        StringBuilder full = new StringBuilder("SELECT date, uid, mid FROM messages_v2 WHERE mid > 0 AND (");
        full.append(sb);
        full.append(")");
        if (maxDate > 0) {
            full.append(" AND date >= ");
            full.append(maxDate);
        }
        full.append(" ORDER BY date ASC, uid ASC, mid ASC LIMIT 1");
        try {
            SQLiteCursor cursor = storage.getDatabase().queryFinalized(full.toString(), new Object[0]);
            try {
                if (!cursor.next()) {
                    return null;
                }
                Cursor result = new Cursor();
                result.set(cursor.intValue(0), cursor.longValue(1), cursor.intValue(2));
                return result;
            } finally {
                cursor.dispose();
            }
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        }
    }

    private static void appendCursorBound(StringBuilder sb, Cursor cursor, boolean older, boolean inclusive) {
        String op = older ? "<" : ">";
        sb.append(" AND (date");
        sb.append(op);
        sb.append(' ');
        sb.append(cursor.date);
        sb.append(" OR (date = ");
        sb.append(cursor.date);
        sb.append(" AND uid ");
        sb.append(op);
        sb.append(' ');
        sb.append(cursor.uid);
        sb.append(") OR (date = ");
        sb.append(cursor.date);
        sb.append(" AND uid = ");
        sb.append(cursor.uid);
        sb.append(" AND mid ");
        sb.append(inclusive ? op + "=" : op);
        sb.append(cursor.mid);
        sb.append(')');
    }

    private static int compareDesc(Cursor a, Cursor b) {
        if (a.date != b.date) {
            return a.date > b.date ? -1 : 1;
        }
        if (a.uid != b.uid) {
            return a.uid > b.uid ? -1 : 1;
        }
        return -Integer.compare(a.mid, b.mid);
    }

    public NewerPage loadNewerPage(ArrayList<ChannelSnapshot> channels, Cursor cursor) {
        NewerPage page = new NewerPage();
        page.first.set(cursor.date, cursor.uid, cursor.mid);
        try {
            ArrayList<Long> dialogIds = new ArrayList<>(channels.size());
            for (int i = 0; i < channels.size(); i++) {
                dialogIds.add(channels.get(i).dialogId);
            }
            MessagesStorage storage = MessagesStorage.getInstance(this.currentAccount);
            ArrayList<Long> usersToLoad = new ArrayList<>();
            ArrayList<Long> chatsToLoad = new ArrayList<>();
            StringBuilder sb = new StringBuilder("SELECT data, mid, date, uid FROM messages_v2 WHERE uid IN (");
            sb.append(TextUtils.join(",", dialogIds));
            sb.append(") AND mid > 0");
            appendCursorBound(sb, cursor, false, false);
            sb.append(" ORDER BY date ASC, uid ASC, mid ASC LIMIT ");
            sb.append(50);
            SQLiteCursor c = storage.getDatabase().queryFinalized(sb.toString(), new Object[0]);
            int count = 0;
            while (c.next()) {
                count++;
                page.first.set(c.intValue(2), c.longValue(3), c.intValue(1));
                TLRPC.Message message = readMessage(c);
                if (message != null) {
                    page.messages.add(message);
                    MessagesStorage.addUsersAndChatsFromMessage(message, usersToLoad, chatsToLoad, null);
                }
            }
            c.dispose();
            page.hasMore = count == 50;
            if (!usersToLoad.isEmpty()) {
                storage.getUsersInternal(usersToLoad, page.users);
            }
            if (!chatsToLoad.isEmpty()) {
                storage.getChatsInternal(TextUtils.join(",", chatsToLoad), page.chats);
            }
        } catch (Exception e) {
            FileLog.e(e);
            page.failed = true;
        }
        clusterGroupedMessages(page.messages);
        return page;
    }

    public WindowPage loadChannelWindow(ArrayList<Long> dialogIds, Cursor newestCursor, Cursor oldestCursor) {
        WindowPage page = new WindowPage();
        if (!dialogIds.isEmpty() && !newestCursor.isEmpty() && !oldestCursor.isEmpty()) {
            try {
                MessagesStorage storage = MessagesStorage.getInstance(this.currentAccount);
                ArrayList<Long> usersToLoad = new ArrayList<>();
                ArrayList<Long> chatsToLoad = new ArrayList<>();
                StringBuilder sb = new StringBuilder("SELECT data, mid, date, uid FROM messages_v2 WHERE uid IN (");
                sb.append(TextUtils.join(",", dialogIds));
                sb.append(") AND mid > 0");
                appendCursorBound(sb, newestCursor, true, true);
                appendCursorBound(sb, oldestCursor, false, true);
                sb.append(" ORDER BY date DESC, uid DESC, mid DESC LIMIT ");
                sb.append(501);
                SQLiteCursor c = storage.getDatabase().queryFinalized(sb.toString(), new Object[0]);
                int count = 0;
                while (c.next()) {
                    count++;
                    if (count > 500) {
                        page.truncated = true;
                        break;
                    }
                    TLRPC.Message message = readMessage(c);
                    if (message != null) {
                        page.messages.add(message);
                        MessagesStorage.addUsersAndChatsFromMessage(message, usersToLoad, chatsToLoad, null);
                    }
                }
                c.dispose();
                if (!usersToLoad.isEmpty()) {
                    storage.getUsersInternal(usersToLoad, page.users);
                }
                if (!chatsToLoad.isEmpty()) {
                    storage.getChatsInternal(TextUtils.join(",", chatsToLoad), page.chats);
                }
            } catch (Exception e) {
                FileLog.e(e);
                page.failed = true;
                page.messages.clear();
                page.users.clear();
                page.chats.clear();
            }
            clusterGroupedMessages(page.messages);
        }
        return page;
    }

    private void completeTrailingAlbum(MessagesStorage storage, OlderPage page,
                                       ArrayList<Long> usersToLoad, ArrayList<Long> chatsToLoad) throws Exception {
        if (page.messages.isEmpty()) {
            return;
        }
        TLRPC.Message last = page.messages.get(page.messages.size() - 1);
        if (last.grouped_id == 0) {
            return;
        }
        SQLiteCursor c = storage.getDatabase().queryFinalized(
                "SELECT data, mid, date, uid FROM messages_v2 WHERE uid = " + last.dialog_id +
                        " AND mid > 0 AND mid < " + last.id + " ORDER BY date DESC, mid DESC LIMIT 9", new Object[0]);
        while (c.next()) {
            TLRPC.Message message = readMessage(c);
            if (message == null || message.grouped_id != last.grouped_id) {
                break;
            }
            page.messages.add(message);
            MessagesStorage.addUsersAndChatsFromMessage(message, usersToLoad, chatsToLoad, null);
        }
        c.dispose();
    }

    private TLRPC.Message readMessage(SQLiteCursor cursor) throws Exception {
        NativeByteBuffer data = cursor.byteBufferValue(0);
        if (data == null) {
            return null;
        }
        TLRPC.Message message = TLRPC.Message.TLdeserialize(data, data.readInt32(false), false);
        if (message == null) {
            data.reuse();
            return null;
        }
        message.readAttachPath(data, UserConfig.getInstance(this.currentAccount).clientUserId);
        data.reuse();
        if ((message instanceof TLRPC.TL_messageEmpty) || message.action != null) {
            return null;
        }
        message.id = cursor.intValue(1);
        message.date = cursor.intValue(2);
        message.dialog_id = cursor.longValue(3);
        return message;
    }

    /* For every channel, determine the earliest locally-cached message (depth), considering holes. */
    private static void loadChannelDepths(MessagesStorage storage, ArrayList<ChannelSnapshot> channels) throws Exception {
        LongSparseArray<ChannelSnapshot> byId = new LongSparseArray<>(channels.size());
        for (int i = 0; i < channels.size(); i++) {
            ChannelSnapshot snapshot = channels.get(i);
            snapshot.depthMid = 0;
            snapshot.depthDate = Integer.MAX_VALUE;
            snapshot.hasCached = false;
            snapshot.localStartReached = false;
            byId.put(snapshot.dialogId, snapshot);
        }
        int i = 0;
        while (i < channels.size()) {
            int end = Math.min(i + 64, channels.size());
            StringBuilder sb = new StringBuilder();
            while (i < end) {
                if (sb.length() > 0) {
                    sb.append(" UNION ALL ");
                }
                ChannelSnapshot snapshot = channels.get(i);
                int fromMid = Math.max(snapshot.holeEnd, 1);
                sb.append("SELECT uid, mid, date FROM (SELECT uid, mid, date FROM messages_v2 WHERE uid = ");
                sb.append(snapshot.dialogId);
                sb.append(" AND mid >= ");
                sb.append(fromMid);
                sb.append(" ORDER BY date ASC, mid ASC LIMIT 1)");
                i++;
            }
            SQLiteCursor c = storage.getDatabase().queryFinalized(sb.toString(), new Object[0]);
            while (c.next()) {
                ChannelSnapshot snapshot = byId.get(c.longValue(0));
                if (snapshot != null) {
                    snapshot.depthMid = c.intValue(1);
                    snapshot.depthDate = c.intValue(2);
                    snapshot.hasCached = true;
                }
            }
            c.dispose();
        }
        for (int k = 0; k < channels.size(); k++) {
            ChannelSnapshot snapshot = channels.get(k);
            snapshot.localStartReached = !snapshot.hasHole && snapshot.hasCached;
        }
    }

    /* Group all parts of an album contiguously, preserving first-seen position. */
    private static void clusterGroupedMessages(ArrayList<TLRPC.Message> messages) {
        if (messages.size() < 3) {
            return;
        }
        HashMap<Long, ArrayList<TLRPC.Message>> grouped = new HashMap<>();
        boolean duplicated = false;
        for (int i = 0; i < messages.size(); i++) {
            long groupedId = messages.get(i).grouped_id;
            if (groupedId != 0) {
                ArrayList<TLRPC.Message> list = grouped.get(groupedId);
                if (list == null) {
                    list = new ArrayList<>();
                    grouped.put(groupedId, list);
                } else {
                    duplicated = true;
                }
                list.add(messages.get(i));
            }
        }
        if (duplicated) {
            ArrayList<TLRPC.Message> result = new ArrayList<>(messages.size());
            HashSet<Long> emitted = new HashSet<>();
            for (int i = 0; i < messages.size(); i++) {
                TLRPC.Message message = messages.get(i);
                long groupedId = message.grouped_id;
                if (groupedId == 0) {
                    result.add(message);
                } else if (emitted.add(groupedId)) {
                    result.addAll(grouped.get(groupedId));
                }
            }
            messages.clear();
            messages.addAll(result);
        }
    }
}
