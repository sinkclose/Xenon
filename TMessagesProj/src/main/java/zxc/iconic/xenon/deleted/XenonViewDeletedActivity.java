package zxc.iconic.xenon.deleted;

import android.content.Context;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import android.os.Bundle;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ContactsController;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.BulletinFactory;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * A read-only viewer that lists saved deleted messages of a dialog as text cards
 * (date + author + text/media placeholder), backed by the file-based
 * {@link XenonDeletedMessagesController}. Messages are paged lazily: only the newest
 * {@link #PAGE_SIZE} messages are built when the screen opens (scrolled to the bottom,
 * i.e. the newest message) and older pages are loaded while the user scrolls up.
 */
public class XenonViewDeletedActivity extends BaseFragment {

    private static final int PAGE_SIZE = 50;
    private static final int LOAD_MORE_THRESHOLD = 10;

    private final long dialogId;
    private final int account;

    private RecyclerView listView;
    private LinearLayoutManager layoutManager;
    private DeletedMessagesAdapter adapter;
    private TextView emptyView;

    private ArrayList<MessageObject> messages = new ArrayList<>();
    // all saved ids sorted ascending (oldest first)
    private ArrayList<Integer> allIds = new ArrayList<>();
    // index into allIds of the oldest currently loaded message
    private int loadedFrom;
    private boolean loadingPage;
    private boolean initialLoaded;

    public XenonViewDeletedActivity(long dialogId, int account) {
        this.dialogId = dialogId;
        this.account = account;
        setCurrentAccount(account);
    }

    @Override
    public boolean onFragmentCreate() {
        super.onFragmentCreate();
        setCurrentAccount(account);
        return true;
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

        FrameLayout frameLayout = new FrameLayout(context);
        frameLayout.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));

        adapter = new DeletedMessagesAdapter(context);
        layoutManager = new LinearLayoutManager(context);
        layoutManager.setStackFromEnd(true);

        listView = new RecyclerView(context);
        listView.setLayoutManager(layoutManager);
        listView.setAdapter(adapter);
        listView.setClipToPadding(false);
        listView.setPadding(0, 0, 0, AndroidUtilities.dp(8));
        listView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        listView.setVerticalScrollBarEnabled(false);
        listView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                maybeLoadOlder();
            }
        });
        frameLayout.addView(listView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        emptyView = new TextView(context);
        emptyView.setText(LocaleController.getString(R.string.ViewDeletedEmpty));
        emptyView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(32), AndroidUtilities.dp(16), AndroidUtilities.dp(32));
        emptyView.setVisibility(View.GONE);
        frameLayout.addView(emptyView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        fragmentView = frameLayout;

        messages.clear();
        allIds = new ArrayList<>();
        loadedFrom = 0;
        initialLoaded = false;
        loadingPage = true;
        XenonDeletedMessagesController.getInstance().getSortedSavedMessageIds(dialogId, account, ids -> {
            loadingPage = false;
            if (getContext() == null || listView == null) return;
            if (ids == null || ids.isEmpty()) {
                initialLoaded = true;
                updateEmptyVisibility();
                return;
            }
            allIds = ids;
            actionBar.setSubtitle(String.valueOf(allIds.size()));
            loadedFrom = Math.max(0, allIds.size() - PAGE_SIZE);
            loadRange(new ArrayList<>(allIds.subList(loadedFrom, allIds.size())), true);
        });
        return fragmentView;
    }

    private void maybeLoadOlder() {
        if (loadingPage || !initialLoaded || allIds.isEmpty() || loadedFrom <= 0 || getContext() == null) {
            return;
        }
        int first = layoutManager.findFirstVisibleItemPosition();
        if (first == RecyclerView.NO_POSITION || first > LOAD_MORE_THRESHOLD) {
            return;
        }
        int from = Math.max(0, loadedFrom - PAGE_SIZE);
        loadRange(new ArrayList<>(allIds.subList(from, loadedFrom)), false);
        loadedFrom = from;
    }

    private void loadRange(ArrayList<Integer> ids, boolean initial) {
        if (ids.isEmpty()) {
            initialLoaded = true;
            updateEmptyVisibility();
            return;
        }
        loadingPage = true;
        XenonDeletedMessagesController.getInstance().getMessagesByIds(dialogId, account, ids, objs -> {
            loadingPage = false;
            if (!initialLoaded && (getContext() == null || listView == null)) return;
            if (objs == null || objs.isEmpty()) {
                initialLoaded = true;
                updateEmptyVisibility();
                return;
            }
            if (initial) {
                initialLoaded = true;
                messages.addAll(objs);
                adapter.notifyDataSetChanged();
                updateEmptyVisibility();
            } else {
                messages.addAll(0, objs);
                adapter.notifyItemRangeInserted(0, objs.size());
            }
        });
    }

    private void updateEmptyVisibility() {
        if (emptyView == null) return;
        boolean empty = messages.isEmpty();
        emptyView.setVisibility(empty ? View.VISIBLE : View.GONE);
        listView.setVisibility(empty ? View.GONE : View.VISIBLE);
    }

    private void openInChat(MessageObject obj) {
        if (obj == null) return;
        org.telegram.messenger.FileLog.d("XenonViewDeleted: openInChat dialog=" + dialogId + " msg=" + obj.getId());
        XenonDeletedState.setPendingHighlight(dialogId, obj.getId());
        Bundle args = new Bundle();
        if (DialogObject.isEncryptedDialog(dialogId)) {
            args.putInt("enc_id", DialogObject.getEncryptedChatId(dialogId));
        } else if (DialogObject.isUserDialog(dialogId)) {
            args.putLong("user_id", dialogId);
        } else {
            args.putLong("chat_id", -dialogId);
        }
        args.putInt("message_id", obj.getId());
        ChatActivity chatActivity = new ChatActivity(args);
        chatActivity.setCurrentAccount(account);
        boolean ok = presentFragment(chatActivity);
        if (!ok) {
            BulletinFactory.of(this).createSimpleBulletin(R.raw.error, "Не удалось открыть чат").show();
        }
    }

    private class DeletedMessagesAdapter extends RecyclerView.Adapter<DeletedMessagesAdapter.Cell> {

        private final Context context;

        DeletedMessagesAdapter(Context context) {
            this.context = context;
            setHasStableIds(true);
        }

        @NonNull
        @Override
        public Cell onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Cell(parent.getContext());
        }

        @Override
        public void onBindViewHolder(@NonNull Cell holder, int position) {
            MessageObject obj = messages.get(position);
            holder.bind(obj);
        }

        @Override
        public int getItemCount() {
            return messages.size();
        }

        @Override
        public long getItemId(int position) {
            MessageObject obj = messages.get(position);
            return obj != null ? obj.getId() : RecyclerView.NO_ID;
        }

        private class Cell extends RecyclerView.ViewHolder {

            private final TextView dateView;
            private final TextView nameView;
            private final TextView textView;
            private MessageObject boundObject;

            Cell(Context context) {
                super(new LinearLayout(context));
                LinearLayout card = (LinearLayout) itemView;
                card.setOrientation(LinearLayout.VERTICAL);
                card.setBackground(Theme.createSelectorWithBackgroundDrawable(Theme.getColor(Theme.key_windowBackgroundWhite), Theme.getColor(Theme.key_listSelector)));
                card.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(12), AndroidUtilities.dp(16), AndroidUtilities.dp(12));
                card.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                card.setClickable(true);
                card.setFocusable(true);
                itemView.setOnClickListener(v -> {
                    if (boundObject != null) {
                        openInChat(boundObject);
                    }
                });

                dateView = new TextView(context);
                dateView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
                dateView.setTextSize(12);
                card.addView(dateView);

                nameView = new TextView(context);
                nameView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText));
                nameView.setTextSize(13);
                nameView.setTypeface(AndroidUtilities.bold());
                nameView.setPadding(0, AndroidUtilities.dp(1), 0, 0);
                card.addView(nameView);

                textView = new TextView(context);
                textView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
                textView.setTextSize(15);
                textView.setPadding(0, AndroidUtilities.dp(3), 0, 0);
                card.addView(textView);
            }

            void bind(MessageObject obj) {
                boundObject = obj;
                long ts = obj.messageOwner != null ? obj.messageOwner.date * 1000L : 0L;
                dateView.setText(ts > 0 ? DateFormat.format("dd.MM.yyyy HH:mm:ss", new Date(ts)).toString() : "");
                String author = resolveAuthorName(obj);
                nameView.setVisibility(TextUtils.isEmpty(author) ? View.GONE : View.VISIBLE);
                if (!TextUtils.isEmpty(author)) {
                    nameView.setText(author);
                }
                textView.setText(describe(obj));
            }
        }
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
