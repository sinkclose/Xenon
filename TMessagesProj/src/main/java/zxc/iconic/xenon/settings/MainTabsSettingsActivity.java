package zxc.iconic.xenon.settings;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.MainTabsManager;

import java.util.ArrayList;
import java.util.List;

import zxc.iconic.xenon.NekoConfig;

public class MainTabsSettingsActivity extends BaseNekoSettingsActivity {

    private final int tabsPreviewRow = rowId++;
    private final int showTabsRow = rowId++;
    private final int showTabTitleRow = rowId++;
    private final int resetOrderRow = rowId++;

    private MainTabsPreviewCell tabsView;
    private FrameLayout previewContainer;
    private ArrayList<MainTabsManager.Tab> tabs;
    private String initialTabsState;
    private boolean tabsSaved;
    private boolean hadChanges;

    @Override
    public boolean onFragmentCreate() {
        tabs = copyTabs(MainTabsManager.getAllTabs());
        initialTabsState = tabsToState(tabs);
        hadChanges = false;
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        super.onFragmentDestroy();
        if (!tabsSaved) {
            checkSaveTabs();
        }
        if (hadChanges) {
            postUpdateTabsNotification();
        }
    }

    private void ensurePreviewCreated() {
        if (previewContainer != null) {
            return;
        }
        previewContainer = new FrameLayout(getContext());
        previewContainer.setClipToPadding(false);
        previewContainer.setPadding(0, AndroidUtilities.dp(6), 0, AndroidUtilities.dp(6));
        previewContainer.setMinimumHeight(AndroidUtilities.dp(60));

        tabsView = new MainTabsPreviewCell(getContext());
        tabsView.setEditMode(true);
        tabsView.setTabs(tabs, getContext(), getResourceProvider(), currentAccount, NekoConfig.showMainTabsTitle);
        tabsView.setOnChangedListener(this::onTabsChanged);
        previewContainer.addView(tabsView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 48, Gravity.CENTER, 12, 0, 12, 0));

        previewContainer.setAlpha(NekoConfig.showMainTabs ? 1f : 0.45f);
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asHeader(LocaleController.getString(R.string.MainTabsAppearance)));

        ensurePreviewCreated();

        UItem customItem = UItem.asCustom(previewContainer);
        customItem.id = tabsPreviewRow;
        items.add(customItem);
        items.add(UItem.asShadow(LocaleController.getString(R.string.MainTabsPreviewHint)));

        items.add(UItem.asCheck(showTabsRow, LocaleController.getString(R.string.MainTabsShow))
                .setChecked(NekoConfig.showMainTabs));
        items.add(UItem.asCheck(showTabTitleRow, LocaleController.getString(R.string.MainTabsShowTitle))
                .setChecked(NekoConfig.showMainTabsTitle)
                .setEnabled(NekoConfig.showMainTabs));
        items.add(UItem.asShadow(LocaleController.getString(R.string.MainTabsShowHint)));

        items.add(UItem.asButton(resetOrderRow, R.drawable.msg_reset,
                        LocaleController.getString(R.string.MainTabsResetOrder))
                .accent().slug("mainTabsReset"));
        items.add(UItem.asShadow(LocaleController.getString(R.string.MainTabsResetOrderHint)));
    }

    @Override
    protected void onItemClick(UItem item, View view, int position, float x, float y) {
        if (!item.enabled) return;
        int id = item.id;
        if (id == showTabsRow) {
            NekoConfig.setShowMainTabs(!NekoConfig.showMainTabs);
            setChecked(view, NekoConfig.showMainTabs);
            applyPreviewVisibility(true);
            notifyItemChanged(showTabTitleRow);
            hadChanges = true;
        } else if (id == showTabTitleRow) {
            NekoConfig.setShowMainTabsTitle(!NekoConfig.showMainTabsTitle);
            setChecked(view, NekoConfig.showMainTabsTitle);
            if (tabsView != null) {
                tabsView.setShowTitle(NekoConfig.showMainTabsTitle, true);
            }
            hadChanges = true;
        } else if (id == resetOrderRow) {
            confirmResetOrder();
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

    private void applyPreviewVisibility(boolean animated) {
        if (previewContainer == null) {
            return;
        }
        float target = NekoConfig.showMainTabs ? 1f : 0.45f;
        if (!animated) {
            previewContainer.setAlpha(target);
            return;
        }
        previewContainer.animate().cancel();
        previewContainer.animate()
                .alpha(target)
                .setDuration(200)
                .setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT)
                .start();
    }

    private void onTabsChanged() {
        hadChanges = true;
    }

    private void confirmResetOrder() {
        if (getParentActivity() == null) {
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity(), resourcesProvider);
        builder.setTitle(LocaleController.getString(R.string.MainTabsResetOrder));
        builder.setMessage(LocaleController.getString(R.string.MainTabsResetOrderConfirm));
        builder.setPositiveButton(LocaleController.getString(R.string.Reset),
                (dialog, which) -> resetMainTabsOrder());
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        showDialog(builder.create());
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
            tabsView.setTabs(tabs, getContext(), getResourceProvider(), currentAccount, NekoConfig.showMainTabsTitle);
        }
        hadChanges = true;
    }

    private void postUpdateTabsNotification() {
        // MainTabsActivity (which sits below us in the stack) listens for this notification
        // and rebuilds its tab strip + ViewPager pages in onTabsConfigurationChanged().
        // No need to nuke the whole parent layout via rebuildAllFragmentViews().
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.mainTabsConfigUpdated);
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
