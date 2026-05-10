package org.telegram.messenger;

import android.app.Activity;
import android.content.Context;
import android.text.TextUtils;
import android.view.ViewGroup;

import org.telegram.messenger.regular.BuildConfig;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.Components.UpdateAppAlertDialog;
import org.telegram.ui.Components.UpdateLayout;
import org.telegram.ui.IUpdateLayout;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

import zxc.iconic.xenon.Extra;
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
    private volatile File downloadedApkFile;

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
        GitHubUpdateHelper.checkForUpdates(new GitHubUpdateHelper.UpdateCallback() {
            @Override
            public void onUpdateAvailable(GitHubUpdateHelper.GitHubRelease release) {
                String title = !TextUtils.isEmpty(release.name) ? release.name : release.tagName;
                String changelog = release.body;
                pendingRelease = release;
                pendingApkUrl = GitHubUpdateHelper.findApkDownloadUrl(release);
                pendingTitle = title;
                // version must be parseable as "x.y.z" for BetaUpdate.higherThan() comparison.
                // Use APP_VERSION_NAME + ".1" to guarantee it's always higher than the
                // current build's version (same base). The tag_name (commit hash) uniqueness
                // already ensures we only reach here when a genuinely new release exists.
                String versionStr = BuildConfig.VERSION_NAME + ".1";
                int versionCode = (int) (BuildConfig.VERSION_CODE + 1);
                pendingUpdate = new BetaUpdate(versionStr, versionCode, changelog);
                downloadedApkFile = null;
                downloading = false;
                if (whenDone != null) {
                    AndroidUtilities.runOnUIThread(whenDone);
                }
            }

            @Override
            public void onNoUpdate() {
                pendingUpdate = null;
                pendingRelease = null;
                pendingApkUrl = null;
                if (whenDone != null) {
                    AndroidUtilities.runOnUIThread(whenDone);
                }
            }

            @Override
            public void onError(String error) {
                FileLog.d(TAG + ": update check error: " + error);
                pendingUpdate = null;
                pendingRelease = null;
                pendingApkUrl = null;
                if (whenDone != null) {
                    AndroidUtilities.runOnUIThread(whenDone);
                }
            }
        });
    }

    @Override
    public BetaUpdate getUpdate() {
        return pendingUpdate;
    }

    @Override
    public void downloadUpdate() {
        if (downloading) return;
        String apkUrl = pendingApkUrl;
        if (TextUtils.isEmpty(apkUrl)) return;

        downloading = true;
        downloadProgress = 0f;
        downloadedApkFile = null;

        new Thread(() -> {
            HttpURLConnection connection = null;
            InputStream is = null;
            FileOutputStream fos = null;
            try {
                File dir = new File(applicationContext.getCacheDir(), APK_DIR);
                if (!dir.exists()) dir.mkdirs();
                File tempFile = new File(dir, "xenon_update.apk");
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
                is = connection.getInputStream();
                fos = new FileOutputStream(tempFile);

                byte[] buffer = new byte[8192];
                long downloaded = 0;
                int read;
                while ((read = is.read(buffer)) != -1) {
                    if (!downloading) {
                        tempFile.delete();
                        return;
                    }
                    fos.write(buffer, 0, read);
                    downloaded += read;
                    if (totalSize > 0) {
                        downloadProgress = (float) downloaded / totalSize;
                    }
                }
                fos.flush();

                downloadedApkFile = tempFile;
                downloading = false;
                downloadProgress = 1f;
            } catch (Throwable e) {
                FileLog.e(TAG + ": download failed", e);
                downloading = false;
                downloadProgress = 0f;
            } finally {
                try { if (fos != null) fos.close(); } catch (Throwable ignored) {}
                try { if (is != null) is.close(); } catch (Throwable ignored) {}
                if (connection != null) connection.disconnect();
            }
        }, "XenonUpdateDownload").start();
    }

    @Override
    public void cancelDownloadingUpdate() {
        downloading = false;
        downloadProgress = 0f;
        downloadedApkFile = null;
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
        try {
            TLRPC.TL_help_appUpdate appUpdate = new TLRPC.TL_help_appUpdate();
            appUpdate.version = !TextUtils.isEmpty(pendingTitle) ? pendingTitle : update.version;
            appUpdate.text = update.changelog != null ? update.changelog : "";
            appUpdate.can_not_skip = false;
            if (!TextUtils.isEmpty(pendingApkUrl)) {
                appUpdate.url = pendingApkUrl;
                appUpdate.flags |= 4;
            }
            (new UpdateAppAlertDialog(context, appUpdate, account)).show();
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
