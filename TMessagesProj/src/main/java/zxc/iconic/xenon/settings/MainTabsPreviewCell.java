package zxc.iconic.xenon.settings;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import android.content.res.Resources;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.UserConfig;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.glass.GlassTabView;
import org.telegram.ui.MainTabsManager;

import java.util.Collections;
import java.util.List;

public class MainTabsPreviewCell extends FrameLayout {

    private final RecyclerListView listView;
    private final TabsAdapter adapter;
    private List<MainTabsManager.Tab> tabs;
    private boolean editMode;
    private boolean showTitle;
    private Theme.ResourcesProvider resourcesProvider;
    private int currentAccount;
    private Runnable onChanged;

    public MainTabsPreviewCell(Context context) {
        super(context);
        
        listView = new RecyclerListView(context);
        LinearLayoutManager layoutManager = new LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false);
        listView.setLayoutManager(layoutManager);
        adapter = new TabsAdapter(context);
        listView.setAdapter(adapter);
        
        ItemTouchHelper itemTouchHelper = new ItemTouchHelper(new ItemTouchHelper.Callback() {
            @Override
            public boolean isLongPressDragEnabled() {
                return editMode;
            }

            @Override
            public boolean isItemViewSwipeEnabled() {
                return false;
            }

            @Override
            public int getMovementFlags(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder) {
                return makeMovementFlags(ItemTouchHelper.LEFT | ItemTouchHelper.RIGHT, 0);
            }

            @Override
            public boolean onMove(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder, @NonNull RecyclerView.ViewHolder target) {
                int fromPos = viewHolder.getAdapterPosition();
                int toPos = target.getAdapterPosition();
                Collections.swap(tabs, fromPos, toPos);
                adapter.notifyItemMoved(fromPos, toPos);
                if (onChanged != null) {
                    onChanged.run();
                }
                return true;
            }

            @Override
            public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, int direction) {
            }
        });
        itemTouchHelper.attachToRecyclerView(listView);
        
        addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
    }

    public void setEditMode(boolean editMode) {
        this.editMode = editMode;
    }

    public void setOnChangedListener(Runnable onChanged) {
        this.onChanged = onChanged;
    }

    public void setTabs(List<MainTabsManager.Tab> tabs, Context context, Theme.ResourcesProvider resourcesProvider, int currentAccount, boolean showTitle) {
        this.tabs = tabs;
        this.showTitle = showTitle;
        this.resourcesProvider = resourcesProvider;
        this.currentAccount = currentAccount;
        adapter.notifyDataSetChanged();
    }

    public void setShowTitle(boolean showTitle, boolean animated) {
        if (this.showTitle == showTitle && !animated) {
            return;
        }
        this.showTitle = showTitle;
        for (int i = 0; i < listView.getChildCount(); i++) {
            View child = listView.getChildAt(i);
            if (child instanceof FrameLayout frameLayout && frameLayout.getChildCount() > 0) {
                View tabChild = frameLayout.getChildAt(0);
                if (tabChild instanceof GlassTabView tabView) {
                    tabView.setShowTitle(showTitle, animated);
                }
            }
        }
    }

    private class TabsAdapter extends RecyclerListView.SelectionAdapter {

        private final Context context;

        public TabsAdapter(Context context) {
            this.context = context;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return editMode;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            FrameLayout frameLayout = new FrameLayout(context);
            RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT);
            frameLayout.setLayoutParams(lp);
            return new RecyclerListView.Holder(frameLayout);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            FrameLayout frameLayout = (FrameLayout) holder.itemView;
            frameLayout.removeAllViews();
            
            int totalWidth = MainTabsPreviewCell.this.getMeasuredWidth();
            if (totalWidth == 0) totalWidth = Resources.getSystem().getDisplayMetrics().widthPixels - AndroidUtilities.dp(40);
            int width = totalWidth / Math.max(1, tabs.size());
            
            frameLayout.setLayoutParams(new RecyclerView.LayoutParams(width, ViewGroup.LayoutParams.MATCH_PARENT));

            MainTabsManager.Tab tab = tabs.get(position);
            GlassTabView tabView = MainTabsManager.createTabView(context, resourcesProvider, currentAccount, tab.type);
            tabView.setShowTitle(showTitle, false);
            
            // CHATS и PROFILE нельзя отключить
            boolean canDisable = tab.type != MainTabsManager.TabType.CHATS && tab.type != MainTabsManager.TabType.PROFILE;
            tabView.setAlpha(tab.enabled ? 1.0f : 0.45f);
            
            tabView.setOnClickListener(v -> {
                if (editMode && canDisable) {
                    tab.enabled = !tab.enabled;
                    tabView.setAlpha(tab.enabled ? 1.0f : 0.45f);
                    if (onChanged != null) {
                        onChanged.run();
                    }
                }
            });
            
            frameLayout.addView(tabView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.CENTER));
        }

        @Override
        public int getItemCount() {
            return tabs == null ? 0 : tabs.size();
        }
    }
}
