package org.telegram.messenger;

import android.app.Activity;
import android.content.Context;
import android.text.TextUtils;
import org.telegram.messenger.regular.BuildConfig;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.UpdateAppAlertDialog;
import org.telegram.ui.Components.UpdateLayout;
import org.telegram.ui.IUpdateLayout;
import android.view.ViewGroup;
import java.io.*;
import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.List;
import zxc.iconic.xenon.Extra;
import zxc.iconic.xenon.helpers.ApkInstaller;
import zxc.iconic.xenon.helpers.remote.GitHubUpdateHelper;

/** One update session shared by automatic checks, settings and the chats menu. */
public class ApplicationLoaderImpl extends ApplicationLoader {
    private static final String APK_DIR = "updates";
    private static final int MAX_DOWNLOAD_RETRIES = 5;
    private final Object downloadLock = new Object();
    private final List<Runnable> downloadCompletions = new ArrayList<>();
    private volatile BetaUpdate pendingUpdate;
    private volatile GitHubUpdateHelper.GitHubRelease pendingRelease;
    private volatile String pendingApkUrl;
    private volatile boolean downloading, cancelled, retryingUpdate;
    private volatile float downloadProgress;
    private volatile long downloadTotalSize, downloadBytesDownloaded;
    private volatile File downloadedApkFile;
    private volatile String downloadedUrl;
    private volatile String updateCheckError;
    private volatile HttpURLConnection activeConnection;
    // Remains set until the worker closes its streams and removes its partial file.
    private Thread downloadWorker;
    private int checkCounter;

    @Override protected String onGetApplicationId() { return BuildConfig.APPLICATION_ID; }
    @Override protected boolean isStandalone() { return Extra.isDirectApp(); }
    @Override public boolean isCustomUpdate() { return true; }

    @Override public void setGitHubUpdate(GitHubUpdateHelper.GitHubRelease release) {
        synchronized (downloadLock) {
            // A check finishing late must not replace the release being downloaded.
            if (downloadWorker != null) return;
            String url = GitHubUpdateHelper.findApkDownloadUrl(release);
            if (TextUtils.isEmpty(url)) return;
            if (!url.equals(pendingApkUrl)) {
                downloadedApkFile = null;
                downloadedUrl = null;
                downloadProgress = 0;
                downloadBytesDownloaded = 0;
            }
            pendingRelease = release;
            pendingApkUrl = url;
            pendingUpdate = new BetaUpdate(BuildConfig.VERSION_NAME + ".1",
                    BuildConfig.VERSION_CODE + ++checkCounter, release.name);
            downloadedApkFile = findDownloadedApk(url);
            if (downloadedApkFile != null) {
                downloadedUrl = url;
                downloadProgress = 1;
                downloadBytesDownloaded = downloadedApkFile.length();
            }
            downloadTotalSize = GitHubUpdateHelper.findApkSize(release);
        }
        notifyUpdateDownloadChanged();
    }

    @Override public void checkUpdate(boolean force, Runnable whenDone) {
        updateCheckError = null;
        synchronized (downloadLock) {
            if (downloadWorker != null || getDownloadedUpdateFile() != null) {
                if (whenDone != null) AndroidUtilities.runOnUIThread(whenDone);
                return;
            }
        }
        GitHubUpdateHelper.checkForUpdates(new GitHubUpdateHelper.UpdateCallback() {
            @Override public void onUpdateAvailable(GitHubUpdateHelper.GitHubRelease release) {
                setGitHubUpdate(release);
                if (whenDone != null) whenDone.run();
            }
            @Override public void onNoUpdate() {
                synchronized (downloadLock) {
                    if (downloadWorker == null && getDownloadedUpdateFile() == null) {
                        pendingUpdate = null;
                        pendingRelease = null;
                        pendingApkUrl = null;
                    }
                }
                if (whenDone != null) whenDone.run();
            }
            @Override public void onError(String error) {
                updateCheckError = error;
                synchronized (downloadLock) {
                    if (downloadWorker == null && getDownloadedUpdateFile() == null) pendingUpdate = null;
                }
                FileLog.e("Update check failed: " + error);
                BulletinFactory.global().createSimpleBulletin(R.raw.chats_infotip,
                        "Update check failed: " + error, "Retry", () -> retryUpdateCheck(force)).show();
                if (whenDone != null) whenDone.run();
            }
        }, false);
    }

    @Override public BetaUpdate getUpdate() { return pendingUpdate; }
    @Override public String getUpdateCheckError() { return updateCheckError; }
    @Override public void downloadUpdate() { downloadUpdate((Runnable) null); }
    @Override public void downloadUpdate(Runnable complete) { downloadUpdate(pendingApkUrl, complete); }
    @Override public void downloadUpdate(String url, Runnable complete) {
        if (TextUtils.isEmpty(url)) return;
        synchronized (downloadLock) {
            if (downloadWorker != null) {
                if (!cancelled && complete != null) downloadCompletions.add(complete);
                return;
            }
            File cached = findDownloadedApk(url);
            if (cached != null) {
                pendingApkUrl = url;
                downloadedApkFile = cached;
                downloadedUrl = url;
                downloadProgress = 1;
                downloadBytesDownloaded = downloadTotalSize = cached.length();
                notifyUpdateDownloadChanged();
                if (complete != null) AndroidUtilities.runOnUIThread(complete);
                return;
            }
            pendingApkUrl = url;
            downloadedApkFile = null;
            downloadedUrl = null;
            cancelled = false;
            downloading = true;
            retryingUpdate = false;
            downloadProgress = 0;
            downloadBytesDownloaded = 0;
            downloadCompletions.clear();
            if (complete != null) downloadCompletions.add(complete);
            downloadWorker = new Thread(() -> runDownload(url), "XenonUpdateDownload");
            downloadWorker.start();
        }
        notifyUpdateDownloadChanged();
    }

    private void runDownload(String url) {
        String error = null;
        boolean success = false;
        for (int attempt = 0; attempt <= MAX_DOWNLOAD_RETRIES && !cancelled; attempt++) {
            retryingUpdate = attempt > 0;
            downloadProgress = 0;
            downloadBytesDownloaded = 0;
            downloadTotalSize = GitHubUpdateHelper.findApkSize(pendingRelease);
            notifyUpdateDownloadChanged();
            try {
                downloadOnce(url);
                success = true;
                break;
            } catch (Exception e) {
                error = e.getMessage();
                if (!cancelled) FileLog.e("Update download attempt failed", e);
            }
            // Retry immediately. Each attempt starts with a clean partial file and counters.
        }
        final List<Runnable> completions;
        final boolean wasCancelled;
        synchronized (downloadLock) {
            success &= !cancelled;
            if (!success) {
                if (cancelled && downloadedApkFile != null) {
                    File identity = new File(downloadedApkFile.getParent(), downloadedApkFile.getName() + ".url");
                    if (identity.exists() && !identity.delete()) FileLog.e("Cannot remove cancelled APK identity");
                    if (!downloadedApkFile.delete()) FileLog.e("Cannot remove cancelled APK");
                }
                downloadedApkFile = null;
                downloadedUrl = null;
                downloadProgress = 0;
                downloadBytesDownloaded = 0;
            }
            completions = success ? new ArrayList<>(downloadCompletions) : new ArrayList<>();
            downloadCompletions.clear();
            retryingUpdate = false;
            downloading = false;
            downloadWorker = null;
            wasCancelled = cancelled;
        }
        notifyUpdateDownloadChanged();
        final boolean completed = success;
        final String failure = error;
        AndroidUtilities.runOnUIThread(() -> {
            for (Runnable callback : completions) {
                try { callback.run(); } catch (Exception e) { FileLog.e(e); }
            }
            if (completed) {
                File apk = getDownloadedUpdateFile();
                Activity activity = getUpdateActivity();
                if (apk != null && activity != null && !activity.isFinishing()) {
                    BulletinFactory.global().createSimpleBulletin(R.raw.ic_download,
                            LocaleController.getString(R.string.UpdateDownloaded),
                            LocaleController.getString(R.string.NekoUpdate),
                            () -> ApkInstaller.installUpdate(activity, apk)).show();
                }
            } else if (!wasCancelled) {
                BulletinFactory.global().createSimpleBulletin(R.raw.chats_infotip,
                        "Download failed: " + failure, "Retry", this::downloadUpdate).show();
            }
        });
    }

    private void downloadOnce(String url) throws Exception {
        File dir = new File(applicationContext.getCacheDir(), APK_DIR);
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Cannot create updates directory");
        File partial = new File(dir, "xenon_update.apk.part");
        HttpURLConnection connection = GitHubUpdateHelper.openConnection(url);
        try {
            activeConnection = connection;
            if (cancelled) throw new IOException("Cancelled");
            connection.setInstanceFollowRedirects(true);
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(15000);
            connection.setRequestProperty("Accept-Encoding", "identity");
            if (connection.getResponseCode() != 200) throw new IOException("Download HTTP " + connection.getResponseCode());
            long length = connection.getContentLengthLong();
            if (length > 0) downloadTotalSize = length;
            long lastProgress = 0;
            try (InputStream input = connection.getInputStream(); FileOutputStream output = new FileOutputStream(partial)) {
                byte[] buffer = new byte[32768];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    if (cancelled) throw new IOException("Cancelled");
                    output.write(buffer, 0, read);
                    downloadBytesDownloaded += read;
                    retryingUpdate = false;
                    if (downloadTotalSize > 0) downloadProgress = Math.min(1, (float) downloadBytesDownloaded / downloadTotalSize);
                    long now = android.os.SystemClock.elapsedRealtime();
                    if (now - lastProgress >= 100) {
                        lastProgress = now;
                        notifyUpdateDownloadChanged();
                    }
                }
                output.flush();
            }
            if (cancelled) throw new IOException("Cancelled");
            if (downloadBytesDownloaded == 0 || (downloadTotalSize > 0 && downloadBytesDownloaded != downloadTotalSize)) {
                throw new IOException("Incomplete APK download");
            }
            // Only a complete APK can be found by subsequent checks, even after process death.
            File apk = new File(dir, "xenon_update.apk");
            synchronized (downloadLock) {
                if (cancelled) throw new IOException("Cancelled");
                File identity = new File(dir, "xenon_update.apk.url");
                if (identity.exists() && !identity.delete()) throw new IOException("Cannot remove old APK identity");
                if (apk.exists() && !apk.delete()) throw new IOException("Cannot replace APK");
                if (!partial.renameTo(apk)) throw new IOException("Cannot save APK");
                try (FileWriter writer = new FileWriter(identity)) { writer.write(url); }
                downloadedUrl = url;
                downloadedApkFile = apk;
                downloadProgress = 1;
            }
        } finally {
            activeConnection = null;
            connection.disconnect();
            if (partial.exists() && !partial.delete()) FileLog.e("Cannot delete partial update APK");
        }
    }

    @Override public void cancelDownloadingUpdate() {
        HttpURLConnection connection;
        synchronized (downloadLock) {
            cancelled = true;
            connection = activeConnection;
            downloadCompletions.clear();
        }
        if (connection != null) connection.disconnect();
        notifyUpdateDownloadChanged();
    }
    @Override public boolean isDownloadingUpdate() { return downloading && !cancelled; }
    @Override public boolean deleteDownloadedUpdateFile() {
        synchronized (downloadLock) {
            // Never remove the APK while a worker is finalizing it.
            if (downloadWorker != null) return false;
            File apk = getDownloadedUpdateFile();
            if (apk == null || !apk.delete()) return false;
            File identity = new File(apk.getParent(), apk.getName() + ".url");
            if (identity.exists() && !identity.delete()) FileLog.e("Cannot delete update identity");
            downloadedApkFile = null;
            downloadedUrl = null;
            downloadProgress = 0;
            downloadBytesDownloaded = 0;
        }
        notifyUpdateDownloadChanged();
        return true;
    }
    @Override public boolean isRetryingUpdate() { return retryingUpdate; }
    @Override public float getDownloadingUpdateProgress() { return downloadProgress; }
    @Override public long getDownloadTotalSize() { return downloadTotalSize; }
    @Override public long getDownloadBytesDownloaded() { return downloadBytesDownloaded; }
    @Override public File getDownloadedUpdateFile() {
        File file = downloadedApkFile;
        return !downloading && file != null && file.exists() ? file : null;
    }
    private File findDownloadedApk(String url) {
        File file = getDownloadedUpdateFile();
        if (url.equals(downloadedUrl) && file != null) return file;
        File apk = new File(applicationContext.getCacheDir(), APK_DIR + "/xenon_update.apk");
        File identity = new File(apk.getParent(), apk.getName() + ".url");
        if (apk.length() <= 0 || !identity.exists()) return null;
        try (BufferedReader reader = new BufferedReader(new FileReader(identity))) {
            return url.equals(reader.readLine()) ? apk : null;
        } catch (IOException e) { return null; }
    }

    @Override public void showUpdateDownload(Context context, int account) {
        showCustomUpdateAppPopup(context, pendingUpdate, account);
    }
    @Override public boolean showUpdateAppPopup(Context context, TLRPC.TL_help_appUpdate update, int account) {
        new UpdateAppAlertDialog(context, update, account).show();
        return true;
    }
    @Override public boolean showCustomUpdateAppPopup(Context context, BetaUpdate update, int account) {
        if (update == null || pendingRelease == null) return false;
        TLRPC.TL_help_appUpdate info = new TLRPC.TL_help_appUpdate();
        GitHubUpdateHelper.GitHubRelease release = pendingRelease;
        info.version = !TextUtils.isEmpty(release.name) ? release.name : release.tagName;
        info.text = release.changelog != null ? release.changelog : LocaleController.getString(R.string.Loading);
        info.url = pendingApkUrl;
        info.flags |= 4;
        UpdateAppAlertDialog dialog = new UpdateAppAlertDialog(context, info, account);
        dialog.bindUpdateDownload();
        GitHubUpdateHelper.loadChangelog(release, dialog::setChangelog);
        dialog.show();
        return true;
    }
    @Override public IUpdateLayout takeUpdateLayout(Activity activity, ViewGroup container) {
        return new UpdateLayout(activity, container);
    }
}
