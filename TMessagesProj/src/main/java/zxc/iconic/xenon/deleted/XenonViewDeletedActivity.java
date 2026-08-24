package zxc.iconic.xenon.deleted;

import android.content.Context;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ContactsController;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;

import java.util.ArrayList;
import java.util.Date;

/**
 * A simple read-only viewer that lists every saved deleted message of a dialog as text cards
 * (date + author + text/media placeholder), backed by the file-based
 * {@link XenonDeletedMessagesController}. Kept intentionally lightweight — no paging, search or
 * context menu — matching {@code XenonEditsHistoryActivity}. Opens scrolled to the newest
 * (bottom) message.
 */
public class XenonViewDeletedActivity extends BaseFragment {

    private final long dialogId;
    private final int account;

    private ScrollView scrollView;
    private LinearLayout root;
    private TextView emptyView;

    public XenonViewDeletedActivity(long dialogId, int account) {
        this.dialogId = dialogId;
        this.account = account;
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(LocaleController.getString(R.string.ViewDeleted));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        scrollView = new ScrollView(context);
        scrollView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));

        root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        scrollView.addView(root, new FrameLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        emptyView = new TextView(context);
        emptyView.setText(LocaleController.getString(R.string.ViewDeletedEmpty));
        emptyView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(32), AndroidUtilities.dp(16), AndroidUtilities.dp(32));
        emptyView.setVisibility(View.GONE);
        root.addView(emptyView);

        fragmentView = scrollView;

        XenonDeletedMessagesController.getInstance().getAllMessagesForDialog(dialogId, account, this::bind);
        return fragmentView;
    }

    private void bind(ArrayList<MessageObject> messages) {
        if (root == null) return;
        if (messages == null || messages.isEmpty()) {
            emptyView.setVisibility(View.VISIBLE);
            return;
        }
        emptyView.setVisibility(View.GONE);
        actionBar.setSubtitle(String.valueOf(messages.size()));
        Context context = root.getContext();
        for (MessageObject obj : messages) {
            root.addView(buildCard(context, obj));
        }
        // Always open at the newest (bottom) message.
        if (scrollView != null) {
            scrollView.post(() -> scrollView.fullScroll(View.FOCUS_DOWN));
        }
    }

    private View buildCard(Context context, MessageObject obj) {
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        card.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(12), AndroidUtilities.dp(16), AndroidUtilities.dp(12));

        TextView dateView = new TextView(context);
        long ts = obj.messageOwner != null ? obj.messageOwner.date * 1000L : 0L;
        dateView.setText(ts > 0 ? DateFormat.format("dd.MM.yyyy HH:mm:ss", new Date(ts)).toString() : "");
        dateView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        dateView.setTextSize(12);
        card.addView(dateView);

        String author = resolveAuthorName(obj);
        if (!TextUtils.isEmpty(author)) {
            TextView nameView = new TextView(context);
            nameView.setText(author);
            nameView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText));
            nameView.setTextSize(13);
            nameView.setTypeface(AndroidUtilities.bold());
            nameView.setPadding(0, AndroidUtilities.dp(1), 0, 0);
            card.addView(nameView);
        }

        TextView text = new TextView(context);
        text.setText(describe(obj));
        text.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        text.setTextSize(15);
        text.setPadding(0, AndroidUtilities.dp(3), 0, 0);
        card.addView(text);

        LinearLayout wrap = new LinearLayout(context);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.addView(card);
        View divider = new View(context);
        divider.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, AndroidUtilities.dp(8)));
        wrap.addView(divider);
        return wrap;
    }

    private String resolveAuthorName(MessageObject obj) {
        if (obj.isOutOwner()) {
            TLRPC.User self = MessagesController.getInstance(account).getUser(getUserConfig().getClientUserId());
            return self != null ? UserObject.getUserName(self) : LocaleController.getString(R.string.FromYou);
        }
        long fromId = obj.getFromChatId();
        if (fromId > 0) {
            TLRPC.User user = MessagesController.getInstance(account).getUser(fromId);
            if (user != null) {
                return ContactsController.formatName(user.first_name, user.last_name);
            }
        } else if (fromId < 0) {
            TLRPC.Chat chat = MessagesController.getInstance(account).getChat(-fromId);
            if (chat != null) {
                return chat.title;
            }
        }
        return null;
    }

    private CharSequence describe(MessageObject obj) {
        CharSequence textValue = obj.messageOwner != null ? obj.messageOwner.message : null;
        if (!TextUtils.isEmpty(textValue)) {
            return textValue;
        }
        if (obj.isPhoto()) return "🖼 " + LocaleController.getString(R.string.AttachPhoto);
        if (obj.isVideo()) return "🎬 " + LocaleController.getString(R.string.AttachVideo);
        if (obj.isVoice()) return "🎤 " + LocaleController.getString(R.string.AttachAudio);
        if (obj.isMusic()) return "🎵 " + LocaleController.getString(R.string.AttachMusic);
        if (obj.isGif()) return "GIF";
        if (obj.isSticker() || obj.isAnimatedSticker()) return LocaleController.getString(R.string.AttachSticker);
        if (obj.isDocument()) return "📎 " + LocaleController.getString(R.string.AttachDocument);
        return LocaleController.getString(R.string.ViewDeletedMedia);
    }
}
