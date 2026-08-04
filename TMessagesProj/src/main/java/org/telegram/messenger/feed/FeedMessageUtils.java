package org.telegram.messenger.feed;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.tgnet.RequestDelegate;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.BulletinFactory;

import java.util.ArrayList;
import java.util.Calendar;

public abstract class FeedMessageUtils {

    public static boolean isAllowedDoubleTapAction(int id) {
        return id == 2 || id == 3 || id == 4 || id == 6 || id == 9;
    }

    public static boolean isAllowedFeedOption(int id) {
        return id == 2 || id == 3 || id == 4 || id == 6 || id == 7 || id == 8 || id == 10 || id == 16 || id == 22 || id == 29 || id == 36 || id == 200 || id == 203 || id == 206;
    }

    public static boolean isPostRow(MessageObject messageObject) {
        return messageObject != null && !messageObject.isDateObject && messageObject.type != 6 && !messageObject.isSponsored();
    }

    public static MessageObject createUnreadDivider(int account, int stableId) {
        TLRPC.TL_message message = new TLRPC.TL_message();
        message.message = "";
        message.id = 0;
        MessageObject obj = new MessageObject(account, message, false, false);
        obj.type = 6;
        obj.contentType = 2;
        obj.stableId = stableId;
        return obj;
    }

    public static MessageObject createDateHeader(int account, MessageObject sourceMsg, int stableId) {
        TLRPC.TL_message message = new TLRPC.TL_message();
        message.message = LocaleController.formatDateChat(sourceMsg.messageOwner.date);
        message.id = 0;
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(((long) sourceMsg.messageOwner.date) * 1000);
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        message.date = (int) (cal.getTimeInMillis() / 1000);
        MessageObject header = new MessageObject(account, message, false, false);
        header.type = 10;
        header.contentType = 1;
        header.isDateObject = true;
        header.stableId = stableId;
        return header;
    }

    public static TLRPC.InputPeer getInputPeerForMessageRequest(MessagesController controller, long dialogId, boolean useFeedContext, MessageObject msg) {
        if (useFeedContext && msg != null) {
            dialogId = msg.getDialogId();
        }
        return controller.getInputPeer(dialogId);
    }

    public static boolean matchesPlaybackNotification(int account, MessageObject msg, int syntheticId) {
        if (msg == null) return false;
        if (msg.getId() == syntheticId) return true;
        FeedController fc = FeedController.peekInstance(account);
        if (fc == null) return false;
        long realDialogId = fc.resolveRealDialogId(syntheticId);
        return realDialogId != 0 && realDialogId == msg.getDialogId() && fc.resolveRealMessageId(realDialogId, syntheticId) == msg.getRealId();
    }

    public static int getPlaybackScrollMessageId(boolean isFeedSearch, long dialogId, MessageObject msg) {
        if (msg != null && msg.searchType == 4 && !isFeedSearch && msg.getDialogId() == dialogId) {
            return msg.getRealId();
        }
        if (msg != null) return msg.getId();
        return 0;
    }

    public static MessageObject getForwardingMessageObject(int account, boolean isFeed, MessageObject src) {
        if (!isFeed || src == null || src.getId() == src.getRealId()) {
            return src;
        }
        TLRPC.TL_message copy = copyMessage(src.messageOwner);
        copy.id = src.getRealId();
        copy.realId = 0;
        copy.dialog_id = src.getDialogId();
        MessageObject result = new MessageObject(account, copy, src.replyMessageObject, null, null, null, null, false, true, 0L, false, false, false);
        result.isPrimaryGroupMessage = src.isPrimaryGroupMessage;
        result.localGroupId = src.localGroupId;
        result.copyStableParams(src);
        return result;
    }

    public static MessageObject createReplacement(int account, long dialogId, MessageObject src) {
        if (src == null) return null;
        FeedController fc = FeedController.getInstance(account);
        MessageObject feedMsg = fc.getMessage(dialogId, src.getRealId());
        if (feedMsg == null) return null;
        TLRPC.TL_message copy = copyMessage(src.messageOwner);
        copy.id = feedMsg.getId();
        copy.realId = feedMsg.getRealId();
        copy.dialog_id = feedMsg.getDialogId();
        MessageObject result = new MessageObject(account, copy, feedMsg.replyMessageObject, null, null, null, null, true, true, 0L, false, false, false, 4);
        result.isPrimaryGroupMessage = feedMsg.isPrimaryGroupMessage;
        result.localGroupId = feedMsg.localGroupId;
        result.copyStableParams(feedMsg);
        fc.replaceMessage(feedMsg, result);
        return result;
    }

    public static ArrayList<MessageObject> createReplacements(int account, long dialogId, ArrayList<MessageObject> list) {
        ArrayList<MessageObject> result = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            MessageObject r = createReplacement(account, dialogId, list.get(i));
            if (r != null) result.add(r);
        }
        return result;
    }

    public static void filterAllowedOptions(ArrayList<CharSequence> texts, ArrayList<Integer> ids, ArrayList<Integer> icons) {
        for (int i = ids.size() - 1; i >= 0; i--) {
            if (!isAllowedFeedOption(ids.get(i))) {
                icons.remove(i);
                texts.remove(i);
                ids.remove(i);
            }
        }
    }

    public static void copyFeedPostLink(ChatActivity activity, MessageObject msg) {
        if (activity == null || msg == null) return;
        TLRPC.Chat chat = activity.getMessagesController().getChat(-msg.getDialogId());
        if (chat == null || !ChatObject.isChannel(chat)) return;
        TLRPC.TL_channels_exportMessageLink req = new TLRPC.TL_channels_exportMessageLink();
        req.id = msg.getRealId();
        req.channel = MessagesController.getInputChannel(chat);
        activity.getConnectionsManager().sendRequest(req, (response, error) ->
            AndroidUtilities.runOnUIThread(() -> {
                if (response instanceof TLRPC.TL_exportedMessageLink) {
                    String link = ((TLRPC.TL_exportedMessageLink) response).link;
                    if (AndroidUtilities.addToClipboard(link) && BulletinFactory.canShowBulletin(activity)) {
                        BulletinFactory.of(activity).createCopyLinkBulletin(link.contains("/c/")).show();
                    }
                }
            })
        );
    }

    public static void copyTranslationState(MessageObject src, MessageObject dst) {
        if (src == null || dst == null || src == dst) return;
        TLRPC.Message s = src.messageOwner;
        TLRPC.Message d = dst.messageOwner;
        if (s == null || d == null) return;
        d.translatedText = s.translatedText;
        d.translatedToLanguage = s.translatedToLanguage;
        d.translatedVoiceTranscription = s.translatedVoiceTranscription;
        d.translatedPoll = s.translatedPoll;
        d.summaryText = s.summaryText;
        d.summarizedOpen = s.summarizedOpen;
        d.translatedSummaryText = s.translatedSummaryText;
        d.translatedSummaryLanguage = s.translatedSummaryLanguage;
    }

    private static TLRPC.TL_message copyMessage(TLRPC.Message src) {
        TLRPC.TL_message dst = new TLRPC.TL_message();
        dst.id = src.id;
        dst.from_id = src.from_id;
        dst.from_boosts_applied = src.from_boosts_applied;
        dst.peer_id = src.peer_id;
        dst.saved_peer_id = src.saved_peer_id;
        dst.date = src.date;
        dst.expire_date = src.expire_date;
        dst.action = src.action;
        dst.message = src.message;
        dst.media = src.media;
        dst.flags = src.flags;
        dst.flags2 = src.flags2;
        dst.mentioned = src.mentioned;
        dst.media_unread = src.media_unread;
        dst.out = src.out;
        dst.unread = src.unread;
        dst.entities = src.entities;
        dst.via_bot_name = src.via_bot_name;
        dst.reply_markup = src.reply_markup;
        dst.views = src.views;
        dst.forwards = src.forwards;
        dst.replies = src.replies;
        dst.edit_date = src.edit_date;
        dst.silent = src.silent;
        dst.post = src.post;
        dst.from_scheduled = src.from_scheduled;
        dst.legacy = src.legacy;
        dst.edit_hide = src.edit_hide;
        dst.pinned = src.pinned;
        dst.fwd_from = src.fwd_from;
        dst.via_bot_id = src.via_bot_id;
        dst.via_business_bot_id = src.via_business_bot_id;
        dst.reply_to = src.reply_to;
        dst.post_author = src.post_author;
        dst.grouped_id = src.grouped_id;
        dst.reactions = src.reactions;
        dst.restriction_reason = src.restriction_reason;
        dst.ttl_period = src.ttl_period;
        dst.quick_reply_shortcut_id = src.quick_reply_shortcut_id;
        dst.effect = src.effect;
        dst.noforwards = src.noforwards;
        dst.invert_media = src.invert_media;
        dst.offline = src.offline;
        dst.factcheck = src.factcheck;
        dst.send_state = src.send_state;
        dst.fwd_msg_id = src.fwd_msg_id;
        dst.params = src.params;
        dst.random_id = src.random_id;
        dst.local_id = src.local_id;
        dst.attachPath = src.attachPath;
        dst.dialog_id = src.dialog_id;
        dst.ttl = src.ttl;
        dst.destroyTime = src.destroyTime;
        dst.destroyTimeMillis = src.destroyTimeMillis;
        dst.layer = src.layer;
        dst.seq_in = src.seq_in;
        dst.seq_out = src.seq_out;
        dst.with_my_score = src.with_my_score;
        dst.replyMessage = src.replyMessage;
        dst.reqId = src.reqId;
        dst.realId = src.realId;
        dst.stickerVerified = src.stickerVerified;
        dst.isThreadMessage = src.isThreadMessage;
        dst.voiceTranscription = src.voiceTranscription;
        dst.voiceTranscriptionOpen = src.voiceTranscriptionOpen;
        dst.voiceTranscriptionRated = src.voiceTranscriptionRated;
        dst.voiceTranscriptionFinal = src.voiceTranscriptionFinal;
        dst.voiceTranscriptionForce = src.voiceTranscriptionForce;
        dst.voiceTranscriptionId = src.voiceTranscriptionId;
        dst.premiumEffectWasPlayed = src.premiumEffectWasPlayed;
        dst.originalLanguage = src.originalLanguage;
        dst.translatedToLanguage = src.translatedToLanguage;
        dst.translatedText = src.translatedText;
        dst.replyStory = src.replyStory;
        dst.quick_reply_shortcut = src.quick_reply_shortcut;
        return dst;
    }
}