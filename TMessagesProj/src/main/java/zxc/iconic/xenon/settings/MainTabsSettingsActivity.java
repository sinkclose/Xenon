package zxc.iconic.xenon.settings;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.MainTabsManager;

import java.util.ArrayList;
import java.util.List;

import zxc.iconic.xenon.NekoConfig;

public class MainTabsSettingsActivity extends BaseNekoSettingsActivity {

    private final int enableTabsRow = rowId++;
    private final int tabsPreviewRow = rowId++;
    private final int openSettingsBySwipeRow = rowId++;
    private final int showTabTitleRow = rowId++;
    private MainTabsPreviewCell tabsView;
    private ArrayList<MainTabsManager.Tab> tabs;
    private String initialTabsState;
    private boolean tabsSaved;

    @Override
    public boolean onFragmentCreate() {
        tabs = copyTabs(MainTabsManager.getAllTabs());
        initialTabsState = tabsToState(tabs);
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        super.onFragmentDestroy();
        if (!tabsSaved) {
            checkSaveTabs();
        }
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asHeader(LocaleController.getString(R.string.MainTabsCustomizeTitle)));

        items.add(UItem.asCheck(enableTabsRow, "Enable Tabs")
                .setChecked(NekoConfig.showMainTabs)
                .slug("enableTabsRow"));

        if (NekoConfig.showMainTabs) {
            items.add(UItem.asShadow(null));
            items.add(UItem.asHeader("Appearance"));

            FrameLayout previewContainer = new FrameLayout(getContext());
            previewContainer.setClipToPadding(false);
            previewContainer.setPadding(0, AndroidUtilities.dp(6), 0, AndroidUtilities.dp(6));
            previewContainer.setMinimumHeight(AndroidUtilities.dp(60));

            tabsView = new MainTabsPreviewCell(getContext());
            tabsView.setEditMode(true);
            tabsView.setTabs(tabs, getContext(), getResourceProvider(), currentAccount, NekoConfig.showMainTabsTitle, true);
            tabsView.setOnChangedListener(this::postUpdateTabsNotification);
            previewContainer.addView(tabsView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 48, Gravity.CENTER, 12, 0, 12, 0));

            UItem customItem = UItem.asCustom(previewContainer);
            customItem.id = tabsPreviewRow;
            items.add(customItem);
            items.add(UItem.asShadow("Tap to enable/disable, hold and drag to reorder.")); // AP_MainTabsPreviewSum

            items.add(UItem.asCheck(showTabTitleRow, "Show tab titles")
                    .setChecked(NekoConfig.showMainTabsTitle));
            items.add(UItem.asShadow(null));

            items.add(UItem.asHeader("Actions"));
            items.add(UItem.asCheck(openSettingsBySwipeRow, "Open settings by swipe") // AP_OpenSettingsBySwipe
                    .setChecked(NekoConfig.openSettingsBySwipe));
            items.add(UItem.asShadow(null));
        }
    }

    @Override
    protected void onItemClick(UItem item, View view, int position, float x, float y) {
        int id = item.id;
        if (id == enableTabsRow) {
            NekoConfig.setShowMainTabs(!NekoConfig.showMainTabs);
            setChecked(view, NekoConfig.showMainTabs);
            if (!NekoConfig.showMainTabs) {
                resetMainTabsOrder();
            }
            updateRows();
            postUpdateTabsNotification();
        } else if (id == showTabTitleRow) {
            NekoConfig.setShowMainTabsTitle(!NekoConfig.showMainTabsTitle);
            setChecked(view, NekoConfig.showMainTabsTitle);
            if (tabsView != null) {
                tabsView.setTabs(tabs, getContext(), getResourceProvider(), currentAccount, NekoConfig.showMainTabsTitle, true);
            }
            postUpdateTabsNotification();
        } else if (id == openSettingsBySwipeRow) {
            NekoConfig.setOpenSettingsBySwipe(!NekoConfig.openSettingsBySwipe);
            setChecked(view, NekoConfig.openSettingsBySwipe);
        }
    }

    @Override
    protected boolean onItemLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }

    @Override
    protected String getActionBarTitle() {
        return LocaleController.getString(R.string.MainTabsCustomizeTitle);
    }

    @Override
    protected String getKey() {
        return "mainTabs";
    }

    @Override
    public boolean onBackPressed(boolean invoked) {
        checkSaveTabs();
        return super.onBackPressed(invoked);
    }

    private void setChecked(View view, boolean checked) {
        if (view instanceof TextCheckCell textCheckCell) {
            textCheckCell.setChecked(checked);
        }
    }

    private void checkSaveTabs() {
        if (tabs == null) {
            return;
        }
        tabsSaved = true;
        String currentState = tabsToState(tabs);
        MainTabsManager.saveTabs(tabs);
        if (!currentState.equals(initialTabsState)) {
            initialTabsState = currentState;
            postUpdateTabsNotification();
        }
    }

    private void resetMainTabsOrder() {
        MainTabsManager.resetTabs();
        tabs.clear();
        tabs.addAll(copyTabs(MainTabsManager.getAllTabs()));
        initialTabsState = tabsToState(tabs);
        if (tabsView != null) {
            tabsView.setTabs(tabs, getContext(), getResourceProvider(), currentAccount, NekoConfig.showMainTabsTitle, true);
        }
    }

    private void postUpdateTabsNotification() {
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.mainTabsConfigUpdated);
        if (getParentLayout() != null) {
            getParentLayout().rebuildAllFragmentViews(false, false);
        }
    }

    private static String tabsToState(List<MainTabsManager.Tab> tabs) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < tabs.size(); i++) {
            MainTabsManager.Tab tab = tabs.get(i);
            if (i > 0) {
                builder.append(',');
            }
            if (!tab.enabled) {
                builder.append('!');
            }
            builder.append(tab.type.name());
        }
        return builder.toString();
    }

    private static ArrayList<MainTabsManager.Tab> copyTabs(List<MainTabsManager.Tab> source) {
        ArrayList<MainTabsManager.Tab> result = new ArrayList<>(source.size());
        for (MainTabsManager.Tab tab : source) {
            result.add(new MainTabsManager.Tab(tab.type, tab.enabled));
        }
        return result;
    }
}
