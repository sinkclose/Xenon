package zxc.iconic.xenon.plugins;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import org.telegram.messenger.AndroidUtilities;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** ADB shell transport, explicitly enabled by the user, also in release builds. */
public class PluginDevProvider extends ContentProvider {
    public static final String PREF = "plugin_dev_mode";
    private static final AtomicBoolean busy = new AtomicBoolean();
    private static volatile String status = "idle";
    private static final int LIMIT = 2 * 1024 * 1024;

    private void checkAccess() {
        if (Binder.getCallingUid() != 2000) {
            throw new SecurityException("Plugin Dev mode accepts ADB shell only");
        }
        if (!getContext().getSharedPreferences("xenon_plugins", 0).getBoolean(PREF, false)) {
            throw new SecurityException("Enable Dev mode in Xenon > Plugins first");
        }
    }

    private static void result(String value) {
        status = value;
        Log.i("XenonPluginDev", value);
    }

    @Override public boolean onCreate() { return true; }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws java.io.FileNotFoundException {
        checkAccess();
        List<String> path = uri.getPathSegments();
        if (!"w".equals(mode) || path.size() != 2 || !"install".equals(path.get(0))
                || !path.get(1).matches("[A-Za-z0-9_.-]+\\.xplugin")) {
            throw new java.io.FileNotFoundException("Use /install/name.xplugin in write mode");
        }
        if (!busy.compareAndSet(false, true)) throw new java.io.FileNotFoundException("Dev operation in progress");
        try {
            ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
            result("receiving " + path.get(1));
            new Thread(() -> {
                File directory = null;
                File source = null;
                try (ParcelFileDescriptor.AutoCloseInputStream input = new ParcelFileDescriptor.AutoCloseInputStream(pipe[0])) {
                    directory = new File(getContext().getCacheDir(), "plugin-dev-" + System.nanoTime());
                    if (!directory.mkdir()) throw new IOException("Cannot create staging directory");
                    source = new File(directory, path.get(1));
                    try (FileOutputStream output = new FileOutputStream(source)) {
                        byte[] buffer = new byte[8192];
                        int total = 0, count;
                        while ((count = input.read(buffer)) != -1) {
                            if ((total += count) > LIMIT) throw new IOException("Plugin exceeds 2 MiB");
                            output.write(buffer, 0, count);
                        }
                        if (total == 0) throw new IOException("Empty plugin");
                    }
                    final File staged = source, stagingDirectory = directory;
                    AndroidUtilities.runOnUIThread(() -> {
                        try {
                            // Recheck: turning Dev mode off also cancels an in-flight upload.
                            if (!getContext().getSharedPreferences("xenon_plugins", 0).getBoolean(PREF, false)) {
                                throw new SecurityException("Dev mode was disabled");
                            }
                            PluginManager manager = PluginManager.getInstance();
                            PluginManager.LoadedPlugin plugin = manager.installFrom(staged);
                            if (plugin != null) result("installed " + plugin.fileName);
                            else if (PluginManager.isLastInstallEngineOff()) result("installed; plugin engine disabled");
                            else result("error: " + PluginManager.getLastParseError());
                        } catch (Throwable error) {
                            result("error: " + error);
                            Log.e("XenonPluginDev", "Install failed", error);
                        } finally {
                            staged.delete();
                            stagingDirectory.delete();
                            busy.set(false);
                        }
                    });
                } catch (Throwable error) {
                    if (source != null) source.delete();
                    if (directory != null) directory.delete();
                    result("error: " + error);
                    busy.set(false);
                }
            }, "plugin-dev-upload").start();
            return pipe[1];
        } catch (IOException error) {
            busy.set(false);
            throw new java.io.FileNotFoundException(error.toString());
        }
    }

    @Override public Bundle call(String method, String arg, Bundle extras) {
        checkAccess();
        if ("reload".equals(method)) {
            if (!busy.compareAndSet(false, true)) throw new IllegalStateException("Dev operation in progress");
            result("reloading");
            AndroidUtilities.runOnUIThread(() -> {
                try { PluginManager.getInstance().reloadAll(); result("reloaded"); }
                catch (Throwable error) { result("error: " + error); }
                finally { busy.set(false); }
            });
        } else if (!"status".equals(method)) throw new IllegalArgumentException("Use status or reload");
        Bundle value = new Bundle();
        value.putString("status", status);
        value.putBoolean("busy", busy.get());
        return value;
    }

    @Override public String getType(Uri uri) { checkAccess(); return "application/octet-stream"; }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) { checkAccess(); throw new UnsupportedOperationException(); }
    @Override public Uri insert(Uri uri, ContentValues values) { checkAccess(); throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] args) { checkAccess(); throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { checkAccess(); throw new UnsupportedOperationException(); }
}
