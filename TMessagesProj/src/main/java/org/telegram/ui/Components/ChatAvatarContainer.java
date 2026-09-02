/*
 * This is the source code of Telegram for Android v. 5.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 */

package org.telegram.ui.Components;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.AndroidUtilities.dpf2;
import static org.telegram.messenger.AndroidUtilities.replaceArrows;
import static org.telegram.messenger.LocaleController.getString;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewParent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.Emoji;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.ImageLoader;
import org.telegram.messenger.ImageReceiver;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarPopupWindow;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.SimpleTextView;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Business.BusinessLinksController;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.Forum.ForumUtilities;
import org.telegram.ui.ProfileActivity;
import org.telegram.ui.Stories.StoriesUtilities;
import org.telegram.ui.TopicsFragment;
import org.telegram.ui.community.CommunityArrowDrawable;

import java.util.concurrent.atomic.AtomicReference;

import me.vkryl.android.animator.BoolAnimator;
import me.vkryl.android.animator.FactorAnimator;

public class ChatAvatarContainer extends FrameLayout implements FactorAnimator.Target, NotificationCenter.NotificationCenterDelegate {

    private static final int ANIMATOR_ID_TIME_ITEM_VISIBLE = 0;
    private final BoolAnimator animatorTimeVisible = new BoolAnimator(ANIMATOR_ID_TIME_ITEM_VISIBLE, this, CubicBezierInterpolator.EASE_OUT_QUINT, 320);

    public boolean allowDrawStories;
    private Integer storiesForceState;
    private int avatarSizeInDp = 42;
    public BackupImageView avatarImageView;
    private boolean avatarImageIsHidden;
    private SimpleTextView titleTextView;
    private AtomicReference<SimpleTextView> titleTextLargerCopyView = new AtomicReference<>();
    private SimpleTextView subtitleTextView;
    private AnimatedTextView animatedSubtitleTextView;
    private AtomicReference<SimpleTextView> subtitleTextLargerCopyView = new AtomicReference<>();
    private ImageView timeItem;
    private ImageView communityItem;
    private ImageView starBgItem, starFgItem;
    private TimerDrawable timerDrawable;
    private ChatActivity parentFragment;
    private StatusDrawable[] statusDrawables = new StatusDrawable[6];
    private AvatarDrawable avatarDrawable = new AvatarDrawable();
    private int currentAccount = UserConfig.selectedAccount;
    private boolean occupyStatusBar = true;
    private int leftPadding = dp(8);
    private int rightAvatarPadding = 0;
    private int avatarPlacement = zxc.iconic.xenon.NekoConfig.AVATAR_PLACEMENT_LEFT;
    private int rightTextInset = 0;
    private boolean textOnlyPill = false;
    private boolean biggerAvatar = false;
    private View rightAnchorView;
    private int lastRightAvatarLeft = Integer.MIN_VALUE;
    StatusDrawable currentTypingDrawable;

    private int lastWidth = -1;
    private int largerWidth = -1;


    private AnimatorSet titleAnimation;

    private boolean[] isOnline = new boolean[1];
    public boolean[] statusMadeShorter = new boolean[1];

    private boolean secretChatTimer;

    private int onlineCount = -1;
    private int currentConnectionState;
    private CharSequence lastSubtitle;
    private int lastSubtitleColorKey = -1;
    private Integer overrideSubtitleColor;

    private SharedMediaLayout.SharedMediaPreloader sharedMediaPreloader;
    private Theme.ResourcesProvider resourcesProvider;

    public boolean allowShorterStatus = false;
    public boolean premiumIconHiddable = false;

    private final AnimatedEmojiDrawable.SwapAnimatedEmojiDrawable emojiStatusDrawable;
    private final AnimatedEmojiDrawable.SwapAnimatedEmojiDrawable botVerificationDrawable;

    protected boolean useAnimatedSubtitle() {
        return false;
    }

    public void hideSubtitle() {
        if (getSubtitleTextView() != null) {
            getSubtitleTextView().setVisibility(View.GONE);
        }
    }

    public void setStoriesForceState(Integer storiesForceState) {
        this.storiesForceState = storiesForceState;
    }

    private class SimpleTextConnectedView extends SimpleTextView {

        private AtomicReference<SimpleTextView> reference;
        public SimpleTextConnectedView(Context context, AtomicReference<SimpleTextView> reference) {
            super(context);
            this.reference = reference;
        }

        @Override
        public void setTranslationY(float translationY) {
            if (reference != null) {
                SimpleTextView connected = reference.get();
                if (connected != null) {
                    connected.setTranslationY(translationY);
                }
            }
            super.setTranslationY(translationY);
        }

        @Override
        public boolean setText(CharSequence value) {
            if (reference != null) {
                SimpleTextView connected = reference.get();
                if (connected != null) {
                    connected.setText(value);
                }
            }
            return super.setText(value);
        }
    }

    public ChatAvatarContainer(Context context, BaseFragment baseFragment, boolean needTime) {
        this(context, baseFragment, needTime, null);
    }

    public ChatAvatarContainer(Context context, BaseFragment baseFragment, boolean needTime, Theme.ResourcesProvider resourcesProvider) {
        super(context);
        this.resourcesProvider = resourcesProvider;
        if (baseFragment instanceof ChatActivity) {
            parentFragment = (ChatActivity) baseFragment;
        }

        final boolean avatarClickable = parentFragment != null && (parentFragment.getChatMode() == 0 || parentFragment.getChatMode() == ChatActivity.MODE_SUGGESTIONS) && !UserObject.isReplyUser(parentFragment.getCurrentUser()) && (parentFragment.getCurrentUser() == null || parentFragment.getCurrentUser().id != UserObject.VERIFY);
        avatarImageView = new BackupImageView(context) {

            StoriesUtilities.AvatarStoryParams params = new StoriesUtilities.AvatarStoryParams(true) {
                @Override
                public void openStory(long dialogId, Runnable onDone) {
                    baseFragment.getOrCreateStoryViewer().open(getContext(), dialogId, (dialogId1, messageId, storyId, type, holder) -> {
                        holder.crossfadeToAvatarImage = holder.storyImage = imageReceiver;
                        holder.params = params;
                        holder.isLive = params.drawnLive;
                        holder.view = avatarImageView;
                        holder.alpha = avatarImageView.getAlpha();
                        holder.clipTop = 0;
                        holder.clipBottom = AndroidUtilities.displaySize.y;
                        holder.clipParent = (View) getParent();
                        return true;
                    });
                }
            };

            @Override
            public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(info);
                if (avatarClickable && getImageReceiver().hasNotThumb()) {
                    info.setText(getString(R.string.AccDescrProfilePicture));
                    info.addAction(new AccessibilityNodeInfo.AccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, getString(R.string.Open)));
                } else {
                    info.setVisibleToUser(false);
                }
            }

            @Override
            protected void onDraw(Canvas canvas) {
                if (allowDrawStories && animatedEmojiDrawable == null) {
                    params.originalAvatarRect.set(0, 0, getMeasuredWidth(), getMeasuredHeight());
                    params.drawSegments = true;
                    params.drawInside = true;
                    params.resourcesProvider = resourcesProvider;
                    if (storiesForceState != null) {
                        params.forceState = storiesForceState;
                    }

                    long dialogId = 0;
                    if (parentFragment != null) {
                        dialogId = parentFragment.getDialogId();
                    } else if (baseFragment instanceof TopicsFragment) {
                        dialogId = ((TopicsFragment) baseFragment).getDialogId();
                    }

                    StoriesUtilities.drawAvatarWithStory(dialogId, canvas, imageReceiver, params);
                } else {
                    super.onDraw(canvas);
                }
            }

            @Override
            public boolean onTouchEvent(MotionEvent event) {
                if (allowDrawStories) {
                    if (params.checkOnTouchEvent(event, this)) {
                        return true;
                    }
                }
                return super.onTouchEvent(event);
            }
        };
        if (baseFragment instanceof ChatActivity || baseFragment instanceof TopicsFragment) {
            if (parentFragment == null || (parentFragment.getChatMode() != ChatActivity.MODE_QUICK_REPLIES && parentFragment.getChatMode() != ChatActivity.MODE_WELCOME_MESSAGES && parentFragment.getChatMode() != ChatActivity.MODE_EDIT_BUSINESS_LINK) && parentFragment.getChatMode() != ChatActivity.MODE_SUGGESTIONS && !parentFragment.isInBotForumMode()) {
                sharedMediaPreloader = new SharedMediaLayout.SharedMediaPreloader(baseFragment);
            }
            avatarImageIsHidden = parentFragment != null && (
                parentFragment.isThreadChat() && !parentFragment.isReplyChatComment() ||
                parentFragment.getChatMode() == ChatActivity.MODE_PINNED ||
                parentFragment.getChatMode() == ChatActivity.MODE_QUICK_REPLIES ||
                parentFragment.getChatMode() == ChatActivity.MODE_WELCOME_MESSAGES ||
                parentFragment.getChatMode() == ChatActivity.MODE_EDIT_BUSINESS_LINK
            );
            if (avatarImageIsHidden) {
                avatarImageView.setVisibility(GONE);
            }
        }
        avatarImageView.setContentDescription(getString(R.string.AccDescrProfilePicture));
        avatarImageView.setRoundRadius(dp(21));
        addView(avatarImageView);
        if (avatarClickable) {
            final TLRPC.Chat chat = parentFragment != null ? parentFragment.getCurrentChat() : null;
            if (chat != null && chat.linked_community_id != 0) {
                ScaleStateListAnimator.apply(avatarImageView, .05f, 1.2f);
            }
            avatarImageView.setOnClickListener(v -> {
                if (!onAvatarClick()) {
                    openProfile(true);
                }
            });
        }

        titleTextView = new SimpleTextConnectedView(context, titleTextLargerCopyView);
        titleTextView.setEllipsizeByGradient(true);
        titleTextView.setTextColor(getThemedColor(Theme.key_actionBarDefaultTitle));
        titleTextView.setTextSize(18);
        titleTextView.setGravity(Gravity.LEFT);
        titleTextView.setTypeface(AndroidUtilities.bold());
        titleTextView.setLeftDrawableTopPadding(-dp(1.3f));
        titleTextView.setCanHideRightDrawable(false);
        titleTextView.setRightDrawableOutside(true);
        titleTextView.setPadding(0, dp(6), 0, dp(12));
        addView(titleTextView);

        if (useAnimatedSubtitle()) {
            animatedSubtitleTextView = new AnimatedTextView(context, true, true, true);
            animatedSubtitleTextView.setAnimationProperties(.3f, 0, 320, CubicBezierInterpolator.EASE_OUT_QUINT);
            animatedSubtitleTextView.setEllipsizeByGradient(true);
            animatedSubtitleTextView.setTextColor(getThemedColor(Theme.key_actionBarDefaultSubtitle));
            animatedSubtitleTextView.setTag(Theme.key_actionBarDefaultSubtitle);
            animatedSubtitleTextView.setTextSize(dp(14));
            animatedSubtitleTextView.setGravity(Gravity.LEFT);
            animatedSubtitleTextView.setPadding(0, 0, dp(10), 0);
            animatedSubtitleTextView.setTranslationY(-dp(1));
            addView(animatedSubtitleTextView);
        } else {
            subtitleTextView = new SimpleTextConnectedView(context, subtitleTextLargerCopyView);
            subtitleTextView.setEllipsizeByGradient(true);
            subtitleTextView.setTextColor(getThemedColor(Theme.key_actionBarDefaultSubtitle));
            subtitleTextView.setTag(Theme.key_actionBarDefaultSubtitle);
            subtitleTextView.setTextSize(14);
            subtitleTextView.setGravity(Gravity.LEFT);
            subtitleTextView.setPadding(0, 0, dp(10), 0);
            addView(subtitleTextView);
        }

        if (parentFragment != null) {
            communityItem = new ImageView(context);
            communityItem.setScaleType(ImageView.ScaleType.CENTER);
            communityItem.setVisibility(GONE);
            communityItem.setImageDrawable(new CommunityArrowDrawable());
            addView(communityItem);

            timeItem = new ImageView(context);
            timeItem.setScaleType(ImageView.ScaleType.CENTER);
            timeItem.setVisibility(GONE);
            timeItem.setImageDrawable(timerDrawable = new TimerDrawable(context, resourcesProvider));
            timerDrawable.setBackgroundColor(0);
            addView(timeItem);
            secretChatTimer = needTime;

            timeItem.setOnClickListener(v -> {
                if (secretChatTimer) {
                    parentFragment.showDialog(AlertsCreator.createTTLAlert(getContext(), parentFragment.getCurrentEncryptedChat(), resourcesProvider).create());
                } else {
                    openSetTimer();
                }
            });
            if (secretChatTimer) {
                timeItem.setContentDescription(getString(R.string.SetTimer));
            } else {
                timeItem.setContentDescription(getString(R.string.AccAutoDeleteTimer));
            }

            starBgItem = new ImageView(context);
            starBgItem.setImageResource(R.drawable.star_small_outline);
            starBgItem.setColorFilter(new PorterDuffColorFilter(getThemedColor(Theme.key_actionBarDefault), PorterDuff.Mode.SRC_IN));
            starBgItem.setAlpha(0.0f);
            starBgItem.setVisibility(View.INVISIBLE);
            starBgItem.setScaleY(0.0f);
            starBgItem.setScaleX(0.0f);
            addView(starBgItem);

            starFgItem = new ImageView(context);
            starFgItem.setImageResource(R.drawable.star_small_inner);
            starFgItem.setAlpha(0.0f);
            starFgItem.setVisibility(View.INVISIBLE);
            starFgItem.setScaleY(0.0f);
            starFgItem.setScaleX(0.0f);
            addView(starFgItem);
        }

        if (parentFragment != null && (parentFragment.getChatMode() == 0 || parentFragment.getChatMode() == ChatActivity.MODE_SUGGESTIONS || parentFragment.getChatMode() == ChatActivity.MODE_SAVED)) {
            if ((!parentFragment.isThreadChat() || parentFragment.isTopic || parentFragment.isComments) && !UserObject.isReplyUser(parentFragment.getCurrentUser()) && (parentFragment.getCurrentUser() == null || parentFragment.getCurrentUser().id != UserObject.VERIFY)) {
                setOnClickListener(v -> {
                    openProfile(false);
                });
            }

            TLRPC.Chat chat = parentFragment.getCurrentChat();
            statusDrawables[0] = new TypingDotsDrawable(true);
            statusDrawables[1] = new RecordStatusDrawable(true);
            statusDrawables[2] = new SendingFileDrawable(true);
            statusDrawables[3] = new PlayingGameDrawable(false, resourcesProvider);
            statusDrawables[4] = new RoundStatusDrawable(true);
            statusDrawables[5] = new ChoosingStickerStatusDrawable(true);
            for (int a = 0; a < statusDrawables.length; a++) {
                statusDrawables[a].setIsChat(chat != null);
            }
        }

        emojiStatusDrawable = new AnimatedEmojiDrawable.SwapAnimatedEmojiDrawable(titleTextView, dp(24));
        botVerificationDrawable = new AnimatedEmojiDrawable.SwapAnimatedEmojiDrawable(titleTextView, dp(17));
    }

    public ButtonBounce bounce = new ButtonBounce(this);
    private Runnable onLongClick = () -> {
        pressed = false;
        bounce.setPressed(false);
        if (canSearch()) {
            openSearch();
        }
    };

    private boolean pressed;
    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (ev.getAction() == MotionEvent.ACTION_DOWN && canSearch()) {
            pressed = true;
            bounce.setPressed(true);
            AndroidUtilities.cancelRunOnUIThread(this.onLongClick);
            AndroidUtilities.runOnUIThread(this.onLongClick, ViewConfiguration.getLongPressTimeout());
            return true;
        } else if (ev.getAction() == MotionEvent.ACTION_UP || ev.getAction() == MotionEvent.ACTION_CANCEL) {
            if (pressed) {
                bounce.setPressed(false);
                pressed = false;
                if (isClickable()) {
                    openProfile(false);
                }
                AndroidUtilities.cancelRunOnUIThread(this.onLongClick);
            }
        }
        return super.onTouchEvent(ev);
    }

    @Override
    public void setPressed(boolean pressed) {
        super.setPressed(pressed);
        bounce.setPressed(pressed);
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        canvas.save();
        final float s = bounce.getScale(.02f);
        canvas.scale(s, s, getPivotX(), getHeight() - ActionBar.getCurrentActionBarHeight() / 2f);
        super.dispatchDraw(canvas);
        canvas.restore();
    }

    @Override
    protected boolean drawChild(@NonNull Canvas canvas, View child, long drawingTime) {
        if (child == avatarImageView) {
            final boolean hasTimer = timeItem != null && timeItem.getVisibility() == VISIBLE;
            final boolean hasCommunity = communityItem != null && communityItem.getVisibility() == VISIBLE;
            if (hasTimer || hasCommunity) {
                AndroidUtilities.rectTmp.set(child.getX(), child.getY(), child.getX() + child.getWidth(), child.getY() + child.getHeight());
                AndroidUtilities.rectTmp.inset(-dp(3), -dp(3));
                canvas.saveLayer(AndroidUtilities.rectTmp, null);
                final boolean b = super.drawChild(canvas, child, drawingTime);
                if (hasTimer) {
                    final float cx = timeItem.getX() + timeItem.getWidth() / 2f;
                    final float cy = timeItem.getY() + timeItem.getHeight() / 2f;
                    final float r = dpf2(12f) * timeItem.getScaleX();
                    canvas.drawCircle(cx, cy - dpf2(0.33f), r, Theme.PAINT_CLEAR);
                }
                if (hasCommunity) {
                    final float cx = communityItem.getX() + communityItem.getWidth() / 2f;
                    final float cy = communityItem.getY() + communityItem.getHeight() / 2f;
                    final float r = dpf2(7.66f) * communityItem.getScaleX();
                    canvas.drawCircle(cx, cy, r, Theme.PAINT_CLEAR);
                }
                canvas.restore();
                return b;
            }
        }
        return super.drawChild(canvas, child, drawingTime);
    }

    public boolean ignoreTouches;
    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (ignoreTouches) return false;
        return super.dispatchTouchEvent(ev);
    }

    protected boolean canSearch() {
        return false;
    }

    protected void openSearch() {

    }

    protected boolean onAvatarClick() {
        return false;
    }

    public void setOverrideSubtitleColor(Integer overrideSubtitleColor) {
        this.overrideSubtitleColor = overrideSubtitleColor;
    }

    public boolean openSetTimer() {
        if (parentFragment.getParentActivity() == null) {
            return false;
        }
        TLRPC.Chat chat = parentFragment.getCurrentChat();
        if (chat != null && !ChatObject.canUserDoAdminAction(chat, ChatObject.ACTION_DELETE_MESSAGES)) {
            if (animatorTimeVisible.getValue()) {
                parentFragment.showTimerHint();
            }
            return false;
        }
        TLRPC.ChatFull chatInfo = parentFragment.getCurrentChatInfo();
        TLRPC.UserFull userInfo = parentFragment.getCurrentUserInfo();
        int ttl = 0;
        if (userInfo != null) {
            ttl = userInfo.ttl_period;
        } else if (chatInfo != null) {
            ttl = chatInfo.ttl_period;
        }

        ActionBarPopupWindow[] scrimPopupWindow = new ActionBarPopupWindow[1];
        AutoDeletePopupWrapper autoDeletePopupWrapper = new AutoDeletePopupWrapper(getContext(), null, new AutoDeletePopupWrapper.Callback() {
            @Override
            public void dismiss() {
                if (scrimPopupWindow[0] != null) {
                    scrimPopupWindow[0].dismiss();
                }
            }

            @Override
            public void setAutoDeleteHistory(int time, int action) {
                if (parentFragment == null) {
                    return;
                }
                parentFragment.getMessagesController().setDialogHistoryTTL(parentFragment.getDialogId(), time);
                TLRPC.ChatFull chatInfo = parentFragment.getCurrentChatInfo();
                TLRPC.UserFull userInfo = parentFragment.getCurrentUserInfo();
                if (userInfo != null || chatInfo != null) {
                    UndoView undoView = parentFragment.getUndoView();
                    if (undoView != null) {
                        undoView.showWithAction(parentFragment.getDialogId(), action, parentFragment.getCurrentUser(), userInfo != null ? userInfo.ttl_period : chatInfo.ttl_period, null, null);
                    }
                }

            }
        }, true, 0, resourcesProvider);
        autoDeletePopupWrapper.updateItems(ttl);

        scrimPopupWindow[0] = new ActionBarPopupWindow(autoDeletePopupWrapper.windowLayout, LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT) {
            @Override
            public void dismiss() {
                super.dismiss();
                if (parentFragment != null) {
                    parentFragment.dimBehindView(false);
                }
            }
        };
        scrimPopupWindow[0].setPauseNotifications(true);
        scrimPopupWindow[0].setDismissAnimationDuration(220);
        scrimPopupWindow[0].setOutsideTouchable(true);
        scrimPopupWindow[0].setClippingEnabled(true);
        scrimPopupWindow[0].setAnimationStyle(R.style.PopupContextAnimation);
        scrimPopupWindow[0].setFocusable(true);
        autoDeletePopupWrapper.windowLayout.measure(View.MeasureSpec.makeMeasureSpec(dp(1000), View.MeasureSpec.AT_MOST), View.MeasureSpec.makeMeasureSpec(dp(1000), View.MeasureSpec.AT_MOST));
        scrimPopupWindow[0].setInputMethodMode(ActionBarPopupWindow.INPUT_METHOD_NOT_NEEDED);
        scrimPopupWindow[0].getContentView().setFocusableInTouchMode(true);
        scrimPopupWindow[0].showAtLocation(avatarImageView, 0, (int) (avatarImageView.getX() + getX()), (int) avatarImageView.getY());
        parentFragment.dimBehindView(true);
        return true;
    }

    public void openProfile(boolean byAvatar) {
        openProfile(byAvatar, true, false);
    }

    public void openProfile(boolean byAvatar, boolean fromChatAnimation, boolean removeLast) {
        if (byAvatar && (AndroidUtilities.isTablet() || AndroidUtilities.displaySize.x > AndroidUtilities.displaySize.y || !avatarImageView.getImageReceiver().hasNotThumb())) {
            byAvatar = false;
        }
        TLRPC.User user = parentFragment.getCurrentUser();
        TLRPC.Chat chat = parentFragment.getCurrentChat();
        final boolean monoforum = chat != null && chat.monoforum;
        if (chat != null && chat.monoforum) {
            TLRPC.Chat channel = parentFragment.getMessagesController().getChat(chat.linked_monoforum_id);
            if (channel == null) return;
            chat = channel;
            if (parentFragment.getSendMonoForumPeerId() != 0) {
                TLRPC.User fromUser = parentFragment.getMessagesController().getUser(parentFragment.getSendMonoForumPeerId());
                if (fromUser != null) {
                    user = fromUser;
                    chat = null;
                }
            }
        }
        ImageReceiver imageReceiver = avatarImageView.getImageReceiver();
        String key = imageReceiver.getImageKey();
        ImageLoader imageLoader = ImageLoader.getInstance();
        if (key != null && !imageLoader.isInMemCache(key, false)) {
            Drawable drawable = imageReceiver.getDrawable();
            if (drawable instanceof BitmapDrawable && !(drawable instanceof AnimatedFileDrawable)) {
                imageLoader.putImageToCache((BitmapDrawable) drawable, key, false);
            }
        }

        if (parentFragment.isComments) {
            if (chat == null) return;
            parentFragment.presentFragment(ProfileActivity.of(-chat.id), removeLast);
            return;
        }

        if (user != null) {
            if (user.id == UserObject.VERIFY) {
                return;
            }
            Bundle args = new Bundle();
            if (UserObject.isUserSelf(user)) {
                if (!sharedMediaPreloader.hasSharedMedia()) {
                    return;
                }
                args.putLong("dialog_id", parentFragment.getDialogId());
                if (parentFragment.getChatMode() == ChatActivity.MODE_SAVED) {
                    args.putLong("topic_id", parentFragment.getSavedDialogId());
                }
                MediaActivity fragment = new MediaActivity(args, sharedMediaPreloader);
                fragment.setChatInfo(parentFragment.getCurrentChatInfo());
                parentFragment.presentFragment(fragment, removeLast);
            } else {
                if (parentFragment.getChatMode() == ChatActivity.MODE_SAVED) {
                    long dialogId = parentFragment.getSavedDialogId();
                    args.putBoolean("saved", true);
                    if (dialogId >= 0) {
                        args.putLong("user_id", dialogId);
                    } else {
                        args.putLong("chat_id", -dialogId);
                    }
                } else {
                    args.putLong("user_id", user.id);
                    if (timeItem != null && !monoforum) {
                        args.putLong("dialog_id", parentFragment.getDialogId());
                    }
                }
                if (UserObject.isBotForum(user)) {
                    args.putLong("topic_id", parentFragment.getTopicId());
                }
                args.putBoolean("reportSpam", parentFragment.hasReportSpam());
                args.putInt("actionBarColor", getThemedColor(Theme.key_actionBarDefault));
                final ProfileActivity fragment = new ProfileActivity(args, sharedMediaPreloader);
                if (!monoforum) {
                    fragment.setUserInfo(parentFragment.getCurrentUserInfo(), parentFragment.profileChannelMessageFetcher, parentFragment.birthdayAssetsFetcher);
                }
                if (fromChatAnimation) {
                    fragment.setPlayProfileAnimation(byAvatar ? 2 : 1);
                }
                parentFragment.presentFragment(fragment, removeLast);
            }
        } else if (chat != null) {
            Bundle args = new Bundle();
            args.putLong("chat_id", chat.id);
            if (parentFragment.getChatMode() == ChatActivity.MODE_SAVED) {
                args.putLong("topic_id", parentFragment.getSavedDialogId());
            } else if (parentFragment.isTopic) {
                args.putLong("topic_id", parentFragment.getThreadMessage().getId());
            }
            final ProfileActivity fragment = new ProfileActivity(args, sharedMediaPreloader);
            if (!monoforum) {
                fragment.setChatInfo(parentFragment.getCurrentChatInfo());
            }
            if (fromChatAnimation) {
                fragment.setPlayProfileAnimation(byAvatar ? 2 : 1);
            }
            parentFragment.presentFragment(fragment, removeLast);
        }
    }

    public void setOccupyStatusBar(boolean value) {
        occupyStatusBar = value;
    }

    public void setTitleColors(int title, int subtitle) {
        titleTextView.setTextColor(title);
        subtitleTextView.setTextColor(subtitle);
        subtitleTextView.setTag(subtitle);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        final int width = MeasureSpec.getSize(widthMeasureSpec);
        final boolean avatarVisible = avatarImageView.getVisibility() == VISIBLE;
        final boolean rightAvatar = avatarPlacement == zxc.iconic.xenon.NekoConfig.AVATAR_PLACEMENT_RIGHT && avatarVisible;
        final int availableWidth = (rightAvatar || textOnlyPill)
            ? Math.max(0, width - rightTextInset - dp(16))
            : width - dp((avatarVisible ? 54 : 0) + 16);
        avatarImageView.measure(MeasureSpec.makeMeasureSpec(dp(avatarSizeInDp) - 2, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(dp(avatarSizeInDp) - 2, MeasureSpec.EXACTLY));
        final int maxTextWidth;
        if (textOnlyPill) {
            int pillMaxWidth = actionBar != null ? actionBar.getCenteredPillMaxWidth() : Integer.MAX_VALUE;
            maxTextWidth = Math.min(availableWidth, pillMaxWidth - dp(34));
        } else {
            maxTextWidth = availableWidth;
        }
        titleTextView.measure(MeasureSpec.makeMeasureSpec(maxTextWidth, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(dp(24 + 8), MeasureSpec.AT_MOST));
        if (subtitleTextView != null) {
            subtitleTextView.measure(MeasureSpec.makeMeasureSpec(maxTextWidth, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(dp(20), MeasureSpec.AT_MOST));
        } else if (animatedSubtitleTextView != null) {
            animatedSubtitleTextView.measure(MeasureSpec.makeMeasureSpec(maxTextWidth, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(dp(20), MeasureSpec.AT_MOST));
        }
        if (communityItem != null) {
            communityItem.measure(MeasureSpec.makeMeasureSpec(dp(14), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(dp(14), MeasureSpec.EXACTLY));
        }
        if (timeItem != null) {
            timeItem.measure(MeasureSpec.makeMeasureSpec(dp(34), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(dp(34), MeasureSpec.EXACTLY));
        }
        if (starBgItem != null) {
            starBgItem.measure(MeasureSpec.makeMeasureSpec(dp(20), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(dp(20), MeasureSpec.EXACTLY));
        }
        if (starFgItem != null) {
            starFgItem.measure(MeasureSpec.makeMeasureSpec(dp(20), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(dp(20), MeasureSpec.EXACTLY));
        }
        setMeasuredDimension(width, MeasureSpec.getSize(heightMeasureSpec));
        if (lastWidth != -1 && lastWidth != width && lastWidth > width) {
            fadeOutToLessWidth(lastWidth);
        }
        SimpleTextView titleTextLargerCopyView = this.titleTextLargerCopyView.get();
        if (titleTextLargerCopyView != null) {
            int largerAvailableWidth = largerWidth - dp((avatarImageView.getVisibility() == VISIBLE ? 54 : 0) + 16);
            titleTextLargerCopyView.measure(MeasureSpec.makeMeasureSpec(largerAvailableWidth, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(dp(24), MeasureSpec.AT_MOST));
        }
        lastWidth = width;
    }

    private void fadeOutToLessWidth(int largerWidth) {
        this.largerWidth = largerWidth;
        SimpleTextView titleTextLargerCopyView = this.titleTextLargerCopyView.get();
        if (titleTextLargerCopyView != null) {
            removeView(titleTextLargerCopyView);
        }
        titleTextLargerCopyView = new SimpleTextView(getContext());
        this.titleTextLargerCopyView.set(titleTextLargerCopyView);
        titleTextLargerCopyView.setTextColor(getThemedColor(Theme.key_actionBarDefaultTitle));
        titleTextLargerCopyView.setTextSizePx(dp(glassMode ? 17.5f : 18));
        titleTextLargerCopyView.setGravity(Gravity.LEFT);
        titleTextLargerCopyView.setTypeface(AndroidUtilities.bold());
        titleTextLargerCopyView.setLeftDrawableTopPadding(-dp(1.3f));
        titleTextLargerCopyView.setRightDrawable(titleTextView.getRightDrawable());
        titleTextLargerCopyView.setRightDrawable2(titleTextView.getRightDrawable2());
        titleTextLargerCopyView.setRightDrawableOutside(titleTextView.getRightDrawableOutside());
        titleTextLargerCopyView.setLeftDrawable(titleTextView.getLeftDrawable());
        titleTextLargerCopyView.setText(titleTextView.getText());
        titleTextLargerCopyView.animate().alpha(0).setDuration(350).setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT).withEndAction(() -> {
            SimpleTextView titleTextLargerCopyView2 = this.titleTextLargerCopyView.get();
            if (titleTextLargerCopyView2 != null) {
                removeView(titleTextLargerCopyView2);
                this.titleTextLargerCopyView.set(null);
            }
        }).start();
        addView(titleTextLargerCopyView);

        SimpleTextView subtitleTextLargerCopyView = this.subtitleTextLargerCopyView.get();
        if (subtitleTextLargerCopyView != null) {
            removeView(subtitleTextLargerCopyView);
        }
        subtitleTextLargerCopyView = new SimpleTextView(getContext());
        this.subtitleTextLargerCopyView.set(subtitleTextLargerCopyView);
        subtitleTextLargerCopyView.setTextColor(getThemedColor(Theme.key_actionBarDefaultSubtitle));
        subtitleTextLargerCopyView.setTag(Theme.key_actionBarDefaultSubtitle);
        subtitleTextLargerCopyView.setTextSizePx(dp(glassMode ? 13.5f : 14));
        subtitleTextLargerCopyView.setGravity(Gravity.LEFT);
        if (subtitleTextView != null) {
            subtitleTextLargerCopyView.setText(subtitleTextView.getText());
        } else if (animatedSubtitleTextView != null) {
            subtitleTextLargerCopyView.setText(animatedSubtitleTextView.getText());
        }
        subtitleTextLargerCopyView.animate().alpha(0).setDuration(350).setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT).withEndAction(() -> {
            SimpleTextView subtitleTextLargerCopyView2 = this.subtitleTextLargerCopyView.get();
            if (subtitleTextLargerCopyView2 != null) {
                removeView(subtitleTextLargerCopyView2);
                this.subtitleTextLargerCopyView.set(null);
                if (!allowDrawStories) {
                    setClipChildren(true);
                }
            }
        }).start();
        addView(subtitleTextLargerCopyView);

        setClipChildren(false);
    }

    private boolean glassMode;

    public void setGlassMode() {
        if (zxc.iconic.xenon.helpers.NonIslandHelper.chatElements()) return;
        if (titleTextView != null) {
            titleTextView.setTextSizePx(dp(17.5f));
        }
        if (subtitleTextView != null) {
            subtitleTextView.setTextSizePx(dp(13.5f));
        }
        glassMode = true;
    }

    private boolean m3HeaderMode;

    public void setM3HeaderMode(boolean enabled) {
        if (zxc.iconic.xenon.helpers.NonIslandHelper.chatElements()) enabled = false;
        m3HeaderMode = enabled;
        updateAvatarSizeIfNeeded();
        if (subtitleTextView != null) {
            subtitleTextView.setTextSizePx(dp(effectiveM3() ? 14f : 13.5f));
            subtitleTextView.setAlpha(effectiveM3() ? 0.85f : 1f);
        }
    }

    public void setBiggerAvatar(boolean enabled) {
        biggerAvatar = enabled;
        updateAvatarSizeIfNeeded();
    }

    private void updateAvatarSizeIfNeeded() {
        int newSize = biggerAvatar ? 48 : 42;
        if (avatarSizeInDp != newSize) {
            avatarSizeInDp = newSize;
            if (avatarImageView != null) {
                avatarImageView.setRoundRadius(getAvatarCornerRadius());
            }
            requestLayout();
        }
    }

    public void setAvatarPlacement(int placement) {
        avatarPlacement = placement;
        avatarSizeInDp = biggerAvatar ? 48 : 42;
        lastRightAvatarLeft = Integer.MIN_VALUE;
        if (avatarImageView != null) {
            avatarImageView.setRoundRadius(getAvatarCornerRadius());
        }
        requestLayout();
    }

    public void setRightTextInset(int inset) {
        rightTextInset = inset;
    }

    public void setTextOnlyPill(boolean textOnlyPill) {
        this.textOnlyPill = textOnlyPill;
    }

    public void setRightAnchorView(View anchorView) {
        this.rightAnchorView = anchorView;
    }

    public void setAvatarOffset(float offset) {
        if (avatarImageView != null) avatarImageView.setTranslationX(offset);
        if (communityItem != null) communityItem.setTranslationX(offset);
        if (timeItem != null) timeItem.setTranslationX(offset);
        if (starBgItem != null) starBgItem.setTranslationX(offset);
        if (starFgItem != null) starFgItem.setTranslationX(offset);
    }

    public int getAvatarPlacement() {
        return avatarPlacement;
    }

    /**
     * M3 header layout only applies when the avatar is actually visible and the
     * chat is not the self-chat (Saved Messages), so chats with a centered title
     * don't shift when M3 chat headers are enabled.
     */
    private boolean effectiveM3() {
        if (!m3HeaderMode) {
            return false;
        }
        if (avatarPlacement == zxc.iconic.xenon.NekoConfig.AVATAR_PLACEMENT_RIGHT) {
            return false;
        }
        if (avatarImageView == null || avatarImageView.getVisibility() != VISIBLE) {
            return false;
        }
        if (parentFragment != null && parentFragment.getCurrentUser() != null && parentFragment.getCurrentUser().self) {
            return false;
        }
        return true;
    }

    private int getAvatarCornerRadius() {
        return dp((avatarSizeInDp - 2) / 2);
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        final int actionBarHeight = ActionBar.getCurrentActionBarHeight();
        final boolean rightPlacement = avatarPlacement == zxc.iconic.xenon.NekoConfig.AVATAR_PLACEMENT_RIGHT && avatarImageView.getVisibility() == VISIBLE;
        int viewTop;
        if (rightPlacement) {
            viewTop = (actionBarHeight - (dp(42) - 2) - 2) / 2 + (occupyStatusBar ? AndroidUtilities.statusBarHeight : 0);
        } else {
            viewTop = (actionBarHeight - avatarImageView.getMeasuredHeight() - 2) / 2 + (occupyStatusBar ? AndroidUtilities.statusBarHeight : 0);
        }
        final boolean m3 = effectiveM3();
        int subtitleTop;
        if (m3) {
            int subTextHeight = subtitleTextView != null ? subtitleTextView.getTextHeight() : dp(16);
            subtitleTop = 1 + viewTop + avatarImageView.getMeasuredHeight() - dp(4f) - subTextHeight;
        } else {
            subtitleTop = viewTop + dp(glassMode ? 23.66f : 24);
        }

final boolean avatarVisible = avatarImageView.getVisibility() == VISIBLE;
        final boolean rightAvatar = avatarPlacement == zxc.iconic.xenon.NekoConfig.AVATAR_PLACEMENT_RIGHT && avatarVisible;
        final boolean textOnly = textOnlyPill && avatarVisible;
        final int avatarLeft;
        final int badgeBase;
        int avatarTop = 1 + viewTop + dp(0.3f);
        if (rightAvatar) {
            int[] anchor = anchorCenterInParent();
            android.view.ViewGroup.MarginLayoutParams lp = (android.view.ViewGroup.MarginLayoutParams) getLayoutParams();
            if (anchor != null) {
                avatarLeft = anchor[0] - avatarImageView.getMeasuredWidth() / 2 - lp.leftMargin;
                avatarTop = anchor[1] - avatarImageView.getMeasuredHeight() / 2 + dp(0.3f);
                lastRightAvatarLeft = avatarLeft;
            } else {
                avatarLeft = lastRightAvatarLeft != Integer.MIN_VALUE ? lastRightAvatarLeft : getWidth() + dp(3);
                avatarTop = 1 + (actionBarHeight - avatarImageView.getMeasuredHeight() - 2) / 2 + (occupyStatusBar ? AndroidUtilities.statusBarHeight : 0) + dp(0.3f);
                if (rightAnchorView != null && !rightAnchorView.isLaidOut()) {
                    post(() -> requestLayout());
                }
            }
            badgeBase = avatarLeft;
        } else {
            avatarLeft = 1 + leftPadding;
            badgeBase = leftPadding;
        }
        final int l;
        if (textOnly) {
            l = leftPadding;
        } else if (rightAvatar) {
            l = leftPadding + (!m3HeaderMode && !textOnlyPill ? dp(7f) : 0);
        } else {
            l = leftPadding + (avatarVisible ? dp(m3 ? 57f : glassMode ? 49.66f : 55) : dp(glassMode ? 13 : 1)) + rightAvatarPadding;
        }
        avatarImageView.layout(avatarLeft, avatarTop, avatarLeft + avatarImageView.getMeasuredWidth(), avatarTop + avatarImageView.getMeasuredHeight());
        final int titleL;
        final int subtitleL;
        final int pillRight;
        if (textOnlyPill) {
            final int pillLeft = leftPadding - dp(6) - dp(3);
            final int targetPillWidth = getVisualWidth() + dp(12);
            final int realPillWidth = actionBar != null ? actionBar.getCurrentChatPillWidth() : 0;
            final boolean pillAnimating = actionBar != null && actionBar.isChatAvatarContainerWidthAnimating();
            final int pillWidth = realPillWidth > 0 && !pillAnimating ? realPillWidth : targetPillWidth;
            pillRight = pillLeft + pillWidth;
            if (actionBar != null) {
                actionBar.setContainerLayoutPillWidth(pillWidth);
            }
            final int titleWidth = titleTextView.getDrawnWidth();
            final int titleFadeShift = titleTextView.getDrawnWidth() < (int) titleTextView.getExactWidth() ? dp(8) : 0;
            final View subTextView = getSubtitleTextView();
            final int subWidth;
            if (subTextView != null && subTextView.getVisibility() != GONE) {
                if (subtitleTextView != null) {
                    subWidth = subtitleTextView.getDrawnWidth();
                } else if (animatedSubtitleTextView != null) {
                    subWidth = (int) animatedSubtitleTextView.getDrawable().getCurrentWidth();
                } else {
                    subWidth = 0;
                }
            } else {
                subWidth = 0;
            }
            final int subFadeShift = subtitleTextView != null && subtitleTextView.getDrawnWidth() < (int) subtitleTextView.getExactWidth() ? dp(8) : 0;
            titleL = Math.max(leftPadding, pillLeft + (pillWidth - titleWidth) / 2 + titleFadeShift);
            subtitleL = subTextView != null && subTextView.getVisibility() != GONE ? Math.max(leftPadding, pillLeft + (pillWidth - subWidth) / 2 + subFadeShift) : titleL;
        } else {
            titleL = l;
            subtitleL = l;
            pillRight = Integer.MAX_VALUE;
        }
        SimpleTextView titleTextLargerCopyView = this.titleTextLargerCopyView.get();
        if (getSubtitleTextView().getVisibility() != GONE) {
            titleTextView.layout(titleL, viewTop + dp(m3 ? 2.5f : 1.66f) - titleTextView.getPaddingTop(), Math.min(titleL + titleTextView.getMeasuredWidth(), pillRight), viewTop + titleTextView.getTextHeight() + dp(m3 ? 2.5f : 1.66f) - titleTextView.getPaddingTop() + titleTextView.getPaddingBottom());
            if (titleTextLargerCopyView != null) {
                titleTextLargerCopyView.layout(titleL, viewTop + dp(m3 ? 2.5f : 1.66f), titleL + titleTextLargerCopyView.getMeasuredWidth(), viewTop + titleTextLargerCopyView.getTextHeight() + dp(m3 ? 2.5f : 1.66f));
            }
        } else {
            titleTextView.layout(titleL, viewTop + dp(11) - titleTextView.getPaddingTop(), Math.min(titleL + titleTextView.getMeasuredWidth(), pillRight), viewTop + titleTextView.getTextHeight() + dp(11) - titleTextView.getPaddingTop() + titleTextView.getPaddingBottom());
            if (titleTextLargerCopyView != null) {
                titleTextLargerCopyView.layout(titleL, viewTop + dp(10), titleL + titleTextLargerCopyView.getMeasuredWidth(), viewTop + titleTextLargerCopyView.getTextHeight() + dp(10));
            }
        }
        if (communityItem != null) {
            communityItem.layout(
                badgeBase + dp(m3 ? 34f : 29f),
                avatarTop - 1 + dp(m3 ? 32.33f : 27.33f),
                badgeBase + dp(m3 ? 34f : 29f) + communityItem.getMeasuredWidth(),
                avatarTop - 1 + dp(m3 ? 32.33f : 27.33f) + communityItem.getMeasuredHeight());
        }
        if (timeItem != null) {
            timeItem.layout(
                badgeBase + dp(m3 ? 24.333f : 19.333f),
                avatarTop - 1 - dp(8),
                badgeBase + dp(m3 ? 24.333f : 19.333f) + timeItem.getMeasuredWidth(),
                avatarTop - 1 - dp(8) + timeItem.getMeasuredHeight()
            );
        }
        if (starBgItem != null) {
            starBgItem.layout(badgeBase + dp(m3 ? 33 : 28), avatarTop - 1 + dp(m3 ? 29 : 24), badgeBase + dp(m3 ? 33 : 28) + starBgItem.getMeasuredWidth(), avatarTop - 1 + dp(m3 ? 29 : 24) + starBgItem.getMeasuredHeight());
        }
        if (starFgItem != null) {
            starFgItem.layout(badgeBase + dp(m3 ? 33 : 28), avatarTop - 1 + dp(m3 ? 29 : 24), badgeBase + dp(m3 ? 33 : 28) + starFgItem.getMeasuredWidth(), avatarTop - 1 + dp(m3 ? 29 : 24) + starFgItem.getMeasuredHeight());
        }
        if (subtitleTextView != null) {
            subtitleTextView.layout(subtitleL, subtitleTop, Math.min(subtitleL + subtitleTextView.getMeasuredWidth(), pillRight), subtitleTop + subtitleTextView.getTextHeight());
        } else if (animatedSubtitleTextView != null) {
            animatedSubtitleTextView.layout(subtitleL, subtitleTop, Math.min(subtitleL + animatedSubtitleTextView.getMeasuredWidth(), pillRight), subtitleTop + animatedSubtitleTextView.getTextHeight());
        }
        SimpleTextView subtitleTextLargerCopyView = this.subtitleTextLargerCopyView.get();
        if (subtitleTextLargerCopyView != null) {
            subtitleTextLargerCopyView.layout(subtitleL, subtitleTop, Math.min(subtitleL + subtitleTextLargerCopyView.getMeasuredWidth(), pillRight), subtitleTop + subtitleTextLargerCopyView.getTextHeight());
        }
    }

    private int[] anchorCenterInParent() {
        if (rightAnchorView == null || !rightAnchorView.isLaidOut()) return null;
        int x = rightAnchorView.getLeft();
        int y = rightAnchorView.getTop();
        ViewParent actionBar = getParent();
        ViewParent p = rightAnchorView.getParent();
        while (p != null && p != actionBar && p instanceof View) {
            View pv = (View) p;
            x += pv.getLeft() + (int) pv.getTranslationX();
            y += pv.getTop() + (int) pv.getTranslationY();
            p = pv.getParent();
        }
        x += rightAnchorView.getWidth() / 2;
        y += rightAnchorView.getHeight() / 2;
        return new int[]{x, y};
    }

    public void setLeftPadding(int value) {
        leftPadding = value;
    }

    public int getLeftPadding() {
        return leftPadding;
    }

    public void setRightAvatarPadding(int value) {
        rightAvatarPadding = value;
    }

    public void setCommunityItemVisible(boolean visible) {
        if (communityItem != null) {
            communityItem.setVisibility(visible && !avatarImageIsHidden ? VISIBLE : GONE);
        }
    }


    @Override
    public void onFactorChanged(int id, float factor, float fraction, FactorAnimator callee) {
        if (id == ANIMATOR_ID_TIME_ITEM_VISIBLE) {
            if (timeItem != null) {
                timeItem.setAlpha(factor);
                timeItem.setScaleX(factor * 0.85f);
                timeItem.setScaleY(factor * 0.85f);
                timeItem.setVisibility(factor > 0 ? VISIBLE : GONE);
            }
        }
    }


    public void showTimeItem(boolean animated) {
        animatorTimeVisible.setValue(true, animated);
    }

    public void hideTimeItem(boolean animated) {
        animatorTimeVisible.setValue(false, animated);
    }

    public void setTime(int value, boolean animated) {
        if (timerDrawable == null) {
            return;
        }
        boolean show = !stars;
        if (value == 0 && !secretChatTimer) {
            show = false;
            return;
        }
        if (show) {
            showTimeItem(animated);
            timerDrawable.setTime(value);
        } else {
            hideTimeItem(animated);
        }
    }

    public boolean stars;
    public void setStars(boolean stars, boolean animated) {
        if (starBgItem == null || starFgItem == null) return;
        this.stars = stars;
        if (!animated) {
            starBgItem.setVisibility(stars ? VISIBLE : INVISIBLE);
            starBgItem.setAlpha(stars ? 1f : 0f);
            starBgItem.setScaleX(stars ? 1.1f : 0f);
            starBgItem.setScaleY(stars ? 1.1f : 0f);
            starFgItem.setVisibility(stars ? VISIBLE : INVISIBLE);
            starFgItem.setAlpha(stars ? 1f : 0f);
            starFgItem.setScaleX(stars ? 1f : 0f);
            starFgItem.setScaleY(stars ? 1f : 0f);
        } else {
            if (stars) {
                starBgItem.setVisibility(VISIBLE);
                starFgItem.setVisibility(VISIBLE);
            }
            starBgItem.animate().alpha(stars ? 1f : 0f).scaleX(stars ? 1.1f : 0f).scaleY(stars ? 1.1f : 0f).withEndAction(() -> {
                if (!stars) {
                    starBgItem.setVisibility(INVISIBLE);
                }
            }).start();
            starFgItem.animate().alpha(stars ? 1f : 0f).scaleX(stars ? 1f : 0f).scaleY(stars ? 1f : 0f).withEndAction(() -> {
                if (!stars) {
                    starFgItem.setVisibility(INVISIBLE);
                }
            }).start();
        }
    }

    private boolean rightDrawableIsScamOrVerified = false;
    private boolean rightDrawableIsScam = false;
    private String rightDrawableContentDescription = null;
    private String rightDrawable2ContentDescription = null;

    public void setTitleIcons(Drawable leftIcon, Drawable mutedIcon) {
        titleTextView.setLeftDrawable(leftIcon);
        if (!rightDrawableIsScamOrVerified && !rightDrawableIsScam) {
            if (mutedIcon != null) {
                rightDrawable2ContentDescription = getString(R.string.NotificationsMuted);
            } else {
                rightDrawable2ContentDescription = null;
            }
            titleTextView.setRightDrawable2(mutedIcon);
        }
        checkActionBar(true);
    }

    public AnimatedEmojiDrawable.SwapAnimatedEmojiDrawable getBotVerificationDrawable(long icon, boolean animated) {
        if (icon == 0) {
            return null;
        }
        botVerificationDrawable.set(icon, animated);
        botVerificationDrawable.setColor(getThemedColor(Theme.key_profile_verifiedBackground));
        botVerificationDrawable.offset(0, dp(1));
        return botVerificationDrawable;
    }

    public void setTitle(CharSequence value) {
        setTitle(value, false, false, false, false, null, false);
    }

    public void setTitle(CharSequence value, boolean scam, boolean fake, boolean verified, boolean premium, TLRPC.EmojiStatus emojiStatus, boolean animated) {
        if (value != null) {
            value = Emoji.replaceEmoji(value, titleTextView.getPaint().getFontMetricsInt(), false);
        }
        titleTextView.setText(value);
        rightDrawableIsScam = false;
        if (scam || fake) {
            rightDrawableIsScam = true;
            if (!(titleTextView.getRightDrawable() instanceof ScamDrawable)) {
                ScamDrawable drawable = new ScamDrawable(11, scam ? 0 : 1);
                drawable.setColor(getThemedColor(Theme.key_actionBarDefaultSubtitle));
                titleTextView.setRightDrawable2(drawable);
//                titleTextView.setRightPadding(0);
                rightDrawable2ContentDescription = getString(R.string.ScamMessage);
                rightDrawableIsScamOrVerified = true;
            }
        } else if (verified) {
            verifiedBackground = getResources().getDrawable(R.drawable.verified_area).mutate();
            verifiedBackground.setColorFilter(new PorterDuffColorFilter(getThemedColor(Theme.key_profile_verifiedBackground), PorterDuff.Mode.MULTIPLY));
            verifiedCheck = getResources().getDrawable(R.drawable.verified_check).mutate();
            verifiedCheck.setColorFilter(new PorterDuffColorFilter(getThemedColor(Theme.key_profile_verifiedCheck), PorterDuff.Mode.MULTIPLY));
            Drawable verifiedDrawable = new CombinedDrawable(verifiedBackground, verifiedCheck);
            titleTextView.setRightDrawable2(verifiedDrawable);
            rightDrawableIsScamOrVerified = true;
            rightDrawable2ContentDescription = getString(R.string.AccDescrVerified);
        } else if (titleTextView.getRightDrawable() instanceof ScamDrawable) {
            titleTextView.setRightDrawable2(null);
            rightDrawableIsScamOrVerified = false;
            rightDrawable2ContentDescription = null;
        }
        if (premium || DialogObject.getEmojiStatusDocumentId(emojiStatus) != 0) {
            if (titleTextView.getRightDrawable() instanceof AnimatedEmojiDrawable.WrapSizeDrawable &&
                ((AnimatedEmojiDrawable.WrapSizeDrawable) titleTextView.getRightDrawable()).getDrawable() instanceof AnimatedEmojiDrawable) {
                ((AnimatedEmojiDrawable) ((AnimatedEmojiDrawable.WrapSizeDrawable) titleTextView.getRightDrawable()).getDrawable()).removeView(titleTextView);
            }
            if (DialogObject.getEmojiStatusDocumentId(emojiStatus) != 0) {
                emojiStatusDrawable.set(DialogObject.getEmojiStatusDocumentId(emojiStatus), animated);
            } else if (premium) {
                emojiStatusDefaultDrawable = ContextCompat.getDrawable(ApplicationLoader.applicationContext, R.drawable.msg_premium_liststar).mutate();
                emojiStatusDefaultDrawable.setColorFilter(new PorterDuffColorFilter(getThemedColor(Theme.key_profile_verifiedBackground), PorterDuff.Mode.MULTIPLY));
                emojiStatusDrawable.set(emojiStatusDefaultDrawable, animated);
            } else {
                emojiStatusDrawable.set((Drawable) null, animated);
            }
            emojiStatusDrawable.setColor(getThemedColor(Theme.key_profile_verifiedBackground));
            titleTextView.setRightDrawable(emojiStatusDrawable);
            rightDrawableIsScamOrVerified = false;
            rightDrawableContentDescription = getString(R.string.AccDescrPremium);
        } else {
            titleTextView.setRightDrawable(null);
            rightDrawableContentDescription = null;
        }
        checkActionBar(animated);
        requestLayout();
    }

    private Drawable emojiStatusDefaultDrawable;
    private Drawable verifiedBackground;
    private Drawable verifiedCheck;


    public void setSubtitle(CharSequence value) {
        if (lastSubtitle == null) {
            if (subtitleTextView != null) {
                subtitleTextView.setText(value);
            } else if (animatedSubtitleTextView != null) {
                animatedSubtitleTextView.setText(value);
            }
        } else {
            lastSubtitle = value;
        }
        checkActionBar(true);
        requestLayout();
    }

    public ImageView getTimeItem() {
        return timeItem;
    }

    public SimpleTextView getTitleTextView() {
        return titleTextView;
    }

    public View getSubtitleTextView() {
        if (subtitleTextView != null) {
            return subtitleTextView;
        }
        if (animatedSubtitleTextView != null) {
            return animatedSubtitleTextView;
        }
        return null;
    }

    public TextPaint getSubtitlePaint() {
        return subtitleTextView != null ? subtitleTextView.getTextPaint() : animatedSubtitleTextView.getPaint();
    }

    public void onDestroy() {
        if (sharedMediaPreloader != null) {
            sharedMediaPreloader.onDestroy(parentFragment);
        }
    }

    private void setTypingAnimation(boolean start) {
        if (subtitleTextView == null) return;
        if (start) {
            try {
                int type = subtitleIsThinkingBot ? 0 : MessagesController.getInstance(currentAccount).getPrintingStringType(parentFragment.getDialogId(), parentFragment.getThreadId());
                if (statusDrawables[type] == null) return;
                if (type == 5) {
                    subtitleTextView.replaceTextWithDrawable(statusDrawables[type], "**oo**");
                    statusDrawables[type].setColor(getThemedColor(Theme.key_chat_status));
                    subtitleTextView.setLeftDrawable(null);
                } else {
                    subtitleTextView.replaceTextWithDrawable(null, null);
                    statusDrawables[type].setColor(getThemedColor(Theme.key_chat_status));
                    subtitleTextView.setLeftDrawable(statusDrawables[type]);
                }
                currentTypingDrawable = statusDrawables[type];
                for (int a = 0; a < statusDrawables.length; a++) {
                    if (statusDrawables[a] == null) continue;
                    if (a == type) {
                        statusDrawables[a].start();
                    } else {
                        statusDrawables[a].stop();
                    }
                }
            } catch (Exception e) {
                FileLog.e(e);
            }
        } else {
            currentTypingDrawable = null;
            subtitleTextView.setLeftDrawable(null);
            subtitleTextView.replaceTextWithDrawable(null, null);
            for (int a = 0; a < statusDrawables.length; a++) {
                if (statusDrawables[a] != null) {
                    statusDrawables[a].stop();
                }
            }
        }
    }

    public void updateSubtitle() {
        updateSubtitle(false);
    }

    private boolean subtitleIsThinkingBot;

    private boolean showingSavedMessagesHint;

    public void updateSubtitle(boolean animated) {
        if (parentFragment == null) {
            return;
        }
        if (parentFragment.getChatMode() == ChatActivity.MODE_EDIT_BUSINESS_LINK) {
            setSubtitle(BusinessLinksController.stripHttps(parentFragment.businessLink.link));
            return;
        }
        TLRPC.User user = parentFragment.getCurrentUser();
        TLRPC.Chat chat = parentFragment.getCurrentChat();
        boolean showSavedMessagesHint = (
            UserObject.isUserSelf(user) &&
            parentFragment.getChatMode() == ChatActivity.MODE_DEFAULT &&
            parentFragment.getMessagesController().getSavedMessagesController().getAllCount() >= 3 &&
            (showingSavedMessagesHint || (MessagesController.getGlobalMainSettings().getInt("savedmsgschatshint", 0) < 3))
        );
        if ((UserObject.isUserSelf(user) && !showSavedMessagesHint || UserObject.isReplyUser(user) || user != null && user.id == UserObject.VERIFY || parentFragment.getChatMode() != 0 && parentFragment.getChatMode() != ChatActivity.MODE_SUGGESTIONS) && parentFragment.getChatMode() != ChatActivity.MODE_SAVED) {
            if (getSubtitleTextView().getVisibility() != GONE) {
                getSubtitleTextView().setVisibility(GONE);
            }
            requestLayout();
            return;
        } else if (showSavedMessagesHint) {
            if (getSubtitleTextView().getVisibility() != VISIBLE) {
                getSubtitleTextView().setVisibility(VISIBLE);
            }
            if (!showingSavedMessagesHint) {
                MessagesController.getGlobalMainSettings().edit().putInt(
                    "savedmsgschatshint", MessagesController.getGlobalMainSettings().getInt("savedmsgschatshint", 0) + 1
                ).apply();
                showingSavedMessagesHint = true;
            }
        }

        subtitleIsThinkingBot = false;
        CharSequence printString = MessagesController.getInstance(currentAccount).getPrintingString(parentFragment.getDialogId(), parentFragment.getThreadId(), false);
        if (printString == null && UserObject.isBotForum(user)) {
            //if (BotForumHelper.getInstance(currentAccount).isThinking(user.id, (int) parentFragment.getTopicId())) {
            //    printString = "thinking";
            //    subtitleIsThinkingBot = true;
            //}
        }

        if (printString != null) {
            printString = TextUtils.replace(printString, new String[]{"..."}, new String[]{""});
        }
        CharSequence newSubtitle;
        boolean useOnlineColor = false;
        if (printString == null || printString.length() == 0 || ChatObject.isChannel(chat) && !chat.megagroup) {
            if (parentFragment.isThreadChat() && !parentFragment.isTopic) {
                if (titleTextView.getTag() != null) {
                    return;
                }
                titleTextView.setTag(1);
                if (titleAnimation != null) {
                    titleAnimation.cancel();
                    titleAnimation = null;
                }
                if (animated) {
                    titleAnimation = new AnimatorSet();
                    titleAnimation.playTogether(
                        ObjectAnimator.ofFloat(titleTextView, View.TRANSLATION_Y, dp(9.7f)),
                        ObjectAnimator.ofFloat(getSubtitleTextView(), View.ALPHA, 0.0f)
                    );
                    titleAnimation.addListener(new AnimatorListenerAdapter() {
                        @Override
                        public void onAnimationCancel(Animator animation) {
                            titleAnimation = null;
                        }

                        @Override
                        public void onAnimationEnd(Animator animation) {
                            if (titleAnimation == animation) {
                                getSubtitleTextView().setVisibility(INVISIBLE);
                                titleAnimation = null;
                            }
                        }
                    });
                    titleAnimation.setDuration(180);
                    titleAnimation.start();
                } else {
                    titleTextView.setTranslationY(dp(9.7f));
                    getSubtitleTextView().setAlpha(0.0f);
                    getSubtitleTextView().setVisibility(INVISIBLE);
                }
                requestLayout();
                return;
            }
            setTypingAnimation(false);
            if (parentFragment.getChatMode() == ChatActivity.MODE_SUGGESTIONS) {
                if (parentFragment.isSubscriberSuggestions) {
                    newSubtitle = getString(R.string.ChatMessageSuggestions);
                } else {
                    final long dialogId = parentFragment.getTopicId();
                    if (dialogId == 0) {
                        int topicsCount = parentFragment.getMessagesController().getTopicsController().getTopicsCount(-parentFragment.getDialogId());
                        if (topicsCount > 0) {
                            newSubtitle = LocaleController.formatPluralStringComma("Chats", topicsCount);
                        } else {
                            newSubtitle = getString(R.string.ChatMessageSuggestions);
                        }
                    } else {
                        TLRPC.TL_forumTopic topic = MessagesController.getInstance(currentAccount).getTopicsController().findTopic(chat.id, parentFragment.getTopicId());
                        int count = 0;
                        if (topic != null) {
                            count = topic.totalMessagesCount;
                        }
                        if (count > 0) {
                            newSubtitle = LocaleController.formatPluralString("messages", count, count);
                        } else {
                            newSubtitle = LocaleController.formatString(R.string.TopicProfileStatus, ForumUtilities.getMonoForumTitle(currentAccount, chat));
                        }
                    }
                }
            } else if (parentFragment.getChatMode() == ChatActivity.MODE_SAVED) {
                int messagesCount = parentFragment.getMessagesController().getSavedMessagesController().getMessagesCount(parentFragment.getSavedDialogId());
                newSubtitle = LocaleController.formatPluralString("SavedMessagesCount", Math.max(1, messagesCount));
            } else if (parentFragment.isTopic && chat != null) {
                TLRPC.TL_forumTopic topic = MessagesController.getInstance(currentAccount).getTopicsController().findTopic(chat.id, parentFragment.getTopicId());
                int count = 0;
                if (topic != null) {
                    count = topic.totalMessagesCount - 1;
                }
                if (count > 0) {
                    newSubtitle = LocaleController.formatPluralString("messages", count, count);
                } else {
                    newSubtitle = LocaleController.formatString(R.string.TopicProfileStatus, chat.title);
                }
            } else if (chat != null) {
                TLRPC.ChatFull info = parentFragment.getCurrentChatInfo();
                newSubtitle = getChatSubtitle(chat, info, onlineCount);
            } else if (user != null) {
                TLRPC.User newUser = MessagesController.getInstance(currentAccount).getUser(user.id);
                if (newUser != null) {
                    user = newUser;
                }
                CharSequence newStatus;
                if (UserObject.isReplyUser(user)) {
                    newStatus = "";
                } else if (user.id == UserObject.VERIFY) {
                    newStatus = "";//LocaleController.getString(R.string.VerifyCodesNotifications);
                } else if (user.id == UserConfig.getInstance(currentAccount).getClientUserId()) {
                    if (showSavedMessagesHint) {
                        newStatus = replaceArrows(getString(R.string.SavedMessagesViewAsChatsHint), false);
                    } else {
                        newStatus = getString(R.string.ChatYourSelf);
                    }
                } else if (user.id == 333000 || user.id == 777000 || user.id == 42777) {
                    newStatus = getString(R.string.ServiceNotifications);
                } else if (MessagesController.isSupportUser(user)) {
                    newStatus = getString(R.string.SupportStatus);
                } else if (user.bot && user.bot_active_users != 0) {
                    newStatus = LocaleController.formatPluralStringComma("BotUsers", user.bot_active_users, ',');
                } else if (user.bot) {
                    newStatus = getString(R.string.Bot);
                } else {
                    isOnline[0] = false;
                    newStatus = LocaleController.formatUserStatus(currentAccount, user, isOnline, allowShorterStatus ? statusMadeShorter : null);
                    useOnlineColor = isOnline[0];
                }
                newSubtitle = newStatus;
            } else {
                newSubtitle = "";
            }
        } else {
            if (parentFragment.isThreadChat()) {
                if (titleTextView.getTag() != null) {
                    titleTextView.setTag(null);
                    getSubtitleTextView().setVisibility(VISIBLE);
                    if (titleAnimation != null) {
                        titleAnimation.cancel();
                        titleAnimation = null;
                    }
                    if (animated) {
                        titleAnimation = new AnimatorSet();
                        titleAnimation.playTogether(
                                ObjectAnimator.ofFloat(titleTextView, View.TRANSLATION_Y, 0),
                                ObjectAnimator.ofFloat(getSubtitleTextView(), View.ALPHA, 1.0f));
                        titleAnimation.addListener(new AnimatorListenerAdapter() {
                            @Override
                            public void onAnimationEnd(Animator animation) {
                                titleAnimation = null;
                            }
                        });
                        titleAnimation.setDuration(180);
                        titleAnimation.start();
                    } else {
                        titleTextView.setTranslationY(0.0f);
                        getSubtitleTextView().setAlpha(1.0f);
                    }
                }
            }
            newSubtitle = printString;
            Integer type = MessagesController.getInstance(currentAccount).getPrintingStringType(parentFragment.getDialogId(), parentFragment.getThreadId());
            if (type != null && type == 5) {
                newSubtitle = Emoji.replaceEmoji(newSubtitle, getSubtitlePaint().getFontMetricsInt(), false);
            }
            useOnlineColor = true;
            setTypingAnimation(true);
        }
        lastSubtitleColorKey = useOnlineColor ? Theme.key_chat_status : Theme.key_actionBarDefaultSubtitle;
        if (lastSubtitle == null) {
            if (subtitleTextView != null) {
                subtitleTextView.setText(newSubtitle);
                if (overrideSubtitleColor == null) {
                    subtitleTextView.setTextColor(getThemedColor(lastSubtitleColorKey));
                    subtitleTextView.setTag(lastSubtitleColorKey);
                } else {
                    subtitleTextView.setTextColor(overrideSubtitleColor);
                }
            } else {
                animatedSubtitleTextView.setText(newSubtitle, animated);
                if (overrideSubtitleColor == null) {
                    animatedSubtitleTextView.setTextColor(getThemedColor(lastSubtitleColorKey));
                    animatedSubtitleTextView.setTag(lastSubtitleColorKey);
                } else {
                    animatedSubtitleTextView.setTextColor(overrideSubtitleColor);
                }
            }
        } else {
            lastSubtitle = newSubtitle;
        }
        checkActionBar(animated);
        requestLayout();
    }

    public static CharSequence getChatSubtitle(TLRPC.Chat chat, TLRPC.ChatFull info, int onlineCount) {
        CharSequence newSubtitle = null;
        if (ChatObject.isChannel(chat)) {
            if (info != null && info.participants_count != 0) {
                if (chat.megagroup) {
                    if (onlineCount > 1) {
                        newSubtitle = String.format("%s, %s", LocaleController.formatPluralString("Members", info.participants_count), LocaleController.formatPluralString("OnlineCount", Math.min(onlineCount, info.participants_count)));
                    } else {
                        newSubtitle = LocaleController.formatPluralString("Members", info.participants_count);
                    }
                } else {
                    int[] result = new int[1];
                    boolean ignoreShort = AndroidUtilities.isAccessibilityScreenReaderEnabled();
                    String shortNumber = ignoreShort ? String.valueOf(result[0] = info.participants_count) : LocaleController.formatShortNumber(info.participants_count, result);
                    if (chat.megagroup) {
                        newSubtitle = LocaleController.formatPluralString("Members", result[0]).replace(String.format("%d", result[0]), shortNumber);
                    } else {
                        newSubtitle = LocaleController.formatPluralString("Subscribers", result[0]).replace(String.format("%d", result[0]), shortNumber);
                    }
                }
            } else {
                if (chat.megagroup) {
                    if (info == null) {
                        newSubtitle = getString(R.string.Loading).toLowerCase();
                    } else {
                        if (chat.has_geo) {
                            newSubtitle = getString(R.string.MegaLocation).toLowerCase();
                        } else if (ChatObject.isPublic(chat)) {
                            newSubtitle = getString(R.string.MegaPublic).toLowerCase();
                        } else {
                            newSubtitle = getString(R.string.MegaPrivate).toLowerCase();
                        }
                    }
                } else {
                    if (ChatObject.isPublic(chat)) {
                        newSubtitle = getString(R.string.ChannelPublic).toLowerCase();
                    } else {
                        newSubtitle = getString(R.string.ChannelPrivate).toLowerCase();
                    }
                }
            }
        } else {
            if (ChatObject.isKickedFromChat(chat)) {
                newSubtitle = getString(R.string.YouWereKicked);
            } else if (ChatObject.isLeftFromChat(chat)) {
                newSubtitle = getString(R.string.YouLeft);
            } else {
                int count = chat.participants_count;
                if (info != null && info.participants != null) {
                    count = info.participants.participants.size();
                }
                if (onlineCount > 1 && count != 0) {
                    newSubtitle = String.format("%s, %s", LocaleController.formatPluralString("Members", count), LocaleController.formatPluralString("OnlineCount", onlineCount));
                } else {
                    newSubtitle = LocaleController.formatPluralString("Members", count);
                }
            }
        }
        return newSubtitle;
    }

    public int getLastSubtitleColorKey() {
        return lastSubtitleColorKey;
    }

    public void setChatAvatar(TLRPC.Chat chat) {
        avatarDrawable.setInfo(currentAccount, chat);
        if (avatarImageView != null) {
            avatarImageView.setForUserOrChat(chat, avatarDrawable);
            avatarImageView.setRoundRadius(ChatObject.isForum(chat) ? dp(ChatObject.hasStories(chat) ? 11 : 16) : getAvatarCornerRadius());
        }
    }

    public void setFeedAvatar() {
        avatarDrawable.setInfo(UserConfig.getInstance(currentAccount).getClientUserId());
        avatarDrawable.setAvatarType(1);
        avatarDrawable.setCustomIcon(Theme.avatarDrawables[25]);
        if (avatarImageView != null) {
            avatarImageView.setImage(null, null, avatarDrawable, null);
        }
    }

    public void setUserAvatar(TLRPC.User user) {
        setUserAvatar(user, false);
    }

    public void setUserAvatar(TLRPC.User user, boolean showSelf) {
        avatarDrawable.setInfo(currentAccount, user);
        if (UserObject.isReplyUser(user)) {
            avatarDrawable.setAvatarType(AvatarDrawable.AVATAR_TYPE_REPLIES);
            avatarDrawable.setScaleSize(.8f);
            if (avatarImageView != null) {
                avatarImageView.setImage(null, null, avatarDrawable, user);
            }
        } else if (UserObject.isAnonymous(user)) {
            avatarDrawable.setAvatarType(AvatarDrawable.AVATAR_TYPE_ANONYMOUS);
            avatarDrawable.setScaleSize(.8f);
            if (avatarImageView != null) {
                avatarImageView.setImage(null, null, avatarDrawable, user);
            }
        } else if (UserObject.isUserSelf(user) && !showSelf) {
            avatarDrawable.setAvatarType(AvatarDrawable.AVATAR_TYPE_SAVED);
            avatarDrawable.setScaleSize(.8f);
            if (avatarImageView != null) {
                avatarImageView.setImage(null, null, avatarDrawable, user);
            }
        } else {
            avatarDrawable.setScaleSize(1f);
            if (avatarImageView != null) {
                avatarImageView.setForUserOrChat(user, avatarDrawable);
            }
        }
    }

    public void checkAndUpdateAvatar() {
        if (parentFragment == null) {
            return;
        }

        TLRPC.User user = parentFragment.getCurrentUser();
        TLRPC.Chat chat = parentFragment.getCurrentChat();
        if (parentFragment.getChatMode() == ChatActivity.MODE_SAVED) {
            long dialogId = parentFragment.getSavedDialogId();
            if (dialogId >= 0) {
                user = parentFragment.getMessagesController().getUser(dialogId);
                chat = null;
            } else {
                user = null;
                chat = parentFragment.getMessagesController().getChat(-dialogId);
            }
        }
        if (user != null) {
            avatarDrawable.setInfo(currentAccount, user);
            if (UserObject.isReplyUser(user)) {
                avatarDrawable.setScaleSize(.8f);
                avatarDrawable.setAvatarType(AvatarDrawable.AVATAR_TYPE_REPLIES);
                if (avatarImageView != null) {
                    avatarImageView.setAnimatedEmojiDrawable(null);
                    avatarImageView.setImage(null, null, avatarDrawable, user);
                }
            } else if (UserObject.isAnonymous(user)) {
                avatarDrawable.setScaleSize(.8f);
                avatarDrawable.setAvatarType(AvatarDrawable.AVATAR_TYPE_ANONYMOUS);
                if (avatarImageView != null) {
                    avatarImageView.setAnimatedEmojiDrawable(null);
                    avatarImageView.setImage(null, null, avatarDrawable, user);
                }
            } else if (UserObject.isUserSelf(user) && parentFragment.getChatMode() == ChatActivity.MODE_SAVED) {
                avatarDrawable.setScaleSize(.8f);
                avatarDrawable.setAvatarType(AvatarDrawable.AVATAR_TYPE_MY_NOTES);
                if (avatarImageView != null) {
                    avatarImageView.setAnimatedEmojiDrawable(null);
                    avatarImageView.setImage(null, null, avatarDrawable, user);
                }
            } else if (UserObject.isUserSelf(user)) {
                avatarDrawable.setScaleSize(.8f);
                avatarDrawable.setAvatarType(AvatarDrawable.AVATAR_TYPE_SAVED);
                if (avatarImageView != null) {
                    avatarImageView.setAnimatedEmojiDrawable(null);
                    avatarImageView.setImage(null, null, avatarDrawable, user);
                }
            } else {
                avatarDrawable.setScaleSize(1f);
                if (avatarImageView != null) {
                    avatarImageView.setAnimatedEmojiDrawable(null);
                    avatarImageView.imageReceiver.setForUserOrChat(user, avatarDrawable,  null, true, VectorAvatarThumbDrawable.TYPE_STATIC, false);
                }
            }
        } else if (ChatObject.isMonoForum(chat)) {
            final long dialogId = parentFragment.getTopicId();
            if (ChatObject.canManageMonoForum(currentAccount, chat) && dialogId != 0) {
                if (dialogId > 0) {
                    final TLRPC.User user2 = parentFragment.getMessagesController().getUser(dialogId);
                    avatarDrawable.setInfo(user2);
                    avatarImageView.setAnimatedEmojiDrawable(null);
                    avatarImageView.setForUserOrChat(user2, avatarDrawable);
                } else {
                    final TLRPC.Chat chat2 = parentFragment.getMessagesController().getChat(-dialogId);
                    avatarDrawable.setInfo(chat2);
                    avatarImageView.setAnimatedEmojiDrawable(null);
                    avatarImageView.setForUserOrChat(chat2, avatarDrawable);
                }
            } else {
                avatarImageView.setAnimatedEmojiDrawable(null);
                ForumUtilities.setMonoForumAvatar(currentAccount, chat, avatarDrawable, avatarImageView);
            }
            avatarImageView.setRoundRadius(getAvatarCornerRadius());
        } else if (chat != null) {
            avatarDrawable.setScaleSize(1f);
            avatarDrawable.setInfo(currentAccount, chat);

            if (avatarImageView != null) {
                avatarImageView.setAnimatedEmojiDrawable(null);
                avatarImageView.setForUserOrChat(chat, avatarDrawable);
                avatarImageView.setRoundRadius(chat.forum ? dp(ChatObject.hasStories(chat) ? 11 : 16) : getAvatarCornerRadius());
            }
        }
    }

    public void updateOnlineCount() {
        if (parentFragment == null) {
            return;
        }
        onlineCount = 0;
        TLRPC.ChatFull info = parentFragment.getCurrentChatInfo();
        if (info == null) {
            return;
        }
        int currentTime = ConnectionsManager.getInstance(currentAccount).getCurrentTime();
        if (info instanceof TLRPC.TL_chatFull || info instanceof TLRPC.TL_channelFull && info.participants_count <= 200 && info.participants != null) {
            for (int a = 0; a < info.participants.participants.size(); a++) {
                TLRPC.ChatParticipant participant = info.participants.participants.get(a);
                TLRPC.User user = MessagesController.getInstance(currentAccount).getUser(participant.user_id);
                if (user != null && user.status != null && (user.status.expires > currentTime || user.id == UserConfig.getInstance(currentAccount).getClientUserId()) && user.status.expires > 10000) {
                    onlineCount++;
                }
            }
        } else if (info instanceof TLRPC.TL_channelFull && info.participants_count > 200) {
            onlineCount = info.online_count;
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (parentFragment != null) {
            NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.didUpdateConnectionState);
            NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.emojiLoaded);
            if (parentFragment.getChatMode() == ChatActivity.MODE_SAVED) {
                NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.savedMessagesDialogsUpdate);
            }
            currentConnectionState = ConnectionsManager.getInstance(currentAccount).getConnectionState();
            updateCurrentConnectionState();
        }
        if (emojiStatusDrawable != null) {
            emojiStatusDrawable.attach();
        }
        if (botVerificationDrawable != null) {
            botVerificationDrawable.attach();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (parentFragment != null) {
            NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.didUpdateConnectionState);
            NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.emojiLoaded);
            if (parentFragment.getChatMode() == ChatActivity.MODE_SAVED) {
                NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.savedMessagesDialogsUpdate);
            }
        }
        if (emojiStatusDrawable != null) {
            emojiStatusDrawable.detach();
        }
        if (botVerificationDrawable != null) {
            botVerificationDrawable.detach();
        }
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.didUpdateConnectionState) {
            int state = ConnectionsManager.getInstance(currentAccount).getConnectionState();
            if (currentConnectionState != state) {
                currentConnectionState = state;
                updateCurrentConnectionState();
            }
        } else if (id == NotificationCenter.emojiLoaded) {
            if (titleTextView != null) {
                titleTextView.invalidate();
            }
            if (getSubtitleTextView() != null) {
                getSubtitleTextView().invalidate();
            }
            invalidate();
        } else if (id == NotificationCenter.savedMessagesDialogsUpdate) {
            updateSubtitle(true);
        }
    }

    private void updateCurrentConnectionState() {
        String title = null;
        if (currentConnectionState == ConnectionsManager.ConnectionStateWaitingForNetwork) {
            title = getString(R.string.WaitingForNetwork);
        } else if (currentConnectionState == ConnectionsManager.ConnectionStateConnecting) {
            title = getString(R.string.Connecting);
        } else if (currentConnectionState == ConnectionsManager.ConnectionStateUpdating) {
            title = getString(R.string.Updating);
        } else if (currentConnectionState == ConnectionsManager.ConnectionStateConnectingToProxy) {
            title = getString(R.string.ConnectingToProxy);
        }
        if (title == null) {
            if (lastSubtitle != null) {
                if (subtitleTextView != null) {
                    subtitleTextView.setText(lastSubtitle);
                    lastSubtitle = null;
                    if (overrideSubtitleColor != null) {
                        subtitleTextView.setTextColor(overrideSubtitleColor);
                    } else if (lastSubtitleColorKey >= 0) {
                        subtitleTextView.setTextColor(getThemedColor(lastSubtitleColorKey));
                        subtitleTextView.setTag(lastSubtitleColorKey);
                    }
                } else if (animatedSubtitleTextView != null) {
                    animatedSubtitleTextView.setText(lastSubtitle, !LocaleController.isRTL);
                    lastSubtitle = null;
                    if (overrideSubtitleColor != null) {
                        animatedSubtitleTextView.setTextColor(overrideSubtitleColor);
                    } else if (lastSubtitleColorKey >= 0) {
                        animatedSubtitleTextView.setTextColor(getThemedColor(lastSubtitleColorKey));
                        animatedSubtitleTextView.setTag(lastSubtitleColorKey);
                    }
                }
            }
        } else {
            if (subtitleTextView != null) {
                if (lastSubtitle == null) {
                    lastSubtitle = subtitleTextView.getText();
                }
                subtitleTextView.setText(title);
                if (overrideSubtitleColor != null) {
                    subtitleTextView.setTextColor(overrideSubtitleColor);
                } else {
                    subtitleTextView.setTextColor(getThemedColor(Theme.key_actionBarDefaultSubtitle));
                    subtitleTextView.setTag(Theme.key_actionBarDefaultSubtitle);
                }
            } else if (animatedSubtitleTextView != null) {
                if (lastSubtitle == null) {
                    lastSubtitle = animatedSubtitleTextView.getText();
                }
                animatedSubtitleTextView.setText(title, !LocaleController.isRTL);
                if (overrideSubtitleColor != null) {
                    animatedSubtitleTextView.setTextColor(overrideSubtitleColor);
                } else {
                    animatedSubtitleTextView.setTextColor(getThemedColor(Theme.key_actionBarDefaultSubtitle));
                    animatedSubtitleTextView.setTag(Theme.key_actionBarDefaultSubtitle);
                }
            }
        }
        checkActionBar(true);
        requestLayout();
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        StringBuilder sb = new StringBuilder();
        sb.append(titleTextView.getText());
        if (rightDrawableContentDescription != null) {
            sb.append(", ");
            sb.append(rightDrawableContentDescription);
        }
        if (rightDrawable2ContentDescription != null) {
            sb.append(", ");
            sb.append(rightDrawable2ContentDescription);
        }
        sb.append("\n");
        if (subtitleTextView != null) {
            sb.append(subtitleTextView.getText());
        } else if (animatedSubtitleTextView != null) {
            sb.append(animatedSubtitleTextView.getText());
        }
        info.setContentDescription(sb);
        if (info.isClickable()) {
            info.addAction(new AccessibilityNodeInfo.AccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, getString(R.string.OpenProfile)));
        }
        if (info.isLongClickable()) {
            info.addAction(new AccessibilityNodeInfo.AccessibilityAction(AccessibilityNodeInfo.ACTION_LONG_CLICK, LocaleController.getString("Search", R.string.Search)));
        }
    }

    public SharedMediaLayout.SharedMediaPreloader getSharedMediaPreloader() {
        return sharedMediaPreloader;
    }

    public BackupImageView getAvatarImageView() {
        return avatarImageView;
    }

    private int getThemedColor(int key) {
        return Theme.getColor(key, resourcesProvider);
    }

    public void updateColors() {
        if (currentTypingDrawable != null) {
            currentTypingDrawable.setColor(getThemedColor(Theme.key_chat_status));
        }
        if (emojiStatusDefaultDrawable != null) {
            emojiStatusDefaultDrawable.setColorFilter(new PorterDuffColorFilter(getThemedColor(Theme.key_profile_verifiedBackground), PorterDuff.Mode.MULTIPLY));
        }
        if (botVerificationDrawable != null) {
            botVerificationDrawable.setColor(getThemedColor(Theme.key_profile_verifiedBackground));
        }
        if (emojiStatusDrawable != null) {
            emojiStatusDrawable.setColor(getThemedColor(Theme.key_profile_verifiedBackground));
        }
        if (verifiedBackground != null) {
            verifiedBackground.setColorFilter(new PorterDuffColorFilter(getThemedColor(Theme.key_profile_verifiedBackground), PorterDuff.Mode.MULTIPLY));
        }
        if (verifiedCheck != null) {
            verifiedCheck.setColorFilter(new PorterDuffColorFilter(getThemedColor(Theme.key_profile_verifiedCheck), PorterDuff.Mode.MULTIPLY));
        }
        invalidate();
    }

    private ActionBar actionBar;

    public void setActionBar(ActionBar actionBar) {
        this.actionBar = actionBar;
    }

    private void checkActionBar(boolean animated) {
        if (actionBar != null) {
            actionBar.checkAvatarContainerWidth(animated);
        }
    }

    public boolean hasVisibleAvatar() {
        return avatarImageView != null && avatarImageView.getVisibility() == VISIBLE;
    }

    public int getAvatarRightEdge() {
        if (avatarImageView == null || avatarImageView.getVisibility() != VISIBLE) {
            return 0;
        }
        if (avatarPlacement != zxc.iconic.xenon.NekoConfig.AVATAR_PLACEMENT_LEFT) {
            return 0;
        }
        MarginLayoutParams lp = (MarginLayoutParams) getLayoutParams();
        return (lp != null ? lp.leftMargin : 0) + 1 + leftPadding + avatarImageView.getMeasuredWidth();
    }

    public int getVisualWidth() {
        float width = 0;

        if (titleTextView != null) {
            width = Math.max(width, titleTextView.getDrawnWidth());
        }
        if (subtitleTextView != null) {
            width = Math.max(width, subtitleTextView.getDrawnWidth());
        } else if (animatedSubtitleTextView != null) {
            width = Math.max(width, animatedSubtitleTextView.getDrawable().getCurrentWidth());
        }
        if (textOnlyPill) {
            width += dp(22);
        } else if (hasVisibleAvatar()) {
            width += dp(52 + 18);
        } else {
            width += dp(34);
        }
        return (int) width;
    }
}
