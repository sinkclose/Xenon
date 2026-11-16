package zxc.iconic.xenon.helpers;


import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.provider.Settings;

import androidx.core.app.NotificationChannelCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.LaunchActivity;

import java.io.File;

import zxc.iconic.xenon.NekoConfig;

public final class ApkInstaller {
    public static void installUpdate(Activity context, File apkFile) {
        if (context != null && apkFile != null && apkFile.exists()) {
            AndroidUtilities.openForView(apkFile, "install.apk", "application/vnd.android.package-archive", context, null, false);
        }
    }

    public static void installUpdate(Activity context, TLRPC.Document document) {
        if (context != null && document != null) {
            installUpdate(context, FileLoader.getInstance(UserConfig.selectedAccount).getPathToAttach(document, true));
        }
    }

    public static class UpdateReceiver extends BroadcastReceiver {

        @Override
        public void onReceive(Context context, Intent intent) {
            if (!Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())) return;

            var packageName = context.getPackageName();
            var installer = context.getPackageManager().getInstallerPackageName(packageName);
            if (!packageName.equals(installer)) return;

            var startIntent = new Intent(context, LaunchActivity.class).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || Settings.canDrawOverlays(context)) {
                context.startActivity(startIntent);
            } else {
                var channel = new NotificationChannelCompat.Builder("updated", NotificationManagerCompat.IMPORTANCE_HIGH)
                        .setName(LocaleController.getString(R.string.UpdateApp))
                        .setLightsEnabled(false)
                        .setVibrationEnabled(false)
                        .setSound(null, null)
                        .build();
                var notificationManager = NotificationManagerCompat.from(context);
                notificationManager.createNotificationChannel(channel);
                var pendingIntent = PendingIntent.getActivity(context, 0, startIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                notificationManager.notify(8732833,
                        new NotificationCompat.Builder(context, "updated")
                                .setSmallIcon(R.drawable.notification)
                                .setColor(NekoConfig.getNotificationColor())
                                .setShowWhen(false)
                                .setAutoCancel(true)
                                .setContentText(LocaleController.getString(R.string.UpdateInstalledNotification))
                                .setCategory(NotificationCompat.CATEGORY_STATUS)
                                .setContentIntent(pendingIntent)
                                .build());
            }
        }
    }
}
