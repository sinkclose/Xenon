package org.telegram.messenger;

import android.app.Activity;
import android.content.Context;
import android.text.TextUtils;
import android.view.ViewGroup;
import android.widget.Toast;

import org.telegram.messenger.regular.BuildConfig;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.Components.Bulletin;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.UpdateAppAlertDialog;
import org.telegram.ui.Components.UpdateLayout;
import org.telegram.ui.IUpdateLayout;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

import zxc.iconic.xenon.Extra;
import zxc.iconic.xenon.helpers.ApkInstaller;
import zxc.iconic.xenon.helpers.remote.GitHubUpdateHelper;

/**
 * Application loader with GitHub-based update integration.
 *
 * Uses {@link GitHubUpdateHelper} to check for new releases.
 * Supports the custom update path in {@link org.telegram.ui.LaunchActivity#checkAppUpdate}:
 * {@code isCustomUpdate() -> checkUpdate() -> getUpdate() -> showCustomUpdateAppPopup()}.
 */
public class ApplicationLoaderImpl extends ApplicationLoader {

    private static final String TAG = "ApplicationLoaderImpl";
    private static final String APK_DIR = "updates";

    private volatile BetaUpdate pendingUpdate;
    private volatile GitHubUpdateHelper.GitHubRelease pendingRelease;
    private volatile String pendingApkUrl;
    private volatile String pendingTitle;
    private volatile boolean downloading;
    private volatile float downloadProgress;
    private volatile long downloadTotalSize;
    private volatile long downloadBytesDownloaded;
    private volatile File downloadedApkFile;
    private volatile int checkCounter;

    @Override
    protected String onGetApplicationId() {
        return BuildConfig.APPLICATION_ID;
    }

    @Override
    protected boolean isStandalone() {
        return Extra.isDirectApp();
    }

    @Override
    public boolean isCustomUpdate() {
        return true;
    }

    @Override
    public void checkUpdate(boolean force, Runnable whenDone) {
        // Increment counter so each check produces a BetaUpdate with a unique
        // (monotonically increasing) versionCode. This prevents
        // BetaUpdate.higherThan() from returning false on repeated manual checks
        // when the same GitHub release is found — LaunchActivity captures
        // prevUpdate *before* calling checkUpdate(), so without this counter
        // the dialog would only appear on the very first check.
        final int thisCheck = ++checkCounter;
        GitHubUpdateHelper.checkForUpdates(new GitHubUpdateHelper.UpdateCallback() {
            @Override
            public void onUpdateAvailable(GitHubUpdateHelper.GitHubRelease release) {
                String title = !TextUtils.isEmpty(release.name) ? release.name : release.tagName;
                String changelog = release.body;
                String apkUrl = GitHubUpdateHelper.findApkDownloadUrl(release);
                if (TextUtils.isEmpty(apkUrl)) {
                    // Release exists but has no arm64-v8a APK asset (Xenon is
                    // arm64-only — see GitHubUpdateHelper#findApkDownloadUrl).
                    // Don't pretend an update is available: surface this as an
                    // error so the user knows why nothing happened.
                    FileLog.e(TAG + ": release " + release.tagName + " has no arm64 APK asset");
                    pendingUpdate = null;
                    pendingRelease = null;
                    pendingApkUrl = null;
                    AndroidUtilities.runOnUIThread(() -> {
                        try {
                            Toast.makeText(applicationContext,
                                    "No arm64 build for this release", Toast.LENGTH_LONG).show();
                        } catch (Throwable ignored) {}
                    });
                    if (whenDone != null) whenDone.run();
                    return;
                }
                pendingRelease = release;
                pendingApkUrl = apkUrl;
                pendingTitle = title;
                // version must be parseable as "x.y.z" for BetaUpdate.higherThan().
                // Use VERSION_NAME + ".1" so it's always >= current build.
                // thisCheck in versionCode guarantees higherThan(prevUpdate) == true
                // on every manual re-check of the same release.
                String versionStr = BuildConfig.VERSION_NAME + ".1";
                int versionCode = (int) (BuildConfig.VERSION_CODE + thisCheck);
                pendingUpdate = new BetaUpdate(versionStr, versionCode, changelog);
                downloadedApkFile = null;
                downloading = false;
                if (whenDone != null) whenDone.run();
            }

            @Override
            public void onNoUpdate() {
                pendingUpdate = null;
                pendingRelease = null;
                pendingApkUrl = null;
                if (whenDone != null) whenDone.run();
            }

            @Override
            public void onError(String error) {
                FileLog.e(TAG + ": update check error: " + error);
                pendingUpdate = null;
                pendingRelease = null;
                pendingApkUrl = null;
                // Show error to user — LaunchActivity would otherwise display
                // "Your version is up to date" because pendingUpdate is null.
                // This callback runs on the GitHubUpdateHelper worker thread,
                // which has no Looper; calling Toast.show() directly there
                // throws RuntimeException ("Can't toast on a thread that has
                // not called Looper.prepare()") and used to be silently
                // swallowed by a broad catch — meaning the user never saw any
                // feedback for failed checks. Always dispatch via the UI thread.
                AndroidUtilities.runOnUIThread(() -> {
                    try {
                        BulletinFactory.global()
                                .createSimpleBulletin(R.raw.chats_infotip,
                                        "Update check failed: " + error,
                                        "Retry",
                                        () -> checkUpdate(force, null))
                                .show();
                    } catch (Throwable ignored) {}
                });
                if (whenDone != null) whenDone.run();
            }
        }, false);
    }

    @Override
    public BetaUpdate getUpdate() {
        return pendingUpdate;
    }

    @Override
    public void downloadUpdate() {
        downloadUpdate(null);
    }

    public void downloadUpdate(Runnable onComplete) {
        if (downloading) return;
        String apkUrl = pendingApkUrl;
        if (TextUtils.isEmpty(apkUrl)) return;
        doDownload(apkUrl, onComplete);
    }

    public void downloadUpdate(String apkUrl, Runnable onComplete) {
        if (downloading) return;
        if (TextUtils.isEmpty(apkUrl)) return;
        pendingApkUrl = apkUrl;
        doDownload(apkUrl, onComplete);
    }

    private void doDownload(String apkUrl, Runnable onComplete) {

        downloading = true;
        downloadProgress = 0f;
        downloadTotalSize = 0;
        downloadBytesDownloaded = 0;
        downloadedApkFile = null;

        new Thread(() -> {
            HttpURLConnection connection = null;
            InputStream is = null;
            FileOutputStream fos = null;
            File tempFile = null;
            boolean cleanupPartial = false;
            try {
                File dir = new File(applicationContext.getCacheDir(), APK_DIR);
                if (!dir.exists()) dir.mkdirs();
                tempFile = new File(dir, "xenon_update.apk");
                if (tempFile.exists()) tempFile.delete();

                URL url = new URL(apkUrl);
                connection = (HttpURLConnection) url.openConnection();
                connection.setInstanceFollowRedirects(true);
                connection.setConnectTimeout(30000);
                connection.setReadTimeout(60000);
                connection.connect();

                int code = connection.getResponseCode();
                if (code != 200) {
                    throw new Exception("Download HTTP " + code);
                }

                long totalSize = connection.getContentLength();
                downloadTotalSize = totalSize;
                is = connection.getInputStream();
                fos = new FileOutputStream(tempFile);

                byte[] buffer = new byte[8192];
                long downloaded = 0;
                int read;
                while ((read = is.read(buffer)) != -1) {
                    if (!downloading) {
                        cleanupPartial = true;
                        return;
                    }
                    fos.write(buffer, 0, read);
                    downloaded += read;
                    downloadBytesDownloaded = downloaded;
                    if (totalSize > 0) {
                        downloadProgress = (float) downloaded / totalSize;
                    }
                }
                fos.flush();

                downloadedApkFile = tempFile;
                downloading = false;
                downloadProgress = 1f;
                if (pendingRelease != null && !TextUtils.isEmpty(pendingRelease.tagName)) {
                    File tagFile = new File(tempFile.getParent(), tempFile.getName() + ".tag");
                    try (java.io.FileWriter w = new java.io.FileWriter(tagFile)) {
                        w.write(pendingRelease.tagName);
                    } catch (Throwable ignored) {}
                }
                if (onComplete != null) {
                    AndroidUtilities.runOnUIThread(onComplete);
                }
            } catch (Throwable e) {
                FileLog.e(TAG + ": download failed", e);
                downloading = false;
                downloadProgress = 0f;
                cleanupPartial = true;
                AndroidUtilities.runOnUIThread(() -> {
                    try {
                        Toast.makeText(applicationContext,
                                "Download failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    } catch (Throwable ignored) {}
                });
            } finally {
                try { if (fos != null) fos.close(); } catch (Throwable ignored) {}
                try { if (is != null) is.close(); } catch (Throwable ignored) {}
                if (connection != null) connection.disconnect();
                if (cleanupPartial && tempFile != null && tempFile.exists()) {
                    if (!tempFile.delete()) {
                        FileLog.e(TAG + ": failed to delete partial APK at " + tempFile);
                    }
                }
            }
        }, "XenonUpdateDownload").start();
    }

    @Override
    public void cancelDownloadingUpdate() {
        downloading = false;
        downloadProgress = 0f;
        // If a fully-downloaded APK was already produced (download finished
        // before the user pressed cancel), delete it too — leaving the file
        // around would let getDownloadedUpdateFile() resurface a stale APK
        // on the next "Install update" tap.
        File f = downloadedApkFile;
        downloadedApkFile = null;
        if (f != null && f.exists()) {
            try { f.delete(); } catch (Throwable ignored) {}
        }
    }

    @Override
    public boolean isDownloadingUpdate() {
        return downloading;
    }

    @Override
    public float getDownloadingUpdateProgress() {
        return downloadProgress;
    }

    @Override
    public File getDownloadedUpdateFile() {
        return downloadedApkFile;
    }

    public long getDownloadTotalSize() {
        return downloadTotalSize;
    }

    public long getDownloadBytesDownloaded() {
        return downloadBytesDownloaded;
    }

    @Override
    public boolean showUpdateAppPopup(Context context, TLRPC.TL_help_appUpdate update, int account) {
        try {
            (new UpdateAppAlertDialog(context, update, account)).show();
        } catch (Exception e) {
            FileLog.e(e);
        }
        return true;
    }

    @Override
    public boolean showCustomUpdateAppPopup(Context context, BetaUpdate update, int account) {
        if (update == null) return false;

        File cachedApk = new File(applicationContext.getCacheDir(), APK_DIR + "/xenon_update.apk");
        if (cachedApk.exists() && cachedApk.length() > 0 && context instanceof Activity) {
            boolean tagMatch = false;
            File tagFile = new File(cachedApk.getParent(), cachedApk.getName() + ".tag");
            if (tagFile.exists() && pendingRelease != null && !TextUtils.isEmpty(pendingRelease.tagName)) {
                try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(tagFile))) {
                    tagMatch = pendingRelease.tagName.equals(r.readLine());
                } catch (Throwable ignored) {}
            }
            if (tagMatch) {
                downloadedApkFile = cachedApk;
                Activity activity = (Activity) context;
                try {
                    BulletinFactory.global()
                            .createSimpleBulletin(R.raw.ic_download,
                                    LocaleController.getString(R.string.UpdateDownloaded),
                                    "Update",
                                    Integer.MAX_VALUE,
                                    () -> ApkInstaller.installUpdate(activity, cachedApk))
                            .show();
                } catch (Throwable ignored) {}
                return true;
            }
        }

        try {
            TLRPC.TL_help_appUpdate appUpdate = new TLRPC.TL_help_appUpdate();
            appUpdate.version = !TextUtils.isEmpty(pendingTitle) ? pendingTitle : update.version;
            appUpdate.text = update.changelog != null ? update.changelog : "";
            appUpdate.can_not_skip = false;
            if (!TextUtils.isEmpty(pendingApkUrl)) {
                appUpdate.url = pendingApkUrl;
                appUpdate.flags |= 4;
            }
            UpdateAppAlertDialog dialog = new UpdateAppAlertDialog(context, appUpdate, account);
            dialog.setOnDownloadClickListener(() -> {
                final Bulletin[] progBulletin = new Bulletin[1];
                AndroidUtilities.runOnUIThread(() -> {
                    try {
                        Bulletin b = BulletinFactory.global()
                                .createSimpleBulletin(R.raw.ic_download, "Downloading update...", "Cancel", Integer.MAX_VALUE, () -> cancelDownloadingUpdate());
                        if (b.getLayout() instanceof Bulletin.LottieLayout) {
                            ((Bulletin.LottieLayout) b.getLayout()).setIconPaddingBottom(2);
                        }
                        b.show();
                        progBulletin[0] = b;
                    } catch (Throwable ignored) {}
                }, 100);
                downloadUpdate(() -> {
                    AndroidUtilities.runOnUIThread(() -> {
                        try { if (progBulletin[0] != null) progBulletin[0].hide(); } catch (Throwable ignored) {}
                    });
                    File apkFile = getDownloadedUpdateFile();
                    if (apkFile != null && apkFile.exists() && context instanceof Activity) {
                        Activity activity = (Activity) context;
                        AndroidUtilities.runOnUIThread(() -> {
                            try {
                                Bulletin b2 = BulletinFactory.global()
                                        .createSimpleBulletin(R.raw.ic_download,
                                                LocaleController.getString(R.string.UpdateDownloaded),
                                                "Update",
                                                Integer.MAX_VALUE,
                                                () -> ApkInstaller.installUpdate(activity, apkFile));
                                if (b2.getLayout() instanceof Bulletin.LottieLayout) {
                                    ((Bulletin.LottieLayout) b2.getLayout()).setIconPaddingBottom(2);
                                }
                                b2.show();
                            } catch (Throwable ignored) {}
                        });
                    }
                });
                AndroidUtilities.runOnUIThread(new Runnable() {
                    @Override
                    public void run() {
                        if (isDownloadingUpdate() && progBulletin[0] != null) {
                            try {
                                float prog = getDownloadingUpdateProgress();
                                long total = getDownloadTotalSize();
                                long downloaded = getDownloadBytesDownloaded();
                                String text;
                                if (total > 0) {
                                    String d = android.text.format.Formatter.formatShortFileSize(applicationContext, downloaded);
                                    String t = android.text.format.Formatter.formatShortFileSize(applicationContext, total);
                                    text = "Downloading update... " + d + " / " + t;
                                } else {
                                    text = "Downloading update... " + (int)(prog * 100) + "%";
                                }
                                ((Bulletin.LottieLayout) progBulletin[0].getLayout()).textView.setText(text);
                            } catch (Throwable ignored) {}
                            AndroidUtilities.runOnUIThread(this, 500);
                        }
                    }
                }, 500);
            });
            dialog.show();
        } catch (Exception e) {
            FileLog.e(e);
        }
        return true;
    }

    @Override
    public IUpdateLayout takeUpdateLayout(Activity activity, ViewGroup sideMenuContainer) {
        return new UpdateLayout(activity, sideMenuContainer);
    }
}
