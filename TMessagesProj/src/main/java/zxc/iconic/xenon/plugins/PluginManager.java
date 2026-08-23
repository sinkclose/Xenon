package zxc.iconic.xenon.plugins;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.lib.jse.JsePlatform;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.UserConfig;

import java.io.File;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import zxc.iconic.xenon.NekoConfig;

/**
 * Core of the Xenon plugin engine.
 *
 * <p>Plugins are plain Lua scripts stored as {@code *.xplugin} files inside
 * {@code /xenonplugins/} in app internal storage. Each plugin runs in its own
 * luaj {@link Globals} (sandboxed), with a {@link PluginApi} bound as the global
 * {@code xenon} table. Plugins register handlers for hooks (e.g. {@code onResume},
 * {@code onSendMessage}) by calling {@code xenon.on(...)}.
 *
 * <p>Host code triggers hooks via {@link #fire(String, LuaValue...)} and the
 * specialized helpers ({@link #fireBooleanResult}).
 *
 * <p>Thread-safety: plugin handlers can be invoked from arbitrary threads.
 * luaj Globals are not strictly thread-safe, so each plugin keeps its own
 * Globals and is never shared between concurrent fires. Hook dispatch itself
 * iterates a {@link CopyOnWriteArrayList}, so add/remove during fire is safe.
 */
public class PluginManager {

    public static final String TAG = "XenonPlugin";
    public static final String PLUGINS_DIR = "xenonplugins";
    public static final String PLUGIN_EXT = ".xplugin";

    /**
     * Security scopes a plugin can request via its manifest. {@link #SCOPE_GENERAL}
     * is always granted; {@link #SCOPE_MESSAGING} gates the messaging/query API
     * (sendMessage, deleteMessage, setReaction, readHistory, message queries,
     * watchers). Used by {@link PluginApi} to silently refuse protected calls
     * from plugins that lack the scope. God Mode ({@code NekoConfig.pluginGodMode})
     * bypasses the check.
     */
    public static final String SCOPE_GENERAL = "GENERAL";
    public static final String SCOPE_MESSAGING = "MESSAGING";

    private static volatile PluginManager instance;
    private static WeakReference<Activity> currentActivity = new WeakReference<>(null);
    private static volatile long currentDialogId;
    private static volatile boolean requestFinishFragment;

    public static void setRequestFinishFragment(boolean v) {
        requestFinishFragment = v;
    }

    public static boolean checkRequestFinishFragment() {
        boolean v = requestFinishFragment;
        requestFinishFragment = false;
        return v;
    }

    public static void setCurrentActivity(Activity activity) {
        currentActivity = new WeakReference<>(activity);
    }

    public static Activity getCurrentActivity() {
        return currentActivity.get();
    }

    public static void setCurrentDialogId(long dialogId) {
        currentDialogId = dialogId;
    }

    public static long getCurrentDialogId() {
        return currentDialogId;
    }

    private final CopyOnWriteArrayList<LoadedPlugin> plugins = new CopyOnWriteArrayList<>();
    private volatile boolean initialized;

    // ------------------------------------------------------------------
    // Global engine watchdog
    // ------------------------------------------------------------------

    /**
     * If any hook has been running for longer than this without returning, the
     * engine assumes the UI thread is wedged and force-restarts the process so
     * the next launch boots clean (with plugins disabled by the boot guard).
     * This is a last resort — the per-hook timeout (for fire-and-forget hooks)
     * and Throwable catch (for result hooks) handle most cases. But a plugin
     * that blocks the UI thread (e.g. a synchronous onSendMessage that loops)
     * can't be interrupted from Java, so the only recovery is to kill the
     * process and let Safe Mode take over.
     */
    private static final long ENGINE_WATCHDOG_TIMEOUT_MS = 10_000;
    private static volatile long hookStartTimestamp;
    private static volatile String hookInProgress;
    private static Thread watchdogThread;
    // Set once when the watchdog would have killed the process but automatic
    // Safe Mode is off, so we only log the skip a single time.
    private static volatile boolean watchdogKillSkippedLogged;

    private static void startWatchdogIfNeeded() {
        if (watchdogThread != null && watchdogThread.isAlive()) return;
        hookStartTimestamp = System.currentTimeMillis();
        watchdogThread = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    return;
                }
                if (hookStartTimestamp == 0) continue;
                long elapsed = System.currentTimeMillis() - hookStartTimestamp;
                if (elapsed > ENGINE_WATCHDOG_TIMEOUT_MS) {
                    String hook = hookInProgress;
                    if (!NekoConfig.pluginAutoSafeMode) {
                        // Automatic Safe Mode is off — never kill the process or
                        // disable plugins on our own. If the UI thread is wedged
                        // the user can still recover via the hardware panic switch
                        // (press volume keys 4 times quickly).
                        if (!watchdogKillSkippedLogged) {
                            watchdogKillSkippedLogged = true;
                            org.telegram.messenger.FileLog.e("Plugin engine watchdog: hook '"
                                    + hook + "' has been running for " + elapsed
                                    + "ms — automatic safe mode is off, not killing the process");
                        }
                        continue;
                    }
                    org.telegram.messenger.FileLog.e("Plugin engine watchdog: hook '"
                            + hook + "' has been running for " + elapsed
                            + "ms — killing process to recover");
                    // Try to write a crash note first (best-effort).
                    try {
                        PluginSafeMode.reportPluginFailure(getCurrentActivity(),
                                "unknown", "watchdog: hook '" + hook + "' exceeded "
                                        + ENGINE_WATCHDOG_TIMEOUT_MS + "ms",
                                new java.util.concurrent.TimeoutException(
                                        "Plugin engine watchdog killed the process: hook '"
                                                + hook + "' did not return in "
                                                + ENGINE_WATCHDOG_TIMEOUT_MS + "ms"));
                    } catch (Throwable ignored) {
                    }
                    // Nuclear option: kill the process. Boot guard will disable
                    // plugins on the next launch and show the Safe Mode sheet.
                    System.exit(2);
                }
            }
        }, "XenonPluginWatchdog");
        watchdogThread.setDaemon(true);
        watchdogThread.start();
    }

    /** Mark that a hook is about to run (called by fire/fireReturn). */
    public static void markHookStart(String hookName) {
        hookInProgress = hookName;
        hookStartTimestamp = System.currentTimeMillis();
        startWatchdogIfNeeded();
    }

    /** Mark that a hook finished (called by fire/fireReturn). */
    public static void markHookEnd() {
        hookStartTimestamp = 0;
        hookInProgress = null;
    }

    private PluginManager() {
    }

    private void ensureLoaded() {
        if (initialized) return;
        initialized = true;
        Log.d(TAG, "ensureLoaded: pluginsEnabled=" + isEnabled());
        if (isEnabled()) {
            reloadAll();
        }
    }

    public static PluginManager getInstance() {
        if (instance == null) {
            synchronized (PluginManager.class) {
                if (instance == null) {
                    instance = new PluginManager();
                }
            }
        }
        return instance;
    }

    public static File getPluginsDir() {
        File dir = new File(ApplicationLoader.applicationContext.getFilesDir(), PLUGINS_DIR);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    /**
     * @return unmodifiable snapshot of currently loaded plugins.
     */
    public List<LoadedPlugin> getPlugins() {
        ensureLoaded();
        return Collections.unmodifiableList(new ArrayList<>(plugins));
    }

    /**
     * Lightweight metadata for every {@code .xplugin} file on disk, regardless
     * of whether the engine is enabled or the plugin is active. Used by the UI
     * so the plugins list is always visible (even with the engine off), letting
     * the user toggle/remove plugins before re-enabling the engine.
     */
    public static class PluginInfo {
        public final String fileName;
        public final String name;
        public final String description;
        public final String pluginId;
        public final String author;
        public final String version;
        public final boolean active;

        public PluginInfo(String fileName, String name, String description, String pluginId, String author, String version, boolean active) {
            this.fileName = fileName;
            this.name = name;
            this.description = description;
            this.pluginId = pluginId;
            this.author = author;
            this.version = version;
            this.active = active;
        }
    }

    /**
     * All installed plugins as {@link PluginInfo}, parsed from disk without
     * executing any Lua. Works whether or not the engine is enabled, because the
     * user must be able to manage plugins while the engine is off.
     */
    public List<PluginInfo> getAllPluginInfos() {
        File dir = getPluginsDir();
        File[] files = dir.listFiles((d, name) -> name.endsWith(PLUGIN_EXT));
        List<PluginInfo> result = new ArrayList<>();
        if (files == null) return result;
        for (File file : files) {
            String[] meta = parseMetadata(file);
            String name = meta != null && meta[0] != null ? meta[0] : null;
            String desc = meta != null && meta[1] != null ? meta[1] : null;
            String id = meta != null && meta[2] != null ? meta[2] : null;
            String author = meta != null && meta[3] != null ? meta[3] : null;
            String version = meta != null && meta[4] != null ? meta[4] : null;
            boolean active = false;
            if (isEnabled()) {
                for (LoadedPlugin p : plugins) {
                    if (p.fileName.equals(file.getName())) {
                        active = p.isEnabled();
                        break;
                    }
                }
            }
            result.add(new PluginInfo(file.getName(), name, desc, id, author, version, active));
        }
        return result;
    }

    public LoadedPlugin findPlugin(String fileName) {
        ensureLoaded();
        for (LoadedPlugin p : plugins) {
            if (p.fileName.equals(fileName)) return p;
        }
        return null;
    }

    /**
     * Load a plugin on demand for editing its settings, even when the plugin is
     * disabled or the engine is off. The plugin's Lua runs once (to build its
     * settings schema), but it is NOT added to the active plugins list — so it
     * won't receive hooks until properly enabled. Returns null if loading failed.
     */
    public LoadedPlugin loadPluginForSettings(String fileName) {
        File dir = getPluginsDir();
        File file = new File(dir, fileName);
        if (!file.exists()) return null;
        try {
            return loadFile(file);
        } catch (Throwable t) {
            FileLog.e("loadPluginForSettings failed for " + fileName, t);
            Log.e(TAG, "loadPluginForSettings: " + fileName + " threw: " + t.getMessage());
            return null;
        }
    }

    public LoadedPlugin findByPluginId(String pluginId) {
        if (pluginId == null) return null;
        ensureLoaded();
        for (LoadedPlugin p : plugins) {
            if (pluginId.equals(p.pluginId)) return p;
        }
        return null;
    }

    private static SharedPreferences getPrefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("xenon_plugins", Context.MODE_PRIVATE);
    }

    public boolean isEnabled() {
        return NekoConfig.pluginsEnabled;
    }

    /**
     * Called by {@link NekoConfig#togglePluginsEnabled()} whenever the global
     * plugins toggle flips. Reacts immediately: loads plugins when enabled,
     * unloads them all when disabled (no zombie Globals kept around).
     */
    public void onEnabledChanged() {
        Log.d(TAG, "onEnabledChanged: " + isEnabled());
        if (isEnabled()) {
            reloadAll();
        } else {
            Log.d(TAG, "onEnabledChanged: stopping all plugin code");
            PluginApi.stopAll();
            plugins.clear();
        }
    }

    /**
     * Reload all {@code *.xplugin} files from the plugins directory. Clears any
     * previously loaded plugins first. Safe to call repeatedly (e.g. after the
     * user adds/removes a plugin). No-op when plugins are disabled.
     */
    public void reloadAll() {
        PluginApi.stopAll();
        plugins.clear();
        zxc.iconic.xenon.helpers.CustomBadgeController.getInstance().init();
        if (!isEnabled()) {
            Log.d(TAG, "reloadAll: plugins disabled, skipping");
            return;
        }
        File dir = getPluginsDir();
        Log.d(TAG, "reloadAll: scanning " + dir.getAbsolutePath());
        File[] files = dir.listFiles((d, name) -> name.endsWith(PLUGIN_EXT));
        if (files == null || files.length == 0) {
            Log.d(TAG, "reloadAll: no .xplugin files found in " + dir.getAbsolutePath());
            return;
        }
        Log.d(TAG, "reloadAll: found " + files.length + " plugin file(s)");
        for (File file : files) {
            // Skip plugins the user has toggled off. We still show them in the
            // UI (via getAllPluginInfos, which reads the file without executing),
            // but their Lua code doesn't run and their hooks aren't registered.
            if (!getPrefs().getBoolean("plugin_enabled_" + file.getName(), true)) {
                Log.d(TAG, "reloadAll: skipping disabled plugin " + file.getName());
                continue;
            }
            LoadedPlugin plugin = null;
            try {
                plugin = loadFile(file);
            } catch (Throwable t) {
                // Catch EVERYTHING — Error (StackOverflowError, OutOfMemoryError),
                // RuntimeException, anything. One broken plugin's top-level code
                // must never take the whole app down. Quarantine it and move on.
                FileLog.e("Plugin " + file.getName() + " crashed during load", t);
                Log.e(TAG, "reloadAll: plugin " + file.getName() + " threw during load: "
                        + t.getClass().getSimpleName() + ": " + t.getMessage());
                quarantineFile(file.getName(), "loading", t);
                continue;
            }
            if (plugin != null) {
                plugins.add(plugin);
                Log.d(TAG, "reloadAll: loaded plugin " + plugin.displayName);
            } else {
                Log.e(TAG, "reloadAll: failed to load plugin from " + file.getName());
            }
        }
        Log.d(TAG, "reloadAll: done, " + plugins.size() + " plugin(s) loaded");
    }

    /**
     * Install a plugin from an arbitrary input file (e.g. picked via the system
     * file picker). Copies it into the plugins directory and loads it. Returns
     * the installed plugin, or {@code null} on failure.
     */
    public LoadedPlugin installFrom(File source) {
        if (source == null || !source.exists()) {
            lastParseError = "source file is null or doesn't exist";
            Log.e(TAG, "installFrom: " + lastParseError);
            return null;
        }
        lastInstallEngineOff = false;
        Log.d(TAG, "installFrom: processing " + source.getName());
        // Reject plugins without plugin_id
        String[] meta = parseMetadata(source);
        if (meta == null || meta.length < 3 || meta[2] == null || meta[2].isEmpty()) {
            String err = lastParseError;
            if (err != null) {
                Log.e(TAG, "installFrom: plugin rejected: " + err);
            } else {
                lastParseError = "plugin must have a plugin_id (format: something_something)";
                Log.e(TAG, "installFrom: " + lastParseError);
            }
            return null;
        }
        File dest = new File(getPluginsDir(), source.getName());
        if (!dest.getName().endsWith(PLUGIN_EXT)) {
            lastParseError = dest.getName() + " doesn't end with " + PLUGIN_EXT;
            Log.e(TAG, "installFrom: " + lastParseError);
            return null;
        }
        // If source is already inside the plugins dir, copy would truncate itself
        if (!source.getAbsolutePath().equals(dest.getAbsolutePath())) {
            try (FileInputStream in = new FileInputStream(source);
                 FileOutputStream out = new FileOutputStream(dest)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }
                Log.d(TAG, "installFrom: copied to " + dest.getAbsolutePath());
            } catch (IOException e) {
                FileLog.e(e);
                lastParseError = "copy failed - " + e.getMessage();
                Log.e(TAG, "installFrom: " + lastParseError);
                return null;
            }
        } else {
            Log.d(TAG, "installFrom: source already in plugins dir");
        }
        // Enable in prefs regardless of engine state
        getPrefs().edit().putBoolean("plugin_enabled_" + dest.getName(), true).apply();
        // Parse plugin_id from the file (needed for removeByPluginId even when engine is off)
        String newFileName = dest.getName();
        String[] loadMeta = parseMetadata(dest);
        String loadPluginId = (loadMeta != null && loadMeta.length > 2) ? loadMeta[2] : null;
        if (loadPluginId != null) {
            removeByPluginId(loadPluginId, newFileName);
        }
        // If engine disabled, don't load — plugin activates when engine is turned on
        if (!isEnabled()) {
            lastInstallEngineOff = true;
            Log.d(TAG, "installFrom: engine disabled, postponing load");
            return null;
        }
        LoadedPlugin plugin = loadFile(dest);
        if (plugin != null) {
            // removeByPluginId was already called above with parsed plugin_id,
            // but run it again with the LoadedPlugin to ensure cleanup.
            removeByPluginId(plugin.pluginId, newFileName);
            plugins.add(plugin);
            Log.d(TAG, "installFrom: plugin " + plugin.displayName + " installed and loaded");
        } else {
            Log.e(TAG, "installFrom: plugin file copied but loading failed: " + dest.getName());
        }
        return plugin;
    }

    private void removeByPluginId(String pluginId) {
        removeByPluginId(pluginId, null);
    }

    private void removeByPluginId(String pluginId, String keepFileName) {
        if (pluginId == null) return;
        for (int i = plugins.size() - 1; i >= 0; i--) {
            LoadedPlugin p = plugins.get(i);
            if (pluginId.equals(p.pluginId)) {
                PluginApi.stopAllForPlugin(p.fileName);
                plugins.remove(i);
                File oldFile = new File(getPluginsDir(), p.fileName);
                if (p.fileName.equals(keepFileName)) {
                    Log.d(TAG, "removeByPluginId: unloaded " + p.fileName + " (id=" + pluginId + ")");
                } else {
                    deletePluginFile(oldFile, p.fileName);
                }
            }
        }
        // Also scan filesystem for disabled/unloaded plugins with same plugin_id.
        // A disabled plugin is not in the plugins list, so without this scan,
        // installing a different version would leave the old file on disk → duplication.
        File dir = getPluginsDir();
        File[] files = dir.listFiles((d, name) -> name.endsWith(PLUGIN_EXT));
        if (files != null) {
            for (File file : files) {
                String name = file.getName();
                if (name.equals(keepFileName)) continue;
                boolean alreadyHandled = false;
                for (LoadedPlugin p : plugins) {
                    if (p.fileName.equals(name)) {
                        alreadyHandled = true;
                        break;
                    }
                }
                if (alreadyHandled) continue;
                String[] meta = parseMetadata(file);
                if (meta != null && meta.length > 2 && pluginId.equals(meta[2])) {
                    deletePluginFile(file, name);
                }
            }
        }
    }

    private void deletePluginFile(File file, String fileName) {
        if (file.exists()) file.delete();
        getPrefs().edit().remove("plugin_enabled_" + fileName).apply();
        Log.d(TAG, "removeByPluginId: removed " + fileName);
    }

    /**
     * Remove a plugin by its file name. Unloads it from memory and deletes the
     * underlying {@code .xplugin} file.
     */
    public boolean remove(String fileName) {
        PluginApi.stopAllForPlugin(fileName);
        boolean removed = false;
        for (int i = 0; i < plugins.size(); i++) {
            LoadedPlugin p = plugins.get(i);
            if (p.fileName.equals(fileName)) {
                plugins.remove(i);
                removed = true;
                break;
            }
        }
        File file = new File(getPluginsDir(), fileName);
        if (file.exists()) {
            file.delete();
            Log.d(TAG, "remove: deleted file " + fileName);
        }
        Log.d(TAG, "remove: " + fileName + " removed=" + removed);
        return removed;
    }

    private LoadedPlugin loadFile(File file) {
        Log.d(TAG, "loadFile: loading " + file.getName() + " (" + file.length() + " bytes)");
        String source;
        try (InputStream in = new FileInputStream(file)) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                baos.write(buf, 0, n);
            }
            byte[] full = baos.toByteArray();
            source = full.length > 0 ? new String(full, "UTF-8") : "";
            Log.d(TAG, "loadFile: content (" + full.length + " bytes)");
        } catch (Exception e) {
            lastParseError = "error reading file: " + e.getMessage();
            Log.e(TAG, "loadFile: " + lastParseError);
            return null;
        }
        Globals globals = createSandboxGlobals();
        PluginApi.setCurrentPluginFileName(file.getName());
        // Pre-parse metadata so plugin_id is available before Lua execution
        // for settings keying, scope gating, etc.
        String[] pluginMeta = parseMetadata(file);
        PluginApi.setCurrentPluginId(pluginMeta != null && pluginMeta.length > 2 ? pluginMeta[2] : null);
        // Auto-grant declared scopes BEFORE the plugin's top-level code runs,
        // so protected calls made during load are gated correctly.
        grantScopes(file.getName(), file);
        LuaTable[] tables = PluginApi.createApiTable(globals);
        globals.set("xenon", tables[0]);
        LuaTable hooks = tables[1];
        Log.d(TAG, "loadFile: hooks@" + System.identityHashCode(hooks) + " initialSize=" + hooks.length());
        try {
            globals.load(source, file.getName(), globals).call();
            Log.d(TAG, "loadFile: " + file.getName() + " executed, hooks@" + System.identityHashCode(hooks) + " size=" + hooks.length());
            LuaValue[] hookKeys = hooks.keys();
            if (hookKeys.length > 0) {
                StringBuilder sb = new StringBuilder();
                for (LuaValue k : hookKeys) {
                    if (sb.length() > 0) sb.append(", ");
                    sb.append(k.tojstring());
                }
                Log.d(TAG, "loadFile: registered hooks: [" + sb + "]");
            } else {
                Log.w(TAG, "loadFile: no hooks registered by " + file.getName());
            }
        } catch (LuaError e) {
            FileLog.e("Failed to load plugin " + file.getName(), e);
            lastParseError = e.getMessage();
            Log.e(TAG, "loadFile: " + file.getName() + " failed - " + lastParseError);
            return null;
        }
        // Read metadata from Lua globals
        String pluginName = null;
        String pluginDesc = null;
        String pluginId = null;
        LuaValue nameVal = globals.get("plugin_name");
        if (!nameVal.isnil()) {
            pluginName = nameVal.tojstring();
            if (pluginName.trim().isEmpty()) pluginName = null;
        }
        LuaValue descVal = globals.get("plugin_description");
        if (!descVal.isnil()) {
            pluginDesc = descVal.tojstring();
            if (pluginDesc.trim().isEmpty()) pluginDesc = null;
        }
        LuaValue idVal = globals.get("plugin_id");
        if (!idVal.isnil()) {
            String s = idVal.tojstring().trim();
            if (!s.isEmpty()) pluginId = s;
        }
        String pluginAuthor = null;
        LuaValue authorVal = globals.get("plugin_author");
        if (!authorVal.isnil()) {
            pluginAuthor = authorVal.tojstring().trim();
            if (pluginAuthor.isEmpty()) pluginAuthor = null;
        }
        String pluginVersion = null;
        LuaValue versionVal = globals.get("plugin_version");
        if (!versionVal.isnil()) {
            pluginVersion = sanitizeVersion(versionVal.tojstring());
        }
        // Read settings from Lua globals
        List<LoadedPlugin.PluginSetting> settings = new ArrayList<>();
        LuaValue settingsVal = globals.get("plugin_settings");
        if (settingsVal.istable()) {
            for (int i = 1; i <= settingsVal.length(); i++) {
                LuaValue entry = settingsVal.get(i);
                if (!entry.istable()) continue;
                    try {
                        LuaValue typeVal = entry.get("type");
                        LuaValue keyVal = entry.get("key");
                        LuaValue nameEntry = entry.get("name");
                        if (typeVal.isnil() || keyVal.isnil() || nameEntry.isnil()) continue;
                        String type = typeVal.tojstring();
                        String skey = keyVal.tojstring();
                        String sname = nameEntry.tojstring();
                    LoadedPlugin.PluginSetting setting = null;
                    switch (type) {
                        case "toggle": {
                            boolean def = entry.get("default").toboolean();
                            setting = new LoadedPlugin.PluginSetting(LoadedPlugin.PluginSetting.Type.TOGGLE, skey, sname, def, 0, null, 0, 0, 0, null, null);
                            break;
                        }
                        case "seekbar": {
                            int min = (int) entry.get("min").checkdouble();
                            int max = (int) entry.get("max").checkdouble();
                            int step = (int) entry.get("step").checkdouble();
                            if (step <= 0) step = 1;
                            int def = entry.get("default").isnil() ? min : (int) entry.get("default").checkdouble();
                            setting = new LoadedPlugin.PluginSetting(LoadedPlugin.PluginSetting.Type.SEEKBAR, skey, sname, false, def, null, min, max, step, null, null);
                            break;
                        }
                        case "text": {
                            LuaValue defVal = entry.get("default");
                            String def = defVal.isnil() ? "" : defVal.tojstring();
                            LuaValue hintVal = entry.get("hint");
                            String hint = hintVal.isnil() ? "" : hintVal.tojstring();
                            setting = new LoadedPlugin.PluginSetting(LoadedPlugin.PluginSetting.Type.TEXT, skey, sname, false, 0, def, 0, 0, 0, hint, null);
                            break;
                        }
                        case "button": {
                            LuaValue action = entry.get("action");
                            setting = new LoadedPlugin.PluginSetting(LoadedPlugin.PluginSetting.Type.BUTTON, skey, sname, false, 0, null, 0, 0, 0, null, action != null && !action.isnil() ? action : null);
                            break;
                        }
                        case "list": {
                            // Parse the options array: { "Option A", "Option B", ... }
                            LuaValue optsVal = entry.get("options");
                            java.util.List<String> opts = new java.util.ArrayList<>();
                            if (optsVal.istable()) {
                                for (int oi = 1; oi <= optsVal.length(); oi++) {
                                    LuaValue v = optsVal.get(oi);
                                    if (!v.isnil()) opts.add(v.tojstring());
                                }
                            }
                            String[] optionsArr = opts.toArray(new String[0]);
                            // default is the index (0-based) of the selected option
                            LuaValue defVal = entry.get("default");
                            int defIdx = defVal.isnil() ? 0 : defVal.toint();
                            if (defIdx < 0) defIdx = 0;
                            if (optionsArr.length > 0 && defIdx >= optionsArr.length) defIdx = 0;
                            setting = new LoadedPlugin.PluginSetting(LoadedPlugin.PluginSetting.Type.LIST, skey, sname, false, defIdx, null, 0, 0, 0, null, null, optionsArr);
                            break;
                        }
                    }
                    if (setting != null) {
                        settings.add(setting);
                    }
                } catch (Exception e) {
                    FileLog.e("Failed to parse plugin setting entry " + i + " in " + file.getName(), e);
                }
            }
        }
        return new LoadedPlugin(file.getName(), pluginName, pluginDesc, pluginId, pluginAuthor, pluginVersion, settings, globals, hooks);
    }

    // ------------------------------------------------------------------
    // Hook dispatch
    // ------------------------------------------------------------------

    /**
     * Fire a hook with no expected return value. Calls every registered handler
     * for {@code hookName}, passing the given Lua args. Exceptions in a single
     * plugin are logged and do not stop other plugins or the host.
     */
    public void fire(String hookName, LuaValue... args) {
        ensureLoaded();
        if (!isEnabled()) {
            Log.d(TAG, "fire(" + hookName + "): plugins disabled");
            return;
        }
        if (plugins.isEmpty()) {
            Log.d(TAG, "fire(" + hookName + "): no plugins loaded");
            return;
        }
        Log.d(TAG, "fire(" + hookName + "): firing on " + plugins.size() + " plugin(s)");
        markHookStart(hookName);
        try {
            for (LoadedPlugin plugin : plugins) {
                if (!plugin.isEnabled()) {
                    Log.d(TAG, "fire(" + hookName + "): " + plugin.displayName + " disabled, skipping");
                    continue;
                }
                // Run on a guarded executor with a timeout, so a plugin that loops
                // forever (while true do end) can't hang the calling thread (often
                // the UI thread, e.g. onResume). We can't hard-kill the Lua thread
                // (Java has no safe Thread.stop), but on timeout we flag the plugin
                // as misbehaving, disable it, and rebuild — which is enough to keep
                // the app responsive. Only applies to fire-and-forget hooks;
                // synchronous result hooks (fireReturn) are left intact.
                try {
                    invokeHookWithTimeout(plugin, hookName, args);
                } catch (LuaError e) {
                    FileLog.e("Plugin " + plugin.fileName + " hook " + hookName + " error", e);
                    Log.e(TAG, "fire(" + hookName + "): error in " + plugin.fileName + ": " + e.getMessage());
                }
            }
        } finally {
            markHookEnd();
        }
    }

    /** Background executor used for guarded hook invocation. */
    private static final java.util.concurrent.ExecutorService hookExecutor =
            java.util.concurrent.Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "XenonPluginHook");
                t.setDaemon(true);
                return t;
            });

    private static final long HOOK_TIMEOUT_MS = 5000;

    private void invokeHookWithTimeout(LoadedPlugin plugin, String hookName, LuaValue[] args) {
        java.util.concurrent.Future<?> future = hookExecutor.submit(() -> {
            try {
                plugin.invokeHook(hookName, args);
            } catch (LuaError e) {
                FileLog.e("Plugin " + plugin.fileName + " hook " + hookName + " error", e);
                Log.e(TAG, "fire(" + hookName + "): error in " + plugin.fileName + ": " + e.getMessage());
            }
        });
        try {
            future.get(HOOK_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            // Plugin is stuck. Cancel (best-effort interrupt) and quarantine it.
            future.cancel(true);
            Log.e(TAG, "fire(" + hookName + "): TIMEOUT after " + HOOK_TIMEOUT_MS
                    + "ms, quarantining " + plugin.fileName);
            quarantinePlugin(plugin, "hook '" + hookName + "' exceeded timeout");
        } catch (Exception e) {
            Log.e(TAG, "fire(" + hookName + "): wait failed: " + e.getMessage());
        }
    }

    /**
     * Invoke a SYNCHRONOUS result hook (fireReturn / fireBooleanResult) with a
     * Throwable-catch around it. These hooks can't use the timeout executor
     * (they must return a value immediately), but they still must not be able
     * to crash the app via a StackOverflowError / OutOfMemoryError escaping
     * Lua. If such an Error is thrown, we quarantine the offending plugin and
     * treat the hook as returning nil/false. Normal LuaError is rethrown so the
     * caller's catch(LuaError) handles it as before.
     */
    private LuaValue invokeHookReturnGuarded(LoadedPlugin plugin, String hookName, LuaValue[] args) {
        try {
            return plugin.invokeHookReturn(hookName, args);
        } catch (LuaError e) {
            // Normal Lua error — let the caller's catch(LuaError) deal with it.
            throw e;
        } catch (Throwable t) {
            // StackOverflowError, OutOfMemoryError, or any other JVM-level
            // error. These would otherwise propagate out of fireReturn and crash
            // the app. Quarantine the plugin and move on.
            Log.e(TAG, plugin.fileName + " hook " + hookName + " threw "
                    + t.getClass().getSimpleName() + ": " + t.getMessage());
            quarantineFile(plugin.fileName, "hook '" + hookName + "'", t);
            return null;
        }
    }

    /**
     * Disable a misbehaving plugin permanently (until the user re-enables it)
     * and rebuild the engine so it stops receiving hooks. Used by the hook
     * timeout guard. Also surfaces a failure sheet so the user can copy the
     * reason (a hook running longer than the guard allows, i.e. an infinite loop).
     */
    private void quarantinePlugin(LoadedPlugin plugin, String reason) {
        try {
            if (!NekoConfig.pluginAutoSafeMode) {
                // Automatic Safe Mode is off — don't disable plugins on our own.
                // Log the failure so it can be debugged, but keep the plugin
                // enabled; the user asked to be in full control of the engine.
                FileLog.e("Plugin failure (automatic safe mode is off, not disabling): "
                        + plugin.fileName + " — " + reason);
                return;
            }
            getPrefs().edit().putBoolean("plugin_enabled_" + plugin.fileName, false).apply();
            FileLog.e("Plugin quarantined: " + plugin.fileName + " — " + reason);
            org.telegram.messenger.AndroidUtilities.runOnUIThread(() -> {
                try {
                    PluginApi.stopAllForPlugin(plugin.fileName);
                    reloadAll();
                } catch (Exception ignored) {
                }
            });
            // Report with a synthetic throwable so the failure sheet + copy
            // button show a meaningful, copyable reason.
            Throwable synthetic = new java.util.concurrent.TimeoutException(reason);
            android.app.Activity activity = getCurrentActivity();
            PluginSafeMode.reportPluginFailure(activity, plugin.fileName, reason, synthetic);
        } catch (Exception ignored) {
        }
    }

    /**
     * Disable a plugin by file name without needing a loaded instance (e.g. when
     * its top-level code threw during load). Sets the prefs flag so it won't run
     * again, writes a full crash log, and shows a sheet so the user can copy the
     * exact error (the full stack trace, not a truncated class name).
     */
    public void quarantineFile(String fileName, String stage, Throwable t) {
        try {
            if (!NekoConfig.pluginAutoSafeMode) {
                // Automatic Safe Mode is off — don't disable plugins on our own.
                // Log the failure so it can be debugged, but keep the plugin
                // enabled; the user asked to be in full control of the engine.
                FileLog.e("Plugin failure (automatic safe mode is off, not disabling): "
                        + fileName + " — " + stage, t);
                return;
            }
            getPrefs().edit().putBoolean("plugin_enabled_" + fileName, false).apply();
            FileLog.e("Plugin quarantined: " + fileName + " — " + stage, t);
            PluginApi.stopAllForPlugin(fileName);
            for (int i = 0; i < plugins.size(); i++) {
                if (plugins.get(i).fileName.equals(fileName)) {
                    plugins.remove(i);
                    break;
                }
            }
            String report = PluginSafeMode.buildPluginFailureReport(fileName, stage, t);
            android.app.Activity activity = getCurrentActivity();
            if (activity != null) {
                PluginSafeMode.reportPluginFailure(activity, fileName, stage, t);
            } else {
                String msg = t != null ? t.getClass().getSimpleName() : stage;
                PluginApi.showBulletinError("Plugin " + fileName
                        + " was disabled: " + msg, report);
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * Fire a hook and return the first handler's result. Iterates plugins in
     * order; the first handler that returns a non-{@code nil} value wins and
     * its {@link LuaValue} is returned. Handlers returning {@code nil} are
     * skipped, so plugins can observe without deciding.
     */
    public LuaValue fireReturn(String hookName, LuaValue... args) {
        ensureLoaded();
        if (!isEnabled()) {
            Log.d(TAG, "fireReturn(" + hookName + "): plugins disabled");
            return null;
        }
        if (plugins.isEmpty()) {
            Log.d(TAG, "fireReturn(" + hookName + "): no plugins loaded");
            return null;
        }
        Log.d(TAG, "fireReturn(" + hookName + "): firing on " + plugins.size() + " plugin(s)");
        markHookStart(hookName);
        try {
            for (LoadedPlugin plugin : plugins) {
                if (!plugin.isEnabled()) {
                    Log.d(TAG, "fireReturn(" + hookName + "): " + plugin.displayName + " disabled, skipping");
                    continue;
                }
                try {
                    LuaValue res = invokeHookReturnGuarded(plugin, hookName, args);
                    if (res != null && !res.isnil()) {
                        Log.d(TAG, "fireReturn(" + hookName + "): " + plugin.fileName + " returned a value");
                        return res;
                    }
                } catch (LuaError e) {
                    FileLog.e("Plugin " + plugin.fileName + " hook " + hookName + " error", e);
                    Log.e(TAG, "fireReturn(" + hookName + "): error in " + plugin.fileName + ": " + e.getMessage());
                }
            }
            Log.d(TAG, "fireReturn(" + hookName + "): no plugin returned a value");
            return null;
        } finally {
            markHookEnd();
        }
    }

    /**
     * Fire a hook whose handler returns a boolean decision. Each handler is
     * asked in turn; if any returns {@code true} (or a Lua truthy value) the
     * result becomes {@code true}. Useful for "should I block?" style hooks.
     */
    public boolean fireBooleanResult(String hookName, LuaValue... args) {
        ensureLoaded();
        if (!isEnabled()) {
            Log.d(TAG, "fireBooleanResult(" + hookName + "): plugins disabled");
            return false;
        }
        if (plugins.isEmpty()) {
            Log.d(TAG, "fireBooleanResult(" + hookName + "): no plugins loaded");
            return false;
        }
        Log.d(TAG, "fireBooleanResult(" + hookName + "): firing on " + plugins.size() + " plugin(s)");
        markHookStart(hookName);
        try {
            for (LoadedPlugin plugin : plugins) {
                if (!plugin.isEnabled()) {
                    Log.d(TAG, "fireBooleanResult(" + hookName + "): " + plugin.displayName + " disabled, skipping");
                    continue;
                }
                try {
                    LuaValue res = invokeHookReturnGuarded(plugin, hookName, args);
                    if (res != null && res.toboolean()) {
                        Log.d(TAG, "fireBooleanResult(" + hookName + "): " + plugin.fileName + " returned true");
                        return true;
                    }
                } catch (LuaError e) {
                    FileLog.e("Plugin " + plugin.fileName + " hook " + hookName + " error", e);
                    Log.e(TAG, "fireBooleanResult(" + hookName + "): error in " + plugin.fileName + ": " + e.getMessage());
                }
            }
            return false;
        } finally {
            markHookEnd();
        }
    }

    /**
     * Quickly parse a .xplugin file's metadata (name, description, pluginId) without
     * keeping the plugin loaded. Returns an array of three strings: [name, description, pluginId],
     * or null if parsing fails.
     */
    private static String lastParseError;
    private static boolean lastInstallEngineOff;

    public static String getLastParseError() {
        return lastParseError;
    }

    public static boolean isLastInstallEngineOff() {
        return lastInstallEngineOff;
    }

    public static String[] parseMetadata(File file) {
        lastParseError = null;
        if (file == null || !file.exists() || !file.getName().endsWith(PLUGIN_EXT)) return null;
        // SECURITY: parse metadata by SCANNING THE SOURCE TEXT, never by executing
        // the Lua. Executing (the old approach) meant a malformed or malicious
        // plugin could crash/hang the app the moment its file is previewed —
        // before it was even installed. Reading plugin_id / plugin_name /
        // plugin_description with a regex over the first chunk of the file is
        // crash-proof and fast, because no interpreter runs.
        try (InputStream in = new FileInputStream(file)) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            // Only read up to 64KB; metadata lives at the top of the file.
            while ((n = in.read(buf)) > 0 && baos.size() < 65536) {
                baos.write(buf, 0, n);
            }
            String source = new String(baos.toByteArray(), "UTF-8");
            String name = extractStringAssignment(source, "plugin_name");
            String desc = extractStringAssignment(source, "plugin_description");
            String id = extractStringAssignment(source, "plugin_id");
            String author = extractStringAssignment(source, "plugin_author");
            String version = extractStringAssignment(source, "plugin_version");
            // Sanitize version: keep "N.M" format, max 5 digits after the dot.
            version = sanitizeVersion(version);
            return new String[]{name, desc, id, author, version};
        } catch (Throwable t) {
            // Catch Throwable (not Exception) — even an OOM while reading must
            // not crash the app here.
            FileLog.e(t);
            String msg = t.getMessage();
            lastParseError = msg != null ? msg : "unknown parse error";
            Log.e(TAG, "parseMetadata: failed for " + file.getName() + ": " + lastParseError);
            return null;
        }
    }

    public static String hashPluginFile(File file) {
        if (file == null || !file.exists()) return null;
        try (InputStream is = new FileInputStream(file)) {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
            byte[] digest = md.digest();
            StringBuilder sb = new StringBuilder(32);
            for (byte b : digest) {
                sb.append(String.format("%02x", b & 0xff));
            }
            return sb.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    public static boolean isPluginFileIdentical(File pluginFile, String pluginId) {
        String newHash = hashPluginFile(pluginFile);
        if (newHash == null) return false;
        File dir = getPluginsDir();
        File[] files = dir.listFiles((d, name) -> name.endsWith(PLUGIN_EXT));
        if (files == null) return false;
        for (File file : files) {
            if (file.getAbsolutePath().equals(pluginFile.getAbsolutePath())) continue;
            String existingHash = hashPluginFile(file);
            if (newHash.equals(existingHash)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Pull a top-level {@code var = "..."} string assignment out of Lua source
     * without executing it. Matches common forms: single/double quotes, and an
     * optional "local" prefix. Returns the trimmed string value, or null.
     */
    private static String extractStringAssignment(String source, String varName) {
        if (source == null || varName == null) return null;
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(
                "(?:^|\\n)\\s*(?:local\\s+)?" + java.util.regex.Pattern.quote(varName)
                        + "\\s*=\\s*\"([^\"]*)\"");
        java.util.regex.Matcher m = p.matcher(source);
        if (m.find()) {
            String s = m.group(1);
            if (s != null) {
                s = s.trim();
                if (!s.isEmpty()) return s;
            }
        }
        return null;
    }

    /**
     * Parse a plugin's requested security scopes (e.g.
     * {@code plugin_scopes = {"MESSAGING"}}) by scanning the source text, never
     * by executing Lua. Returns the list of recognized scope names found. Unknown
     * names are dropped (deny-by-default). Mirrors the crash-proof regex
     * approach used by {@link #parseMetadata}.
     */
    public static List<String> parseScopes(File file) {
        List<String> scopes = new ArrayList<>();
        if (file == null || !file.exists()) return scopes;
        try (InputStream in = new FileInputStream(file)) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0 && baos.size() < 65536) {
                baos.write(buf, 0, n);
            }
            String source = new String(baos.toByteArray(), "UTF-8");
            // Match the RHS of `plugin_scopes = { ... }` as a flat string blob,
            // then pull out every quoted token. This is deliberately loose: it
            // catches both {"MESSAGING"} and { "MESSAGING", "GENERAL" } without
            // caring about whitespace or order.
            java.util.regex.Pattern p = java.util.regex.Pattern.compile(
                    "(?:^|\\n)\\s*(?:local\\s+)?plugin_scopes\\s*=\\s*\\{([^}]*)\\}",
                    java.util.regex.Pattern.DOTALL);
            java.util.regex.Matcher m = p.matcher(source);
            if (!m.find()) return scopes;
            String body = m.group(1);
            java.util.regex.Pattern tok = java.util.regex.Pattern.compile("\"([^\"]*)\"");
            java.util.regex.Matcher tm = tok.matcher(body);
            while (tm.find()) {
                String s = tm.group(1).trim().toUpperCase();
                if (SCOPE_GENERAL.equals(s) || SCOPE_MESSAGING.equals(s)) {
                    if (!scopes.contains(s)) scopes.add(s);
                }
            }
        } catch (Throwable t) {
            FileLog.e(t);
        }
        return scopes;
    }

    /**
     * Build the locked-down {@link Globals} every plugin runs in. Starts from
     * the full JSE platform (so {@code print}, {@code pairs}, {@code pcall},
     * {@code tostring}, {@code type}, {@code setmetatable} and friends are all
     * present), then strips everything that can touch the filesystem, spawn
     * processes, load code, or escape the interpreter. The session token and
     * phone number live in {@code userconfig.xml}; with {@code io}/
     * {@code os.execute} gone a plugin cannot read them.
     * <p>When God Mode is on, {@code luajava} is left in place so that plugins
     * can reflect on any Java class. This is the trade-off: maximum power for
     * maximum trust.
     */

    /**
     * Custom {@code luajava} library that overrides the default one when God
     * Mode is enabled. The only change from the stock {@link
     * org.luaj.vm2.lib.jse.LuajavaLib} is a smarter {@link #classForName}
     * that tries the app's context classloader before falling back to the
     * system one, so Android-internal classes like
     * {@code org.telegram.ui.CameraScanActivity} can be resolved.
     * <p>
     * We deliberately do <b>not</b> override {@link
     * org.luaj.vm2.lib.LibFunction#call(LuaValue, LuaValue)} / {@link
     * org.luaj.vm2.lib.jse.LuajavaLib#invoke invoke} because the inherited
     * INIT opcode (opcode 0 in {@code LuajavaLib.invoke}) already uses
     * {@code this.getClass()} for the {@link #bind bind} call — so loading
     * an instance of this subclass is sufficient to make all Lua-callable
     * entries ({@code bindClass}, {@code newInstance}, {@code new},
     * {@code createProxy}, {@code loadLib}) also instances of this
     * subclass, which in turn means {@code classForName} is always the
     * overridden version.
     */
    public static class XenonLuajavaLib extends org.luaj.vm2.lib.jse.LuajavaLib {
        protected Class<?> classForName(String name) throws ClassNotFoundException {
            try {
                return Class.forName(name, true, Thread.currentThread().getContextClassLoader());
            } catch (ClassNotFoundException e) {
                try {
                    if (org.telegram.messenger.ApplicationLoader.applicationContext != null) {
                        return Class.forName(name, true, org.telegram.messenger.ApplicationLoader.applicationContext.getClassLoader());
                    }
                } catch (ClassNotFoundException ignored) {}
                try {
                    return Class.forName(name, true, PluginManager.class.getClassLoader());
                } catch (ClassNotFoundException e3) {
                    return super.classForName(name);
                }
            }
        }
    }

    private static Globals createSandboxGlobals() {
        Globals globals = JsePlatform.standardGlobals();
        // If God Mode is on, replace the default luajava module with our
        // custom XenonLuajavaLib (which overrides classForName to use the
        // app context classloader). This MUST happen before "package" is
        // removed below, because the INIT opcode of LuajavaLib tries to
        // register itself in package.loaded.
        if (NekoConfig.pluginGodMode) {
            globals.load(new XenonLuajavaLib());
        }
        // Filesystem / process / code-loading libs — remove entirely.
        globals.set("io", LuaValue.NIL);
        globals.set("package", LuaValue.NIL);
        globals.set("debug", LuaValue.NIL);
        if (!NekoConfig.pluginGodMode) {
            globals.set("luajava", LuaValue.NIL);
        }
        globals.set("loadfile", LuaValue.NIL);
        globals.set("dofile", LuaValue.NIL);
        globals.set("load", LuaValue.NIL);
        globals.set("loadstring", LuaValue.NIL);
        globals.set("require", LuaValue.NIL);
        // Keep only the harmless os.* functions; build a fresh table so there's
        // no leftover reference to the dangerous originals.
        LuaValue oldOs = globals.get("os");
        LuaTable safeOs = new LuaTable();
        String[] keep = {"time", "date", "clock", "difftime"};
        for (String fn : keep) {
            LuaValue v = oldOs.get(fn);
            if (!v.isnil()) safeOs.set(fn, v);
        }
        globals.set("os", safeOs);
        return globals;
    }

    /**
     * Does {@code fileName} hold {@code scope}? {@link #SCOPE_GENERAL} is always
     * granted. Other scopes read the persisted auto-grant flag
     * ({@code plugin_scope_<file>_<SCOPE>}). God Mode
     * ({@code NekoConfig.pluginGodMode}) short-circuits to true for every scope.
     */
    public static boolean hasScope(String fileName, String scope) {
        if (fileName == null || scope == null) return false;
        if (SCOPE_GENERAL.equals(scope)) return true;
        if (zxc.iconic.xenon.NekoConfig.pluginGodMode) return true;
        return getPrefs().getBoolean("plugin_scope_" + fileName + "_" + scope, false);
    }

    /**
     * Auto-grant every scope a plugin declares in its manifest. {@link #SCOPE_GENERAL}
     * is forced on unconditionally. Declared scopes are granted only on FIRST
     * install/encounter: once the pref key exists (true or false), the user's
     * explicit choice is preserved — so revoking a scope from the Permissions
     * screen sticks across reloads. Called when a plugin loads.
     */
    private static void grantScopes(String fileName, File file) {
        if (fileName == null || file == null) return;
        List<String> scopes = parseScopes(file);
        SharedPreferences prefs = getPrefs();
        SharedPreferences.Editor ed = prefs.edit();
        ed.putBoolean("plugin_scope_" + fileName + "_" + SCOPE_GENERAL, true);
        for (String s : scopes) {
            if (!SCOPE_GENERAL.equals(s)) {
                String key = "plugin_scope_" + fileName + "_" + s;
                // Only grant if the user has never decided — preserve revocation.
                if (!prefs.contains(key)) {
                    ed.putBoolean(key, true);
                }
            }
        }
        ed.apply();
    }

    /**
     * Normalize a plugin version string to "N.M" form. Keeps digits and at most
     * one dot; if there's a fractional part, limits it to 5 digits after the
     * dot. Returns null for garbage / empty input.
     */
    private static String sanitizeVersion(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty()) return null;
        // Keep only digits and the first dot.
        StringBuilder sb = new StringBuilder();
        boolean dotSeen = false;
        int afterDot = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '.' && !dotSeen) {
                sb.append('.');
                dotSeen = true;
            } else if (Character.isDigit(c)) {
                if (dotSeen) {
                    if (afterDot < 5) {
                        sb.append(c);
                        afterDot++;
                    }
                    // else: truncate beyond 5 digits after dot.
                } else {
                    sb.append(c);
                }
            }
            // Ignore anything else.
        }
        String result = sb.toString();
        if (result.isEmpty()) return null;
        // Strip a trailing dot.
        if (result.endsWith(".")) result = result.substring(0, result.length() - 1);
        return result.isEmpty() ? null : result;
    }

    /**
     * A loaded plugin: its file name plus the Lua globals it runs in.
     */
    public static class LoadedPlugin {
        public final String fileName;
        public final String displayName;
        public final String name;
        public final String description;
        public final String pluginId;
        public final String author;
        public final String version;
        public final List<PluginSetting> settings;
        private final Globals globals;
        private final LuaTable hooks;

        LoadedPlugin(String fileName, String name, String description, String pluginId, String author, String version, List<PluginSetting> settings, Globals globals, LuaTable hooks) {
            this.fileName = fileName;
            this.globals = globals;
            this.hooks = hooks;
            String disp = fileName;
            if (disp.endsWith(PLUGIN_EXT)) {
                disp = disp.substring(0, disp.length() - PLUGIN_EXT.length());
            }
            this.displayName = disp;
            this.name = name;
            this.description = description;
            this.pluginId = pluginId;
            this.author = author;
            this.version = version;
            this.settings = settings;
        }

        public static class PluginSetting {
            public enum Type { TOGGLE, SEEKBAR, TEXT, BUTTON, HEADER, LIST }
            public final Type type;
            public final String key;
            public final String name;
            public final boolean defaultBool;
            public final int defaultInt;
            public final String defaultString;
            public final int min, max, step;
            public final String hint;
            public final LuaValue action;
            /** For LIST: the selectable option labels. */
            public final String[] options;

            PluginSetting(Type type, String key, String name, boolean defaultBool, int defaultInt, String defaultString, int min, int max, int step, String hint, LuaValue action) {
                this(type, key, name, defaultBool, defaultInt, defaultString, min, max, step, hint, action, null);
            }

            PluginSetting(Type type, String key, String name, boolean defaultBool, int defaultInt, String defaultString, int min, int max, int step, String hint, LuaValue action, String[] options) {
                this.type = type; this.key = key; this.name = name;
                this.defaultBool = defaultBool; this.defaultInt = defaultInt; this.defaultString = defaultString;
                this.min = min; this.max = max; this.step = step;
                this.hint = hint; this.action = action; this.options = options;
            }
        }

        public boolean isEnabled() {
            return PluginManager.getPrefs().getBoolean("plugin_enabled_" + fileName, true);
        }

        public void setEnabled(boolean enabled) {
            PluginManager.getPrefs().edit().putBoolean("plugin_enabled_" + fileName, enabled).apply();
        }

        public void refreshSettingsFromGlobals() {
            this.settings.clear();
            LuaValue settingsVal = globals.get("plugin_settings");
            if (settingsVal.istable()) {
                for (int i = 1; i <= settingsVal.length(); i++) {
                    LuaValue entry = settingsVal.get(i);
                    if (!entry.istable()) continue;
                    try {
                        LuaValue typeVal = entry.get("type");
                        LuaValue keyVal = entry.get("key");
                        LuaValue nameEntry = entry.get("name");
                        if (typeVal.isnil() || keyVal.isnil() || nameEntry.isnil()) continue;
                        String type = typeVal.tojstring();
                        String skey = keyVal.tojstring();
                        String sname = nameEntry.tojstring();
                        PluginSetting setting = null;
                        switch (type) {
                            case "toggle": {
                                boolean def = entry.get("default").toboolean();
                                setting = new PluginSetting(PluginSetting.Type.TOGGLE, skey, sname, def, 0, null, 0, 0, 0, null, null);
                                break;
                            }
                            case "seekbar": {
                                int min = (int) entry.get("min").checkdouble();
                                int max = (int) entry.get("max").checkdouble();
                                int step = (int) entry.get("step").checkdouble();
                                if (step <= 0) step = 1;
                                int def = entry.get("default").isnil() ? min : (int) entry.get("default").checkdouble();
                                setting = new PluginSetting(PluginSetting.Type.SEEKBAR, skey, sname, false, def, null, min, max, step, null, null);
                                break;
                            }
                            case "text": {
                                LuaValue defVal = entry.get("default");
                                String def = defVal.isnil() ? "" : defVal.tojstring();
                                LuaValue hintVal = entry.get("hint");
                                String hint = hintVal.isnil() ? "" : hintVal.tojstring();
                                setting = new PluginSetting(PluginSetting.Type.TEXT, skey, sname, false, 0, def, 0, 0, 0, hint, null);
                                break;
                            }
                            case "button": {
                                LuaValue action = entry.get("action");
                                setting = new PluginSetting(PluginSetting.Type.BUTTON, skey, sname, false, 0, null, 0, 0, 0, null, action != null && !action.isnil() ? action : null);
                                break;
                            }
                            case "header": {
                                setting = new PluginSetting(PluginSetting.Type.HEADER, skey, sname, false, 0, null, 0, 0, 0, null, null);
                                break;
                            }
                            case "list": {
                                int def = (int) entry.get("default").checkdouble();
                                LuaValue optsVal = entry.get("options");
                                String[] opts = null;
                                if (optsVal.istable()) {
                                    opts = new String[optsVal.length()];
                                    for (int o = 1; o <= optsVal.length(); o++) {
                                        opts[o - 1] = optsVal.get(o).tojstring();
                                    }
                                }
                                setting = new PluginSetting(PluginSetting.Type.LIST, skey, sname, false, def, null, 0, 0, 0, null, null, opts);
                                break;
                            }
                        }
                        if (setting != null) {
                            this.settings.add(setting);
                        }
                    } catch (Exception e) {
                        FileLog.e(e);
                    }
                }
            }
        }

        public void invokeHook(String hookName, LuaValue[] args) {
            LuaValue handler = hooks.get(hookName);
            if (handler.isnil()) {
                Log.d(TAG, displayName + ": no handler for hook '" + hookName + "' (hooks table has " + hooks.keys().length + " entries)");
                return;
            }
            Log.d(TAG, displayName + ": invoking hook '" + hookName + "'");
            handler.invoke(args);
            Log.d(TAG, displayName + ": hook '" + hookName + "' completed");
        }

        LuaValue invokeHookReturn(String hookName, LuaValue[] args) {
            LuaValue handler = hooks.get(hookName);
            if (handler.isnil()) {
                Log.d(TAG, displayName + ": no handler for hook '" + hookName + "'");
                return null;
            }
            Log.d(TAG, displayName + ": invoking return-hook '" + hookName + "'");
            LuaValue result = handler.invoke(LuaValue.varargsOf(args)).arg1();
            Log.d(TAG, displayName + ": return-hook '" + hookName + "' returned " + (result.isnil() ? "nil" : result.toString()));
            return result;
        }
    }
}
