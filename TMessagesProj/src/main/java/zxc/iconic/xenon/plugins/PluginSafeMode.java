package zxc.iconic.xenon.plugins;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;

import zxc.iconic.xenon.NekoConfig;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Crash-aware "Safe Mode" — catches <b>any</b> uncaught exception and restarts
 * the app instead of letting it die.
 *
 * <p>Two responsibilities:
 * <ol>
 *   <li><b>Capture + restart:</b> installs a global {@link Thread.UncaughtExceptionHandler}
 *       that catches <i>any</i> uncaught exception (not just plugin-caused ones),
 *       records the stack trace to the crash-log file, flips {@code appCrash} on,
 *       then <b>relaunches the app</b> into the launcher activity and kills the
 *       crashing process — so the user sees a restart, not the system "keeps
 *       stopping" dialog. A loop guard prevents infinite restarts.</li>
 *   <li><b>Recover:</b> on the next app start, if a crash (or a hang/ANR that the
 *       Java handler couldn't catch) was recorded, shows a {@link BottomSheet}
 *       with the crash log, a "Copy crash log" button, and "Close". Plugins are
 *       disabled only if they were active — a crash in pure Telegram code is
 *       not blamed on plugins.</li>
 * </ol>
 *
 * <p>The handler is deliberately minimal and defensive: writing files or shared
 * prefs from a crashing process is best-effort. When the app is relaunched the
 * previous handler is intentionally not invoked, so the system crash dialog is
 * suppressed; only the best-effort relaunch fallback (no context, can't start
 * activity, or loop guard) chains to the previous handler.
 */
public final class PluginSafeMode {

    private static final String PREFS = "xenon_plugins_safemode";
    private static final String KEY_CRASH_FLAG = "pluginCrash";
    private static final String KEY_CRASH_TIME = "pluginCrashTime";
    private static final String KEY_APP_CRASH_FLAG = "appCrash";
    private static final String KEY_APP_CRASH_TIME = "appCrashTime";
    private static final String KEY_BOOT_FLAG = "bootIn";        // boot-guard: was set on the last start
    private static final String KEY_BOOT_TIME = "bootInTime";    // when that start happened
    private static final String CRASH_LOG_NAME = "plugin_crash.txt";

    /** When true, we've already entered this launch — guards against double-handling. */
    private static volatile boolean bootHandledThisLaunch;

    /**
     * Snapshot of the boot flag from the PREVIOUS launch, captured before
     * markBootStarted overwrites it with our own "in progress" marker. This is
     * what checkAndHandleCrash must read — reading prefs directly would return
     * the value we just wrote for this launch, causing a false "failed to start"
     * every single time.
     */
    private static volatile boolean previousBootIncomplete;
    private static volatile long previousBootTime;

    private PluginSafeMode() {
    }

    // ------------------------------------------------------------------
    // Crash capture
    // ------------------------------------------------------------------

    /**
     * Install the crash handler. Call once very early in app startup (e.g.
     * {@code ApplicationLoader.onCreate}). Catches <b>any</b> uncaught
     * exception, records the trace, flags the crash for the next-launch
     * recovery sheet, then <b>restarts the app</b> into the launcher activity
     * and kills the crashing process — so the user sees a relaunch instead of
     * the system "keeps stopping" dialog. A loop guard prevents infinite
     * restarts if the relaunch itself crashes before the sheet clears the
     * flag.
     */
    public static void install() {
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            try {
                handleUncaught(thread, throwable, previous);
            } catch (Throwable ignore) {
                // Never let the handler itself throw — that would swallow the
                // original crash. Best-effort only.
                if (previous != null) {
                    previous.uncaughtException(thread, throwable);
                }
            }
        });
    }

    private static void handleUncaught(Thread thread, Throwable throwable, Thread.UncaughtExceptionHandler previous) {
        try {
            FileLog.fatal(throwable, false);
        } catch (Throwable ignore) {
        }

        Context ctx = ApplicationLoader.applicationContext;
        if (ctx == null) {
            // No context — can't restart. Fall through to the default handler so
            // the process still terminates normally.
            if (previous != null) {
                previous.uncaughtException(thread, throwable);
            }
            return;
        }

        android.content.SharedPreferences prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        boolean alreadyCrashing = prefs.getBoolean(KEY_APP_CRASH_FLAG, false);

        // Always record the trace (generic header; plugin info added if active).
        writeCrashLog(buildCrashTrace(thread, throwable));

        if (alreadyCrashing) {
            // We crashed again before the recovery sheet could clear the flag —
            // don't restart (avoid a crash loop). Let the OS terminate.
            if (previous != null) {
                previous.uncaughtException(thread, throwable);
            }
            return;
        }

        boolean pluginsActive = arePluginsActive();

        // Mark the crash. appCrashFlag is universal; pluginCrash is set only
        // when plugins were active so the recovery sheet can disable + attribute
        // them.
        android.content.SharedPreferences.Editor ed = prefs.edit();
        ed.putBoolean(KEY_APP_CRASH_FLAG, true);
        ed.putLong(KEY_APP_CRASH_TIME, System.currentTimeMillis());
        ed.putBoolean("pluginsActiveLastTime", pluginsActive);
        if (pluginsActive) {
            ed.putBoolean(KEY_CRASH_FLAG, true);
            ed.putLong(KEY_CRASH_TIME, System.currentTimeMillis());
        }
        ed.commit();

        // Restart the app into the launcher activity, then kill this process.
        // We deliberately do NOT chain to the previous handler, so the system's
        // "keeps stopping" dialog is suppressed — the app just relaunches.
        try {
            Intent i = ctx.getPackageManager().getLaunchIntentForPackage(ctx.getPackageName());
            if (i != null) {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                ctx.startActivity(i);
            }
        } catch (Throwable ignore) {
            // If we can't restart, fall through to the default handler.
            if (previous != null) {
                previous.uncaughtException(thread, throwable);
            }
            return;
        }
        android.os.Process.killProcess(android.os.Process.myPid());
        System.exit(1);
    }

    private static String buildCrashTrace(Thread thread, Throwable throwable) {
        StringBuilder sb = new StringBuilder();
        sb.append("Xenon crash report\n");
        sb.append("Time: ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                .format(new Date())).append("\n");
        sb.append("Thread: ").append(thread != null ? thread.getName() : "unknown").append("\n");
        sb.append("App: ").append(getAppVersion()).append("\n");
        sb.append("Android: ").append(Build.VERSION.RELEASE)
                .append(" (").append(Build.MODEL).append(")\n");
        sb.append("Plugins enabled: ").append(NekoConfig.pluginsEnabled).append("\n");
        sb.append("Plugins active: ").append(arePluginsActive()).append("\n");
        sb.append("\n--- Stack trace ---\n");
        if (throwable != null) {
            sb.append(LogExceptionToString(throwable));
        } else {
            sb.append("(no throwable)");
        }
        return sb.toString();
    }

    private static String LogExceptionToString(Throwable t) {
        // Manual walk so we don't depend on android.util.Log.getStackTraceString
        // behaving a particular way; also keeps chained causes.
        StringBuilder sb = new StringBuilder();
        Throwable current = t;
        while (current != null) {
            sb.append(current.toString()).append("\n");
            StackTraceElement[] frames = current.getStackTrace();
            for (StackTraceElement frame : frames) {
                sb.append("    at ").append(frame.toString()).append("\n");
            }
            Throwable cause = current.getCause();
            if (cause != null) {
                sb.append("Caused by: ");
            }
            current = cause;
        }
        return sb.toString();
    }

    private static String getAppVersion() {
        try {
            Context ctx = ApplicationLoader.applicationContext;
            return ctx.getPackageName() + " " +
                    ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "unknown";
        }
    }

    private static void writeCrashLog(String content) {
        try {
            File file = getCrashLogFile();
            if (file == null) return;
            // Write fresh each crash — last crash is the interesting one.
            java.io.FileOutputStream fos = new java.io.FileOutputStream(file, false);
            fos.write(content.getBytes("UTF-8"));
            fos.close();
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private static File getCrashLogFile() {
        Context ctx = ApplicationLoader.applicationContext;
        if (ctx == null) return null;
        return new File(ctx.getFilesDir(), CRASH_LOG_NAME);
    }

    // ------------------------------------------------------------------
    // Volume-key Safe Mode (cold launch)
    // ------------------------------------------------------------------

    /** Set by onVolumeKeyDown during the launch window. */
    private static volatile boolean volumeKeyHeldAtLaunch;

    /**
     * Incremented each time markBootStarted is called (once per process start).
     * consumeVolumeKeySafeMode uses this to only trigger Safe Mode on the very
     * first resume after boot, preventing false triggers from volume key presses
     * during normal use (which set volumeKeyHeldAtLaunch but should not cause a
     * second Safe Mode activation).
     */
    private static volatile int bootSessionId;
    private static volatile int lastConsumedBootSession = -1;

    /**
     * Called from LaunchActivity.dispatchKeyEvent when a volume key goes down.
     * Records the press — the flag is consumed by consumeVolumeKeySafeMode on
     * the next onResume, but only once per boot session.
     */
    public static void onVolumeKeyDown(int keyCode) {
        if (keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP
                || keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN) {
            volumeKeyHeldAtLaunch = true;
        }
    }

    /**
     * If a volume key was held during launch, trigger Safe Mode: disable
     * plugins and show the sheet. Called from onResume before plugins fire.
     * Only fires once per boot session — subsequent volume key presses during
     * normal use set volumeKeyHeldAtLaunch again, but we skip them because
     * lastConsumedBootSession already matches bootSessionId.
     * Clears the flag. Returns true if Safe Mode was activated.
     */
    public static boolean consumeVolumeKeySafeMode(Activity activity) {
        // Only the very first call after a new boot (process start) may trigger.
        if (lastConsumedBootSession == bootSessionId) return false;
        lastConsumedBootSession = bootSessionId;
        if (volumeKeyHeldAtLaunch) {
            volumeKeyHeldAtLaunch = false;
            triggerSafeModeManual(activity, "Volume key held at launch");
            return true;
        }
        // Flag not set yet — key events may arrive after window gets focus
        // (dispatchKeyEvent can't fire before onResume completes and the
        // window is focused). Schedule a delayed check to catch them.
        final Activity act = activity;
        org.telegram.messenger.AndroidUtilities.runOnUIThread(() -> {
            if (volumeKeyHeldAtLaunch) {
                volumeKeyHeldAtLaunch = false;
                triggerSafeModeManual(act, "Volume key held at launch");
            }
        }, 2500);
        return false;
    }

    private static void triggerSafeModeManual(Activity activity, String reason) {
        try {
            Context ctx = ApplicationLoader.applicationContext;
            if (ctx != null) {
                NekoConfig.pluginsEnabled = false;
                ctx.getSharedPreferences("nekoconfig", Context.MODE_PRIVATE)
                        .edit().putBoolean("pluginsEnabled", false).apply();
            }
            PluginManager.getInstance().onEnabledChanged();
            writeCrashLog("Xenon plugin safe-mode report\n"
                    + "Reason: " + reason + "\n"
                    + "Time: " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                        .format(new Date()) + "\n"
                    + "Plugins disabled by user (safe mode).\n");
            if (activity != null) {
                org.telegram.messenger.AndroidUtilities.runOnUIThread(
                        () -> showCrashSheet(activity, System.currentTimeMillis(), "safe", true), 500);
            }
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }

    // ------------------------------------------------------------------
    // Recovery
    // ------------------------------------------------------------------

    /**
     * Mark the start of a launch as "in progress". Call as early as possible
     * (e.g. ApplicationLoader.onCreate). The matching {@link #markBootCompleted}
     * clears it once the UI is up. If the process dies before that — whether by
     * a Java crash, an ANR, or an infinite loop hanging the UI thread — the flag
     * stays set, and the next launch knows the previous one never finished.
     */
    public static void markBootStarted() {
        // Start a new boot session — volume key presses during this session
        // (the first onResume) will trigger Safe Mode if any were detected.
        bootSessionId++;
        volumeKeyHeldAtLaunch = false;
        Context ctx = ApplicationLoader.applicationContext;
        if (ctx == null) return;
        // CAPTURE the previous launch's state BEFORE we overwrite it with our
        // own "in progress" marker. This is read later by checkAndHandleCrash.
        android.content.SharedPreferences prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        previousBootIncomplete = prefs.getBoolean(KEY_BOOT_FLAG, false);
        previousBootTime = prefs.getLong(KEY_BOOT_TIME, 0);
        bootHandledThisLaunch = false;

        boolean pluginsActive = arePluginsActive();

        prefs.edit()
                .putBoolean(KEY_BOOT_FLAG, true)
                .putLong(KEY_BOOT_TIME, System.currentTimeMillis())
                .putBoolean("pluginsActiveLastTime", pluginsActive)
                .apply();
    }

    private static boolean arePluginsActive() {
        try {
            if (!NekoConfig.pluginsEnabled) {
                return false;
            }
            File dir = PluginManager.getPluginsDir();
            File[] files = dir.listFiles((d, name) -> name.endsWith(PluginManager.PLUGIN_EXT));
            return files != null && files.length > 0;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Mark this launch as having reached a fully interactive state. Call from
     * {@link org.telegram.ui.LaunchActivity#onResume} once the UI is ready.
     * Clears the boot flag so the next start doesn't mistake this one for a
     * hang/crash.
     *
     * <p>Uses {@code commit()} (synchronous) instead of {@code apply()} so the
     * write is guaranteed to persist to disk even if the process is killed
     * immediately after. This prevents a false "Failed to start" on the next
     * launch when the OS reclaims memory right after onResume.
     */
    public static void markBootCompleted() {
        Context ctx = ApplicationLoader.applicationContext;
        if (ctx == null) return;
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_BOOT_FLAG, false)
                .commit();
    }

    /**
     * Called from {@link org.telegram.ui.LaunchActivity#onResume} once the UI is
     * ready. Detects two failure modes from the previous launch and reacts:
     *
     * <ul>
     *   <li><b>Java crash</b> — the {@code pluginCrash} flag was set by the
     *       UncaughtExceptionHandler.</li>
     *   <li><b>Hang / ANR / killed</b> — the {@code bootIn} flag was never
     *       cleared, meaning the previous process died before reaching
     *       {@link #markBootCompleted}. This catches the "stuck on the logo"
     *       case that a crash handler alone can't see.</li>
     * </ul>
     *
     * On either, plugins are disabled and the crash sheet is shown.
     */
    public static void checkAndHandleCrash(Activity activity) {
        if (activity == null) return;
        Context ctx = ApplicationLoader.applicationContext;
        if (ctx == null) return;

        // Only handle once per launch — onResume can fire multiple times.
        if (bootHandledThisLaunch) {
            return;
        }
        bootHandledThisLaunch = true;

        android.content.SharedPreferences prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        // appCrashFlag is set by the handler for ANY uncaught exception;
        // pluginCrash is the legacy plugin-attributed flag. Either means we
        // crashed last launch.
        boolean crashed = prefs.getBoolean(KEY_APP_CRASH_FLAG, false)
                || prefs.getBoolean(KEY_CRASH_FLAG, false);
        // IMPORTANT: read the PREVIOUS launch's boot state from the captured
        // field, not from prefs. prefs[KEY_BOOT_FLAG] was already overwritten
        // to true by markBootStarted() at the start of THIS launch — reading it
        // here would make every launch look like it failed to start.
        boolean hung = previousBootIncomplete;

        if (!crashed && !hung) {
            // Healthy previous launch — nothing to do. This launch is already
            // tracked (markBootStarted ran in ApplicationLoader).
            return;
        }

        // Whether plugins were active during the failed launch — used to decide
        // if we should disable plugins and whether the sheet should mention them.
        boolean pluginsActiveLastTime = prefs.getBoolean("pluginsActiveLastTime", false);

        // Prefer the explicit crash time; fall back to the boot time for hangs.
        long when = prefs.getLong(KEY_APP_CRASH_TIME, 0);
        if (when == 0) {
            when = prefs.getLong(KEY_CRASH_TIME, 0);
        }
        if (when == 0) {
            when = previousBootTime;
        }

        String reason = crashed ? "crash" : "hang";

        // Only disable plugins if they were actually active — a crash/hang in
        // pure Telegram code shouldn't blame (and disable) plugins.
        if (pluginsActiveLastTime) {
            try {
                NekoConfig.pluginsEnabled = false;
                ctx.getSharedPreferences("nekoconfig", Context.MODE_PRIVATE)
                        .edit().putBoolean("pluginsEnabled", false).commit();
                PluginManager.getInstance().onEnabledChanged();
            } catch (Throwable t) {
                FileLog.e("checkAndHandleCrash: onEnabledChanged threw", t);
            }
        }

        // Clear the flags so we don't show this sheet twice, and reset boot
        // tracking for THIS launch. Use commit() (synchronous) so the write
        // is guaranteed to persist even if the process dies immediately after.
        prefs.edit()
                .putBoolean(KEY_APP_CRASH_FLAG, false)
                .putBoolean(KEY_CRASH_FLAG, false)
                .putBoolean(KEY_BOOT_FLAG, false)
                .commit();

        if (hung && !crashed) {
            // No Java stack trace for a hang; leave a note in the log so the
            // "Copy crash log" button has something meaningful.
            writeCrashLog("Xenon crash report\n"
                    + "Reason: application did not finish booting (likely an ANR / hang / killed)\n"
                    + "Boot started at: " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                        .format(new Date(when)) + "\n"
                    + "Plugins enabled at that time: " + NekoConfig.pluginsEnabled + "\n");
        }

        final long crashTime = when;
        final String crashReason = reason;
        final boolean showPlugins = pluginsActiveLastTime;
        org.telegram.messenger.AndroidUtilities.runOnUIThread(
                () -> showCrashSheet(activity, crashTime, crashReason, showPlugins), 800);
    }

    private static void showCrashSheet(Activity activity, long crashTime, String reason, boolean pluginsActive) {
        try {
            final String crashLog = readCrashLog();
            boolean isHang = "hang".equals(reason);

            BottomSheet.Builder builder = new BottomSheet.Builder(activity, false, null);
            LinearLayout layout = new LinearLayout(activity);
            layout.setOrientation(LinearLayout.VERTICAL);
            int pad = org.telegram.messenger.AndroidUtilities.dp(24);
            layout.setPadding(pad, org.telegram.messenger.AndroidUtilities.dp(20), pad, pad);

            // Title
            TextView title = new TextView(activity);
            title.setText(isHang ? "Failed to start" : "Crashed!");
            title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
            title.setTypeface(org.telegram.messenger.AndroidUtilities.getTypeface("fonts/rmedium.ttf"));
            title.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            title.setPadding(0, 0, 0, org.telegram.messenger.AndroidUtilities.dp(12));
            layout.addView(title);

            // Body
            TextView body = new TextView(activity);
            if (isHang) {
                if (pluginsActive) {
                    body.setText("The client failed to finish starting up last time — it most likely "
                            + "hung or was killed. Plugins have been disabled to keep things stable. "
                            + "If a plugin caused this, review or remove it before re-enabling them.");
                } else {
                    body.setText("The client failed to finish starting up last time — it most likely "
                            + "hung or was killed. You can copy the log below to report it.");
                }
            } else {
                if (pluginsActive) {
                    body.setText("The client crashed on the previous launch. Plugins have been "
                            + "disabled to keep things stable. If a plugin caused this, you can "
                            + "review or remove it. Copy the crash log below if you want to report it.");
                } else {
                    body.setText("The client crashed on the previous launch. You can copy the "
                            + "crash log below to report it.");
                }
            }
            body.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            body.setLineSpacing(org.telegram.messenger.AndroidUtilities.dp(2), 1f);
            body.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
            body.setPadding(0, 0, 0, org.telegram.messenger.AndroidUtilities.dp(16));
            layout.addView(body);

            // Time line
            if (crashTime > 0) {
                TextView time = new TextView(activity);
                time.setText((isHang ? "Failure time: " : "Crash time: ")
                        + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date(crashTime)));
                time.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
                time.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText3));
                time.setPadding(0, 0, 0, org.telegram.messenger.AndroidUtilities.dp(16));
                layout.addView(time);
            }

            final BottomSheet[] sheetRef = new BottomSheet[1];

            // Buttons — "Open plugins" only makes sense when plugins were active
            // (and have just been disabled); otherwise just Copy + Close.
            if (pluginsActive) {
                layout.addView(makeButton(activity, "Open plugins", Theme.getColor(Theme.key_windowBackgroundWhiteBlueText), v -> {
                    if (sheetRef[0] != null) sheetRef[0].dismiss();
                    openPlugins(activity);
                }));
                layout.addView(divider(activity));
            }
            layout.addView(makeButton(activity, "Copy crash log", Theme.getColor(Theme.key_windowBackgroundWhiteBlueText), v -> {
                if (sheetRef[0] != null) sheetRef[0].dismiss();
                copyToClipboard(crashLog);
            }));
            layout.addView(divider(activity));
            layout.addView(makeButton(activity, "Close", Theme.getColor(Theme.key_windowBackgroundWhiteGrayText), v -> {
                if (sheetRef[0] != null) sheetRef[0].dismiss();
            }));

            builder.setCustomView(layout);
            sheetRef[0] = builder.show();
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private static View makeButton(Activity activity, String text, int color, View.OnClickListener onClick) {
        TextView btn = new TextView(activity);
        btn.setText(text);
        btn.setTextColor(color);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        btn.setGravity(Gravity.CENTER);
        btn.setPadding(0, org.telegram.messenger.AndroidUtilities.dp(14), 0, org.telegram.messenger.AndroidUtilities.dp(14));
        btn.setOnClickListener(onClick);
        return btn;
    }

    private static View divider(Activity activity) {
        View v = new View(activity);
        v.setBackgroundColor(Theme.getColor(Theme.key_divider));
        v.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                org.telegram.messenger.AndroidUtilities.dp(1)));
        return v;
    }

    private static String readCrashLog() {
        File file = getCrashLogFile();
        if (file == null || !file.exists()) {
            return "(no crash log found)";
        }
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
        } catch (Exception e) {
            FileLog.e(e);
            return "(failed to read crash log)";
        }
        return sb.toString();
    }

    private static void copyToClipboard(String text) {
        try {
            ClipboardManager cm = (ClipboardManager) ApplicationLoader.applicationContext
                    .getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("Xenon crash log", text));
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private static void openPlugins(Activity activity) {
        try {
            if (activity instanceof org.telegram.ui.LaunchActivity) {
                org.telegram.ui.LaunchActivity la = (org.telegram.ui.LaunchActivity) activity;
                la.presentFragment(new zxc.iconic.xenon.settings.NekoPluginsActivity());
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    // ------------------------------------------------------------------
    // Plugin-level failures (not a full app crash)
    // ------------------------------------------------------------------

    /**
     * Report a plugin that threw during load or execution, but didn't take the
     * whole app down. Writes a full stack trace to the crash log and shows a
     * sheet so the user can copy the exact error (e.g. NoClassDefFoundError)
     * instead of a truncated bulletin.
     *
     * @param activity the current activity (for showing the sheet)
     * @param fileName the plugin file that failed
     * @param stage    where it failed, e.g. "loading" or "hook onNewMessage"
     * @param t        the throwable that was caught (the source of the log)
     */
    public static void reportPluginFailure(Activity activity, String fileName, String stage, Throwable t) {
        String report = buildPluginFailureReport(fileName, stage, t);
        writeCrashLog(report);
        if (activity != null) {
            org.telegram.messenger.AndroidUtilities.runOnUIThread(
                    () -> showPluginFailureSheet(activity, fileName, stage), 300);
        }
    }

    public static String buildPluginFailureReport(String fileName, String stage, Throwable t) {
        StringBuilder sb = new StringBuilder();
        sb.append("Xenon plugin failure report\n");
        sb.append("Plugin: ").append(fileName).append("\n");
        sb.append("Stage: ").append(stage).append("\n");
        sb.append("Time: ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                .format(new Date())).append("\n");
        sb.append("App: ").append(getAppVersion()).append("\n");
        sb.append("\n--- Stack trace ---\n");
        sb.append(t != null ? LogExceptionToString(t) : "(no throwable)");
        return sb.toString();
    }

    private static void showPluginFailureSheet(Activity activity, String fileName, String stage) {
        try {
            final String crashLog = readCrashLog();

            BottomSheet.Builder builder = new BottomSheet.Builder(activity, false, null);
            LinearLayout layout = new LinearLayout(activity);
            layout.setOrientation(LinearLayout.VERTICAL);
            int pad = org.telegram.messenger.AndroidUtilities.dp(24);
            layout.setPadding(pad, org.telegram.messenger.AndroidUtilities.dp(20), pad, pad);

            TextView title = new TextView(activity);
            title.setText("Plugin failed");
            title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
            title.setTypeface(org.telegram.messenger.AndroidUtilities.getTypeface("fonts/rmedium.ttf"));
            title.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            title.setPadding(0, 0, 0, org.telegram.messenger.AndroidUtilities.dp(12));
            layout.addView(title);

            TextView body = new TextView(activity);
            body.setText("The plugin \"" + fileName + "\" failed during " + stage
                    + " and has been disabled to keep the app stable. Copy the error below "
                    + "to report or debug it.");
            body.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            body.setLineSpacing(org.telegram.messenger.AndroidUtilities.dp(2), 1f);
            body.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
            body.setPadding(0, 0, 0, org.telegram.messenger.AndroidUtilities.dp(16));
            layout.addView(body);

            // Show the stack trace inline, scrollable-ish via max lines.
            TextView trace = new TextView(activity);
            trace.setText(crashLog);
            trace.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);
            trace.setMaxLines(12);
            trace.setVerticalScrollBarEnabled(true);
            trace.setMovementMethod(android.text.method.ScrollingMovementMethod.getInstance());
            trace.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText3));
            trace.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
            int tp = org.telegram.messenger.AndroidUtilities.dp(12);
            trace.setPadding(tp, tp, tp, tp);
            layout.addView(trace);

            final BottomSheet[] sheetRef = new BottomSheet[1];
            layout.addView(makeButton(activity, "Copy error", Theme.getColor(Theme.key_windowBackgroundWhiteBlueText), v -> {
                if (sheetRef[0] != null) sheetRef[0].dismiss();
                copyToClipboard(crashLog);
            }));
            layout.addView(divider(activity));
            layout.addView(makeButton(activity, "Open plugins", Theme.getColor(Theme.key_windowBackgroundWhiteBlueText), v -> {
                if (sheetRef[0] != null) sheetRef[0].dismiss();
                openPlugins(activity);
            }));
            layout.addView(divider(activity));
            layout.addView(makeButton(activity, "Close", Theme.getColor(Theme.key_windowBackgroundWhiteGrayText), v -> {
                if (sheetRef[0] != null) sheetRef[0].dismiss();
            }));

            builder.setCustomView(layout);
            sheetRef[0] = builder.show();
        } catch (Exception e) {
            FileLog.e(e);
        }
    }
}
