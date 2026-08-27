package zxc.iconic.xenon.helpers;

import android.text.TextUtils;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.NativeByteBuffer;
import org.telegram.tgnet.TLRPC;

/**
 * Shared helpers for re-sending locally-saved deleted messages (forward-as-copy,
 * reply-with-quote). Deleted messages can't reference anything on the server, so
 * their author/content must be embedded into the outgoing message itself.
 */
public final class DeletedMessagesSendHelper {

    private DeletedMessagesSendHelper() {}

    /**
     * Returns the user id of the message author, or 0 if the author is a channel/chat
     * or cannot be resolved. Clickable mention entities only work for users.
     */
    public static long getMentionUserId(int account, MessageObject msg) {
        if (msg == null || msg.messageOwner == null) return 0;
        if (msg.isOutOwner()) {
            return UserConfig.getInstance(account).clientUserId;
        }
        long fromId = msg.getFromChatId();
        return fromId > 0 ? fromId : 0;
    }

    /**
     * Display name for attribution: first name for users, title for chats/channels.
     */
    public static String getAuthorName(int account, MessageObject msg) {
        if (msg == null || msg.messageOwner == null) return null;
        long mentionUserId = getMentionUserId(account, msg);
        if (mentionUserId != 0) {
            TLRPC.User user = MessagesController.getInstance(account).getUser(mentionUserId);
            return user != null ? UserObject.getFirstName(user, false) : null;
        }
        long fromId = msg.getFromChatId();
        if (fromId < 0) {
            TLRPC.Chat chat = MessagesController.getInstance(account).getChat(-fromId);
            if (chat != null) {
                return chat.title;
            }
        }
        return null;
    }

    /**
     * Original text/caption of the message, or a short media placeholder.
     */
    public static String describeContent(MessageObject msg) {
        if (msg != null && msg.messageOwner != null && !TextUtils.isEmpty(msg.messageOwner.message)) {
            return msg.messageOwner.message;
        }
        if (msg == null) return "";
        if (msg.isPhoto()) return "\uD83D\uDDBC " + LocaleController.getString(R.string.AttachPhoto);
        if (msg.isVideo()) return "\uD83C\uDFAC " + LocaleController.getString(R.string.AttachVideo);
        if (msg.isVoice()) return "\uD83C\uDF99 " + LocaleController.getString(R.string.AttachAudio);
        if (msg.isMusic()) return "\uD83C\uDFB5 " + LocaleController.getString(R.string.AttachMusic);
        if (msg.isGif()) return "GIF";
        if (msg.isSticker() || msg.isAnimatedSticker()) return LocaleController.getString(R.string.AttachSticker);
        if (msg.isDocument()) return "\uD83D\uDCCE " + LocaleController.getString(R.string.AttachDocument);
        return LocaleController.getString(R.string.ViewDeletedMedia);
    }

    /**
     * Deep-copies a message entity so its offset can be shifted without mutating
     * the original (entities belong to the still-rendered local copy).
     */
    public static TLRPC.MessageEntity cloneEntity(TLRPC.MessageEntity entity) {
        if (entity == null) return null;
        try {
            NativeByteBuffer buffer = new NativeByteBuffer(entity.getObjectSize());
            entity.serializeToStream(buffer);
            buffer.rewind();
            int constructor = buffer.readInt32(false);
            TLRPC.MessageEntity clone = TLRPC.MessageEntity.TLdeserialize(buffer, constructor, false);
            buffer.reuse();
            return clone;
        } catch (Exception e) {
            return null;
        }
    }
}
