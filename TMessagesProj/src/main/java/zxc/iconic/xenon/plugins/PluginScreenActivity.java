package zxc.iconic.xenon.plugins;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.lib.jse.CoerceJavaToLua;
import org.luaj.vm2.lib.jse.CoerceLuaToJava;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.LaunchActivity;
import java.util.concurrent.CopyOnWriteArrayList;

/** A normal Telegram fragment whose content and lifecycle belong to a Lua plugin. */
public class PluginScreenActivity extends BaseFragment {
    private static final CopyOnWriteArrayList<PluginScreenActivity> screens = new CopyOnWriteArrayList<>();
    private final String owner;
    private final LuaTable options;
    private boolean failed;
    private final java.util.List<RecyclerView> lists = new java.util.ArrayList<>();

    public PluginScreenActivity(String owner, LuaTable options) {
        this.owner = owner;
        this.options = options;
        setCurrentAccount(options.get("account").optint(org.telegram.messenger.UserConfig.selectedAccount));
    }

    public static void open(String owner, LuaTable options) {
        final PluginLuaRuntime generation = PluginManager.getRuntime(owner);
        AndroidUtilities.runOnUIThread(() -> {
            if (!PluginManager.isRuntimeCurrent(owner, generation) || !PluginManager.hasScope(owner, PluginManager.SCOPE_UI)
                    || !PluginManager.hasScope(owner, PluginManager.SCOPE_JAVA)) return;
            BaseFragment parent = LaunchActivity.getSafeLastFragment();
            if (parent != null) parent.presentFragment(new PluginScreenActivity(owner, options));
        });
    }

    public static void closeAll() {
        AndroidUtilities.runOnUIThread(() -> { for (PluginScreenActivity screen : screens) screen.removeSelfFromStack(true); });
    }

    public static void closeForPlugin(String owner) {
        AndroidUtilities.runOnUIThread(() -> {
            for (PluginScreenActivity screen : screens) {
                if (screen.owner.equals(owner)) screen.removeSelfFromStack(true);
            }
        });
    }

    private LuaValue call(LuaTable callbacks, String name, LuaValue... args) {
        if (failed) return LuaValue.NIL;
        LuaValue callback = callbacks.get(name);
        if (!callback.isfunction()) return LuaValue.NIL;
        PluginManager.markHookStart("screen_" + name);
        try {
            return PluginManager.invokeCallback(owner, callback, LuaValue.varargsOf(args)).arg1();
        } catch (Throwable error) {
            failed = true;
            PluginManager.getInstance().quarantineFile(owner, "screen " + name, error);
            return LuaValue.NIL;
        } finally {
            PluginManager.markHookEnd();
        }
    }

    private LuaValue self() { return CoerceJavaToLua.coerce(this); }

    @Override public boolean onFragmentCreate() {
        if (!super.onFragmentCreate()) return false;
        screens.add(this);
        call(options, "onCreate", self());
        if (failed) screens.remove(this);
        return !failed;
    }

    @Override public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(options.get("title").optjstring("Plugin"));
        String subtitle = options.get("subtitle").optjstring("");
        if (!subtitle.isEmpty()) actionBar.setSubtitle(subtitle);
        LuaValue menu = options.get("menu");
        if (menu.istable()) {
            for (int i = 1; i <= menu.length(); i++) {
                LuaValue item = menu.get(i);
                if (item.istable()) actionBar.createMenu().addItem(item.get("id").optint(i),
                        PluginApi.getIconDrawable(item.get("icon").optjstring("msg_info")));
            }
        }
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override public void onItemClick(int id) {
                if (id == -1) { if (onBackPressed(true)) finishFragment(); }
                else call(options, "onMenu", self(), LuaValue.valueOf(id));
            }
        });
        LuaValue content = call(options, "createView", CoerceJavaToLua.coerce(context), self());
        if (content.isuserdata(View.class)) fragmentView = (View) CoerceLuaToJava.coerce(content, View.class);
        else {
            TextView empty = new TextView(context);
            empty.setText(failed ? "Plugin screen failed. See plugin diagnostics." : "createView must return an Android View.");
            empty.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            fragmentView = empty;
        }
        return fragmentView;
    }

    @Override public boolean isSupportEdgeToEdge() { return true; }
    @Override public boolean drawEdgeNavigationBar() { return false; }
    @Override public int getNavigationBarColor() { return Theme.getColor(Theme.key_windowBackgroundGray); }
    @Override public void onInsets(int left, int top, int right, int bottom) {
        for (RecyclerView list : lists) list.setPadding(left, 0, right, bottom);
    }

    @Override public void onResume() { super.onResume(); call(options, "onResume", self()); }
    @Override public void onPause() { call(options, "onPause", self()); super.onPause(); }
    @Override public boolean onBackPressed(boolean invoked) {
        if (!super.onBackPressed(invoked)) return false;
        // Return true from Lua to consume Back; nil/false uses normal navigation.
        return !call(options, "onBack", self(), LuaValue.valueOf(invoked)).toboolean();
    }
    @Override public void onFragmentDestroy() {
        call(options, "onDestroy", self());
        screens.remove(this);
        super.onFragmentDestroy();
    }

    /** Generic recyclable list. No archive or message logic in the client. */
    public RecyclerView createList(Context context, LuaTable callbacks) {
        RecyclerView list = new RecyclerView(context);
        lists.add(list);
        list.setPadding(0, 0, 0, getBottomInset());
        LinearLayoutManager layout = new LinearLayoutManager(context);
        layout.setStackFromEnd(callbacks.get("stackFromEnd").toboolean());
        list.setLayoutManager(layout);
        list.setClipToPadding(false);
        list.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        list.setAdapter(new RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            @Override public int getItemCount() { return Math.max(0, call(callbacks, "getCount").optint(0)); }
            @Override public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int type) {
                FrameLayout holder = new FrameLayout(parent.getContext());
                holder.setLayoutParams(new RecyclerView.LayoutParams(-1, -2));
                LuaValue value = call(callbacks, "createView", CoerceJavaToLua.coerce(parent.getContext()));
                if (value.isuserdata(View.class)) holder.addView((View) CoerceLuaToJava.coerce(value, View.class));
                RecyclerView.ViewHolder result = new RecyclerView.ViewHolder(holder) {};
                holder.setOnClickListener(v -> {
                    int position = result.getAdapterPosition();
                    if (position != RecyclerView.NO_POSITION) call(callbacks, "onClick", LuaValue.valueOf(position));
                });
                return result;
            }
            @Override public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
                View child = ((FrameLayout) holder.itemView).getChildAt(0);
                call(callbacks, "bindView", CoerceJavaToLua.coerce(child), LuaValue.valueOf(position));
            }
        });
        list.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrolled(RecyclerView view, int dx, int dy) {
                call(callbacks, "onScroll", CoerceJavaToLua.coerce(view),
                        LuaValue.valueOf(layout.findFirstVisibleItemPosition()), LuaValue.valueOf(dy));
            }
        });
        return list;
    }
}
