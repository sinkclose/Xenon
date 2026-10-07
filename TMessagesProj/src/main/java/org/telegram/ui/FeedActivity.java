package org.telegram.ui;

import android.content.Context;
import android.content.SharedPreferences;
import org.telegram.messenger.ApplicationLoader;
import android.graphics.Canvas;
import android.graphics.RectF;
import android.os.Build;
import android.view.ViewTreeObserver;
import org.telegram.ui.Components.chat.ViewPositionWatcher;
import org.telegram.ui.Components.CircularProgressDrawable;
import org.telegram.ui.Components.blur3.RenderNodeWithHash;
import org.telegram.ui.Components.blur3.source.BlurredBackgroundSourceRenderNode;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import androidx.core.graphics.Insets;
import androidx.core.view.OnApplyWindowInsetsListener;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import org.telegram.messenger.feed.FeedController;
import java.util.ArrayList;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.ChatActivityContainer;
import org.telegram.ui.Components.Bulletin;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.ChatAvatarContainer;
import org.telegram.ui.Components.LayoutHelper;

public class FeedActivity extends BaseFragment implements NotificationCenter.NotificationCenterDelegate, MainTabsActivity.TabFragmentDelegate {
    private ChatActivityContainer chatContainer;
    private BlurredBackgroundSourceRenderNode tabsBackgroundSource;
    private final RectF chatPositionForTabs = new RectF();
    private ViewTreeObserver.OnPreDrawListener initialPositionListener;
    private final SharedPreferences.OnSharedPreferenceChangeListener headerSettingsListener = (preferences, key) -> {
        if ("material3ChatHeaders".equals(key) || "centerChatHeader".equals(key)
                || "biggerAvatar".equals(key) || "avatarPlacement".equals(key)) {
            refreshFeedHeader();
        }
    };

    private void refreshFeedHeader() {
        if (chatContainer == null || chatContainer.chatActivity.avatarContainer == null) return;
        ActionBarMenu menu = chatContainer.chatActivity.getActionBar().createMenu();
        if (menu.getItem(78) == null) menu.addItem(78, 0, chatContainer.chatActivity.themeDelegate);
        chatContainer.chatActivity.refreshFeedHeaderConfiguration(menu.getItem(78));
        chatContainer.chatActivity.avatarContainer.getAvatarImageView().setOnClickListener(v -> presentFragment(new FeedSettingsActivity()));
        invalidateTabsBackground();
    }

    @Override
    public BlurredBackgroundSourceRenderNode getGlassSource() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || fragmentView == null || chatContainer == null
                || chatContainer.getAlpha() == 0f || chatContainer.chatActivity.contentView == null) return null;
        if (tabsBackgroundSource == null) {
            tabsBackgroundSource = new BlurredBackgroundSourceRenderNode(null);
            tabsBackgroundSource.setupRenderer(new RenderNodeWithHash.Renderer() {
                @Override
                public void renderNodeUpdateDisplayList(Canvas canvas) {
                    canvas.drawColor(getThemedColor(Theme.key_windowBackgroundWhite));
                    ChatActivity chat = chatContainer.chatActivity;
                    if (ViewPositionWatcher.computeRectInParent(chat.contentView, fragmentView, chatPositionForTabs)) {
                        canvas.save();
                        canvas.translate(chatPositionForTabs.left, chatPositionForTabs.top);
                        chat.drawFeedForTabs(canvas);
                        canvas.restore();
                    }
                }
            });
        }
        tabsBackgroundSource.setSize(fragmentView.getWidth(), fragmentView.getHeight());
        tabsBackgroundSource.updateDisplayListIfNeeded();
        return tabsBackgroundSource;
    }

    private void invalidateTabsBackground() {
        if (fragmentView != null) fragmentView.invalidate();
        if (mainTabsActivityController != null) mainTabsActivityController.invalidateTabsBackground();
    }
    private boolean embeddedChatCreated;
    private boolean hasMainTabs;
    private MainTabsActivityController mainTabsActivityController;

    public void setMainTabsActivityController(MainTabsActivityController controller) {
        this.mainTabsActivityController = controller;
    }
    private WindowInsetsCompat lastWindowInsets;
    private final Runnable loadNewPosts;
    private boolean resumedOnce;
    private boolean uiActiveHeld;
    private boolean uiResumedHeld;
    private boolean viewportFullyVisible;

    @Override // org.telegram.ui.ActionBar.BaseFragment
    public boolean drawEdgeNavigationBar() {
        return false;
    }

    @Override // org.telegram.ui.ActionBar.BaseFragment
    public boolean isSupportEdgeToEdge() {
        return true;
    }

    public FeedActivity() {
        this(null);
    }

    public FeedActivity(Bundle bundle) {
        super(bundle);
        this.loadNewPosts = new Runnable() { // from class: org.telegram.messenger.feed.ui.FeedActivity$$ExternalSyntheticLambda1
            @Override // java.lang.Runnable
            public final void run() {
                FeedActivity.this.lambda$new$0();
            }
        };
    }


    public /* synthetic */ void lambda$new$0() {
        ChatActivity chatActivity;
        ChatActivityContainer chatActivityContainer = this.chatContainer;
        if (chatActivityContainer == null || (chatActivity = chatActivityContainer.chatActivity) == null || !this.uiResumedHeld) {
            return;
        }
        chatActivity.loadNewerFeed(true);
    }

    @Override // org.telegram.ui.ActionBar.BaseFragment
    public boolean onFragmentCreate() {
        Bundle bundle = this.arguments;
        boolean z = false;
        if (bundle != null && bundle.getBoolean("hasMainTabs", false)) {
            z = true;
        }
        this.hasMainTabs = z;
        this.viewportFullyVisible = !z;
        NotificationCenter.getInstance(this.currentAccount).addObserver(this, NotificationCenter.didReceiveNewMessages);
        NotificationCenter.getInstance(this.currentAccount).addObserver(this, NotificationCenter.feedNeedReload);
        NotificationCenter.getInstance(this.currentAccount).addObserver(this, NotificationCenter.feedChannelsChanged);
        ApplicationLoader.applicationContext.getSharedPreferences("nekoconfig", Context.MODE_PRIVATE)
                .registerOnSharedPreferenceChangeListener(headerSettingsListener);
        return super.onFragmentCreate();
    }

    @Override // org.telegram.ui.ActionBar.BaseFragment
    public void onFragmentDestroy() {
        AndroidUtilities.cancelRunOnUIThread(this.loadNewPosts);
        destroyEmbeddedChat();
        if (this.uiResumedHeld) {
            this.uiResumedHeld = false;
            FeedController.getInstance(this.currentAccount).setUiResumed(false);
        }
        if (this.uiActiveHeld) {
            this.uiActiveHeld = false;
            FeedController.getInstance(this.currentAccount).setUiActive(false);
        }
        Bulletin.removeDelegate(this);
        NotificationCenter.getInstance(this.currentAccount).removeObserver(this, NotificationCenter.didReceiveNewMessages);
        NotificationCenter.getInstance(this.currentAccount).removeObserver(this, NotificationCenter.feedNeedReload);
        NotificationCenter.getInstance(this.currentAccount).removeObserver(this, NotificationCenter.feedChannelsChanged);
        ApplicationLoader.applicationContext.getSharedPreferences("nekoconfig", Context.MODE_PRIVATE)
                .unregisterOnSharedPreferenceChangeListener(headerSettingsListener);
        super.onFragmentDestroy();
    }

    private void destroyEmbeddedChat() {
        ChatActivity chatActivity;
        ChatActivityContainer chatActivityContainer = this.chatContainer;
        if (chatActivityContainer != null && (chatActivity = chatActivityContainer.chatActivity) != null) {
            if (initialPositionListener != null && chatActivityContainer.getViewTreeObserver().isAlive()) {
                chatActivityContainer.getViewTreeObserver().removeOnPreDrawListener(initialPositionListener);
                initialPositionListener = null;
            }
            if (this.embeddedChatCreated) {
                chatActivity.saveFeedScrollPosition();
            }
            this.chatContainer.chatActivity.setFeedChannelsChangedCallback(null);
            this.chatContainer.chatActivity.setFeedContentChangedCallback(null);
            if (this.embeddedChatCreated) {
                this.chatContainer.chatActivity.onFragmentDestroy();
            }
        }
        this.embeddedChatCreated = false;
        this.chatContainer = null;
        this.tabsBackgroundSource = null;
    }

    @Override // org.telegram.ui.ActionBar.BaseFragment
    public boolean onBackPressed(boolean z) {
        ChatActivity chatActivity;
        ChatActivityContainer chatActivityContainer = this.chatContainer;
        if (chatActivityContainer == null || (chatActivity = chatActivityContainer.chatActivity) == null || chatActivity.getActionBar() == null || !this.chatContainer.chatActivity.getActionBar().isActionModeShowed()) {
            return super.onBackPressed(z);
        }
        if (!z) {
            return false;
        }
        this.chatContainer.chatActivity.clearSelectionMode();
        return false;
    }

    @Override // org.telegram.messenger.NotificationCenter.NotificationCenterDelegate
    public void didReceivedNotification(int i, int i2, Object... objArr) {
        boolean z = false;
        if (i == NotificationCenter.didReceiveNewMessages) {
            if (((Boolean) objArr[2]).booleanValue() || this.chatContainer == null || !FeedController.getInstance(this.currentAccount).isIncludedChannelPost(((Long) objArr[0]).longValue())) {
                return;
            }
            AndroidUtilities.cancelRunOnUIThread(this.loadNewPosts);
            AndroidUtilities.runOnUIThread(this.loadNewPosts, 1000L);
            return;
        }
        if (i == NotificationCenter.feedChannelsChanged) {
            setFeedSubtitle(FeedController.getInstance(currentAccount).getIncludedChannelCount());
            return;
        }
        if (i == NotificationCenter.feedNeedReload) {
            ChatActivityContainer chatActivityContainer = this.chatContainer;
            if (chatActivityContainer != null && chatActivityContainer.chatActivity != null) {
                if (objArr.length > 0 && Boolean.TRUE.equals(objArr[0])) {
                    z = true;
                }
                this.chatContainer.chatActivity.onFeedChannelsChanged(z);
            }
            updateFeedSubtitle();
        }
    }

    @Override // org.telegram.ui.ActionBar.BaseFragment
    public View createView(Context context) {
        destroyEmbeddedChat();
        this.lastWindowInsets = null;
        this.actionBar.setAddToContainer(false);
        this.actionBar.setVisibility(8);
        FrameLayout frameLayout = new FrameLayout(context) {
            @Override
            protected void onDraw(Canvas canvas) {
                super.onDraw(canvas);
                if (chatContainer != null && chatContainer.getAlpha() == 0f) {
                    chatContainer.chatActivity.drawFeedWallpaper(canvas, getWidth(), getHeight());
                }
            }
        };
        frameLayout.setWillNotDraw(false);
        this.fragmentView = frameLayout;
        frameLayout.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
        if (this.hasMainTabs) {
            ViewCompat.setOnApplyWindowInsetsListener(frameLayout, new OnApplyWindowInsetsListener() { // from class: org.telegram.messenger.feed.ui.FeedActivity$$ExternalSyntheticLambda2
                @Override // androidx.core.view.OnApplyWindowInsetsListener
                public final WindowInsetsCompat onApplyWindowInsets(View view, WindowInsetsCompat windowInsetsCompat) {
                    return FeedActivity.this.lambda$createView$1(view, windowInsetsCompat);
                }
            });
            frameLayout.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() { // from class: org.telegram.messenger.feed.ui.FeedActivity.1
                @Override // android.view.View.OnAttachStateChangeListener
                public void onViewDetachedFromWindow(View view) {
                }

                @Override // android.view.View.OnAttachStateChangeListener
                public void onViewAttachedToWindow(View view) {
                    if (FeedActivity.this.lastWindowInsets != null) {
                        ViewCompat.dispatchApplyWindowInsets(view, FeedActivity.this.lastWindowInsets);
                    } else {
                        view.requestApplyInsets();
                    }
                }
            });
        }
        FrameLayout frameLayout2 = new FrameLayout(context);
        frameLayout.addView(frameLayout2, LayoutHelper.createFrame(-1, -1, 119));
        Bundle bundle = new Bundle();
        bundle.putInt("chatMode", 7);
        bundle.putInt("searchType", 4);
        bundle.putBoolean("hasMainTabs", this.hasMainTabs);
        ChatActivityContainer chatActivityContainer = new ChatActivityContainer(context, getParentLayout(), bundle) { // from class: org.telegram.messenger.feed.ui.FeedActivity.2
            boolean activityCreated = false;

            @Override // org.telegram.ui.ChatActivityContainer
            public void initChatActivity() {
                FeedActivity feedActivity;
                View view;
                if (this.activityCreated) {
                    return;
                }
                this.activityCreated = true;
                FeedActivity.this.embeddedChatCreated = true;
                super.initChatActivity();
                FeedActivity.this.applyFloatingWindowLayout();
                FeedActivity.this.setupChatActionBar();
                FeedActivity.this.setupChatTitle();
                if (FeedActivity.this.lastWindowInsets != null && (view = (feedActivity = FeedActivity.this).fragmentView) != null) {
                    ViewCompat.dispatchApplyWindowInsets(view, feedActivity.lastWindowInsets);
                }
                            }
        };
        this.chatContainer = chatActivityContainer;
        ChatActivity chatActivity = chatActivityContainer.chatActivity;
        chatActivity.setFeedContentChangedCallback(this::invalidateTabsBackground);
        if (FeedController.getInstance(currentAccount).getDrawerScrollPosition() != null) {
            // Keep the initial default position out of the first displayed frame.
            chatActivityContainer.setAlpha(0f);
            ImageView initialProgress = new ImageView(context);
            initialProgress.setImageDrawable(new CircularProgressDrawable(
                    AndroidUtilities.dp(40), AndroidUtilities.dp(2.25f), getThemedColor(Theme.key_progressCircle)));
            frameLayout2.addView(initialProgress, LayoutHelper.createFrame(48, 48, android.view.Gravity.CENTER));
            initialPositionListener = new ViewTreeObserver.OnPreDrawListener() {
                @Override
                public boolean onPreDraw() {
                    if (chatActivity.isFeedInitialPositionReady() || FeedController.getInstance(currentAccount).getStore().isEndReached()
                            && FeedController.getInstance(currentAccount).getStore().getVisibleCount() == 0) {
                        chatActivityContainer.getViewTreeObserver().removeOnPreDrawListener(this);
                        initialPositionListener = null;
                        chatActivityContainer.setAlpha(1f);
                        frameLayout2.removeView(initialProgress);
                        invalidateTabsBackground();
                    }
                    return true;
                }
            };
            chatActivityContainer.getViewTreeObserver().addOnPreDrawListener(initialPositionListener);
        }
        chatActivity.isInsideContainer = false;
        chatActivity.setFeedChannelsChangedCallback(new Runnable() { // from class: org.telegram.messenger.feed.ui.FeedActivity$$ExternalSyntheticLambda3
            @Override // java.lang.Runnable
            public final void run() {
                FeedActivity.this.updateFeedSubtitle();
            }
        });
        updateFeedViewportActive(this.viewportFullyVisible);
        if (!this.uiResumedHeld) {
            this.chatContainer.onPause();
        }
        frameLayout2.addView(this.chatContainer, LayoutHelper.createFrame(-1, -1, 119));
        if (!this.uiActiveHeld) {
            this.uiActiveHeld = true;
            FeedController.getInstance(this.currentAccount).setUiActive(true);
        }
        Bulletin.addDelegate(this, new Bulletin.Delegate() { // from class: org.telegram.messenger.feed.ui.FeedActivity.3
            @Override // org.telegram.ui.Components.Bulletin.Delegate
            public int getTopOffset(int i) {
                if (FeedActivity.this.chatContainer != null && FeedActivity.this.chatContainer.chatActivity != null) {
                    return AndroidUtilities.statusBarHeight + ActionBar.getCurrentActionBarHeight();
                }
                return AndroidUtilities.statusBarHeight + ActionBar.getCurrentActionBarHeight();
            }

            @Override // org.telegram.ui.Components.Bulletin.Delegate
            public int getBottomOffset(int i) {
                if (FeedActivity.this.chatContainer == null || FeedActivity.this.chatContainer.chatActivity == null) {
                    return 0;
                }
                return 0;
            }
        });
        return this.fragmentView;
    }

    public /* synthetic */ WindowInsetsCompat lambda$createView$1(View view, WindowInsetsCompat windowInsetsCompat) {
        this.lastWindowInsets = windowInsetsCompat;
        int iDp = hasMainTabs ? AndroidUtilities.dp(zxc.iconic.xenon.helpers.MainTabsUiHelper.getTabsViewHeightDp()) : 0;
        if (iDp == 0) {
            return windowInsetsCompat;
        }
        Insets insets = windowInsetsCompat.getInsets(WindowInsetsCompat.Type.systemBars());
        Insets insets2 = windowInsetsCompat.getInsets(WindowInsetsCompat.Type.navigationBars());
        Insets stableNavigationInsets = windowInsetsCompat.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.navigationBars());
        // The chat's animated bottom inset reads ignoring-visibility values.
        // Update those as well so both the list and page-down button clear tabs.
        return new WindowInsetsCompat.Builder(windowInsetsCompat)
                .setInsets(WindowInsetsCompat.Type.systemBars(), Insets.of(insets.left, insets.top, insets.right, insets.bottom + iDp))
                .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(insets2.left, insets2.top, insets2.right, insets2.bottom + iDp))
                .setInsetsIgnoringVisibility(WindowInsetsCompat.Type.navigationBars(), Insets.of(stableNavigationInsets.left, stableNavigationInsets.top, stableNavigationInsets.right, stableNavigationInsets.bottom + iDp))
                .build();
    }

    @Override // org.telegram.ui.ActionBar.BaseFragment
    public void onResume() {
        ChatActivityContainer chatActivityContainer;
        ChatActivity chatActivity;
        ChatActivity chatActivity2;
        View view;
        WindowInsetsCompat windowInsetsCompat;
        super.onResume();
        ChatActivityContainer chatActivityContainer2 = this.chatContainer;
        if (chatActivityContainer2 != null) {
            chatActivityContainer2.onResume();
            refreshFeedHeader();
            updateFeedViewportActive(this.viewportFullyVisible);
        }
        if (!this.uiResumedHeld) {
            this.uiResumedHeld = true;
            FeedController.getInstance(this.currentAccount).setUiResumed(true);
        }
        if (this.hasMainTabs && (view = this.fragmentView) != null && (windowInsetsCompat = this.lastWindowInsets) != null) {
            ViewCompat.dispatchApplyWindowInsets(view, windowInsetsCompat);
        }
        reattachCurrentFeedVideoTexture();
        if (this.resumedOnce && (chatActivityContainer = this.chatContainer) != null && (chatActivity = chatActivityContainer.chatActivity) != null) {
            chatActivity.reconcileFeedList();
            this.chatContainer.chatActivity.refreshFeedUnreadDivider();
            if (!FeedController.getInstance(this.currentAccount).getMessages().isEmpty()) {
                this.chatContainer.chatActivity.loadNewerFeed(true);
            }
        }
        this.resumedOnce = true;
        updateFeedSubtitle();
    }

    @Override // org.telegram.ui.ActionBar.BaseFragment
    public void onBecomeFullyVisible() {
        super.onBecomeFullyVisible();
        this.viewportFullyVisible = true;
        updateFeedViewportActive(true);
        reattachCurrentFeedVideoTexture();
    }

    @Override // org.telegram.ui.ActionBar.BaseFragment
    public void onBecomeFullyHidden() {
        this.viewportFullyVisible = false;
        updateFeedViewportActive(false);
        super.onBecomeFullyHidden();
    }

    @Override // org.telegram.ui.ActionBar.BaseFragment
    public void onTransitionAnimationStart(boolean z, boolean z2) {
        if (this.hasMainTabs) {
            this.viewportFullyVisible = false;
            updateFeedViewportActive(false);
        }
        super.onTransitionAnimationStart(z, z2);
    }

    @Override // org.telegram.ui.ActionBar.BaseFragment
    public void onTransitionAnimationEnd(boolean z, boolean z2) {
        super.onTransitionAnimationEnd(z, z2);
        if (this.hasMainTabs) {
            this.viewportFullyVisible = z;
            updateFeedViewportActive(z);
        }
    }


    @Override // org.telegram.ui.ActionBar.BaseFragment
    public void onPause() {
        super.onPause();
        if (this.chatContainer != null) {
            updateFeedViewportActive(false);
            this.chatContainer.onPause();
        }
        if (this.uiResumedHeld) {
            this.uiResumedHeld = false;
            FeedController.getInstance(this.currentAccount).setUiResumed(false);
        }
    }

    private void updateFeedViewportActive(boolean z) {
        ChatActivity chatActivity;
        ChatActivityContainer chatActivityContainer = this.chatContainer;
        if (chatActivityContainer == null || (chatActivity = chatActivityContainer.chatActivity) == null) {
            return;
        }
        chatActivity.setFeedViewportActive(z);
    }


    @Override // org.telegram.ui.ActionBar.BaseFragment
    public boolean isLightStatusBar() {
        ChatActivity chatActivity;
        ChatActivityContainer chatActivityContainer = this.chatContainer;
        if (chatActivityContainer != null && (chatActivity = chatActivityContainer.chatActivity) != null) {
            return chatActivity.isLightStatusBar();
        }
        return !Theme.isCurrentThemeDark();
    }

    private void reattachCurrentFeedVideoTexture() {
        ChatActivity chatActivity;
        ChatActivityContainer chatActivityContainer = this.chatContainer;
        if (chatActivityContainer == null || (chatActivity = chatActivityContainer.chatActivity) == null) {
            return;
        }
        chatActivity.reattachCurrentFeedVideoTexture();
    }

    public void setupChatActionBar() {
        ChatActivity chatActivity;
        final ActionBar actionBar;
        ChatActivityContainer chatActivityContainer = this.chatContainer;
        if (chatActivityContainer == null || (chatActivity = chatActivityContainer.chatActivity) == null || (actionBar = chatActivity.getActionBar()) == null) {
            return;
        }
        ActionBarMenu actionBarMenuCreateMenu = actionBar.createMenu();
        if (actionBarMenuCreateMenu.getItem(76) == null) {
            actionBarMenuCreateMenu.addItem(76, R.drawable.msg_markread, this.chatContainer.chatActivity.themeDelegate).setContentDescription(LocaleController.getString(R.string.FeedMarkAllRead));
        }
        if (actionBarMenuCreateMenu.getItem(77) == null) {
            actionBarMenuCreateMenu.addItem(77, R.drawable.msg_settings, this.chatContainer.chatActivity.themeDelegate)
                    .setContentDescription(LocaleController.getString(R.string.Settings));
        }
        refreshFeedHeader();
        if (this.hasMainTabs) {
            applyMainTabsHeaderLayout();
        }
        final ActionBar.ActionBarMenuOnItemClick actionBarMenuOnItemClick = actionBar.getActionBarMenuOnItemClick();
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() { // from class: org.telegram.messenger.feed.ui.FeedActivity.4
            @Override // org.telegram.ui.ActionBar.ActionBar.ActionBarMenuOnItemClick
            public void onItemClick(int i) {
                if (i == -1) {
                    if (FeedActivity.this.hasMainTabs) {
                        if (actionBar.isActionModeShowed()) {
                            actionBar.hideActionMode();
                            return;
                        }
                        // Switch back to the first (Chats) tab
                        if (getParentLayout() != null) {
                            getParentLayout().onBackPressed();
                        }
                        return;
                    }
                    FeedActivity.this.finishFragment();
                    return;
                }
                if (i == 77 || i == 78) {
                    FeedActivity.this.presentFragment(new FeedSettingsActivity());
                    return;
                }
                if (i == 76) {
                    FeedActivity.this.showMarkAllReadDialog();
                    return;
                }
                ActionBar.ActionBarMenuOnItemClick actionBarMenuOnItemClick2 = actionBarMenuOnItemClick;
                if (actionBarMenuOnItemClick2 != null) {
                    actionBarMenuOnItemClick2.onItemClick(i);
                }
            }

            @Override // org.telegram.ui.ActionBar.ActionBar.ActionBarMenuOnItemClick
            public boolean canOpenMenu() {
                ActionBar.ActionBarMenuOnItemClick actionBarMenuOnItemClick2 = actionBarMenuOnItemClick;
                return actionBarMenuOnItemClick2 == null || actionBarMenuOnItemClick2.canOpenMenu();
            }
        });
    }

    public void applyFloatingWindowLayout() {
        ChatActivityContainer chatActivityContainer;
        ChatActivity chatActivity;
        if (getParentLayout() == null || !getParentLayout().isLayersLayout() || (chatActivityContainer = this.chatContainer) == null || (chatActivity = chatActivityContainer.chatActivity) == null) {
            return;
        }
        if (chatActivity.getActionBar() != null) {
            chatActivity.getActionBar().setOccupyStatusBar(false);
        }
        ChatAvatarContainer chatAvatarContainer = chatActivity.avatarContainer;
        if (chatAvatarContainer != null) {
            chatAvatarContainer.setOccupyStatusBar(false);
        }
        ChatActivity.ChatActivityFragmentView chatActivityFragmentView = chatActivity.contentView;
        if (chatActivityFragmentView != null) {
            chatActivityFragmentView.setOccupyStatusBar(false);
        }
    }

    public void applyMainTabsHeaderLayout() {
        ChatActivity chatActivity;
        ChatAvatarContainer chatAvatarContainer;
        ChatActivityContainer chatActivityContainer = this.chatContainer;
        if (chatActivityContainer == null || (chatActivity = chatActivityContainer.chatActivity) == null || (chatAvatarContainer = chatActivity.avatarContainer) == null) {
            return;
        }
        ViewGroup.LayoutParams layoutParams = chatAvatarContainer.getLayoutParams();
        if (layoutParams instanceof ViewGroup.MarginLayoutParams
                && !chatActivity.getActionBar().centerChatHeader && !chatActivity.getActionBar().textOnlyPill) {
            ViewGroup.MarginLayoutParams marginLayoutParams = (ViewGroup.MarginLayoutParams) layoutParams;
            int iDp = 0;
            if (marginLayoutParams.leftMargin != iDp) {
                marginLayoutParams.leftMargin = iDp;
                this.chatContainer.chatActivity.avatarContainer.setLayoutParams(marginLayoutParams);
                chatActivity.getActionBar().checkAvatarContainerWidth(false);
            }
        }
    }

    public void showMarkAllReadDialog() {
        if (getParentActivity() == null) {
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity(), getResourceProvider());
        builder.setTitle(LocaleController.getString(R.string.FeedMarkAllRead));
        builder.setMessage(LocaleController.getString(R.string.FeedMarkAllReadConfirm));
        builder.setPositiveButton(LocaleController.getString(R.string.MarkAsRead), new AlertDialog.OnButtonClickListener() { // from class: org.telegram.messenger.feed.ui.FeedActivity$$ExternalSyntheticLambda5
            @Override // org.telegram.ui.ActionBar.AlertDialog.OnButtonClickListener
            public final void onClick(AlertDialog alertDialog, int i) {
                FeedActivity.this.lambda$showMarkAllReadDialog$2(alertDialog, i);
            }
        });
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        showDialog(builder.create());
    }

    public /* synthetic */ void lambda$showMarkAllReadDialog$2(AlertDialog alertDialog, int i) {
        markAllRead();
        BulletinFactory.of(this).createSimpleBulletin(R.raw.contact_check, LocaleController.getString(R.string.FeedMarkAllReadDone)).show();
    }

    public void markAllRead() {
        ChatActivity chatActivity;
        ChatActivityContainer chatActivityContainer = this.chatContainer;
        if (chatActivityContainer != null && (chatActivity = chatActivityContainer.chatActivity) != null) {
            chatActivity.markFeedAsRead();
        } else {
            FeedController.getInstance(this.currentAccount).markAllRead();
        }
    }


    public void setupChatTitle() {
        ChatActivity chatActivity;
        ChatAvatarContainer chatAvatarContainer;
        ChatActivityContainer chatActivityContainer = this.chatContainer;
        if (chatActivityContainer == null || (chatActivity = chatActivityContainer.chatActivity) == null || (chatAvatarContainer = chatActivity.avatarContainer) == null) {
            return;
        }
        chatAvatarContainer.setTitle(LocaleController.getString(R.string.Feed));
        this.chatContainer.chatActivity.avatarContainer.setFeedAvatar();
        updateFeedSubtitle();
    }

    public void updateFeedSubtitle() {
        FeedController feedController = FeedController.getInstance(this.currentAccount);
        setFeedSubtitle(feedController.getIncludedChannelCount());
        feedController.loadChannels(new FeedController.ChannelsCallback() { // from class: org.telegram.messenger.feed.ui.FeedActivity$$ExternalSyntheticLambda0
            @Override // org.telegram.messenger.feed.FeedController.ChannelsCallback
            public final void onChannels(ArrayList arrayList, int i, boolean z) {
                FeedActivity.this.lambda$updateFeedSubtitle$3(arrayList, i, z);
            }
        });
    }

    public /* synthetic */ void lambda$updateFeedSubtitle$3(ArrayList arrayList, int i, boolean z) {
        if (z) {
            return;
        }
        setFeedSubtitle(i);
    }

    private void setFeedSubtitle(int i) {
        ChatActivity chatActivity;
        ChatAvatarContainer chatAvatarContainer;
        ChatActivityContainer chatActivityContainer = this.chatContainer;
        if (chatActivityContainer == null || (chatActivity = chatActivityContainer.chatActivity) == null || (chatAvatarContainer = chatActivity.avatarContainer) == null) {
            return;
        }
        chatAvatarContainer.setSubtitle(LocaleController.formatPluralString("Channels", i, new Object[0]));
        View subtitleTextView = this.chatContainer.chatActivity.avatarContainer.getSubtitleTextView();
        if (subtitleTextView != null) {
            subtitleTextView.setVisibility(0);
        }
        chatActivity.getActionBar().checkAvatarContainerWidth(false);
    }



}
