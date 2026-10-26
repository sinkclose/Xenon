package zxc.iconic.xenon.settings;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Typeface;
import android.net.Uri;
import android.provider.OpenableColumns;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.Components.BulletinFactory;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.util.List;
import java.util.Locale;

import zxc.iconic.xenon.helpers.ClientFontFiles;
import zxc.iconic.xenon.helpers.ClientFonts;

/** Font library and document picker, owned by the appearance settings fragment. */
final class ClientFontPicker {
    static final int REQUEST_FONT = 5371;
    private final BaseNekoSettingsActivity host;
    private final Runnable changed;
    private boolean importing;

    ClientFontPicker(BaseNekoSettingsActivity host, Runnable changed) {
        this.host = host;
        this.changed = changed;
    }

    void show() {
        if (host.getParentActivity() == null || importing) return;
        host.showDialog(new ClientFontSheet(host, this::select, this::chooseFile));
    }

    private void chooseFile() {
        try {
            host.startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*")
                    .addCategory(Intent.CATEGORY_OPENABLE), REQUEST_FONT);
        } catch (Exception error) {
            FileLog.e(error);
            failure();
        }
    }

    private boolean select(String id) {
        try {
            ClientFonts.select(id);
            changed.run();
            return true;
        } catch (Exception error) {
            FileLog.e(error);
            failure();
            return false;
        }
    }

    void onResult(int result, Intent data) {
        Activity activity = host.getParentActivity();
        if (result != Activity.RESULT_OK || data == null || data.getData() == null || activity == null || importing) return;
        Uri uri = data.getData();
        android.content.Context context = activity.getApplicationContext();
        importing = true;
        AlertDialog progress = new AlertDialog(activity, AlertDialog.ALERT_TYPE_SPINNER);
        progress.setCanCancel(false);
        host.showDialog(progress);
        Utilities.globalQueue.postRunnable(() -> {
            List<ClientFontFiles.Entry> imported = null;
            boolean archive = false;
            try {
                String name = filename(context, uri);
                try (BufferedInputStream input = new BufferedInputStream(context.getContentResolver().openInputStream(uri))) {
                    input.mark(4);
                    int a = input.read(), b = input.read();
                    input.reset();
                    archive = name.toLowerCase(Locale.ROOT).endsWith(".zip") || a == 'P' && b == 'K';
                    imported = ClientFontFiles.importFonts(ClientFonts.directory(), input, name, archive, file -> {
                        Typeface face = Typeface.createFromFile(file);
                        if (face == null || face == Typeface.DEFAULT) throw new IOException("Invalid font");
                    });
                }
                ClientFonts.remember(imported);
            } catch (Exception error) { imported = null; FileLog.e(error); }
            List<ClientFontFiles.Entry> ready = imported;
            boolean isArchive = archive;
            AndroidUtilities.runOnUIThread(() -> {
                importing = false;
                try { progress.dismiss(); } catch (Exception ignored) {}
                if (ready == null) {
                    failure();
                } else {
                    // Archives expose every imported face; the user chooses which one to activate.
                    if (!isArchive && !select(ready.get(0).id)) return;
                    if (host.getParentActivity() != null) show();
                }
            });
        });
    }

    private static String filename(android.content.Context context, Uri uri) {
        try (Cursor cursor = context.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) return cursor.getString(0);
        } catch (Exception ignored) {}
        return uri.getLastPathSegment() == null ? "Font" : uri.getLastPathSegment();
    }

    private void failure() {
        if (host.getParentActivity() != null) BulletinFactory.of(host)
                .createErrorBulletin(LocaleController.getString(R.string.ClientFontImportFailed)).show();
    }
}
