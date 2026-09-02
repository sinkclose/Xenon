package zxc.iconic.xenon.settings;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.DocumentObject;
import org.telegram.messenger.Emoji;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MediaDataController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.SvgHelper;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_stars;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.SimpleTextView;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Components.AnimatedEmojiDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.Reactions.ReactionsLayoutInBubble;
import org.telegram.ui.Components.Reactions.ReactionsUtils;
import org.telegram.ui.SelectAnimatedEmojiDialog;

import java.util.ArrayList;
import java.util.List;

import zxc.iconic.xenon.NekoConfig;

public class SwipeActionsSettingsActivity extends BaseNekoSettingsActivity {

    private static final int VIEW_TYPE_SWITCH = 0;
    private static final int VIEW_TYPE_HEADER = 1;
    private static final int VIEW_TYPE_INFO = 2;
    private static final int VIEW_TYPE_ACTION = 3;
    private static final int VIEW_TYPE_SLIDER = 4;

    private static final int ID_SHOW_NAMES = 1;

    private ItemTouchHelper itemTouchHelper;
    private boolean dragInProgress;

    @Override
    protected String getActionBarTitle() {
        return LocaleController.getString(R.string.OtherSwipeActions);
    }

    @Override
    protected String getKey() {
        return "swipeActions";
    }

    @Override
    protected void fillItems(ArrayList<org.telegram.ui.Components.UItem> items, org.telegram.ui.Components.UniversalAdapter adapter) {
        // not used - we override createView with custom RecyclerListView
    }

    @Override
    protected void onItemClick(org.telegram.ui.Components.UItem item, View view, int position, float x, float y) {}

    private RecyclerListView recyclerView;
    private SwipeAdapter swipeAdapter;

    @Override
    public View createView(Context context) {
        View base = super.createView(context);
        // super.createView already created listView; we replace its adapter
        recyclerView = listView;
        swipeAdapter = new SwipeAdapter(context);
        recyclerView.setAdapter(swipeAdapter);
        recyclerView.setLayoutManager(new LinearLayoutManager(context));
        recyclerView.setItemAnimator(new FadeItemAnimator());
        recyclerView.getItemAnimator().setChangeDuration(220);
        recyclerView.getItemAnimator().setMoveDuration(250);
        recyclerView.getItemAnimator().setAddDuration(220);
        recyclerView.getItemAnimator().setRemoveDuration(180);
        recyclerView.setOnItemClickListener((view, position) -> {
            int type = swipeAdapter.getItemViewType(position);
            if (type == VIEW_TYPE_SWITCH) {
                NekoConfig.toggleSwipeBubbleShowNames();
                swipeAdapter.notifyItemChanged(position);
                return;
            }
            if (type == VIEW_TYPE_ACTION) {
                String key = swipeAdapter.getKeyAt(position);
                if (key == null) return;
                if (swipeAdapter.isEnabledPosition(position)) {
                    int removedPos = position;
                    NekoConfig.removeSwipeAction(key);
                    int disabledPos = swipeAdapter.disabledPositionOfKey(key);
                    if (disabledPos >= 0) {
                        swipeAdapter.notifyItemRemoved(removedPos);
                        swipeAdapter.notifyItemInserted(disabledPos);
                    } else {
                        swipeAdapter.notifyDataSetChanged();
                    }
                } else {
                    if ("reaction".equals(key)) {
                        showReactionPicker(view);
                    } else {
                        int disabledPos = position;
                        int insertPos = 3 + NekoConfig.swipeEnabledActions.size();
                        NekoConfig.addSwipeAction(key, NekoConfig.swipeEnabledActions.size());
                        swipeAdapter.notifyItemRemoved(disabledPos);
                        swipeAdapter.notifyItemInserted(insertPos);
                    }
                }
            }
        });
        // long press on row body sets primary; RecyclerListView needs itemView NOT longClickable,
        // otherwise onTouchEvent consumes ACTION_DOWN (interceptedByChild) and kills all clicks
        recyclerView.setOnItemLongClickListener((view, position) -> {
            if (dragInProgress) return false;
            if (!swipeAdapter.isEnabledPosition(position)) return false;
            String key = swipeAdapter.getKeyAt(position);
            if (key == null || key.equals(NekoConfig.swipePrimaryAction)) return false;
            NekoConfig.setSwipePrimaryAction(key);
            swipeAdapter.notifyDataSetChanged();
            return true;
        });

        ItemTouchHelper.Callback callback = new ItemTouchHelper.Callback() {
            @Override
            public int getMovementFlags(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder) {
                int pos = viewHolder.getAdapterPosition();
                if (pos == RecyclerView.NO_POSITION) return 0;
                if (swipeAdapter.isEnabledPosition(pos)) {
                    int dragFlags = ItemTouchHelper.UP | ItemTouchHelper.DOWN;
                    return makeMovementFlags(dragFlags, 0);
                }
                return 0;
            }
            @Override
            public boolean onMove(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder, @NonNull RecyclerView.ViewHolder target) {
                int from = viewHolder.getAdapterPosition();
                int to = target.getAdapterPosition();
                if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false;
                if (!swipeAdapter.isEnabledPosition(from) || !swipeAdapter.isEnabledPosition(to)) return false;
                int fromIdx = swipeAdapter.enabledIndexForPosition(from);
                int toIdx = swipeAdapter.enabledIndexForPosition(to);
                NekoConfig.moveSwipeAction(fromIdx, toIdx);
                swipeAdapter.notifyItemMoved(from, to);
                return true;
            }
            @Override
            public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, int direction) {}
            @Override
            public boolean isLongPressDragEnabled() {
                return false;
            }
            @Override
            public void onSelectedChanged(RecyclerView.ViewHolder viewHolder, int actionState) {
                super.onSelectedChanged(viewHolder, actionState);
                dragInProgress = actionState == ItemTouchHelper.ACTION_STATE_DRAG;
                if (dragInProgress) {
                    // stop pending long-press of the list so drag never triggers primary
                    recyclerView.cancelClickRunnables(false);
                }
            }
            @Override
            public void clearView(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder) {
                super.clearView(recyclerView, viewHolder);
                dragInProgress = false;
            }
        };
        itemTouchHelper = new ItemTouchHelper(callback);
        itemTouchHelper.attachToRecyclerView(recyclerView);

        return base;
    }

    private void showReactionPicker(View anchor) {
        Context ctx = getContext();
        if (ctx == null) return;
        MediaDataController mdc = MediaDataController.getInstance(currentAccount);
        List<TLRPC.TL_availableReaction> available = mdc.getReactionsList();
        if (available == null || available.isEmpty()) {
            org.telegram.ui.Components.BulletinFactory.of(this).createSimpleBulletin(R.raw.info, LocaleController.getString(R.string.Loading)).show();
            return;
        }
        // same order as the ChatActivity message popup: top reactions, recent, then default order
        java.util.LinkedHashMap<String, TLRPC.TL_availableReaction> ordered = new java.util.LinkedHashMap<>();
        java.util.function.BiConsumer<TLRPC.Reaction, Boolean> addIfKnown = (r, top) -> {
            if (r instanceof TLRPC.TL_reactionEmoji) {
                TLRPC.TL_availableReaction ar = mdc.getReactionsMap().get(((TLRPC.TL_reactionEmoji) r).emoticon);
                if (ar != null) ordered.putIfAbsent(ar.reaction, ar);
            } else if (!top) {
                for (TLRPC.TL_availableReaction ar : available) {
                    if (ar.reaction.equals(String.valueOf(ReactionsLayoutInBubble.VisibleReaction.fromTL(r).documentId))) {
                        ordered.putIfAbsent(ar.reaction, ar);
                    }
                }
            }
        };
        for (TLRPC.Reaction r : mdc.getTopReactions()) addIfKnown.accept(r, true);
        for (TLRPC.Reaction r : mdc.getRecentReactions()) addIfKnown.accept(r, false);
        for (TLRPC.TL_availableReaction ar : available) ordered.putIfAbsent(ar.reaction, ar);
        final List<TLRPC.TL_availableReaction> list = new ArrayList<>(ordered.values());
        LinearLayout customView = new LinearLayout(ctx);
        customView.setOrientation(LinearLayout.VERTICAL);
        TextView customEmojiButton = new TextView(ctx);
        customEmojiButton.setText(LocaleController.getString(R.string.AccDescrCustomEmoji));
        customEmojiButton.setTextSize(16);
        customEmojiButton.setTextColor(Theme.getColor(Theme.key_dialogTextBlue));
        customEmojiButton.setTypeface(AndroidUtilities.bold());
        customEmojiButton.setGravity(Gravity.CENTER_VERTICAL);
        customEmojiButton.setPadding(AndroidUtilities.dp(21), AndroidUtilities.dp(12), AndroidUtilities.dp(21), AndroidUtilities.dp(12));
        customEmojiButton.setOnClickListener(v -> showCustomEmojiPicker());
        customView.addView(customEmojiButton, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        RecyclerListView listView = new RecyclerListView(ctx);
        listView.setLayoutManager(new LinearLayoutManager(ctx));
        listView.setVerticalScrollBarEnabled(false);
        RecyclerView.Adapter adapter = new RecyclerView.Adapter() {
            @NonNull
            @Override
            public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
                ReactionPickerCell cell = new ReactionPickerCell(parent.getContext());
                return new RecyclerListView.Holder(cell);
            }
            @Override
            public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
                ReactionPickerCell cell = (ReactionPickerCell) holder.itemView;
                TLRPC.TL_availableReaction r = list.get(position);
                boolean locked = r.premium && !getUserConfig().isPremium();
                cell.bind(r, currentAccount);
                // show lock for premium
                cell.setAlpha(locked ? 0.6f : 1f);
            }
            @Override
            public int getItemCount() { return list.size(); }
        };
        listView.setAdapter(adapter);
        customView.addView(listView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, AndroidUtilities.dp(230)));
        BottomSheet.Builder builder = new BottomSheet.Builder(ctx);
        builder.setTitle(LocaleController.getString(R.string.SwipeActionReaction), true);
        builder.setCustomView(customView);
        reactionPickerSheet = builder.show();
        listView.setOnItemClickListener((view, position) -> {
            TLRPC.TL_availableReaction r = list.get(position);
            if (r.premium && !getUserConfig().isPremium()) {
                showDialog(new org.telegram.ui.Components.Premium.PremiumFeatureBottomSheet(SwipeActionsSettingsActivity.this, org.telegram.ui.PremiumPreviewFragment.PREMIUM_FEATURE_REACTIONS, true));
                return;
            }
            String key = "reaction:" + r.reaction;
            if (!NekoConfig.swipeEnabledActions.contains(key)) {
                int insertPos = 3 + NekoConfig.swipeEnabledActions.size();
                NekoConfig.addSwipeAction(key, NekoConfig.swipeEnabledActions.size());
                swipeAdapter.notifyItemInserted(insertPos);
            }
            if (reactionPickerSheet != null) reactionPickerSheet.dismiss();
        });
    }
    private org.telegram.ui.ActionBar.BottomSheet reactionPickerSheet;
    private SelectAnimatedEmojiDialog.SelectAnimatedEmojiDialogWindow selectAnimatedEmojiDialog;
    private void showCustomEmojiPicker() {
        if (selectAnimatedEmojiDialog != null) return;
        final SelectAnimatedEmojiDialog.SelectAnimatedEmojiDialogWindow[] popup = new SelectAnimatedEmojiDialog.SelectAnimatedEmojiDialogWindow[1];
        SelectAnimatedEmojiDialog popupLayout = new SelectAnimatedEmojiDialog(this, getContext(), false, 0, SelectAnimatedEmojiDialog.TYPE_SET_DEFAULT_REACTION, null) {
            @Override
            protected void onEmojiSelected(View emojiView, Long documentId, TLRPC.Document document, TL_stars.TL_starGiftUnique gift, Integer until) {
                if (documentId == null) return;
                String key = "reaction:animated_" + documentId;
                if (!NekoConfig.swipeEnabledActions.contains(key)) {
                    int insertPos = 3 + NekoConfig.swipeEnabledActions.size();
                    NekoConfig.addSwipeAction(key, NekoConfig.swipeEnabledActions.size());
                    swipeAdapter.notifyItemInserted(insertPos);
                }
                if (popup[0] != null) { selectAnimatedEmojiDialog = null; popup[0].dismiss(); }
            }
            @Override
            protected void onReactionClick(SelectAnimatedEmojiDialog.ImageViewEmoji emoji, ReactionsLayoutInBubble.VisibleReaction reaction) {
                String key = "reaction:" + reaction.emojicon;
                if (!NekoConfig.swipeEnabledActions.contains(key)) {
                    int insertPos = 3 + NekoConfig.swipeEnabledActions.size();
                    NekoConfig.addSwipeAction(key, NekoConfig.swipeEnabledActions.size());
                    swipeAdapter.notifyItemInserted(insertPos);
                }
                if (popup[0] != null) { selectAnimatedEmojiDialog = null; popup[0].dismiss(); }
            }
        };
        List<TLRPC.TL_availableReaction> availableReactions = MediaDataController.getInstance(currentAccount).getReactionsList();
        ArrayList<ReactionsLayoutInBubble.VisibleReaction> reactions = new ArrayList<>(20);
        for (TLRPC.TL_availableReaction r : availableReactions) {
            ReactionsLayoutInBubble.VisibleReaction v = new ReactionsLayoutInBubble.VisibleReaction();
            v.emojicon = r.reaction;
            reactions.add(v);
        }
        popupLayout.setRecentReactions(reactions);
        popupLayout.setSaveState(3);
        popup[0] = selectAnimatedEmojiDialog = new SelectAnimatedEmojiDialog.SelectAnimatedEmojiDialogWindow(popupLayout, LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT) {
            @Override
            public void dismiss() { super.dismiss(); selectAnimatedEmojiDialog = null; }
        };
        // show centered
        try {
            popup[0].showAtLocation(getParentActivity().getWindow().getDecorView(), Gravity.CENTER, 0, 0);
            popup[0].dimBehind();
        } catch (Exception e) {
            // fallback to dropdown on list
            popup[0].showAsDropDown(recyclerView, 0, 0, Gravity.CENTER);
            popup[0].dimBehind();
        }
    }

    // ease-out fades; add runs in parallel with remove (default queues adds after removes)
    private static class FadeItemAnimator extends androidx.recyclerview.widget.DefaultItemAnimator {
        FadeItemAnimator() {
            setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f));
        }

        @Override
        protected long getAddAnimationDelay(long removeDuration, long moveDuration, long changeDuration) {
            return 0;
        }

        @Override
        protected long getMoveAnimationDelay() {
            return 0;
        }
    }

    private class SwipeAdapter extends RecyclerListView.SelectionAdapter {
        private Context ctx;
        SwipeAdapter(Context c) { ctx = c; }

        private List<String> getEnabled() { return NekoConfig.swipeEnabledActions; }
        private List<String> getDisabled() {
            List<String> all = NekoConfig.getAllSwipeActionKeys();
            List<String> disabled = new ArrayList<>();
            for (String k : all) {
                if (k.equals("reaction")) {
                    disabled.add(k); // always show placeholder
                    continue;
                }
                if (!getEnabled().contains(k)) disabled.add(k);
            }
            return disabled;
        }

        int enabledIndexForPosition(int pos) {
            // pos 0 switch,1 info,2 header enabled, 3..3+enabledSize-1 enabled
            return pos - 3;
        }
        boolean isEnabledPosition(int pos) {
            int es = getEnabled().size();
            return pos >= 3 && pos < 3 + es;
        }
        String getKeyAt(int pos) {
            if (pos == 0) return null;
            int es = getEnabled().size();
            if (pos >= 3 && pos < 3 + es) return getEnabled().get(pos - 3);
            int ds = getDisabled().size();
            int disabledStart = 3 + es + 1; // + header
            if (pos >= disabledStart && pos < disabledStart + ds) return getDisabled().get(pos - disabledStart);
            return null;
        }

        int disabledPositionOfKey(String key) {
            int idx = getDisabled().indexOf(key);
            if (idx < 0) return -1;
            return 3 + getEnabled().size() + 1 + idx;
        }

        @Override
        public int getItemCount() {
            int es = getEnabled().size();
            int ds = getDisabled().size();
            // 0 switch,1 info,2 header, enabled actions, disabled header, disabled actions,
            // behavior header and slider
            return 1 + 1 + 1 + es + 1 + ds + 1 + 1;
        }
        @Override
        public int getItemViewType(int position) {
            int es = getEnabled().size();
            if (position == 0) return VIEW_TYPE_SWITCH;
            if (position == 1) return VIEW_TYPE_INFO;
            if (position == 2) return VIEW_TYPE_HEADER;
            if (position >= 3 && position < 3 + es) return VIEW_TYPE_ACTION;
            if (position == 3 + es) return VIEW_TYPE_HEADER;
            int dsStart = 3 + es + 1;
            int ds = getDisabled().size();
            if (position >= dsStart && position < dsStart + ds) return VIEW_TYPE_ACTION;
            if (position == dsStart + ds) return VIEW_TYPE_HEADER;
            return VIEW_TYPE_SLIDER;
        }
        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v;
            switch (viewType) {
                case VIEW_TYPE_SWITCH:
                    TextCheckCell check = new TextCheckCell(ctx, resourcesProvider);
                    v = check;
                    break;
                case VIEW_TYPE_HEADER:
                    HeaderCell hc = new HeaderCell(ctx);
                    v = hc;
                    break;
                case VIEW_TYPE_INFO:
                    TextInfoPrivacyCell info = new TextInfoPrivacyCell(ctx);
                    v = info;
                    break;
                case VIEW_TYPE_SLIDER: {
                    // value is the multiplier x10 (10..30 -> 1.0x..3.0x)
                    AltSeekbar bar = new AltSeekbar(ctx, progress ->
                            NekoConfig.setSwipeSwitchStepMul(progress / 10f), 10, 30,
                            "Switch strength", "Low", "High", resourcesProvider);
                    bar.setValueFormatter(val -> String.format(java.util.Locale.US, "%.1f×", val / 10f));
                    v = bar;
                    break;
                }
                default:
                    ActionCell cell = new ActionCell(ctx);
                    v = cell;
                    break;
            }
            return new RecyclerListView.Holder(v);
        }
        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            int type = getItemViewType(position);
            if (type == VIEW_TYPE_SWITCH) {
                TextCheckCell cell = (TextCheckCell) holder.itemView;
                cell.setTextAndCheck(LocaleController.getString(R.string.ShowNamesNextToBubbles), NekoConfig.swipeBubbleShowNames, false);
            } else if (type == VIEW_TYPE_HEADER) {
                HeaderCell hc = (HeaderCell) holder.itemView;
                int es = getEnabled().size();
                if (position == 2) hc.setText(LocaleController.getString(R.string.EnabledActions));
                else if (position == 3 + es) hc.setText(LocaleController.getString(R.string.DisabledActions));
                else hc.setText("Behavior");
            } else if (type == VIEW_TYPE_INFO) {
                TextInfoPrivacyCell cell = (TextInfoPrivacyCell) holder.itemView;
                cell.setText(LocaleController.getString(R.string.OtherSwipeActionsInfo));
                cell.setBackgroundDrawable(Theme.getThemedDrawableByKey(ctx, R.drawable.greydivider_bottom, Theme.key_windowBackgroundGrayShadow));
            } else if (type == VIEW_TYPE_ACTION) {
                ActionCell cell = (ActionCell) holder.itemView;
                String key = getKeyAt(position);
                boolean enabled = isEnabledPosition(position);
                boolean isPrimary = enabled && key != null && key.equals(NekoConfig.swipePrimaryAction);
                cell.bind(key, enabled, isPrimary);
                // M3 rounded corners for first/last in section
                boolean isFirst, isLast;
                if (enabled) {
                    int idx = enabledIndexForPosition(position);
                    isFirst = idx == 0;
                    isLast = idx == getEnabled().size() - 1;
                } else {
                    int ds = getDisabled().size();
                    int disabledStart = 3 + getEnabled().size() + 1;
                    int idx = position - disabledStart;
                    isFirst = idx == 0;
                    isLast = idx == ds - 1;
                }
                cell.updateBackground(isFirst, isLast);
                // drag handle starts drag immediately on touch down;
                // clickable=true makes RecyclerListView skip the row (intercept loop),
                // so neither click nor long press on handle ever fires
                cell.dragView.setClickable(true);
                cell.dragView.setOnTouchListener((v, event) -> {
                    if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                        itemTouchHelper.startDrag(holder);
                    }
                    return false;
                });
            } else if (type == VIEW_TYPE_SLIDER) {
                ((AltSeekbar) holder.itemView).setValue(NekoConfig.swipeSwitchStepMul * 10f);
            }
        }
        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            int type = getItemViewType(holder.getAdapterPosition());
            return type == VIEW_TYPE_SWITCH || type == VIEW_TYPE_ACTION;
        }
    }

    // transparent background so it matches the dialog background
    private static class ReactionPickerCell extends FrameLayout {
        private final BackupImageView imageView;
        private final SimpleTextView textView;

        ReactionPickerCell(Context context) {
            super(context);
            imageView = new BackupImageView(context);
            imageView.setAspectFit(true);
            imageView.setLayerNum(1);
            addView(imageView, LayoutHelper.createFrame(32, 32, Gravity.LEFT | Gravity.CENTER_VERTICAL, 16, 0, 0, 0));
            textView = new SimpleTextView(context);
            NotificationCenter.listenEmojiLoading(textView);
            textView.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
            textView.setTextSize(16);
            textView.setTypeface(AndroidUtilities.bold());
            textView.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
            addView(textView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.CENTER_VERTICAL, 64, 0, 16, 0));
        }

        void bind(TLRPC.TL_availableReaction r, int account) {
            textView.setText(Emoji.replaceEmoji(r.title, textView.getPaint().getFontMetricsInt(), false));
            SvgHelper.SvgDrawable svgThumb = DocumentObject.getSvgThumb(r.static_icon, Theme.key_dialogTextBlack, 1.0f);
            imageView.setImage(ImageLocation.getForDocument(r.activate_animation), ReactionsUtils.ACTIVATE_ANIMATION_FILTER, "tgs", svgThumb, r);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(AndroidUtilities.dp(48), MeasureSpec.EXACTLY));
        }
    }

    private class ActionCell extends FrameLayout {
        private ImageView iconView;
        private TextView titleView;
        private TextView primaryView;
        public ImageView dragView;
        private BackupImageView reactionView;
        private AnimatedEmojiDrawable.SwapAnimatedEmojiDrawable animatedDrawable;

        void updateBackground(boolean first, boolean last) {
            try {
                if (NekoConfig.m3SectionsStyle) {
                    int color = Theme.getColor(Theme.key_windowBackgroundWhite);
                    int rad = AndroidUtilities.dp(12);
                    setBackground(Theme.createRoundRectDrawable(rad, color));
                } else {
                    setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                }
            } catch (Exception ignore) {
                setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
            }
        }

        ActionCell(Context context) {
            super(context);
            setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
            iconView = new ImageView(context);
            iconView.setScaleType(ImageView.ScaleType.CENTER);
            addView(iconView, LayoutHelper.createFrame(24, 24, Gravity.CENTER_VERTICAL | Gravity.LEFT, 16, 0, 0, 0));
            reactionView = new BackupImageView(context);
            reactionView.setAspectFit(true);
            reactionView.setVisibility(GONE);
            addView(reactionView, LayoutHelper.createFrame(24, 24, Gravity.CENTER_VERTICAL | Gravity.LEFT, 16, 0, 0, 0));
            titleView = new TextView(context);
            titleView.setTextSize(16);
            titleView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            titleView.setSingleLine(true);
            addView(titleView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL | Gravity.LEFT, 56, 0, 48, 0));
            primaryView = new TextView(context);
            primaryView.setTextSize(12);
            primaryView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));
            primaryView.setAlpha(0.6f);
            primaryView.setText(LocaleController.getString(R.string.SwipeActionPrimary));
            addView(primaryView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL | Gravity.RIGHT, 0, 0, 48, 0));
            dragView = new ImageView(context);
            dragView.setImageResource(R.drawable.msg_reorder);
            dragView.setColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteGrayIcon));
            addView(dragView, LayoutHelper.createFrame(24, 24, Gravity.CENTER_VERTICAL | Gravity.RIGHT, 0, 0, 12, 0));
            setPadding(0,0,0,0);
        }
        void bind(String key, boolean enabled, boolean isPrimary) {
            if (key == null) return;
            titleView.setText(NekoConfig.getSwipeActionTitle(key));
            primaryView.setVisibility(isPrimary ? VISIBLE : GONE);
            dragView.setVisibility(enabled ? VISIBLE : GONE);
            // icon
            if (key.startsWith("reaction:")) {
                String emo = key.substring(9);
                if (emo.startsWith("animated_")) {
                    reactionView.setVisibility(GONE);
                    try {
                        long docId = Long.parseLong(emo.substring(9));
                        if (animatedDrawable == null) animatedDrawable = new AnimatedEmojiDrawable.SwapAnimatedEmojiDrawable(this, AndroidUtilities.dp(24));
                        animatedDrawable.set(docId, false);
                        iconView.setImageDrawable(animatedDrawable);
                    } catch (Exception e) {
                        iconView.setImageDrawable(null);
                    }
                } else {
                    if (animatedDrawable != null) { animatedDrawable.detach(); animatedDrawable = null; }
                    TLRPC.TL_availableReaction ar = MediaDataController.getInstance(currentAccount).getReactionsMap().get(emo);
                    if (ar != null) {
                        iconView.setImageDrawable(null);
                        reactionView.setVisibility(VISIBLE);
                        SvgHelper.SvgDrawable svgThumb = DocumentObject.getSvgThumb(ar.static_icon, Theme.key_windowBackgroundGray, 1.0f);
                        reactionView.setImage(ImageLocation.getForDocument(ar.center_icon), "40_40_lastreactframe", "webp", svgThumb, ar);
                    } else {
                        reactionView.setVisibility(GONE);
                        iconView.setImageDrawable(null);
                    }
                }
            } else {
                reactionView.setVisibility(GONE);
                if (animatedDrawable != null) { animatedDrawable.detach(); animatedDrawable = null; }
                int res = getIconRes(key);
                if (res != 0) {
                    Drawable d = getContext().getResources().getDrawable(res).mutate();
                    d.setColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteGrayIcon), android.graphics.PorterDuff.Mode.SRC_IN);
                    iconView.setImageDrawable(d);
                } else iconView.setImageDrawable(null);
            }
        }
        private int getIconRes(String key) {
            switch (key) {
                case "reply": return R.drawable.menu_reply;
                case "edit": return R.drawable.msg_edit;
                case "delete": return R.drawable.msg_delete;
                case "copy": return R.drawable.msg_copy;
                case "forward": return R.drawable.msg_forward;
                case "save": return R.drawable.msg_saved;
                case "pin": return R.drawable.msg_pin;
                case "select": return R.drawable.msg_select;
                case "translate": return R.drawable.msg_translate;
                case "report": return R.drawable.msg_report;
                case "details": return R.drawable.msg_info;
                case "copyphoto": return R.drawable.msg_copy;
                case "qr": return R.drawable.msg_qrcode;
                case "openin": return R.drawable.msg_openin;
                case "reaction": return R.drawable.msg_emoji_smiles;
                default: return 0;
            }
        }
        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(AndroidUtilities.dp(50), MeasureSpec.EXACTLY));
        }
        @Override
        protected void dispatchDraw(Canvas canvas) {
            super.dispatchDraw(canvas);
            if (animatedDrawable != null) {
                animatedDrawable.setBounds(iconView.getLeft(), iconView.getTop(), iconView.getRight(), iconView.getBottom());
                animatedDrawable.draw(canvas);
            }
        }
    }

}
