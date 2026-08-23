package zxc.iconic.xenon.settings;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;

import java.util.ArrayList;

import zxc.iconic.xenon.NekoConfig;

public class NekoMentionSettingsActivity extends BaseNekoSettingsActivity {

    private static final int MENTION_BASE = 1000;

    private ArrayList<String> usernames;
    private static NekoMentionSettingsActivity currentInstance;

    public NekoMentionSettingsActivity() {
        usernames = new ArrayList<>(NekoConfig.customMentionUsernames);
        while (usernames.size() < 5) usernames.add("");
        if (usernames.size() > 5) usernames = new ArrayList<>(usernames.subList(0, 5));
        currentInstance = this;
    }

    @Override
    public void onFragmentDestroy() {
        super.onFragmentDestroy();
        if (currentInstance == this) currentInstance = null;
    }

    static NekoMentionSettingsActivity getCurrentInstance() {
        return currentInstance;
    }

    @Override
    public View createView(Context context) {
        View view = super.createView(context);
        listView.listenReorder((sectionId, items) -> {
            ArrayList<String> reordered = new ArrayList<>();
            for (UItem it : items) {
                String t = it.text != null ? it.text.toString().trim().replace("@", "") : "";
                reordered.add(t);
            }
            usernames.clear();
            usernames.addAll(reordered);
            while (usernames.size() < 5) usernames.add("");
            ArrayList<String> toSave = new ArrayList<>();
            for (String s : usernames) if (!TextUtils.isEmpty(s)) toSave.add(s);
            NekoConfig.setCustomMentionUsernames(toSave);
        });
        listView.allowReorder(true);
        listView.setReorderLongPressEnabled(true);
        return view;
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asHeader("Customize mention menu"));
        boolean hasAny = false;
        for (String s : usernames) if (!TextUtils.isEmpty(s)) { hasAny = true; break; }
        if (!hasAny) {
            items.add(UItem.asShadow("Default behavior — top inline bots are shown. Fill at least one field to replace them."));
        } else {
            items.add(UItem.asShadow("Custom list is active — standard inline bots are hidden. Drag to reorder."));
        }
        adapter.whiteSectionStart();
        adapter.reorderSectionStart();
        for (int i = 0; i < 5; i++) {
            String uname = i < usernames.size() ? usernames.get(i) : "";
            UItem it = MentionEditCellFactory.of(MENTION_BASE + i, uname);
            it.text = uname;
            items.add(it);
        }
        adapter.reorderSectionEnd();
        adapter.whiteSectionEnd();
        items.add(UItem.asShadow(null));
    }

    @Override
    protected void onItemClick(UItem item, View view, int position, float x, float y) {
        int id = item.id;
        if (id >= MENTION_BASE && id < MENTION_BASE + 100) {
            if (view instanceof MentionEditCell) {
                ((MentionEditCell) view).focusEditText();
            }
        }
    }

    @Override
    protected String getActionBarTitle() {
        return "Customize mention menu";
    }

    @Override
    protected String getKey() {
        return "mention_custom";
    }

    void removeItem(UItem item) {
        if (item == null) return;
        // clear instead of removing, keep 5
        item.text = "";
        // find its index and clear
        int mentionIdx = -1;
        int idx = 0;
        for (int i = 0; i < listView.adapter.getItemCount(); i++) {
            UItem it = listView.adapter.getItem(i);
            if (it != null && it.id >= MENTION_BASE && it.id < MENTION_BASE + 100) {
                if (it == item) { mentionIdx = idx; break; }
                idx++;
            }
        }
        if (mentionIdx >= 0 && mentionIdx < usernames.size()) {
            usernames.set(mentionIdx, "");
            ArrayList<String> toSave = new ArrayList<>();
            for (String s : usernames) if (!TextUtils.isEmpty(s)) toSave.add(s);
            NekoConfig.setCustomMentionUsernames(toSave);
            listView.adapter.update(true);
        }
    }

    void updateItem(UItem item, String newUsername) {
        if (item == null) return;
        String t = newUsername == null ? "" : newUsername.trim().replace("@", "").replace(" ", "");
        item.text = t;
        ArrayList<String> newOrder = new ArrayList<>();
        for (int i = 0; i < listView.adapter.getItemCount(); i++) {
            UItem it = listView.adapter.getItem(i);
            if (it != null && it.id >= MENTION_BASE && it.id < MENTION_BASE + 100) {
                String v = it.text != null ? it.text.toString().trim().replace("@", "") : "";
                newOrder.add(v);
            }
        }
        usernames.clear();
        usernames.addAll(newOrder);
        while (usernames.size() < 5) usernames.add("");
        ArrayList<String> toSave = new ArrayList<>();
        for (String s : usernames) if (!TextUtils.isEmpty(s)) toSave.add(s);
        NekoConfig.setCustomMentionUsernames(toSave);
    }

    private static class MentionEditCellFactory extends UItem.UItemFactory<MentionEditCell> {
        static {
            setup(new MentionEditCellFactory());
        }

        @Override
        public MentionEditCell createView(Context context, RecyclerListView listView, int currentAccount, int classGuid, Theme.ResourcesProvider resourcesProvider) {
            return new MentionEditCell(context, resourcesProvider);
        }

        @Override
        public void bindView(View view, UItem item, boolean divider, UniversalAdapter adapter, UniversalRecyclerView listView) {
            MentionEditCell cell = (MentionEditCell) view;
            String uname = item.text != null ? item.text.toString() : "";
            cell.setItem(item, uname);
            cell.setDivider(divider);
        }

        public static UItem of(int id, String username) {
            UItem item = UItem.ofFactory(MentionEditCellFactory.class);
            item.id = id;
            item.text = username;
            return item;
        }

        @Override
        public boolean isClickable() {
            return false;
        }
    }

    private static class MentionEditCell extends FrameLayout {
        private final BackupImageView avatarView;
        private final AvatarDrawable avatarDrawable;
        private final EditText editText;
        private final ImageView deleteView;
        private final ImageView dragHandle;
        private UItem boundItem;
        private boolean ignoreTextChange = false;

        public MentionEditCell(Context context, Theme.ResourcesProvider resourcesProvider) {
            super(context);
            avatarDrawable = new AvatarDrawable();
            avatarDrawable.setTextSize(AndroidUtilities.dp(18));

            // Use RelativeLayout to ensure EditText stretches to max between avatar and delete
            android.widget.RelativeLayout container = new android.widget.RelativeLayout(context);
            int hMargin = zxc.iconic.xenon.helpers.M3SectionsHelper.isEnabled() ? 0 : 16;
            addView(container, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 56, Gravity.CENTER_VERTICAL, hMargin, 0, hMargin, 0));

            dragHandle = new ImageView(context);
            dragHandle.setId(View.generateViewId());
            Drawable dragDrawable = context.getResources().getDrawable(R.drawable.msg_reorder).mutate();
            dragDrawable.setColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteGrayIcon, resourcesProvider), PorterDuff.Mode.SRC_IN);
            dragHandle.setImageDrawable(dragDrawable);
            dragHandle.setPadding(AndroidUtilities.dp(8), AndroidUtilities.dp(8), AndroidUtilities.dp(8), AndroidUtilities.dp(8));
            android.widget.RelativeLayout.LayoutParams dragLp = new android.widget.RelativeLayout.LayoutParams(AndroidUtilities.dp(40), AndroidUtilities.dp(40));
            dragLp.addRule(android.widget.RelativeLayout.ALIGN_PARENT_LEFT);
            dragLp.addRule(android.widget.RelativeLayout.CENTER_VERTICAL);
            container.addView(dragHandle, dragLp);

            avatarView = new BackupImageView(context);
            avatarView.setId(View.generateViewId());
            avatarView.setRoundRadius(AndroidUtilities.dp(16));
            android.widget.RelativeLayout.LayoutParams avatarLp = new android.widget.RelativeLayout.LayoutParams(AndroidUtilities.dp(32), AndroidUtilities.dp(32));
            avatarLp.addRule(android.widget.RelativeLayout.RIGHT_OF, dragHandle.getId());
            avatarLp.addRule(android.widget.RelativeLayout.CENTER_VERTICAL);
            avatarLp.leftMargin = AndroidUtilities.dp(4);
            avatarLp.rightMargin = AndroidUtilities.dp(8);
            container.addView(avatarView, avatarLp);

            deleteView = new ImageView(context);
            deleteView.setId(View.generateViewId());
            Drawable del = context.getResources().getDrawable(R.drawable.msg_close).mutate();
            del.setColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteGrayIcon, resourcesProvider), PorterDuff.Mode.SRC_IN);
            deleteView.setImageDrawable(del);
            deleteView.setPadding(AndroidUtilities.dp(8), AndroidUtilities.dp(8), AndroidUtilities.dp(8), AndroidUtilities.dp(8));
            android.widget.RelativeLayout.LayoutParams deleteLp = new android.widget.RelativeLayout.LayoutParams(AndroidUtilities.dp(40), AndroidUtilities.dp(40));
            deleteLp.addRule(android.widget.RelativeLayout.ALIGN_PARENT_RIGHT);
            deleteLp.addRule(android.widget.RelativeLayout.CENTER_VERTICAL);
            container.addView(deleteView, deleteLp);

            editText = new EditText(context);
            editText.setHint("@username");
            editText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            editText.setSingleLine(true);
            editText.setHorizontallyScrolling(true);
            editText.setMaxLines(1);
            editText.setGravity(Gravity.CENTER_VERTICAL);
            Drawable bg = Theme.createRoundRectDrawable(AndroidUtilities.dp(8), Theme.getColor(Theme.key_windowBackgroundGray, resourcesProvider));
            editText.setBackground(bg);
            editText.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider));
            editText.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText, resourcesProvider));
            editText.setPadding(AndroidUtilities.dp(12), AndroidUtilities.dp(9), AndroidUtilities.dp(12), AndroidUtilities.dp(9));
            android.widget.RelativeLayout.LayoutParams editLp = new android.widget.RelativeLayout.LayoutParams(0, AndroidUtilities.dp(36));
            editLp.addRule(android.widget.RelativeLayout.RIGHT_OF, avatarView.getId());
            editLp.addRule(android.widget.RelativeLayout.LEFT_OF, deleteView.getId());
            editLp.addRule(android.widget.RelativeLayout.CENTER_VERTICAL);
            editLp.leftMargin = AndroidUtilities.dp(8);
            editLp.rightMargin = AndroidUtilities.dp(8);
            // width will be match_parent between avatar and delete via rules, height 36
            editLp.width = android.widget.RelativeLayout.LayoutParams.MATCH_PARENT;
            container.addView(editText, editLp);

            deleteView.setOnClickListener(v -> {
                NekoMentionSettingsActivity act = NekoMentionSettingsActivity.getCurrentInstance();
                if (act != null && boundItem != null) {
                    act.removeItem(boundItem);
                }
            });
            container.addView(deleteView, LayoutHelper.createLinear(40, 40, Gravity.CENTER_VERTICAL));

            editText.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
                @Override public void afterTextChanged(Editable s) {
                    if (ignoreTextChange) return;
                    NekoMentionSettingsActivity act = NekoMentionSettingsActivity.getCurrentInstance();
                    if (act != null && boundItem != null) {
                        act.updateItem(boundItem, s.toString());
                        updateAvatar(s.toString());
                    } else {
                        updateAvatar(s.toString());
                    }
                }
            });

            setWillNotDraw(false);
        }

        void setItem(UItem item, String username) {
            this.boundItem = item;
            ignoreTextChange = true;
            editText.setText(username);
            editText.setSelection(editText.getText().length());
            ignoreTextChange = false;
            updateAvatar(username);
        }

        void focusEditText() {
            editText.requestFocus();
            AndroidUtilities.showKeyboard(editText);
        }

        private void updateAvatar(String usernameRaw) {
            String uname = usernameRaw == null ? "" : usernameRaw.trim().replace("@", "");
            if (TextUtils.isEmpty(uname)) {
                avatarDrawable.setInfo(0, null, null);
                avatarView.setImageDrawable(avatarDrawable);
                return;
            }
            int account = UserConfig.selectedAccount;
            TLObject obj = MessagesController.getInstance(account).getUserOrChat(uname);
            if (obj instanceof TLRPC.User) {
                TLRPC.User user = (TLRPC.User) obj;
                avatarDrawable.setInfo(user);
                avatarView.setForUserOrChat(user, avatarDrawable);
            } else if (obj instanceof TLRPC.Chat) {
                TLRPC.Chat chat = (TLRPC.Chat) obj;
                avatarDrawable.setInfo(chat);
                avatarView.setForUserOrChat(chat, avatarDrawable);
            } else {
                avatarDrawable.setInfo(0, uname, null);
                avatarView.setImageDrawable(avatarDrawable);
            }
        }

        private boolean needDivider;
        void setDivider(boolean divider) {
            needDivider = divider;
            setWillNotDraw(!divider);
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (needDivider) {
                canvas.drawLine(AndroidUtilities.dp(72), getHeight() - 1, getWidth(), getHeight() - 1, Theme.dividerPaint);
            }
        }
    }
}
