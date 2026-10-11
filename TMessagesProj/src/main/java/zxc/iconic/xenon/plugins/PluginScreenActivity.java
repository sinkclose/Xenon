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
    private final PluginLuaRuntime runtime;
    private final LuaTable options;
    private boolean failed;
    private final java.util.List<RecyclerView> lists = new java.util.ArrayList<>();

    public PluginScreenActivity(String owner, LuaTable options) {
        this.owner = owner;
        this.runtime = PluginManager.getRuntime(owner);
        this.options = options;
        int account = options.rawget("account").optint(org.telegram.messenger.UserConfig.selectedAccount);
        if (account < 0 || account >= org.telegram.messenger.UserConfig.MAX_ACCOUNT_COUNT) {
            throw new org.luaj.vm2.LuaError("Invalid plugin screen account");
        }
        setCurrentAccount(account);
    }

    public static void open(String owner, LuaTable options) {
        final PluginLuaRuntime generation = PluginManager.getRuntime(owner);
        AndroidUtilities.runOnUIThread(() -> {
            if (!PluginManager.isRuntimeCurrent(owner, generation) || !PluginManager.hasScope(owner, PluginManager.SCOPE_UI)
                    || !PluginManager.hasScope(owner, PluginManager.SCOPE_JAVA)) return;
            BaseFragment parent = LaunchActivity.getSafeLastFragment();
            try {
                if (parent != null) parent.presentFragment(new PluginScreenActivity(owner, options));
            } catch (Throwable error) {
                PluginManager.getInstance().quarantineFile(owner, "screen open", error);
            }
        });
    }

    public static void closeAll() {
        java.util.List<PluginScreenActivity> closing = new java.util.ArrayList<>(screens);
        AndroidUtilities.runOnUIThread(() -> { for (PluginScreenActivity screen : closing) screen.removeSelfFromStack(true); });
    }

    public static void closeForPlugin(String owner) {
        java.util.List<PluginScreenActivity> closing = new java.util.ArrayList<>(screens);
        AndroidUtilities.runOnUIThread(() -> {
            for (PluginScreenActivity screen : closing) {
                if (screen.owner.equals(owner)) screen.removeSelfFromStack(true);
            }
        });
    }

    private LuaValue call(LuaTable callbacks, String name, LuaValue... args) {
        if (failed || !PluginManager.isRuntimeCurrent(owner, runtime)
                || !PluginManager.hasScope(owner, PluginManager.SCOPE_UI)
                || !PluginManager.hasScope(owner, PluginManager.SCOPE_JAVA)) return LuaValue.NIL;
        LuaValue callback = callbacks.rawget(name);
        if (!callback.isfunction()) return LuaValue.NIL;
        PluginManager.markHookStart("screen_" + name);
        try {
            return PluginManager.invokeCallback(owner, runtime, callback, LuaValue.varargsOf(args)).arg1();
        } catch (Throwable error) {
            fail(name, error);
            return LuaValue.NIL;
        } finally {
            PluginManager.markHookEnd();
        }
    }

    private void fail(String stage, Throwable error) {
        failed = true;
        if (PluginManager.isRuntimeCurrent(owner, runtime)) {
            PluginManager.getInstance().quarantineFile(owner, "screen " + stage, error);
        }
    }

    private View detachedView(LuaValue value) {
        if (!value.isuserdata(View.class)) return null;
        View view = (View) CoerceLuaToJava.coerce(value, View.class);
        if (view.getParent() != null) {
            fail("createView", new org.luaj.vm2.LuaError("Plugin view already has a parent"));
            return null;
        }
        return view;
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
        LuaValue title = options.rawget("title");
        actionBar.setTitle(title.isstring() ? title.tojstring() : "Plugin");
        LuaValue subtitleValue = options.rawget("subtitle");
        String subtitle = subtitleValue.isstring() ? subtitleValue.tojstring() : "";
        if (!subtitle.isEmpty()) actionBar.setSubtitle(subtitle);
        LuaValue menu = options.rawget("menu");
        if (menu.istable()) {
            for (int i = 1; i <= Math.min(menu.rawlen(), 32); i++) {
                LuaValue item = menu.rawget(i);
                if (item.istable()) {
                    LuaValue id = item.rawget("id"), icon = item.rawget("icon");
                    actionBar.createMenu().addItem(id.isnumber() ? id.toint() : i,
                            PluginApi.getIconDrawable(icon.isstring() ? icon.tojstring() : "msg_info"));
                }
            }
        }
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override public void onItemClick(int id) {
                if (id == -1) { if (onBackPressed(true)) finishFragment(); }
                else call(options, "onMenu", self(), LuaValue.valueOf(id));
            }
        });
        LuaValue content = call(options, "createView", CoerceJavaToLua.coerce(context), self());
        View pluginView = detachedView(content);
        if (pluginView != null) fragmentView = pluginView;
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
        layout.setStackFromEnd(callbacks.rawget("stackFromEnd").toboolean());
        list.setLayoutManager(layout);
        list.setClipToPadding(false);
        list.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        list.setAdapter(new RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            @Override public int getItemCount() {
                LuaValue count = call(callbacks, "getCount");
                return count.isnumber() ? Math.max(0, Math.min(100_000, count.toint())) : 0;
            }
            @Override public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int type) {
                FrameLayout holder = new FrameLayout(parent.getContext());
                holder.setLayoutParams(new RecyclerView.LayoutParams(-1, -2));
                LuaValue value = call(callbacks, "createView", CoerceJavaToLua.coerce(parent.getContext()));
                View child = detachedView(value);
                if (child != null) holder.addView(child);
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
