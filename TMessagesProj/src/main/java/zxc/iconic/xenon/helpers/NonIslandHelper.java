package zxc.iconic.xenon.helpers;

import android.graphics.Canvas;
import android.widget.FrameLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.INavigationLayout;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.ChatActivityTopPanelLayout;
import org.telegram.ui.Components.FilterTabsView;
import org.telegram.ui.Components.FragmentSearchField;
import org.telegram.ui.Components.MentionsContainerView;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.SizeNotifierFrameLayout;
import org.telegram.ui.DialogsActivity;
import org.telegram.ui.SearchTabsAndFiltersLayout;

import zxc.iconic.xenon.NekoConfig;
import zxc.iconic.xenon.helpers.BlurBehindHelper;

public class NonIslandHelper {
    public static boolean tabBars() {
        return NekoConfig.nonIslandTabBars;
    }

    public static boolean globalSearch() {
        return NekoConfig.nonIslandGlobalSearch;
    }

    public static boolean chatElements() {
        return NekoConfig.nonIslandChatElements;
    }

    public static boolean hideFadeView() {
        return NekoConfig.hideFadeView;
    }

    public static boolean disableGlassGlare() {
        return NekoConfig.disableGlassGlare;
    }

    public static boolean disableScrimBlur() {
        return NekoConfig.disableScrimBlur;
    }

    public static Boolean needChatLightNavBar(
        float inputBubbleHeight,
        Theme.ResourcesProvider resourcesProvider
    ) {
        if (!chatElements()) return null;
        if (inputBubbleHeight <= 0f) return null;
        int color = Theme.getColor(Theme.key_chat_messagePanelBackground, resourcesProvider);
        return AndroidUtilities.computePerceivedBrightness(color) <= 0.9f;
    }

    public static final float ATTACH_TAB_SHADOW_DP = 3f;

    public static void applyChatAttachTabBar(
        FrameLayout wrapper,
        RecyclerListView recyclerView
    ) {
        if (!tabBars()) return;
        wrapper.setBackground(null);
        wrapper.setClipChildren(false);
        recyclerView.setClipToOutline(false);
        recyclerView.setOutlineProvider(null);
        int innerPaddingTop = AndroidUtilities.dp(4f);
        int innerPadding = AndroidUtilities.dp(6f);
        recyclerView.setPadding(innerPadding, innerPaddingTop, innerPadding, AndroidUtilities.navigationBarHeight);
        recyclerView.setClipToPadding(false);
        FrameLayout.LayoutParams recyclerLp = (FrameLayout.LayoutParams) recyclerView.getLayoutParams();
        if (recyclerLp == null) return;
        int shadowH = AndroidUtilities.dp(ATTACH_TAB_SHADOW_DP);
        recyclerLp.topMargin = shadowH;
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) wrapper.getLayoutParams();
        if (lp == null) return;
        lp.height = shadowH + AndroidUtilities.dp(48f) + innerPaddingTop + AndroidUtilities.navigationBarHeight;
        lp.bottomMargin = -AndroidUtilities.navigationBarHeight;
    }

    public static void applyFilterTabBar(FilterTabsView tabsView, SizeNotifierFrameLayout contentView) {
        if (!tabBars()) return;
        tabsView.setBlurredBackground(null);
        tabsView.setBackground(null);
        tabsView.inu_blurHelper = new BlurBehindHelper(tabsView, contentView, Theme.key_windowBackgroundWhite);
        tabsView.setPadding(0, 0, 0, 0);
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) tabsView.getLayoutParams();
        if (lp == null) return;
        lp.height = AndroidUtilities.dp(36f);
        lp.leftMargin = 0;
        lp.rightMargin = 0;
    }

    public static void applyGlobalSearchBar(FragmentSearchField field, SizeNotifierFrameLayout contentView) {
        if (!globalSearch()) return;
        field.setupBlurredBackground(null);
        field.inu_blurHelper = new BlurBehindHelper(field, contentView, Theme.key_windowBackgroundWhite);
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) field.getLayoutParams();
        if (lp != null) {
            lp.leftMargin = 0;
            lp.rightMargin = 0;
        }
        updateGlobalSearchBarInsets(field);
    }

    public static void updateGlobalSearchBarInsets(FragmentSearchField field) {
        if (!globalSearch()) return;
        int extraTopPadding = AndroidUtilities.statusBarHeight + AndroidUtilities.dp(8f);
        field.setPadding(0, extraTopPadding, 0, 0);
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) field.getLayoutParams();
        if (lp == null) return;
        int height = AndroidUtilities.dp(DialogsActivity.SEARCH_FIELD_HEIGHT) + extraTopPadding;
        if (lp.height != height) {
            lp.height = height;
            field.requestLayout();
        }
    }

    public static void applyGlobalSearchTabs(SearchTabsAndFiltersLayout layout, SizeNotifierFrameLayout contentView) {
        if (!globalSearch()) return;
        layout.setBlurredBackground(null);
        layout.setBackground(null);
        layout.inu_blurHelper = new BlurBehindHelper(layout, contentView, Theme.key_windowBackgroundWhite);
        layout.setPadding(0, 0, 0, 0);
        layout.setTranslationY(-AndroidUtilities.dp(4f));
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) layout.getLayoutParams();
        if (lp == null) return;
        lp.height = AndroidUtilities.dp(36f);
        lp.leftMargin = 0;
        lp.rightMargin = 0;
    }

    public static void applyChatTopPanelButton(TextView view) {
        if (!chatElements()) return;
        view.setStateListAnimator(null);
        view.setBackground(Theme.createSelectorDrawable(
            Theme.multAlpha(view.getCurrentTextColor(), 0.10f), Theme.RIPPLE_MASK_ALL
        ));
    }

    public static void drawChatHeaderShadow(
        INavigationLayout parentLayout,
        Canvas canvas,
        ChatActivityTopPanelLayout topPanelLayout,
        MentionsContainerView mentionContainer,
        float topicsTabsHeight,
        int actionBarBottom
    ) {
        if (!chatElements()) {
            return;
        }

        boolean hasPanel = topPanelLayout != null && actionBarBottom > 0;
        int panelH = hasPanel ? (int) topPanelLayout.getAnimatedHeightWithPadding(0f) : 0;
        if (!(mentionContainer != null && mentionContainer.getVisibility() == android.view.View.VISIBLE)) {
            parentLayout.drawHeaderShadow(canvas, actionBarBottom + (int) topicsTabsHeight + panelH);
        }
    }
}
