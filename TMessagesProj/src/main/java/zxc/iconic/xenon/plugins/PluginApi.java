package zxc.iconic.xenon.plugins;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.content.SharedPreferences;
import android.util.Log;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;

import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.lib.VarArgFunction;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.OneArgFunction;
import org.luaj.vm2.lib.TwoArgFunction;
import org.luaj.vm2.lib.ZeroArgFunction;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import top.canyie.pine.Pine;
import top.canyie.pine.callback.MethodHook;

import org.luaj.vm2.lib.jse.CoerceJavaToLua;
import org.luaj.vm2.lib.jse.CoerceLuaToJava;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.DialogsActivity;

/**
 * Builds the Lua-side API exposed to plugins as the global {@code xenon} table.
 *
 * <h3>Registration</h3>
 * <pre>{@code
 * xenon.on("onResume", function()
 *     xenon.sendMessage("test", 3284201935)
 * end)
 * }</pre>
 *
 * <h3>onSendMessage hook</h3>
 * The handler receives a table {@code {message=string, peer=number}} and may
 * return a table to control the send:
 * <pre>{@code
 * xenon.on("onSendMessage", function(ctx)
 *     -- rewrite the outgoing text
 *     return { message = ctx.message .. " :)", peer = ctx.peer }
 *     -- or block it entirely:
 *     -- return { cancel = true }
 * end)
 * }</pre>
 * Returning {@code nil} (or no return) leaves the send unchanged.
 *
 * <p>All functions run on whatever thread the host hook fired on; host actions
 * that require the main thread (e.g. actually sending a message) marshal
 * themselves there via {@link org.telegram.messenger.AndroidUtilities}.
 */
public class PluginApi {

    static volatile String currentPluginFileName;
    static volatile String currentPluginId;

    private static class TimerEntry {
        Runnable runnable;
        String pluginFileName;
        TimerEntry(Runnable r, String pn) { runnable = r; pluginFileName = pn; }
    }

    private static final Map<Integer, TimerEntry> timers = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Handler timerHandler = new Handler(Looper.getMainLooper());
    private static final java.util.concurrent.atomic.AtomicInteger nextTimerId = new java.util.concurrent.atomic.AtomicInteger(1);

    static int setTimeout(String plugin, double seconds, LuaValue callback) {
        int id = nextTimerId.getAndIncrement();
        Runnable runnable = () -> {
            TimerEntry entry = timers.remove(id);
            if (entry != null && isPluginValid(entry.pluginFileName)) {
                callback.call();
            }
        };
        timers.put(id, new TimerEntry(runnable, plugin));
        timerHandler.postDelayed(runnable, (long) (seconds * 1000));
        return id;
    }

    static void clearTimeout(int id) {
        TimerEntry entry = timers.remove(id);
        if (entry != null) {
            timerHandler.removeCallbacks(entry.runnable);
        }
    }

    private static class MessageWatcher {
        String key;
        String pluginFileName;
        long[] chats;
        int interval;
        int threshold;
        LuaValue callback;
        int timerId;
        Map<Long, Integer> lastIds = new HashMap<>();
        boolean running;
    }

    private static final Map<String, MessageWatcher> watchers = new ConcurrentHashMap<>();

    // Active Pine hooks per plugin, so they can be unhooked when a plugin stops.
    private static final Map<String, List<MethodHook.Unhook>> pineHooks = new ConcurrentHashMap<>();

    // NotificationCenter observers per plugin, for cleanup on stop.
    private static final class NcObserver {
        final NotificationCenter.NotificationCenterDelegate delegate;
        final int id;
        final int account;
        NcObserver(NotificationCenter.NotificationCenterDelegate delegate, int id, int account) {
            this.delegate = delegate;
            this.id = id;
            this.account = account;
        }
    }
    private static final Map<String, List<NcObserver>> ncObservers = new ConcurrentHashMap<>();

    static void startMessageWatcher(String owner, String key, long[] chats, int interval, int threshold, LuaValue callback) {
        stopMessageWatcher(key);
        MessageWatcher w = new MessageWatcher();
        w.key = key;
        w.pluginFileName = owner;
        w.chats = chats;
        w.interval = interval;
        w.threshold = threshold;
        w.callback = callback;
        w.lastIds = loadWatcherIds(key);
        w.running = true;
        watchers.put(key, w);
        Log.d("XenonPlugin", "startMessageWatcher: key=" + key + " chats=" + chats.length + " interval=" + interval + " threshold=" + threshold + " savedIds=" + w.lastIds.size());
        initWatcherBaseline(w);
    }

    private static void initWatcherBaseline(MessageWatcher w) {
        int total = w.chats.length;
        int[] done = {0};
        if (total == 0) return;
        for (long chatId : w.chats) {
            int account = UserConfig.selectedAccount;
            long cid = chatId;
            ensurePeerLoaded(account, cid, () -> {
                if (!w.running) {
                    // Stopped while loading — still finish the round so nothing
                    // hangs.
                    finishWatcherBaselineRound(w, done, total);
                    return;
                }
                MessagesController mc = MessagesController.getInstance(account);
                TLRPC.InputPeer peer = mc.getInputPeer(cid);
                if (peer == null) {
                    // Couldn't resolve: set a baseline of 0 so the watcher isn't
                    // "blind" on this chat for its whole life — the first check
                    // will treat everything as new, which is safer than missing.
                    Log.e("XenonPlugin", "watcher init: chat=" + cid + " peer null, baseline=0");
                    w.lastIds.putIfAbsent(cid, 0);
                    finishWatcherBaselineRound(w, done, total);
                    return;
                }
                TLRPC.TL_messages_getHistory req = new TLRPC.TL_messages_getHistory();
                req.peer = peer;
                req.limit = 20;
                req.offset_id = 0;
                req.offset_date = 0;
                req.add_offset = 0;
                ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> {
                    try {
                        if (error != null) {
                            Log.e("XenonPlugin", "watcher init: chat=" + cid + " error=" + error.text);
                            w.lastIds.putIfAbsent(cid, 0);
                        } else if (response instanceof TLRPC.messages_Messages) {
                            TLRPC.messages_Messages msgs = (TLRPC.messages_Messages) response;
                            int latestAnyId = msgs.messages.isEmpty() ? 0 : msgs.messages.get(0).id;
                            w.lastIds.put(cid, latestAnyId);
                        }
                    } catch (Throwable t) {
                        FileLog.e(t);
                    } finally {
                        // ALWAYS bump the counter — otherwise one failing chat
                        // would prevent scheduleWatcher from ever running.
                        finishWatcherBaselineRound(w, done, total);
                    }
                });
            });
        }
    }

    private static void finishWatcherBaselineRound(MessageWatcher w, int[] done, int total) {
        int n = ++done[0];
        saveWatcherIds(w.key, w.lastIds);
        if (n == total) {
            scheduleWatcher(w);
        }
    }

    static void stopMessageWatcher(String key) {
        MessageWatcher w = watchers.remove(key);
        if (w != null) {
            w.running = false;
            if (w.timerId > 0) {
                clearTimeout(w.timerId);
            }
        }
    }

    private static void scheduleWatcher(MessageWatcher w) {
        if (!w.running) return;
        w.timerId = setTimeout(w.pluginFileName, w.interval, new ZeroArgFunction() {
            @Override
            public LuaValue call() {
                runWatcherCheck(w);
                return LuaValue.NIL;
            }
        });
    }

    private static void runWatcherCheck(MessageWatcher w) {
        if (!w.running || w.chats.length == 0) return;
        Log.d("XenonPlugin", "runWatcherCheck: key=" + w.key + " total=" + w.chats.length);
        int total = w.chats.length;
        int[] done = {0};
        LuaTable triggered = new LuaTable();
        int[] idx = {1};
        for (long chatId : w.chats) {
            int account = UserConfig.selectedAccount;
            long cid = chatId;
            ensurePeerLoaded(account, cid, () -> {
                if (!w.running) {
                    // Watcher was stopped while we were loading the peer. Still
                    // bump done so nothing hangs, then bail.
                    bumpWatcherDone(w, done, total, idx, triggered, 0);
                    return;
                }
                MessagesController mc = MessagesController.getInstance(account);
                TLRPC.InputPeer peer = mc.getInputPeer(cid);
                if (peer == null) {
                    // Couldn't resolve the peer even after loading. Count this
                    // chat as "checked" so the round completes and the next
                    // interval still schedules — otherwise one unresolvable chat
                    // would freeze the whole watcher forever.
                    Log.e("XenonPlugin", "watcher: " + w.key + " chat=" + cid + " peer null, skipping");
                    bumpWatcherDone(w, done, total, idx, triggered, 0);
                    return;
                }
                TLRPC.TL_messages_getHistory req = new TLRPC.TL_messages_getHistory();
                req.peer = peer;
                req.limit = 20;
                req.offset_id = 0;
                req.offset_date = 0;
                req.add_offset = 0;
                ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> {
                    try {
                        if (error != null) {
                            Log.e("XenonPlugin", "watcher: " + w.key + " chat=" + cid + " error=" + error.text);
                        } else if (response instanceof TLRPC.messages_Messages) {
                            TLRPC.messages_Messages msgs = (TLRPC.messages_Messages) response;
                            if (!msgs.messages.isEmpty()) {
                                int latestAnyId = msgs.messages.get(0).id;
                                Integer saved = w.lastIds.get(cid);
                                if (saved == null) {
                                    Log.d("XenonPlugin", "watcher: " + w.key + " chat=" + cid + " init baseline=" + latestAnyId);
                                    w.lastIds.put(cid, latestAnyId);
                                } else {
                                    int prevId = saved;
                                    int incomingCount = 0;
                                    for (TLRPC.Message m : msgs.messages) {
                                        if (!m.out && m.id > prevId) {
                                            incomingCount++;
                                        }
                                    }
                                    Log.d("XenonPlugin", "watcher: " + w.key + " chat=" + cid + " prev=" + prevId + " latestAny=" + latestAnyId + " incomingCount=" + incomingCount + " threshold=" + w.threshold);
                                    if (incomingCount >= w.threshold) {
                                        LuaTable entry = new LuaTable();
                                        entry.set("chatId", cid);
                                        entry.set("new_count", incomingCount);
                                        triggered.set(idx[0]++, entry);
                                    }
                                    w.lastIds.put(cid, latestAnyId);
                                }
                            } else if (!w.lastIds.containsKey(cid)) {
                                w.lastIds.put(cid, 0);
                            }
                        }
                    } catch (Throwable t) {
                        FileLog.e(t);
                    } finally {
                        // ALWAYS bump done, even on error/exception — otherwise
                        // the round never completes and the watcher hangs.
                        bumpWatcherDone(w, done, total, idx, triggered, 0);
                    }
                });
            });
        }
    }

    private static void bumpWatcherDone(MessageWatcher w, int[] done, int total, int[] idx, LuaTable triggered, int unused) {
        int n = ++done[0];
        if (n == total) {
            saveWatcherIds(w.key, w.lastIds);
            AndroidUtilities.runOnUIThread(() -> {
                if (!w.running) return;
                if (!isPluginValid(w.pluginFileName)) {
                    w.running = false;
                    return;
                }
                boolean triggeredAny = idx[0] > 1;
                Log.d("XenonPlugin", "runWatcherCheck: key=" + w.key + " done. triggered=" + triggeredAny + " triggeredCount=" + (idx[0] - 1));
                if (triggeredAny && w.callback != null && !w.callback.isnil()) {
                    try { PluginManager.invokeCallback(w.pluginFileName, w.callback, triggered); }
                    catch (Throwable error) { PluginManager.getInstance().quarantineFile(w.pluginFileName, "watcher", error); }
                }
                scheduleWatcher(w);
            });
        }
    }

    private static Map<Long, Integer> loadWatcherIds(String key) {
        Map<Long, Integer> map = new HashMap<>();
        String raw = getSetting("mw_" + key + "_ids", "");
        if (raw.isEmpty()) return map;
        for (String part : raw.split(",")) {
            int sep = part.indexOf(':');
            if (sep > 0) {
                try {
                    long cid = Long.parseLong(part.substring(0, sep));
                    int mid = Integer.parseInt(part.substring(sep + 1));
                    map.put(cid, mid);
                } catch (NumberFormatException ignored) {}
            }
        }
        return map;
    }

    private static void saveWatcherIds(String key, Map<Long, Integer> ids) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<Long, Integer> e : ids.entrySet()) {
            if (sb.length() > 0) sb.append(',');
            sb.append(e.getKey()).append(':').append(e.getValue());
        }
        setSetting("mw_" + key + "_ids", sb.toString());
    }

    private static boolean isPluginValid(String pluginFileName) {
        if (pluginFileName == null || pluginFileName.isEmpty()) return true;
        PluginManager pm = PluginManager.getInstance();
        if (!pm.isEnabled()) return false;
        PluginManager.LoadedPlugin p = pm.findPlugin(pluginFileName);
        return p != null && p.isEnabled();
    }

    static void stopAllForPlugin(String pluginFileName) {
        if (pluginFileName == null) return;
        PluginManager.stopRuntime(pluginFileName);
        PluginScreenActivity.closeForPlugin(pluginFileName);
        timers.entrySet().removeIf(e -> {
            if (pluginFileName.equals(e.getValue().pluginFileName)) {
                timerHandler.removeCallbacks(e.getValue().runnable);
                return true;
            }
            return false;
        });
        watchers.values().removeIf(w -> {
            if (pluginFileName.equals(w.pluginFileName)) {
                w.running = false;
                if (w.timerId > 0) {
                    TimerEntry te = timers.remove(w.timerId);
                    if (te != null) timerHandler.removeCallbacks(te.runnable);
                }
                return true;
            }
            return false;
        });
        // Unhook all Pine hooks for this plugin
        List<MethodHook.Unhook> hooks = pineHooks.remove(pluginFileName);
        if (hooks != null) {
            for (MethodHook.Unhook hook : hooks) {
                hook.unhook();
            }
            Log.d("XenonPlugin", "Removed " + hooks.size() + " Pine hooks for " + pluginFileName);
        }
        // Remove all NotificationCenter observers for this plugin
        List<NcObserver> obs = ncObservers.remove(pluginFileName);
        if (obs != null) {
            for (NcObserver o : obs) {
                NotificationCenter.getInstance(o.account).removeObserver(o.delegate, o.id);
            }
            Log.d("XenonPlugin", "Removed " + obs.size() + " NC observers for " + pluginFileName);
        }
    }

    static void stopAll() {
        PluginManager.stopAllRuntimes();
        PluginScreenActivity.closeAll();
        timers.values().forEach(e -> timerHandler.removeCallbacks(e.runnable));
        timers.clear();
        watchers.values().forEach(w -> w.running = false);
        watchers.clear();
        // Unhook all Pine hooks
        for (List<MethodHook.Unhook> hooks : pineHooks.values()) {
            for (MethodHook.Unhook hook : hooks) {
                hook.unhook();
            }
        }
        pineHooks.clear();
        // Remove all NC observers
        for (List<NcObserver> obs : ncObservers.values()) {
            for (NcObserver o : obs) {
                NotificationCenter.getInstance(o.account).removeObserver(o.delegate, o.id);
            }
        }
        ncObservers.clear();
    }

    static void setCurrentPluginFileName(String name) {
        currentPluginFileName = name;
    }

    static void setCurrentPluginId(String id) {
        currentPluginId = id;
    }

    /**
     * Check the {@code MESSAGING} scope for the plugin that's currently
     * loading/calling. Protected API methods call this and silently bail
     * (return nil) when it's false, so a plugin without the scope — or without
     * God Mode — can't send/delete/react/read messages or run message queries.
     * The {@code currentPluginFileName} is captured by the API closures at
     * creation time, so it stays correct even though it's a mutable static.
     */
    static boolean hasMessagingScope(String fileName) {
        return PluginManager.hasScope(fileName, PluginManager.SCOPE_MESSAGING);
    }

    static LuaTable[] createApiTable(Globals globals) {
        // Capture the owning plugin's file name once; every API closure below
        // binds this, so it stays tied to the right plugin even though
        // currentPluginFileName is a shared mutable static.
        final String pluginFileName = currentPluginFileName;
        final PluginLuaRuntime apiRuntime = PluginManager.getRuntime(pluginFileName);
        LuaTable hooks = new LuaTable();
        globals.set("xenon_hooks", hooks);

        LuaTable api = new LuaTable();
        api.set("hasPermission", new OneArgFunction() {
            @Override public LuaValue call(LuaValue scope) {
                return LuaValue.valueOf(PluginManager.hasScope(pluginFileName, scope.checkjstring()));
            }
        });
        api.set("on", new TwoArgFunction() {
            @Override
            public LuaValue call(LuaValue name, LuaValue handler) {
                Log.d("XenonPlugin", "xenon.on called: name=" + name.tojstring() + " handler_type=" + handler.type());
                hooks.set(name.checkjstring(), handler);
                Log.d("XenonPlugin", "xenon.on: registered '" + name.tojstring() + "', hooks size=" + hooks.length());
                return LuaValue.NIL;
            }
        });
        api.set("sendMessage", new VarArgFunction() {
            @Override
            public LuaValue invoke(Varargs args) {
                if (!hasMessagingScope(pluginFileName)) return LuaValue.NIL;
                Varargs uargs = args.subargs(1);
                int n = uargs.narg();
                if (n >= 2) {
                    String text = uargs.arg(1).checkjstring();
                    long peer = uargs.arg(2).checklong();
                    int replyTo = n >= 3 && uargs.arg(3).isnumber() ? (int) uargs.arg(3).todouble() : 0;
                    LuaValue cb = n >= 4 && uargs.arg(4).isfunction() ? uargs.arg(4) : null;
                    long senderId = 0;
                    if (n >= 4 && uargs.arg(4).isnumber() && !uargs.arg(4).isfunction()) {
                        senderId = (long) uargs.arg(4).todouble();
                    } else if (n >= 5 && uargs.arg(5).isnumber()) {
                        senderId = (long) uargs.arg(5).todouble();
                    }
                    Log.d("XenonPlugin", "xenon.sendMessage called: peer=" + peer + " replyTo=" + replyTo + " hasCb=" + (cb != null) + " senderId=" + senderId);
                    sendMessage(text, peer, replyTo, cb, senderId);
                }
                return LuaValue.NIL;
            }
        });
        api.set("deleteMessage", new VarArgFunction() {
            @Override
            public LuaValue invoke(Varargs args) {
                if (!hasMessagingScope(pluginFileName)) return LuaValue.NIL;
                Varargs uargs = args.subargs(1);
                if (uargs.narg() >= 2) {
                    long chatId = (long) uargs.arg(1).todouble();
                    int msgId = (int) uargs.arg(2).todouble();
                    Log.d("XenonPlugin", "xenon.deleteMessage called: chatId=" + chatId + " msgId=" + msgId);
                    deleteMessage(chatId, msgId);
                }
                return LuaValue.NIL;
            }
        });
        api.set("sendMedia", new VarArgFunction() {
            @Override
            public LuaValue invoke(Varargs args) {
                if (!hasMessagingScope(pluginFileName)) return LuaValue.NIL;
                Varargs uargs = args.subargs(1);
                if (uargs.narg() >= 2) {
                    long chatId = (long) uargs.arg(1).todouble();
                    int msgId = (int) uargs.arg(2).todouble();
                    int replyTo = uargs.narg() >= 3 && uargs.arg(3).isnumber() ? (int) uargs.arg(3).todouble() : 0;
                    Log.d("XenonPlugin", "xenon.sendMedia called: chatId=" + chatId + " msgId=" + msgId + " replyTo=" + replyTo);
                    sendMedia(chatId, msgId, replyTo);
                }
                return LuaValue.NIL;
            }
        });
        api.set("setReaction", new VarArgFunction() {
            @Override
            public LuaValue invoke(Varargs args) {
                if (!hasMessagingScope(pluginFileName)) return LuaValue.NIL;
                Varargs uargs = args.subargs(1);
                if (uargs.narg() >= 3) {
                    long chatId = (long) uargs.arg(1).todouble();
                    int msgId = (int) uargs.arg(2).todouble();
                    String reaction = uargs.arg(3).optjstring("");
                    Log.d("XenonPlugin", "xenon.setReaction: chatId=" + chatId + " msgId=" + msgId + " reaction=" + reaction);
                    setReaction(chatId, msgId, reaction);
                }
                return LuaValue.NIL;
            }
        });
        api.set("readHistory", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue chatId) {
                if (!hasMessagingScope(pluginFileName)) return LuaValue.NIL;
                long cid = (long) chatId.todouble();
                Log.d("XenonPlugin", "xenon.readHistory: chatId=" + cid);
                readHistory(cid);
                return LuaValue.NIL;
            }
        });
        api.set("setTimeout", new VarArgFunction() {
            @Override
            public LuaValue invoke(Varargs args) {
                Varargs uargs = args.subargs(1);
                if (uargs.narg() >= 2 && uargs.arg(2).isfunction()) {
                    double seconds = uargs.arg(1).todouble();
                    LuaValue callback = uargs.arg(2);
                    int id = setTimeout(pluginFileName, seconds, callback);
                    return LuaValue.valueOf(id);
                }
                return LuaValue.NIL;
            }
        });
        api.set("clearTimeout", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue id) {
                TimerEntry entry = timers.get((int) id.todouble());
                if (entry != null && pluginFileName.equals(entry.pluginFileName)) clearTimeout((int) id.todouble());
                return LuaValue.NIL;
            }
        });
        api.set("startMessageWatcher", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue config) {
                if (!hasMessagingScope(pluginFileName)) return LuaValue.NIL;
                if (!config.istable()) return LuaValue.NIL;
                LuaTable t = (LuaTable) config;
                String key = t.get("key").optjstring("");
                if (key.isEmpty()) return LuaValue.NIL;
                LuaValue chatsVal = t.get("chats");
                if (!chatsVal.istable()) return LuaValue.NIL;
                LuaTable chatsTable = (LuaTable) chatsVal;
                long[] chats = new long[chatsTable.length()];
                for (int i = 1; i <= chatsTable.length(); i++) {
                    chats[i - 1] = (long) chatsTable.get(i).todouble();
                }
                int interval = Math.max(5, (int) t.get("interval").optdouble(60));
                int threshold = Math.max(1, (int) t.get("threshold").optdouble(3));
                LuaValue callback = t.get("callback");
                if (!callback.isfunction()) return LuaValue.NIL;
                startMessageWatcher(pluginFileName, pluginFileName + ":" + key, chats, interval, threshold, callback);
                return LuaValue.TRUE;
            }
        });
        api.set("stopMessageWatcher", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue key) {
                stopMessageWatcher(pluginFileName + ":" + key.optjstring(""));
                return LuaValue.NIL;
            }
        });
        api.set("toast", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue arg) {
                Log.d("XenonPlugin", "xenon.toast called: " + arg.tojstring());
                showToast(arg.checkjstring());
                return LuaValue.NIL;
            }
        });
        api.set("bulletin", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue arg) {
                Log.d("XenonPlugin", "xenon.bulletin called: " + arg.tojstring());
                showBulletin(arg.checkjstring());
                return LuaValue.NIL;
            }
        });
        api.set("bulletinButton", new VarArgFunction() {
            @Override
            public LuaValue invoke(Varargs args) {
                Varargs uargs = args.subargs(1);
                if (uargs.narg() >= 3) {
                    String text = uargs.arg(1).checkjstring();
                    String button = uargs.arg(2).checkjstring();
                    LuaValue cb = uargs.arg(3).isfunction() ? uargs.arg(3) : null;
                    Log.d("XenonPlugin", "xenon.bulletinButton called: text=" + text + " button=" + button);
                    showBulletinButton(text, button, cb);
                }
                return LuaValue.NIL;
            }
        });
        final String capturedPluginForSettings = "settings:" + pluginFileName.length() + ":" + pluginFileName + ":";
        // Only already trusted plugins may import legacy ID-based settings.
        // A newly installed restricted plugin cannot claim another plugin's ID.
        android.content.SharedPreferences settingsPrefs = ApplicationLoader.applicationContext.getSharedPreferences("xenon_plugins", android.content.Context.MODE_PRIVATE);
        String migrationKey = "plugin_settings_v3_" + pluginFileName;
        if (!settingsPrefs.getBoolean(migrationKey, false) && PluginManager.hasScope(pluginFileName, PluginManager.SCOPE_JAVA)) {
            String legacyPrefix = (currentPluginId != null ? currentPluginId : pluginFileName) + "_";
            android.content.SharedPreferences.Editor migration = settingsPrefs.edit();
            for (java.util.Map.Entry<String, ?> entry : settingsPrefs.getAll().entrySet()) {
                if (entry.getKey().startsWith(legacyPrefix) && entry.getValue() instanceof String) {
                    String target = capturedPluginForSettings + entry.getKey().substring(legacyPrefix.length());
                    if (!settingsPrefs.contains(target)) migration.putString(target, (String)entry.getValue());
                }
            }
            migration.putBoolean(migrationKey, true).commit();
        }
        api.set("getSetting", new TwoArgFunction() {
            @Override
            public LuaValue call(LuaValue key, LuaValue def) {
                String rawKey = key.checkjstring();
                String namespacedKey = capturedPluginForSettings + rawKey;
                String defStr = def.isnil() ? null : def.tojstring();
                String val = getSetting(namespacedKey, null);
                if (val == null) val = defStr;
                if (val == null) return LuaValue.NIL;
                return LuaValue.valueOf(val);
            }
        });
        api.set("setSetting", new TwoArgFunction() {
            @Override
            public LuaValue call(LuaValue key, LuaValue value) {
                String namespaced = capturedPluginForSettings + key.checkjstring();
                setSetting(namespaced, value.tojstring());
                return LuaValue.NIL;
            }
        });
        api.set("getPeerName", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue chatId) {
                long id = (long) chatId.todouble();
                String name = getCachedPeerName(id);
                return name != null ? LuaValue.valueOf(name) : LuaValue.NIL;
            }
        });
        api.set("getOpenChatId", new ZeroArgFunction() {
            @Override
            public LuaValue call() {
                return LuaValue.valueOf(PluginManager.getCurrentDialogId());
            }
        });
        api.set("openActivity", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue arg) {
                String name = arg.checkjstring();
                openActivity(name);
                return LuaValue.NIL;
            }
        });
        api.set("openScreen", new OneArgFunction() {
            @Override public LuaValue call(LuaValue options) {
                PluginScreenActivity.open(pluginFileName, options.checktable());
                return LuaValue.NIL;
            }
        });
        final String capturedFileName = currentPluginFileName;
        api.set("openPluginSettings", new ZeroArgFunction() {
            @Override
            public LuaValue call() {
                if (capturedFileName != null) {
                    openPluginSettingsFor(capturedFileName);
                }
                return LuaValue.NIL;
            }
        });
        api.set("promptText", new VarArgFunction() {
            @Override
            public LuaValue invoke(Varargs args) {
                Varargs uargs = args.subargs(1);
                int n = uargs.narg();
                String title = n > 0 ? uargs.arg(1).optjstring("") : "";
                String hint = n > 1 ? uargs.arg(2).optjstring("") : "";
                LuaValue cb = n > 2 ? uargs.arg(3) : LuaValue.NIL;
                if (cb.isfunction()) {
                    promptText(title, hint, cb);
                }
                return LuaValue.NIL;
            }
        });
        api.set("createDialog", new VarArgFunction() {
            @Override
            public LuaValue invoke(Varargs args) {
                Varargs uargs = args.subargs(1);
                int n = uargs.narg();
                String title = n > 0 ? uargs.arg(1).optjstring("") : "";
                String message = n > 1 ? uargs.arg(2).optjstring("") : "";
                LuaValue buttons = n > 2 ? uargs.arg(3) : LuaValue.NIL;
                createDialog(title, message, buttons);
                return LuaValue.NIL;
            }
        });
        api.set("createBottomSheet", new TwoArgFunction() {
            @Override
            public LuaValue call(LuaValue title, LuaValue items) {
                createBottomSheet(title.optjstring(""), items);
                return LuaValue.NIL;
            }
        });
        api.set("finish", new ZeroArgFunction() {
            @Override
            public LuaValue call() {
                PluginManager.setRequestFinishFragment(true);
                return LuaValue.NIL;
            }
        });
        api.set("openChatPicker", new VarArgFunction() {
            @Override
            public LuaValue invoke(Varargs args) {
                Varargs uargs = args.subargs(1);
                int n = uargs.narg();
                LuaValue callback = uargs.arg(1);
                String filter = n >= 2 ? uargs.arg(2).tojstring() : null;
                if (callback.isfunction()) {
                    openChatPicker(callback, filter);
                }
                return LuaValue.NIL;
            }
        });
        api.set("refreshSettings", new ZeroArgFunction() {
            @Override
            public LuaValue call() {
                org.telegram.messenger.AndroidUtilities.runOnUIThread(() -> {
                    zxc.iconic.xenon.settings.PluginSettingsActivity.refreshCurrent();
                });
                return LuaValue.NIL;
            }
        });
        api.set("getMessageById", new VarArgFunction() {
            @Override
            public LuaValue invoke(Varargs args) {
                if (!hasMessagingScope(pluginFileName)) return LuaValue.NIL;
                Varargs uargs = args.subargs(1);
                int n = uargs.narg();
                if (n >= 3 && uargs.arg(1).isnumber() && uargs.arg(2).isnumber() && uargs.arg(3).isfunction()) {
                    getMessageById((long) uargs.arg(1).todouble(), (int) uargs.arg(2).todouble(), uargs.arg(3));
                }
                return LuaValue.NIL;
            }
        });
        api.set("getRecentMessages", new VarArgFunction() {
            @Override
            public LuaValue invoke(Varargs args) {
                if (!hasMessagingScope(pluginFileName)) return LuaValue.NIL;
                Varargs uargs = args.subargs(1);
                int n = uargs.narg();
                if (n >= 3 && uargs.arg(1).isnumber() && uargs.arg(2).isnumber() && uargs.arg(3).isfunction()) {
                    getRecentMessages((long) uargs.arg(1).todouble(), (int) uargs.arg(2).todouble(), uargs.arg(3));
                }
                return LuaValue.NIL;
            }
        });
        api.set("getMessagesFromUser", new VarArgFunction() {
            @Override
            public LuaValue invoke(Varargs args) {
                if (!hasMessagingScope(pluginFileName)) return LuaValue.NIL;
                Varargs uargs = args.subargs(1);
                int n = uargs.narg();
                if (n >= 4 && uargs.arg(1).isnumber() && uargs.arg(2).isnumber() && uargs.arg(3).isnumber() && uargs.arg(4).isfunction()) {
                    getMessagesFromUser((long) uargs.arg(1).todouble(), (int) uargs.arg(2).todouble(), (int) uargs.arg(3).todouble(), uargs.arg(4));
                }
                return LuaValue.NIL;
            }
        });
        api.set("hookMethod", new VarArgFunction() {
            @Override
            public LuaValue invoke(Varargs args) {
                if (!zxc.iconic.xenon.NekoConfig.pluginGodMode) {
                    Log.e("XenonPlugin", "hookMethod requires God Mode!");
                    return LuaValue.NIL;
                }
                // Native ARM translation does not turn x86 ART into ARM ART.
                // Pine supports ARM only; installing its trampoline in WSA
                // otherwise crashes the process on the first hooked call.
                String runtimeArch = System.getProperty("os.arch", "");
                if (!runtimeArch.startsWith("arm") && !runtimeArch.equals("aarch64")) {
                    Log.e("XenonPlugin", "hookMethod: Pine does not support runtime architecture " + runtimeArch);
                    return LuaValue.NIL;
                }
                String className = args.arg(1).checkjstring();
                String methodName = args.arg(2).checkjstring();
                LuaTable callbackTable = args.arg(3).checktable();
                try {
                    Class<?> clazz = Class.forName(className);
                    Method targetMethod = null;
                    for (Method m : clazz.getDeclaredMethods()) {
                        if (m.getName().equals(methodName)) {
                            targetMethod = m;
                            break;
                        }
                    }
                    if (targetMethod == null) {
                        Log.e("XenonPlugin", "hookMethod: " + methodName + " not found in " + className);
                        return LuaValue.NIL;
                    }
                    targetMethod.setAccessible(true);
                    MethodHook.Unhook hook = Pine.hook(targetMethod, new MethodHook() {
                        @Override
                        public void beforeCall(Pine.CallFrame callFrame) throws Throwable {
                            if (!PluginManager.hasScope(pluginFileName, PluginManager.SCOPE_HOOKS) || !PluginManager.isRuntimeCurrent(pluginFileName, apiRuntime)) return;
                            LuaValue beforeCb = callbackTable.get("before");
                            if (beforeCb.isfunction()) {
                                try {
                                    PluginManager.markHookStart("Pine_before_" + methodName);
                                    LuaTable luaArgs = new LuaTable();
                                    if (callFrame.args != null) {
                                        for (int i = 0; i < callFrame.args.length; i++) {
                                            luaArgs.set(i + 1, callFrame.args[i] != null
                                                    ? CoerceJavaToLua.coerce(callFrame.args[i])
                                                    : LuaValue.NIL);
                                        }
                                    }
                                    LuaValue thisObj = callFrame.thisObject != null
                                            ? CoerceJavaToLua.coerce(callFrame.thisObject)
                                            : LuaValue.NIL;
                                    LuaValue result = PluginManager.invokeCallback(pluginFileName, beforeCb, LuaValue.varargsOf(new LuaValue[]{thisObj, luaArgs})).arg1();
                                    if (!result.isnil()) {
                                        callFrame.setResult(CoerceLuaToJava.coerce(result, Object.class));
                                    }
                                } catch (Throwable t) {
                                    PluginManager.getInstance().quarantineFile(pluginFileName, "Pine beforeCall: " + methodName, t);
                                } finally {
                                    PluginManager.markHookEnd();
                                }
                            }
                        }
                        @Override
                        public void afterCall(Pine.CallFrame callFrame) throws Throwable {
                            if (!PluginManager.hasScope(pluginFileName, PluginManager.SCOPE_HOOKS) || !PluginManager.isRuntimeCurrent(pluginFileName, apiRuntime)) return;
                            LuaValue afterCb = callbackTable.get("after");
                            if (afterCb.isfunction()) {
                                try {
                                    PluginManager.markHookStart("Pine_after_" + methodName);
                                    LuaValue resObj = callFrame.getResult() != null
                                            ? CoerceJavaToLua.coerce(callFrame.getResult())
                                            : LuaValue.NIL;
                                    LuaValue thisObj = callFrame.thisObject != null
                                            ? CoerceJavaToLua.coerce(callFrame.thisObject)
                                            : LuaValue.NIL;
                                    LuaValue result = PluginManager.invokeCallback(pluginFileName, afterCb, LuaValue.varargsOf(new LuaValue[]{thisObj, resObj})).arg1();
                                    if (!result.isnil()) {
                                        callFrame.setResult(CoerceLuaToJava.coerce(result, Object.class));
                                    }
                                } catch (Throwable t) {
                                    PluginManager.getInstance().quarantineFile(pluginFileName, "Pine afterCall: " + methodName, t);
                                } finally {
                                    PluginManager.markHookEnd();
                                }
                            }
                        }
                    });
                    pineHooks.computeIfAbsent(pluginFileName, k -> new CopyOnWriteArrayList<>()).add(hook);
                    return new ZeroArgFunction() {
                        @Override
                        public LuaValue call() {
                            hook.unhook();
                            List<MethodHook.Unhook> list = pineHooks.get(pluginFileName);
                            if (list != null) list.remove(hook);
                            return LuaValue.NIL;
                        }
                    };
                } catch (Exception e) {
                    FileLog.e(e);
                    Log.e("XenonPlugin", "hookMethod exception: " + e.getMessage());
                    return LuaValue.NIL;
                }
            }
        });
        api.set("observeNotification", new VarArgFunction() {
            @Override
            public LuaValue invoke(Varargs args) {
                Varargs uargs = args.subargs(1);
                int n = uargs.narg();
                if (n < 2) return LuaValue.NIL;
                int notificationId = uargs.arg(1).checkint();
                LuaValue callback = uargs.arg(2);
                if (!callback.isfunction()) return LuaValue.NIL;
                Log.d("XenonPlugin", "observeNotification: id=" + notificationId + " plugin=" + pluginFileName);
                final int account = UserConfig.selectedAccount;
                NotificationCenter.NotificationCenterDelegate delegate = (id, acc, args2) -> {
                    if (id != notificationId) return;
                    AndroidUtilities.runOnUIThread(() -> {
                        if (!allowsApi(pluginFileName, "observeNotification") || !isPluginValid(pluginFileName)) return;
                        LuaTable luaArgs = new LuaTable();
                        if (args2 != null) {
                            for (int i = 0; i < args2.length; i++) {
                                luaArgs.set(i + 1, args2[i] != null
                                        ? CoerceJavaToLua.coerce(args2[i])
                                        : LuaValue.NIL);
                            }
                        }
                        try {
                            PluginManager.invokeCallback(pluginFileName, callback, luaArgs);
                        } catch (Throwable t) {
                            FileLog.e("ObserveNotification callback error", t);
                            PluginManager.getInstance().quarantineFile(pluginFileName, "observeNotification " + notificationId, t);
                        }
                    });
                };
                NotificationCenter.getInstance(account).addObserver(delegate, notificationId);
                NcObserver entry = new NcObserver(delegate, notificationId, account);
                ncObservers.computeIfAbsent(pluginFileName, k -> new CopyOnWriteArrayList<>()).add(entry);
                return new ZeroArgFunction() {
                    @Override
                    public LuaValue call() {
                        NotificationCenter.getInstance(account).removeObserver(delegate, notificationId);
                        List<NcObserver> list = ncObservers.get(pluginFileName);
                        if (list != null) list.remove(entry);
                        return LuaValue.NIL;
                    }
                };
            }
        });
        api.set("getPrivateField", new TwoArgFunction() {
            @Override
            public LuaValue call(LuaValue target, LuaValue fieldName) {
                try {
                    Object obj = CoerceLuaToJava.coerce(target, Object.class);
                    String name = fieldName.checkjstring();
                    Class<?> clazz = obj instanceof Class ? (Class<?>) obj : obj.getClass();
                    Object instance = obj instanceof Class ? null : obj;
                    java.lang.reflect.Field field = findField(clazz, name);
                    if (field != null) {
                        Object val = field.get(instance);
                        return CoerceJavaToLua.coerce(val);
                    }
                } catch (Throwable t) {
                    FileLog.e(t);
                }
                return LuaValue.NIL;
            }
        });
        api.set("setPrivateField", new VarArgFunction() {
            @Override
            public LuaValue invoke(Varargs args) {
                Varargs uargs = args.subargs(1);
                if (uargs.narg() >= 3) {
                    try {
                        Object obj = CoerceLuaToJava.coerce(uargs.arg(1), Object.class);
                        String name = uargs.arg(2).checkjstring();
                        LuaValue luaVal = uargs.arg(3);
                        Class<?> clazz = obj instanceof Class ? (Class<?>) obj : obj.getClass();
                        Object instance = obj instanceof Class ? null : obj;
                        java.lang.reflect.Field field = findField(clazz, name);
                        if (field != null) {
                            Object javaVal = CoerceLuaToJava.coerce(luaVal, field.getType());
                            field.set(instance, javaVal);
                        }
                    } catch (Throwable t) {
                        FileLog.e(t);
                    }
                }
                return LuaValue.NIL;
            }
        });
        LuaTable scopedApi = new LuaTable();
        PluginCapabilities.attach(api, pluginFileName);
        for (LuaValue key : api.keys()) {
            final String apiName = key.tojstring();
            final LuaValue function = api.get(key);
            scopedApi.set(key, new VarArgFunction() {
                @Override public Varargs invoke(Varargs args) {
                    if (!PluginManager.isRuntimeCurrent(pluginFileName, apiRuntime)) throw new org.luaj.vm2.LuaError("Plugin was unloaded");
                    if (!allowsApi(pluginFileName, apiName)) throw new org.luaj.vm2.LuaError("Permission denied: " + apiName);
                    LuaValue[] values = new LuaValue[args.narg()];
                    java.util.IdentityHashMap<LuaValue, LuaValue> copied = new java.util.IdentityHashMap<>();
                    for (int i = 0; i < values.length; i++) values[i] = guardCallbacks(pluginFileName, apiRuntime,
                            apiName, args.arg(i + 1), copied, 0);
                    return function.invoke(LuaValue.varargsOf(values));
                }
            });
        }
        return new LuaTable[]{scopedApi, hooks};
    }

    private static LuaValue guardCallbacks(String owner, PluginLuaRuntime runtime, String api, LuaValue value,
            java.util.IdentityHashMap<LuaValue, LuaValue> copied, int depth) {
        if (value.isfunction()) {
            return new VarArgFunction() {
                @Override public Varargs invoke(Varargs args) {
                    if (!isPluginValid(owner) || !PluginManager.isRuntimeCurrent(owner, runtime)) return LuaValue.NIL;
                    try { return PluginManager.invokeCallback(owner, value, args); }
                    catch (Throwable error) {
                        if (PluginManager.isRuntimeCurrent(owner, runtime)) PluginManager.getInstance().quarantineFile(owner, "callback " + api, error);
                        return LuaValue.NIL;
                    }
                }
            };
        }
        if (!value.istable()) return value;
        if (copied.containsKey(value)) return copied.get(value);
        if (depth > 32) throw new org.luaj.vm2.LuaError("Callback table is too deeply nested");
        LuaTable table = new LuaTable(); copied.put(value, table);
        for (LuaValue key : value.checktable().keys()) table.set(key, guardCallbacks(owner, runtime, api, value.get(key), copied, depth + 1));
        return table;
    }

    static boolean allowsApi(String owner, String name) {
        String scope;
        switch (name) {
            case "readFile": case "writeFile": case "deleteFile": scope = PluginManager.SCOPE_FILES; break;
            case "httpGet": scope = PluginManager.SCOPE_INTERNET; break;
            case "openLink": scope = PluginManager.SCOPE_LINKS; break;
            case "sendMedia": return PluginManager.hasScope(owner, PluginManager.SCOPE_SEND) && PluginManager.hasScope(owner, PluginManager.SCOPE_READ);
            case "sendMessage": scope = PluginManager.SCOPE_SEND; break;
            case "deleteMessage": scope = PluginManager.SCOPE_DELETE; break;
            case "setReaction": scope = PluginManager.SCOPE_REACTIONS; break;
            case "readHistory": scope = PluginManager.SCOPE_RECEIPTS; break;
            case "getMessageById": case "getRecentMessages": case "getMessagesFromUser":
            case "getPeerName": case "getOpenChatId": case "startMessageWatcher": scope = PluginManager.SCOPE_READ; break;
            case "hookMethod": scope = PluginManager.SCOPE_HOOKS; break;
            case "observeNotification":
                return PluginManager.hasScope(owner, PluginManager.SCOPE_JAVA) && PluginManager.hasScope(owner, PluginManager.SCOPE_READ);
            case "getPrivateField": case "setPrivateField": scope = PluginManager.SCOPE_JAVA; break;
            case "openScreen":
                return PluginManager.hasScope(owner, PluginManager.SCOPE_JAVA) && PluginManager.hasScope(owner, PluginManager.SCOPE_UI);
            case "toast": case "bulletin": case "bulletinButton": case "openActivity":
            case "openPluginSettings": case "promptText": case "createDialog": case "createBottomSheet":
                scope = PluginManager.SCOPE_UI; break;
            case "openChatPicker": return PluginManager.hasScope(owner, PluginManager.SCOPE_UI) && PluginManager.hasScope(owner, PluginManager.SCOPE_READ);
            case "finish": scope = PluginManager.SCOPE_UI; break;
            default: return true;
        }
        return PluginManager.hasScope(owner, scope);
    }

    static void sendMessage(final String text, final long peerId, final int replyToMsgId) {
        sendMessage(text, peerId, replyToMsgId, null, 0);
    }

    static void sendMessage(final String text, final long peerId, final int replyToMsgId, final LuaValue callback) {
        sendMessage(text, peerId, replyToMsgId, callback, 0);
    }

    static void sendMessage(final String text, final long peerId, final int replyToMsgId, final LuaValue callback, final long senderId) {
        org.telegram.messenger.AndroidUtilities.runOnUIThread(() -> {
            try {
                int acc = UserConfig.selectedAccount;
                if (senderId > 0 && senderId != UserConfig.getInstance(acc).getClientUserId()) {
                    for (int i = 0; i < UserConfig.MAX_ACCOUNT_COUNT; i++) {
                        if (UserConfig.getInstance(i).isClientActivated() && UserConfig.getInstance(i).getClientUserId() == senderId) {
                            acc = i;
                            break;
                        }
                    }
                }
                final int account = acc;
                final MessagesController mc = MessagesController.getInstance(account);
                // dialogId convention: positive = user, negative = chat/channel.
                final long dialogId = peerId;
                if (mc.getInputPeer(dialogId) != null) {
                    doSendMessage(account, mc, dialogId, text, replyToMsgId, callback);
                    return;
                }
                // Peer not in memory (common for private chats we haven't
                // opened this session). Resolve via DB → network, then send.
                ensurePeerLoaded(account, dialogId, () -> {
                    if (mc.getInputPeer(dialogId) == null) {
                        Log.e("XenonPlugin", "sendMessage: could not resolve peer " + dialogId);
                        if (callback != null && callback.isfunction()) {
                            failCallback(callback, "could not resolve peer");
                        }
                        return;
                    }
                    doSendMessage(account, mc, dialogId, text, replyToMsgId, callback);
                });
            } catch (Exception e) {
                FileLog.e("Plugin sendMessage failed", e);
                Log.e("XenonPlugin", "sendMessage exception: " + e.getMessage());
                if (callback != null && callback.isfunction()) {
                    failCallback(callback, e.getMessage());
                }
            }
        });
    }

    private static void loadChat(int account, long chatId, Runnable callback) {
        TLRPC.TL_messages_getChats req = new TLRPC.TL_messages_getChats();
        req.id.add(chatId);
        ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> {
            if (error == null && response instanceof TLRPC.messages_Chats) {
                TLRPC.messages_Chats chats = (TLRPC.messages_Chats) response;
                for (TLRPC.Chat chat : chats.chats) {
                    MessagesController.getInstance(account).putChat(chat, false);
                }
            } else {
                Log.e("XenonPlugin", "loadChat error: " + (error != null ? error.text : "bad response"));
            }
            callback.run();
        });
    }

    private static void loadUser(int account, long userId, Runnable callback) {
        TLRPC.TL_users_getUsers req = new TLRPC.TL_users_getUsers();
        TLRPC.TL_inputUser inputUser = new TLRPC.TL_inputUser();
        inputUser.user_id = (int) userId;
        inputUser.access_hash = 0;
        req.id.add(inputUser);
        ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> {
            if (error == null && response instanceof org.telegram.tgnet.Vector) {
                org.telegram.tgnet.Vector vector = (org.telegram.tgnet.Vector) response;
                for (Object obj : vector.objects) {
                    if (obj instanceof TLRPC.User) {
                        MessagesController.getInstance(account).putUser((TLRPC.User) obj, false);
                    }
                }
            } else {
                Log.e("XenonPlugin", "loadUser error: " + (error != null ? error.text : "bad response"));
            }
            callback.run();
        });
    }

    private static void doSendMessage(int account, MessagesController mc, long dialogId, String text, int replyToMsgId, LuaValue callback) {
        TLRPC.InputPeer inputPeer = mc.getInputPeer(dialogId);
        if (inputPeer == null) {
            Log.e("XenonPlugin", "doSendMessage: getInputPeer returned null for dialogId=" + dialogId);
            if (callback != null && callback.isfunction()) {
                failCallback(callback, "peer not available");
            }
            return;
        }
        TLRPC.TL_messages_sendMessage req = new TLRPC.TL_messages_sendMessage();
        req.peer = inputPeer;
        req.message = text;
        req.random_id = Utilities.random.nextLong();
        req.clear_draft = true;
        if (replyToMsgId > 0) {
            TLRPC.TL_inputReplyToMessage replyTo = new TLRPC.TL_inputReplyToMessage();
            replyTo.reply_to_msg_id = replyToMsgId;
            req.reply_to = replyTo;
            req.flags |= 1;
        }
        ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> {
            int sentMsgId = 0;
            if (error != null) {
                FileLog.e("Plugin sendMessage error: " + error.text);
                Log.e("XenonPlugin", "sendMessage error: " + error.text);
            } else if (response instanceof TLRPC.Updates) {
                TLRPC.Updates updates = (TLRPC.Updates) response;
                mc.processUpdates(updates, false);
                // Extract the server-assigned message id from the updates.
                sentMsgId = extractMessageIdFromUpdates(updates, req.random_id);
            }
            if (callback != null && callback.isfunction()) {
                final int finalId = sentMsgId;
                org.telegram.messenger.AndroidUtilities.runOnUIThread(() -> {
                    if (finalId > 0) {
                        callback.call(LuaValue.valueOf(finalId));
                    } else {
                        callback.call(LuaValue.NIL);
                    }
                });
            }
        });
    }

    /**
     * Pull the final server message id out of a sendMessage response.
     * The Updates contain either a TL_updateMessageID (id + random_id) or a
     * TL_updateNewMessage/TL_updateNewChannelMessage whose message.id is the id.
     */
    private static int extractMessageIdFromUpdates(TLRPC.Updates updates, long randomId) {
        if (updates == null || updates.updates == null) return 0;
        // First pass: look for updateMessageID matching our random_id.
        for (TLRPC.Update u : updates.updates) {
            if (u instanceof org.telegram.tgnet.tl.TL_update.TL_updateMessageID
                    && ((org.telegram.tgnet.tl.TL_update.TL_updateMessageID) u).random_id == randomId) {
                return ((org.telegram.tgnet.tl.TL_update.TL_updateMessageID) u).id;
            }
        }
        // Fallback: look for updateNewMessage / updateNewChannelMessage.
        for (TLRPC.Update u : updates.updates) {
            if (u instanceof org.telegram.tgnet.tl.TL_update.TL_updateNewMessage) {
                return ((org.telegram.tgnet.tl.TL_update.TL_updateNewMessage) u).message.id;
            }
            if (u instanceof org.telegram.tgnet.tl.TL_update.TL_updateNewChannelMessage) {
                return ((org.telegram.tgnet.tl.TL_update.TL_updateNewChannelMessage) u).message.id;
            }
        }
        return 0;
    }

    static void sendMedia(final long chatId, final int msgId, final int replyToMsgId) {
        org.telegram.messenger.AndroidUtilities.runOnUIThread(() -> {
            try {
                int account = UserConfig.selectedAccount;
                final MessagesController mc = MessagesController.getInstance(account);
                final long dialogId = chatId;
                if (mc.getInputPeer(dialogId) != null) {
                    fetchMessageAndSendMedia(account, mc, dialogId, chatId, msgId, replyToMsgId);
                    return;
                }
                ensurePeerLoaded(account, dialogId, () -> {
                    if (mc.getInputPeer(dialogId) == null) {
                        Log.e("XenonPlugin", "sendMedia: could not resolve peer " + dialogId);
                        return;
                    }
                    fetchMessageAndSendMedia(account, mc, dialogId, chatId, msgId, replyToMsgId);
                });
            } catch (Exception e) {
                FileLog.e("Plugin sendMedia failed", e);
                Log.e("XenonPlugin", "sendMedia exception: " + e.getMessage());
            }
        });
    }

    private static void fetchMessageAndSendMedia(int account, MessagesController mc, long dialogId, long sourceChatId, int sourceMsgId, int replyToMsgId) {
        TLRPC.Chat chat = null;
        if (sourceChatId < 0) {
            chat = mc.getChat(-sourceChatId);
        }
        boolean isChannel = chat != null && chat instanceof TLRPC.TL_channel;
        TLObject req;
        if (isChannel) {
            TLRPC.TL_channels_getMessages r = new TLRPC.TL_channels_getMessages();
            r.channel = mc.getInputChannel(-sourceChatId);
            r.id.add(sourceMsgId);
            req = r;
        } else {
            TLRPC.TL_messages_getMessages r = new TLRPC.TL_messages_getMessages();
            r.id.add(sourceMsgId);
            req = r;
        }
        ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> {
            if (error == null && response instanceof TLRPC.messages_Messages) {
                TLRPC.messages_Messages msgs = (TLRPC.messages_Messages) response;
                if (!msgs.messages.isEmpty()) {
                    TLRPC.Message msg = msgs.messages.get(0);
                    if (msg.media != null) {
                        doSendMedia(account, mc, dialogId, msg, replyToMsgId);
                        return;
                    }
                }
            }
            Log.e("XenonPlugin", "sendMedia: failed to fetch source message " + sourceMsgId);
        });
    }

    private static void doSendMedia(int account, MessagesController mc, long dialogId, TLRPC.Message msg, int replyToMsgId) {
        TLRPC.InputPeer inputPeer = mc.getInputPeer(dialogId);
        if (inputPeer == null) {
            Log.e("XenonPlugin", "doSendMedia: getInputPeer returned null for dialogId=" + dialogId);
            return;
        }
        TLRPC.InputMedia inputMedia = null;
        if (msg.media instanceof TLRPC.TL_messageMediaPhoto) {
            TLRPC.TL_messageMediaPhoto photoMedia = (TLRPC.TL_messageMediaPhoto) msg.media;
            if (photoMedia.photo != null && !(photoMedia.photo instanceof TLRPC.TL_photoEmpty)) {
                TLRPC.TL_inputMediaPhoto media = new TLRPC.TL_inputMediaPhoto();
                TLRPC.TL_inputPhoto inputPhoto = new TLRPC.TL_inputPhoto();
                inputPhoto.id = photoMedia.photo.id;
                inputPhoto.access_hash = photoMedia.photo.access_hash;
                inputPhoto.file_reference = photoMedia.photo.file_reference;
                media.id = inputPhoto;
                inputMedia = media;
            }
        } else if (msg.media instanceof TLRPC.TL_messageMediaDocument) {
            TLRPC.TL_messageMediaDocument docMedia = (TLRPC.TL_messageMediaDocument) msg.media;
            if (docMedia.document != null) {
                TLRPC.TL_inputMediaDocument media = new TLRPC.TL_inputMediaDocument();
                TLRPC.TL_inputDocument inputDoc = new TLRPC.TL_inputDocument();
                inputDoc.id = docMedia.document.id;
                inputDoc.access_hash = docMedia.document.access_hash;
                inputDoc.file_reference = docMedia.document.file_reference;
                media.id = inputDoc;
                inputMedia = media;
            }
        }
        if (inputMedia == null) {
            Log.e("XenonPlugin", "doSendMedia: unsupported media type or empty media");
            return;
        }
        TLRPC.TL_messages_sendMedia req = new TLRPC.TL_messages_sendMedia();
        req.peer = inputPeer;
        req.media = inputMedia;
        req.message = msg.message != null ? msg.message : "";
        req.random_id = Utilities.random.nextLong();
        req.clear_draft = true;
        if (replyToMsgId > 0) {
            TLRPC.TL_inputReplyToMessage replyTo = new TLRPC.TL_inputReplyToMessage();
            replyTo.reply_to_msg_id = replyToMsgId;
            req.reply_to = replyTo;
            req.flags |= 1;
        }
        ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> {
            if (error != null) {
                FileLog.e("Plugin sendMedia error: " + error.text);
                Log.e("XenonPlugin", "sendMedia error: " + error.text);
            } else if (response instanceof TLRPC.Updates) {
                mc.processUpdates((TLRPC.Updates) response, false);
            }
        });
    }

    static void setReaction(long chatId, int msgId, String reaction) {
        AndroidUtilities.runOnUIThread(() -> {
            try {
                int account = UserConfig.selectedAccount;
                MessagesController mc = MessagesController.getInstance(account);
                TLRPC.TL_messages_sendReaction req = new TLRPC.TL_messages_sendReaction();
                req.peer = mc.getInputPeer(chatId);
                if (req.peer == null) {
                    Log.e("XenonPlugin", "setReaction: failed to get input peer");
                    return;
                }
                req.msg_id = msgId;
                if (reaction != null && !reaction.isEmpty()) {
                    TLRPC.TL_reactionEmoji r = new TLRPC.TL_reactionEmoji();
                    r.emoticon = reaction;
                    req.reaction.add(r);
                    req.flags |= 1;
                }
                ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> {
                    if (error != null) {
                        FileLog.e("Plugin setReaction error: " + error.text);
                        Log.e("XenonPlugin", "setReaction error: " + error.text);
                    } else if (response instanceof TLRPC.Updates) {
                        mc.processUpdates((TLRPC.Updates) response, false);
                    }
                });
            } catch (Exception e) {
                FileLog.e("Plugin setReaction failed", e);
                Log.e("XenonPlugin", "setReaction exception: " + e.getMessage());
            }
        });
    }

    static void readHistory(long chatId) {
        AndroidUtilities.runOnUIThread(() -> {
            try {
                int account = UserConfig.selectedAccount;
                MessagesController mc = MessagesController.getInstance(account);
                TLRPC.InputPeer peer = mc.getInputPeer(chatId);
                if (peer == null) {
                    Log.e("XenonPlugin", "readHistory: failed to get input peer for " + chatId);
                    return;
                }
                int maxId = Integer.MAX_VALUE;
                TLRPC.TL_messages_readHistory req = new TLRPC.TL_messages_readHistory();
                req.peer = peer;
                req.max_id = maxId;
                Log.d("XenonPlugin", "readHistory: sending for chatId=" + chatId + " peer=" + peer);
                ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> {
                    if (error != null) {
                        Log.e("XenonPlugin", "readHistory error: " + error.text + " (code=" + error.code + ")");
                    } else {
                        Log.d("XenonPlugin", "readHistory success: " + response);
                    }
                });
            } catch (Exception e) {
                FileLog.e("Plugin readHistory failed", e);
                Log.e("XenonPlugin", "readHistory exception: " + e.getMessage());
            }
        });
    }

    static void deleteMessage(final long chatId, final int msgId) {
        AndroidUtilities.runOnUIThread(() -> {
            try {
                int account = UserConfig.selectedAccount;
                final MessagesController mc = MessagesController.getInstance(account);
                Runnable doDelete = () -> {
                    TLRPC.Chat chat = null;
                    if (chatId < 0) {
                        chat = mc.getChat(-chatId);
                    }
                    boolean isChannel = chat != null && chat instanceof TLRPC.TL_channel;
                    if (isChannel) {
                        TLRPC.InputChannel channel = mc.getInputChannel(-chatId);
                        if (channel == null) {
                            Log.e("XenonPlugin", "deleteMessage: input channel null for " + chatId);
                            return;
                        }
                        TLRPC.TL_channels_deleteMessages req = new TLRPC.TL_channels_deleteMessages();
                        req.channel = channel;
                        req.id.add(msgId);
                        ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> {
                            if (error != null) {
                                Log.e("XenonPlugin", "deleteMessage error: " + error.text);
                            } else {
                                Log.d("XenonPlugin", "deleteMessage success (channel)");
                            }
                        });
                    } else {
                        TLRPC.InputPeer peer = mc.getInputPeer(chatId);
                        if (peer == null) {
                            Log.e("XenonPlugin", "deleteMessage: input peer null for " + chatId);
                            return;
                        }
                        TLRPC.TL_messages_deleteMessages req = new TLRPC.TL_messages_deleteMessages();
                        req.id.add(msgId);
                        req.revoke = true;
                        ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> {
                            if (error != null) {
                                Log.e("XenonPlugin", "deleteMessage error: " + error.text);
                            } else {
                                Log.d("XenonPlugin", "deleteMessage success");
                            }
                        });
                    }
                };
                if (mc.getInputPeer(chatId) == null) {
                    ensurePeerLoaded(account, chatId, doDelete);
                } else {
                    doDelete.run();
                }
            } catch (Exception e) {
                FileLog.e("Plugin deleteMessage failed", e);
                Log.e("XenonPlugin", "deleteMessage exception: " + e.getMessage());
            }
        });
    }

    static String getCachedPeerName(long chatId) {
        int account = UserConfig.selectedAccount;
        MessagesController mc = MessagesController.getInstance(account);
        if (chatId < 0) {
            TLRPC.Chat chat = mc.getChat(-chatId);
            if (chat != null) {
                return chat.title;
            }
        } else {
            TLRPC.User user = mc.getUser(chatId);
            if (user != null) {
                String name = user.first_name;
                if (user.last_name != null && !user.last_name.isEmpty()) {
                    name += " " + user.last_name;
                }
                return name;
            }
            TLRPC.Chat chat = mc.getChat(chatId);
            if (chat != null) {
                return chat.title;
            }
        }
        return null;
    }

    static void openActivity(final String name) {
        org.telegram.messenger.AndroidUtilities.runOnUIThread(() -> {
            try {
                Activity a = PluginManager.getCurrentActivity();
                if (a == null) return;
                Intent intent;
                switch (name) {
                    case "settings":
                        intent = new Intent(a, org.telegram.ui.LaunchActivity.class);
                        intent.setAction("neko_settings");
                        break;
                    case "plugins":
                        intent = new Intent(a, org.telegram.ui.LaunchActivity.class);
                        intent.setAction("neko_plugins");
                        break;
                    default:
                        Log.e("XenonPlugin", "openActivity: unknown activity '" + name + "'");
                        return;
                }
                a.startActivity(intent);
            } catch (Exception e) {
                FileLog.e(e);
            }
        });
    }

    private static void openPluginSettingsFor(final String fileName) {
        org.telegram.messenger.AndroidUtilities.runOnUIThread(() -> {
            try {
                Activity a = PluginManager.getCurrentActivity();
                if (!(a instanceof org.telegram.ui.LaunchActivity)) return;
                org.telegram.ui.LaunchActivity launch = (org.telegram.ui.LaunchActivity) a;
                PluginManager.LoadedPlugin plugin = PluginManager.getInstance().findPlugin(fileName);
                if (plugin != null) {
                    launch.presentFragment(new zxc.iconic.xenon.settings.PluginSettingsActivity().setPlugin(plugin));
                }
            } catch (Exception e) {
                FileLog.e(e);
            }
        });
    }

    static void promptText(final String title, final String hint, final LuaValue callback) {
        org.telegram.messenger.AndroidUtilities.runOnUIThread(() -> {
            try {
                Activity a = PluginManager.getCurrentActivity();
                if (a == null) return;
                AlertDialog.Builder builder = new AlertDialog.Builder(a);
                if (title != null && !title.isEmpty()) builder.setTitle(title);
                LinearLayout container = new LinearLayout(a);
                container.setOrientation(LinearLayout.VERTICAL);
                container.setPadding(AndroidUtilities.dp(24), AndroidUtilities.dp(16), AndroidUtilities.dp(24), 0);
                EditTextBoldCursor editText = new EditTextBoldCursor(a);
                editText.setTextSize(16);
                editText.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
                if (hint != null && !hint.isEmpty()) {
                    editText.setHint(hint);
                    editText.setHintTextColor(Theme.getColor(Theme.key_dialogTextGray));
                }
                container.addView(editText, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
                builder.setView(container);
                builder.setPositiveButton(LocaleController.getString("OK", R.string.OK), (dialog, which) -> {
                    String val = editText.getText().toString();
                    if (callback != null && callback.isfunction()) {
                        callback.call(LuaValue.valueOf(val));
                    }
                });
                builder.setNegativeButton(LocaleController.getString("Cancel", R.string.Cancel), (dialog, which) -> {
                    if (callback != null && callback.isfunction()) {
                        callback.call(LuaValue.NIL);
                    }
                });
                AlertDialog dialog = builder.show();
                editText.requestFocus();
                AndroidUtilities.showKeyboard(editText);
            } catch (Exception e) {
                FileLog.e(e);
            }
        });
    }

    static void createDialog(final String title, final String message, final LuaValue buttons) {
        org.telegram.messenger.AndroidUtilities.runOnUIThread(() -> {
            try {
                Activity a = PluginManager.getCurrentActivity();
                if (a == null) return;
                if (zxc.iconic.xenon.NekoConfig.replaceDialogsWithSheet) {
                    showSheetAsDialog(a, title, message, buttons);
                } else {
                    AlertDialog.Builder builder = new AlertDialog.Builder(a);
                    if (title != null && !title.isEmpty()) builder.setTitle(title);
                    if (message != null && !message.isEmpty()) builder.setMessage(message);
                    if (buttons.istable()) {
                        LuaTable t = (LuaTable) buttons;
                        if (t.length() > 0) {
                            LuaValue pos = t.get(1);
                            if (!pos.isnil()) {
                                String text = pos.optjstring("OK");
                                LuaValue cb = t.get("callback1");
                                builder.setPositiveButton(text, (dialog, which) -> {
                                    if (cb != null && !cb.isnil()) cb.call();
                                });
                            }
                        }
                        if (t.length() > 1) {
                            LuaValue neg = t.get(2);
                            if (!neg.isnil()) {
                                String text = neg.optjstring("Cancel");
                                LuaValue cb = t.get("callback2");
                                builder.setNegativeButton(text, (dialog, which) -> {
                                    if (cb != null && !cb.isnil()) cb.call();
                                });
                            }
                        }
                    }
                    builder.show();
                }
            } catch (Exception e) {
                FileLog.e(e);
            }
        });
    }

    private static void showSheetAsDialog(Activity a, String title, String message, LuaValue buttons) {
        org.telegram.ui.ActionBar.BottomSheet.Builder builder = new org.telegram.ui.ActionBar.BottomSheet.Builder(a, false, null);
        LinearLayout layout = new LinearLayout(a);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(AndroidUtilities.dp(24), AndroidUtilities.dp(16), AndroidUtilities.dp(24), AndroidUtilities.dp(16));
        if (title != null && !title.isEmpty()) {
            TextView tv = new TextView(a);
            tv.setText(title);
            tv.setTextSize(18);
            tv.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
            tv.setTypeface(AndroidUtilities.getTypeface("fonts/rmedium.ttf"));
            layout.addView(tv);
        }
        if (message != null && !message.isEmpty()) {
            TextView mv = new TextView(a);
            mv.setText(message);
            mv.setTextSize(15);
            mv.setTextColor(Theme.getColor(Theme.key_dialogTextGray));
            mv.setPadding(0, AndroidUtilities.dp(8), 0, AndroidUtilities.dp(16));
            layout.addView(mv);
        }
        final org.telegram.ui.ActionBar.BottomSheet[] sheetRef = new org.telegram.ui.ActionBar.BottomSheet[1];
        if (buttons.istable()) {
            LuaTable t = (LuaTable) buttons;
            if (t.length() > 0) {
                LuaValue pos = t.get(1);
                if (!pos.isnil()) {
                    String text = pos.optjstring("OK");
                    LuaValue cb = t.get("callback1");
                    TextView btn = new TextView(a);
                    btn.setText(text);
                    btn.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText));
                    btn.setTextSize(16);
                    btn.setGravity(android.view.Gravity.CENTER);
                    btn.setPadding(0, AndroidUtilities.dp(12), 0, AndroidUtilities.dp(12));
                    final LuaValue callback = cb;
                    btn.setOnClickListener(v -> {
                        if (callback != null && !callback.isnil()) callback.call();
                        if (sheetRef[0] != null) sheetRef[0].dismiss();
                    });
                    layout.addView(btn);
                }
            }
            if (t.length() > 1) {
                LuaValue neg = t.get(2);
                if (!neg.isnil()) {
                    String text = neg.optjstring("Cancel");
                    LuaValue cb = t.get("callback2");
                    TextView btn = new TextView(a);
                    btn.setText(text);
                    btn.setTextColor(Theme.getColor(Theme.key_dialogTextGray2));
                    btn.setTextSize(16);
                    btn.setGravity(android.view.Gravity.CENTER);
                    btn.setPadding(0, AndroidUtilities.dp(12), 0, AndroidUtilities.dp(12));
                    final LuaValue callback = cb;
                    btn.setOnClickListener(v -> {
                        if (callback != null && !callback.isnil()) callback.call();
                        if (sheetRef[0] != null) sheetRef[0].dismiss();
                    });
                    layout.addView(btn);
                }
            }
        }
        builder.setCustomView(layout);
        sheetRef[0] = builder.show();
    }

    static void createBottomSheet(final String title, final LuaValue items) {
        org.telegram.messenger.AndroidUtilities.runOnUIThread(() -> {
            try {
                Activity a = PluginManager.getCurrentActivity();
                if (a == null) return;
                org.telegram.ui.ActionBar.BottomSheet.Builder builder = new org.telegram.ui.ActionBar.BottomSheet.Builder(a, false, null);
                LinearLayout layout = new LinearLayout(a);
                layout.setOrientation(LinearLayout.VERTICAL);
                layout.setPadding(AndroidUtilities.dp(24), AndroidUtilities.dp(16), AndroidUtilities.dp(24), AndroidUtilities.dp(16));
                if (title != null && !title.isEmpty()) {
                    TextView tv = new TextView(a);
                    tv.setText(title);
                    tv.setTextSize(18);
                    tv.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
                    tv.setTypeface(AndroidUtilities.getTypeface("fonts/rmedium.ttf"));
                    tv.setPadding(0, 0, 0, AndroidUtilities.dp(12));
                    layout.addView(tv);
                }
                final org.telegram.ui.ActionBar.BottomSheet[] sheetRef = new org.telegram.ui.ActionBar.BottomSheet[1];
                if (items.istable()) {
                    LuaTable t = (LuaTable) items;
                    for (int i = 1; i <= t.length(); i++) {
                        LuaValue entry = t.get(i);
                        if (!entry.istable()) continue;
                        LuaValue cb = entry.get("callback");
                        TextView row = new TextView(a);
                        row.setText(entry.get("text").optjstring(""));
                        row.setTextSize(16);
                        row.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
                        row.setPadding(0, AndroidUtilities.dp(10), 0, AndroidUtilities.dp(10));
                        if (cb != null && !cb.isnil()) {
                            final LuaValue onClick = cb;
                            row.setOnClickListener(v -> {
                                onClick.call();
                                if (sheetRef[0] != null) sheetRef[0].dismiss();
                            });
                        }
                        layout.addView(row);
                        if (i < t.length()) {
                            View sep = new View(a);
                            sep.setBackgroundColor(Theme.getColor(Theme.key_divider));
                            sep.setLayoutParams(new android.widget.FrameLayout.LayoutParams(
                                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT, AndroidUtilities.dp(1)));
                            layout.addView(sep);
                        }
                    }
                }
                builder.setCustomView(layout);
                sheetRef[0] = builder.show();
            } catch (Exception e) {
                FileLog.e(e);
            }
        });
    }

    private static void openChatPicker(LuaValue callback, String filter) {
        org.telegram.messenger.AndroidUtilities.runOnUIThread(() -> {
            try {
                Activity a = PluginManager.getCurrentActivity();
                Log.d("XenonPlugin", "openChatPicker: activity=" + a + " filter=" + filter);
                if (!(a instanceof org.telegram.ui.LaunchActivity)) {
                    Log.d("XenonPlugin", "openChatPicker: activity not LaunchActivity, returning");
                    return;
                }
                org.telegram.ui.LaunchActivity launch = (org.telegram.ui.LaunchActivity) a;
                android.os.Bundle args = new android.os.Bundle();
                args.putBoolean("onlySelect", true);
                args.putBoolean("checkCanWrite", false);
                args.putBoolean("allowSwitchAccount", false);
                int dialogsType = org.telegram.ui.DialogsActivity.DIALOGS_TYPE_DEFAULT;
                if ("users".equals(filter)) {
                    dialogsType = org.telegram.ui.DialogsActivity.DIALOGS_TYPE_USERS_ONLY;
                } else if ("channels".equals(filter)) {
                    dialogsType = org.telegram.ui.DialogsActivity.DIALOGS_TYPE_CHANNELS_ONLY;
                } else if ("groups".equals(filter)) {
                    dialogsType = org.telegram.ui.DialogsActivity.DIALOGS_TYPE_GROUPS_ONLY;
                }
                args.putInt("dialogsType", dialogsType);
                DialogsActivity fragment = new DialogsActivity(args);
                fragment.setDelegate((fragment1, dids, message, param, notify, scheduleDate, scheduleRepeatPeriod, topicsFragment) -> {
                    if (dids != null && !dids.isEmpty()) {
                        long dialogId = dids.get(0).dialogId;
                        if (callback.isfunction()) {
                            callback.call(LuaValue.valueOf(dialogId));
                        }
                    }
                    fragment1.finishFragment();
                    return true;
                });
                Log.d("XenonPlugin", "openChatPicker: presenting fragment");
                launch.presentFragment(fragment);
                Log.d("XenonPlugin", "openChatPicker: fragment presented");
            } catch (Exception e) {
                FileLog.e("openChatPicker exception", e);
                Log.e("XenonPlugin", "openChatPicker exception: " + e.getMessage());
            }
        });
    }

    private static void getMessageById(long chatId, int messageId, LuaValue callback) {
        int account = UserConfig.selectedAccount;
        MessagesController mc = MessagesController.getInstance(account);
        TLRPC.Chat chat = null;
        if (chatId < 0) {
            chat = mc.getChat(-chatId);
        }
        boolean isChannel = chat != null && chat instanceof TLRPC.TL_channel;
        if (isChannel) {
            TLRPC.InputChannel inputChannel = mc.getInputChannel(-chatId);
            if (inputChannel == null) {
                // Channel not in memory: load it first so we don't silently
                // drop the callback with a null InputChannel.
                loadChat(account, -chatId, () -> {
                    TLRPC.InputChannel ic = MessagesController.getInstance(account).getInputChannel(-chatId);
                    if (ic == null) {
                        failCallback(callback, "chat not loaded");
                        return;
                    }
                    fetchMessagesById(account, true, ic, chatId, messageId, callback);
                });
                return;
            }
            fetchMessagesById(account, true, inputChannel, chatId, messageId, callback);
        } else {
            fetchMessagesById(account, false, null, chatId, messageId, callback);
        }
    }

    private static void fetchMessagesById(int account, boolean isChannel, TLRPC.InputChannel inputChannel, long chatId, int messageId, LuaValue callback) {
        try {
            TLObject req;
            if (isChannel) {
                TLRPC.TL_channels_getMessages r = new TLRPC.TL_channels_getMessages();
                r.channel = inputChannel;
                r.id.add(messageId);
                req = r;
            } else {
                TLRPC.TL_messages_getMessages r = new TLRPC.TL_messages_getMessages();
                r.id.add(messageId);
                req = r;
            }
            ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> {
                LuaValue result;
                if (response instanceof TLRPC.messages_Messages) {
                    result = messagesToLuaTable((TLRPC.messages_Messages) response);
                } else {
                    result = LuaValue.NIL;
                }
                LuaValue err = error != null ? LuaValue.valueOf(error.text) : LuaValue.NIL;
                final LuaValue resultFinal = result;
                final LuaValue errFinal = err;
                org.telegram.messenger.AndroidUtilities.runOnUIThread(() -> {
                    if (callback.isfunction()) callback.call(resultFinal, errFinal);
                });
            });
        } catch (Exception e) {
            FileLog.e("Plugin getMessageById failed", e);
            failCallback(callback, e.getMessage());
        }
    }

    /** Always invoke a Lua callback with (nil, errMsg) on the UI thread. */
    private static void failCallback(LuaValue callback, String message) {
        if (callback == null || !callback.isfunction()) return;
        final String msg = message != null ? message : "unknown error";
        org.telegram.messenger.AndroidUtilities.runOnUIThread(() -> callback.call(LuaValue.NIL, LuaValue.valueOf(msg)));
    }

    private static void getRecentMessages(long chatId, int count, LuaValue callback) {
        int account = UserConfig.selectedAccount;
        MessagesController mc = MessagesController.getInstance(account);
        TLRPC.InputPeer peer = mc.getInputPeer(chatId);
        if (peer == null) {
            // Peer not in memory. Try to load it so the request actually goes
            // out instead of dying on a null peer (which gives the silent
            // "callback never fires" failure).
            ensurePeerLoaded(account, chatId, () -> doFetchRecentMessages(account, chatId, count, callback));
            return;
        }
        doFetchRecentMessages(account, chatId, count, callback);
    }

    private static void doFetchRecentMessages(int account, long chatId, int count, LuaValue callback) {
        try {
            MessagesController mc = MessagesController.getInstance(account);
            TLRPC.InputPeer peer = mc.getInputPeer(chatId);
            if (peer == null) {
                failCallback(callback, "peer not available");
                return;
            }
            TLRPC.TL_messages_getHistory req = new TLRPC.TL_messages_getHistory();
            req.peer = peer;
            req.limit = count;
            req.offset_id = 0;
            req.offset_date = 0;
            req.add_offset = 0;
            ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> {
                LuaValue result;
                if (response instanceof TLRPC.messages_Messages) {
                    TLRPC.messages_Messages msgs = (TLRPC.messages_Messages) response;
                    result = messagesToLuaTable(msgs);
                } else {
                    result = LuaValue.NIL;
                }
                LuaValue err = error != null ? LuaValue.valueOf(error.text) : LuaValue.NIL;
                final LuaValue resultFinal = result;
                final LuaValue errFinal = err;
                org.telegram.messenger.AndroidUtilities.runOnUIThread(() -> {
                    if (callback.isfunction()) callback.call(resultFinal, errFinal);
                });
            });
        } catch (Exception e) {
            FileLog.e("Plugin getRecentMessages failed", e);
            failCallback(callback, e.getMessage());
        }
    }

    /**
     * Make sure the user/chat for {@code chatId} is cached in the
     * MessagesController before running {@code then}. Resolves the "silent
     * ignore" case where getInputPeer returns null and downstream requests
     * would NPE on the network thread.
     *
     * <p>Order of resolution: (1) in-memory cache, (2) local database — which
     * holds the correct {@code access_hash}, the bit that makes private chats
     * (positive user ids) actually work, (3) network fetch as a last resort.
     * Without the DB step, users we've never interacted with in-session would
     * always resolve to a null InputPeer.
     */
    private static void ensurePeerLoaded(int account, long chatId, Runnable then) {
        MessagesController mc = MessagesController.getInstance(account);
        if (chatId < 0) {
            long chatIdAbs = -chatId;
            if (mc.getChat(chatIdAbs) != null) { then.run(); return; }
            // DB lookup carries the access_hash needed for channels/supergroups.
            TLRPC.Chat fromDb = MessagesStorage.getInstance(account).getChatSync(chatIdAbs);
            if (fromDb != null) {
                mc.putChat(fromDb, true);
                then.run();
                return;
            }
            loadChat(account, chatIdAbs, then);
        } else {
            if (mc.getUser(chatId) != null) { then.run(); return; }
            if (mc.getChat(chatId) != null) { then.run(); return; }
            TLRPC.User fromDb = MessagesStorage.getInstance(account).getUserSync(chatId);
            if (fromDb != null) {
                mc.putUser(fromDb, true);
                then.run();
                return;
            }
            loadUser(account, chatId, then);
        }
    }

    private static long peerToId(TLRPC.Peer peer) {
        if (peer == null) return 0;
        if (peer.user_id != 0) return peer.user_id;
        if (peer.channel_id != 0) return -peer.channel_id;
        if (peer.chat_id != 0) return -peer.chat_id;
        return 0;
    }

    private static LuaValue messagesToLuaTable(TLRPC.messages_Messages msgs) {
        LuaTable t = new LuaTable();
        int account = UserConfig.selectedAccount;
        long myUserId = UserConfig.getInstance(account).getClientUserId();
        for (int i = 0; i < msgs.messages.size(); i++) {
            TLRPC.Message msg = msgs.messages.get(i);
            LuaTable m = new LuaTable();
            m.set("id", msg.id);
            m.set("text", msg.message != null ? LuaValue.valueOf(msg.message) : LuaValue.NIL);
            m.set("sender_id", LuaValue.valueOf(peerToId(msg.from_id)));
            m.set("chat_id", LuaValue.valueOf(peerToId(msg.peer_id)));
            m.set("date", msg.date);
            // The `out` flag from channels.getMessages / messages.getMessages is
            // NOT reliable: the server often leaves it false even for messages
            // we sent. Derive "is this ours" from from_id against our own id, so
            // plugins (e.g. reply-to-me detection) don't silently misfire.
            boolean isOut = msg.out || (msg.from_id != null && msg.from_id.user_id == myUserId);
            m.set("out", LuaValue.valueOf(isOut));
            if (msg.reply_to != null) {
                m.set("reply_to_msg_id", msg.reply_to.reply_to_msg_id);
            }
            if (msg.media instanceof TLRPC.TL_messageMediaPhoto) {
                m.set("media_type", LuaValue.valueOf("photo"));
            } else if (msg.media instanceof TLRPC.TL_messageMediaDocument) {
                m.set("media_type", LuaValue.valueOf("document"));
                if (((TLRPC.TL_messageMediaDocument) msg.media).document != null) {
                    m.set("size", LuaValue.valueOf((double) ((TLRPC.TL_messageMediaDocument) msg.media).document.size));
                }
            } else if (msg.media != null) {
                m.set("media_type", LuaValue.valueOf("other"));
            }
            t.set(i + 1, m);
        }
        return t;
    }

    private static void getMessagesFromUser(long chatId, int userId, int count, LuaValue callback) {
        int account = UserConfig.selectedAccount;
        MessagesController mc = MessagesController.getInstance(account);
        if (mc.getInputPeer(chatId) == null) {
            ensurePeerLoaded(account, chatId, () -> doFetchMessagesFromUser(account, chatId, userId, count, callback));
            return;
        }
        doFetchMessagesFromUser(account, chatId, userId, count, callback);
    }

    private static void doFetchMessagesFromUser(int account, long chatId, int userId, int count, LuaValue callback) {
        try {
            MessagesController mc = MessagesController.getInstance(account);
            TLRPC.InputPeer peer = mc.getInputPeer(chatId);
            if (peer == null) {
                failCallback(callback, "peer not available");
                return;
            }
            TLRPC.TL_messages_getHistory req = new TLRPC.TL_messages_getHistory();
            req.peer = peer;
            req.limit = count;
            req.offset_id = 0;
            req.offset_date = 0;
            req.add_offset = 0;
            ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> {
                LuaValue result;
                if (response instanceof TLRPC.messages_Messages) {
                    TLRPC.messages_Messages msgs = (TLRPC.messages_Messages) response;
                    LuaTable filtered = new LuaTable();
                    int idx = 1;
                    for (int i = 0; i < msgs.messages.size(); i++) {
                        TLRPC.Message msg = msgs.messages.get(i);
                        long sender = peerToId(msg.from_id);
                        if (sender == userId) {
                            LuaTable m = new LuaTable();
                            m.set("id", msg.id);
                            m.set("text", msg.message != null ? LuaValue.valueOf(msg.message) : LuaValue.NIL);
                            m.set("sender_id", LuaValue.valueOf(sender));
                            m.set("chat_id", LuaValue.valueOf(peerToId(msg.peer_id)));
                            m.set("date", msg.date);
                            m.set("out", LuaValue.valueOf(msg.out));
                            if (msg.reply_to != null) {
                                m.set("reply_to_msg_id", msg.reply_to.reply_to_msg_id);
                            }
                            if (msg.media instanceof TLRPC.TL_messageMediaPhoto) {
                                m.set("media_type", LuaValue.valueOf("photo"));
                            } else if (msg.media instanceof TLRPC.TL_messageMediaDocument) {
                                m.set("media_type", LuaValue.valueOf("document"));
                                if (((TLRPC.TL_messageMediaDocument) msg.media).document != null) {
                                    m.set("size", LuaValue.valueOf((double) ((TLRPC.TL_messageMediaDocument) msg.media).document.size));
                                }
                            } else if (msg.media != null) {
                                m.set("media_type", LuaValue.valueOf("other"));
                            }
                            filtered.set(idx++, m);
                        }
                    }
                    result = filtered;
                } else {
                    result = LuaValue.NIL;
                }
                LuaValue err = error != null ? LuaValue.valueOf(error.text) : LuaValue.NIL;
                final LuaValue resultFinal = result;
                final LuaValue errFinal = err;
                org.telegram.messenger.AndroidUtilities.runOnUIThread(() -> {
                    if (callback.isfunction()) callback.call(resultFinal, errFinal);
                });
            });
        } catch (Exception e) {
            FileLog.e("Plugin getMessagesFromUser failed", e);
            failCallback(callback, e.getMessage());
        }
    }

    private static java.lang.reflect.Field findField(Class<?> clazz, String name) {
        Class<?> current = clazz;
        while (current != null) {
            try {
                java.lang.reflect.Field field = current.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException e) {
                current = current.getSuperclass();
            }
        }
        return null;
    }

    public static int getIconDrawable(String iconName) {
        switch (iconName) {
            case "add": return org.telegram.messenger.R.drawable.msg_addcontact;
            case "autodelete": return org.telegram.messenger.R.drawable.msg_autodelete;
            case "background": return org.telegram.messenger.R.drawable.msg_background;
            case "block": return org.telegram.messenger.R.drawable.msg_block2;
            case "calendar": return org.telegram.messenger.R.drawable.msg_calendar;
            case "call": return org.telegram.messenger.R.drawable.msg_callback;
            case "cancel": return org.telegram.messenger.R.drawable.msg_cancel;
            case "channel": return org.telegram.messenger.R.drawable.msg_channel;
            case "clear": return org.telegram.messenger.R.drawable.msg_clear;
            case "copy": return org.telegram.messenger.R.drawable.msg_copy;
            case "delete": return org.telegram.messenger.R.drawable.msg_delete;
            case "discussion": return org.telegram.messenger.R.drawable.msg_discussion;
            case "edit": return org.telegram.messenger.R.drawable.msg_edit;
            case "fave": case "heart": return org.telegram.messenger.R.drawable.msg_fave;
            case "unfave": return org.telegram.messenger.R.drawable.msg_unfave;
            case "forward": return org.telegram.messenger.R.drawable.msg_forward;
            case "help": return org.telegram.messenger.R.drawable.msg_help;
            case "home": return org.telegram.messenger.R.drawable.msg_home;
            case "info": return org.telegram.messenger.R.drawable.msg_info;
            case "leave": return org.telegram.messenger.R.drawable.msg_leave;
            case "link": return org.telegram.messenger.R.drawable.msg_link;
            case "log": return org.telegram.messenger.R.drawable.msg_log;
            case "mute": return org.telegram.messenger.R.drawable.msg_mute;
            case "pin": return org.telegram.messenger.R.drawable.msg_pin;
            case "report": return org.telegram.messenger.R.drawable.msg_report;
            case "saved": return org.telegram.messenger.R.drawable.msg_saved;
            case "search": return org.telegram.messenger.R.drawable.msg_search;
            case "send": return org.telegram.messenger.R.drawable.msg_send;
            case "settings": return org.telegram.messenger.R.drawable.msg_settings_old;
            case "share": return org.telegram.messenger.R.drawable.msg_share;
            case "stats": return org.telegram.messenger.R.drawable.msg_stats;
            case "sticker": return org.telegram.messenger.R.drawable.msg_sticker;
            case "theme": return org.telegram.messenger.R.drawable.msg_theme;
            case "topic": return org.telegram.messenger.R.drawable.msg_topics;
            case "translate": return org.telegram.messenger.R.drawable.msg_translate;
            case "videocall": return org.telegram.messenger.R.drawable.msg_videocall;
            case "vote": return org.telegram.messenger.R.drawable.msg_unvote;
            default: return org.telegram.messenger.R.drawable.msg_edit;
        }
    }

    private static SharedPreferences getPrefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("xenon_plugins", Context.MODE_PRIVATE);
    }

    static String getSetting(String key, String defaultValue) {
        return getPrefs().getString(key, defaultValue);
    }

    static void setSetting(String key, String value) {
        getPrefs().edit().putString(key, value).apply();
    }

    static void removeSetting(String key) {
        getPrefs().edit().remove(key).apply();
    }

    static void showBulletin(final String text) {
        org.telegram.messenger.AndroidUtilities.runOnUIThread(() -> {
            try {
                BulletinFactory.global().createSimpleBulletin(R.raw.chats_infotip, text).show();
            } catch (Exception e) {
                FileLog.e(e);
            }
        });
    }

    /**
     * Show a bulletin with a tappable button. When the user taps the button,
     * the Lua callback is invoked (no args). Use it for actionable feedback
     * like "Plugin updated. [Undo]" or "Done. [Open]".
     */
    static void showBulletinButton(final String text, final String button, final LuaValue callback) {
        org.telegram.messenger.AndroidUtilities.runOnUIThread(() -> {
            try {
                Runnable action = null;
                if (callback != null && callback.isfunction()) {
                    action = () -> {
                        try {
                            callback.call();
                        } catch (Throwable t) {
                            FileLog.e(t);
                        }
                    };
                }
                BulletinFactory.global().createSimpleBulletin(R.raw.chats_infotip, text, button, action).show();
            } catch (Exception e) {
                FileLog.e(e);
            }
        });
    }

    /**
     * Show a bulletin with a "Copy error" button. Tapping the button copies
     * {@code copyText} to the clipboard and shows a brief "Copied" toast.
     * <p>
     * Intended for the {@link PluginManager#quarantineFile no-activity fallback}
     * path, where we cannot show a full BottomSheet.
     */
    static void showBulletinError(final String text, final String copyText) {
        org.telegram.messenger.AndroidUtilities.runOnUIThread(() -> {
            try {
                BulletinFactory.global().createSimpleBulletin(R.raw.chats_infotip, text,
                        LocaleController.getString(R.string.CopyError), () -> {
                            AndroidUtilities.addToClipboard(copyText);
                            BulletinFactory.global().createCopyBulletin(
                                    LocaleController.getString(R.string.ErrorCopied)).show();
                        }).show();
            } catch (Exception e) {
                FileLog.e(e);
            }
        });
    }

    static void showToast(final String text) {
        org.telegram.messenger.AndroidUtilities.runOnUIThread(() -> {
            try {
                android.widget.Toast.makeText(
                        ApplicationLoader.applicationContext, text, android.widget.Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                FileLog.e(e);
            }
        });
    }
}
