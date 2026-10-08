package org.telegram.messenger;

import java.util.concurrent.ConcurrentHashMap;
import java.util.HashSet;
import org.telegram.tgnet.TLRPC;

/** Local account identities are separate from Telegram's server-side send-as peers. */
public final class AccountSendAs {
    /** One loader per chat screen; storage reads must never block the UI thread. */
    public static final class Loader {
        private final HashSet<String> requested = new HashSet<>();
        private boolean closed;

        public void load(int owner, long dialogId, Runnable onLoaded) {
            if (closed || !DialogObject.isChatDialog(dialogId)) return;
            for (int i = 0; i < UserConfig.MAX_ACCOUNT_COUNT; i++) {
                if (i == owner || !UserConfig.getInstance(i).isClientActivated()) continue;
                final int account = i;
                final long userId = UserConfig.getInstance(account).getClientUserId();
                if (!requested.add(account + ":" + userId + ":" + dialogId)) continue;
                MessagesController controller = MessagesController.getInstance(account);
                if (controller.getChat(-dialogId) != null) {
                    if (isMember(account, dialogId)) controller.loadFullChat(-dialogId, 0, false);
                    continue;
                }
                MessagesStorage storage = MessagesStorage.getInstance(account);
                storage.getStorageQueue().postRunnable(() -> {
                    TLRPC.Chat storedChat = storage.getChat(-dialogId);
                    AndroidUtilities.runOnUIThread(() -> {
                        if (closed || !UserConfig.getInstance(account).isClientActivated()
                                || UserConfig.getInstance(account).getClientUserId() != userId) return;
                        // A network update received meanwhile takes precedence over disk.
                        if (storedChat != null && controller.getChat(-dialogId) == null) {
                            controller.putChat(storedChat, true);
                        }
                        if (isMember(account, dialogId)) controller.loadFullChat(-dialogId, 0, false);
                        onLoaded.run();
                    });
                });
            }
        }

        public void close() { closed = true; }
    }
    public static final int UNAVAILABLE_ACCOUNT = -2;
    private static final ConcurrentHashMap<String, Selection> selections = new ConcurrentHashMap<>();
    private static final ThreadLocal<Boolean> routing = new ThreadLocal<>();

    private static final class Selection {
        final int account;
        final long ownerId, senderId;
        Selection(int owner, int account) {
            this.account = account;
            ownerId = UserConfig.getInstance(owner).getClientUserId();
            senderId = UserConfig.getInstance(account).getClientUserId();
        }
    }

    private static String key(int owner, long dialogId) {
        return owner + ":" + dialogId;
    }

    public static boolean isMember(int account, long dialogId) {
        if (account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT || !DialogObject.isChatDialog(dialogId)
                || !UserConfig.getInstance(account).isClientActivated()
                || UserConfig.getInstance(account).getCurrentUser() == null) return false;
        TLRPC.Chat chat = MessagesController.getInstance(account).getChat(-dialogId);
        return chat != null && !ChatObject.isNotInChat(chat) && !ChatObject.isKickedFromChat(chat);
    }

    public static boolean canSend(int account, long dialogId) {
        if (!isMember(account, dialogId)) return false;
        TLRPC.Chat chat = MessagesController.getInstance(account).getChat(-dialogId);
        return !MessagesController.getInstance(account).isFrozen()
                && ChatObject.canWriteToChat(chat) && ChatObject.canSendMessages(chat) && ChatObject.canSendPlain(chat);
    }

    public static boolean isListed(int owner, int account, long dialogId) {
        return isMember(account, dialogId) && (owner == account || canSend(account, dialogId));
    }

    public static boolean hasAlternative(int owner, long dialogId) {
        if (!isMember(owner, dialogId)) return false;
        for (int i = 0; i < UserConfig.MAX_ACCOUNT_COUNT; i++) {
            if (i != owner && canSend(i, dialogId)) return true;
        }
        return false;
    }

    public static void set(int owner, long dialogId, int account) {
        String key = key(owner, dialogId);
        if (account == owner || !isMember(owner, dialogId) || !canSend(account, dialogId)) selections.remove(key);
        else selections.put(key, new Selection(owner, account));
    }

    public static int get(int owner, long dialogId) {
        String key = key(owner, dialogId);
        Selection selection = selections.get(key);
        if (selection == null) return -1;
        if (!isMember(owner, dialogId) || !canSend(selection.account, dialogId)
                || selection.ownerId != UserConfig.getInstance(owner).getClientUserId()
                || selection.senderId != UserConfig.getInstance(selection.account).getClientUserId()) {
            selections.remove(key, selection);
            return -1;
        }
        return selection.account;
    }

    public static boolean isRouting() { return Boolean.TRUE.equals(routing.get()); }

    public static int forSending(int owner, long dialogId) {
        if (isRouting()) return -1;
        if (!selections.containsKey(key(owner, dialogId))) return -1;
        int account = get(owner, dialogId);
        return account < 0 ? UNAVAILABLE_ACCOUNT : account;
    }

    /** A redirected send must never follow the destination account's own selection. */
    public static void route(Runnable send) {
        Boolean previous = routing.get();
        routing.set(true);
        try { send.run(); }
        finally {
            if (previous == null) routing.remove();
            else routing.set(previous);
        }
    }

    private AccountSendAs() {}
}
