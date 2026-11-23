package org.telegram.messenger;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;

import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;

import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.LaunchActivity;

import java.util.LinkedHashMap;
import java.util.UUID;

/** Keeps document and app update downloads active while their UI is in the background. */
public final class FileDownloadService extends Service {
    private static final String CHANNEL_ID = "file_downloads";
    private static final int NOTIFICATION_ID = 0x58444C;
    private static final int UPDATE_NOTIFICATION_ID = 0x58444D;
    private static final String APP_UPDATE = "app_update";
    private static final String EXTRA_DOWNLOAD_KEY = "download_key";
    private static final String ACTION_CANCEL = "org.telegram.messenger.CANCEL_DOWNLOAD.";
    private static final LinkedHashMap<String, Download> downloads = new LinkedHashMap<>();
    private static FileDownloadService instance;

    private final boolean[] activeAccounts = new boolean[UserConfig.MAX_ACCOUNT_COUNT];
    private boolean foreground;
    private Download shownDownload;
    private boolean showingUpdateAlongsideDocument;
    private NotificationManager notifications;

    // FileLoader's manual entry point and the progress notifications run on the main thread.
    static void track(int account, String fileName, TLRPC.Document document, MessageObject message) {
        String key = key(account, fileName);
        if (downloads.containsKey(key)) {
            return;
        }
        Download download = new Download(account, fileName, document, message);
        addDownload(key, download);
    }

    static void startAppUpdate(ApplicationLoader updater) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            AndroidUtilities.runOnUIThread(() -> startAppUpdate(updater));
            return;
        }
        if (updater.isDownloadingUpdate() && !downloads.containsKey(APP_UPDATE)) {
            addDownload(APP_UPDATE, new Download(LocaleController.getString(R.string.DownloadingUpdate),
                    updater.getDownloadBytesDownloaded(), updater.getDownloadTotalSize()));
        }
    }

    static void syncAppUpdate(ApplicationLoader updater) {
        Download download = downloads.get(APP_UPDATE);
        if (download == null) {
            return;
        }
        if (updater.isDownloadingUpdate()) {
            updateProgress(download, updater.getDownloadBytesDownloaded(), updater.getDownloadTotalSize());
        } else {
            finishDownload(APP_UPDATE);
        }
    }

    private static void addDownload(String key, Download download) {
        downloads.put(key, download);
        if (instance != null && instance.foreground) {
            instance.updateActiveAccounts();
            instance.refreshNotifications();
            return;
        }
        Context context = ApplicationLoader.applicationContext;
        Intent intent = new Intent(context, FileDownloadService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
        } catch (IllegalStateException | SecurityException e) {
            downloads.remove(key);
            FileLog.e(e);
        }
    }

    static void updateProgress(int account, String fileName, long loaded, long total) {
        Download download = downloads.get(key(account, fileName));
        updateProgress(download, loaded, total);
    }

    private static void updateProgress(Download download, long loaded, long total) {
        if (download == null || (download.loaded == loaded && download.total == total)) {
            return;
        }
        download.loaded = loaded;
        if (total > 0 || download.account < 0) {
            download.total = total;
        }
        if (instance != null && instance.foreground) {
            instance.postProgress(download);
        }
    }

    static void finishIfIdle(int account, String fileName) {
        if (downloads.isEmpty() || FileLoader.getInstance(account).isLoadingFile(fileName)) {
            return;
        }
        finishDownload(key(account, fileName));
    }

    private static void finishDownload(String key) {
        if (downloads.remove(key) != null && instance != null && instance.foreground) {
            instance.updateActiveAccounts();
            if (downloads.isEmpty()) {
                instance.stopDownloadService();
            } else {
                instance.refreshNotifications();
            }
        }
    }

    private static String key(int account, String fileName) {
        return account + ":" + fileName;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        notifications = getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= 26) {
            notifications.createNotificationChannel(new NotificationChannel(
                    CHANNEL_ID, LocaleController.getString(R.string.DownloadsTabs),
                    NotificationManager.IMPORTANCE_LOW));
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        instance = this;
        shownDownload = selectForegroundDownload();
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(shownDownload),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            foreground = true;
        } catch (IllegalStateException | SecurityException e) {
            FileLog.e(e);
            downloads.clear();
            stopDownloadService();
            return START_NOT_STICKY;
        }
        updateActiveAccounts();
        if (downloads.isEmpty()) {
            stopDownloadService();
        } else {
            refreshNotifications();
        }
        return START_NOT_STICKY;
    }

    private static Download selectForegroundDownload() {
        for (Download download : downloads.values()) {
            if (download.account >= 0) {
                return download;
            }
        }
        return downloads.get(APP_UPDATE);
    }

    private void refreshNotifications() {
        Download next = selectForegroundDownload();
        Download update = downloads.get(APP_UPDATE);
        boolean showSeparateUpdate = update != null && next != update;
        if (shownDownload != next) {
            shownDownload = next;
            notifications.notify(NOTIFICATION_ID, buildNotification(next));
        }
        if (showSeparateUpdate != showingUpdateAlongsideDocument) {
            showingUpdateAlongsideDocument = showSeparateUpdate;
            if (showSeparateUpdate) {
                notifications.notify(UPDATE_NOTIFICATION_ID, buildNotification(update));
            } else {
                notifications.cancel(UPDATE_NOTIFICATION_ID);
            }
        }
    }

    private void postProgress(Download download) {
        int notificationId;
        if (shownDownload == download) {
            notificationId = NOTIFICATION_ID;
        } else if (download.account < 0 && showingUpdateAlongsideDocument) {
            notificationId = UPDATE_NOTIFICATION_ID;
        } else {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (download.account < 0 && now - download.lastNotificationTime < 500) {
            return;
        }
        download.lastNotificationTime = now;
        notifications.notify(notificationId, buildNotification(download));
    }

    private void updateActiveAccounts() {
        for (int account = 0; account < activeAccounts.length; account++) {
            boolean active = false;
            if (foreground) {
                for (Download download : downloads.values()) {
                    if (download.account == account) {
                        active = true;
                        break;
                    }
                }
            }
            if (activeAccounts[account] != active) {
                activeAccounts[account] = active;
                ConnectionsManager.getInstance(account).setBackgroundDownloadActive(active);
            }
        }
    }

    private void stopDownloadService() {
        foreground = false;
        shownDownload = null;
        updateActiveAccounts();
        notifications.cancel(UPDATE_NOTIFICATION_ID);
        showingUpdateAlongsideDocument = false;
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    @Override
    public void onTimeout(int startId, int foregroundServiceType) {
        downloads.clear();
        stopDownloadService();
    }

    @Override
    public void onDestroy() {
        if (instance == this) {
            instance = null;
            notifications.cancel(UPDATE_NOTIFICATION_ID);
            if (foreground) {
                downloads.clear();
                foreground = false;
                updateActiveAccounts();
            }
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private Notification buildNotification(Download download) {
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOnlyAlertOnce(true)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .setShowWhen(false)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setOngoing(true)
                .setContentTitle(LocaleController.getString(R.string.Downloading));
        if (download == null) {
            return builder.build();
        }
        Intent open = new Intent(this, LaunchActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if (download.account < 0) {
            open.setAction(Intent.ACTION_MAIN);
        } else {
            open.setAction("com.tmessages.openchat.download." + download.token)
                    .putExtra("currentAccount", download.account)
                    .putExtra("message_id", download.messageId);
            if (download.dialogId < 0) {
                open.putExtra("chatId", -download.dialogId).putExtra("topicId", download.topicId);
            } else {
                open.putExtra("userId", download.dialogId);
            }
        }
        builder.setContentIntent(PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        String downloadKey = download.account < 0 ? APP_UPDATE : key(download.account, download.fileName);
        Intent cancel = new Intent(this, CancelReceiver.class)
                .setAction(ACTION_CANCEL + download.token)
                .putExtra(EXTRA_DOWNLOAD_KEY, downloadKey);
        builder.addAction(R.drawable.ic_close_white, LocaleController.getString(R.string.Cancel),
                PendingIntent.getBroadcast(this, 0, cancel, PendingIntent.FLAG_IMMUTABLE));
        builder.setContentTitle(download.title);
        boolean indeterminate = download.total <= 0;
        int percent = indeterminate ? 0 : (int) Math.min(100, 100.0 * download.loaded / download.total);
        String loaded = AndroidUtilities.formatFileSize(download.loaded);
        builder.setContentText(indeterminate ? loaded : LocaleController.formatString(
                R.string.AccDescrDownloadProgress, loaded, AndroidUtilities.formatFileSize(download.total)));
        if (Build.VERSION.SDK_INT >= 36) {
            builder.setStyle(new NotificationCompat.ProgressStyle()
                    .setProgress(percent)
                    .setProgressIndeterminate(indeterminate));
            builder.setRequestPromotedOngoing(true);
            if (!indeterminate) {
                builder.setShortCriticalText(percent + "%");
            }
        } else {
            builder.setProgress(100, percent, indeterminate);
        }
        return builder.build();
    }

    public static final class CancelReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            String downloadKey = intent.getStringExtra(EXTRA_DOWNLOAD_KEY);
            Download download = downloads.get(downloadKey);
            if (download == null || !(ACTION_CANCEL + download.token).equals(intent.getAction())) {
                return;
            }
            finishDownload(downloadKey);
            if (download.document != null) {
                FileLoader.getInstance(download.account).cancelLoadFile(download.document);
            } else if (ApplicationLoader.applicationLoaderInstance != null) {
                ApplicationLoader.applicationLoaderInstance.cancelDownloadingUpdate();
            }
        }
    }

    private static final class Download {
        final String token = UUID.randomUUID().toString();
        final int account;
        final String title;
        final long dialogId;
        final int messageId;
        final long topicId;
        final String fileName;
        final TLRPC.Document document;
        long loaded;
        long total;
        long lastNotificationTime;

        Download(String title, long loaded, long total) {
            account = -1;
            this.title = title;
            dialogId = 0;
            messageId = 0;
            topicId = 0;
            fileName = null;
            document = null;
            this.loaded = loaded;
            this.total = total;
        }

        Download(int account, String fileName, TLRPC.Document document, MessageObject message) {
            this.account = account;
            this.fileName = fileName;
            this.document = document;
            String documentName = FileLoader.getDocumentFileName(document);
            title = TextUtils.isEmpty(documentName) ? LocaleController.getString(R.string.Downloading) : documentName;
            dialogId = message.getDialogId();
            messageId = message.getId();
            topicId = dialogId < 0 ? message.getTopicId() : 0;
            total = document.size;
            long[] progress = ImageLoader.getInstance().getFileProgressSizes(fileName);
            if (progress != null) {
                loaded = progress[0];
                if (progress[1] > 0) {
                    total = progress[1];
                }
            }
        }
    }
}
