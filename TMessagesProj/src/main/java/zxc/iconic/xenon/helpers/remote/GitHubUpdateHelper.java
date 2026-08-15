package zxc.iconic.xenon.helpers.remote;

import android.text.TextUtils;

import androidx.annotation.Nullable;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.BuildConfig;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.SharedConfig;
import zxc.iconic.xenon.proxy.XrayTelegramProxyBridge;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;

/**
 * Checks for app updates against GitHub Releases for sinkclose/Xenon.
 *
 * <p>Polls {@code /releases/latest}. Release scheme:
 * <ul>
 *   <li>tag_name = (optional posral-) + short commit hash of the build</li>
 *   <li>name     = first line of the commit message</li>
 *   <li>body     = structured release notes (commit hash, checksums, etc.)</li>
 *   <li>assets   = APK files (Xenon-{version}-{code}-{abi}.apk)</li>
 * </ul>
 *
 * <p>Version comparison: a release is considered an update only if it was
 * built from a <b>newer</b> commit than the installed build. Ordering is
 * established by the commit timestamp, which the CI writes both into the
 * release body ("Build Date") and into {@code BuildConfig.GIT_COMMIT_DATE}.
 * The release body text is used as the changelog.
 */
public class GitHubUpdateHelper {

    private static final String TAG = "GitHubUpdateHelper";
    private static final String GITHUB_API_URL =
            "https://api.github.com/repos/sinkclose/Xenon/releases/latest";
    private static final String GITHUB_API_RELEASES_URL =
            "https://api.github.com/repos/sinkclose/Xenon/releases?per_page=100";
    /**
     * Tag prefix that marks ayu-features (prerelease) builds. A posral build
     * only ever looks at releases whose tag starts with this, and the CI for
     * ayu-features publishes with this prefix. Git forbids "[" / "]" in ref
     * names, so the human-readable "[posral]" lives in the release <i>name</i>
     * while the <i>tag</i> uses this dash form.
     */
    public static final String POSRAL_TAG_PREFIX = "posral-";
    private static final Gson GSON = new Gson();

    private GitHubUpdateHelper() {
    }

    public static HttpURLConnection openConnection(String urlStr) throws Exception {
        URL url = new URL(urlStr);
        java.net.Proxy proxy = java.net.Proxy.NO_PROXY;
        SharedConfig.ProxyInfo info = SharedConfig.isProxyEnabled() ? SharedConfig.currentProxy : null;
        if (info != null && !XrayTelegramProxyBridge.isLocalProxyAddress(info.address)
                && (info.secret == null || info.secret.isEmpty())) {
            proxy = socksProxy(info.address, info.port, info.username, info.password);
        }
        return (HttpURLConnection) url.openConnection(proxy);
    }

    private static java.net.Proxy socksProxy(String address, int port, String username, String password) {
        java.net.Proxy proxy = new java.net.Proxy(java.net.Proxy.Type.SOCKS,
                new java.net.InetSocketAddress(address, port));
        if (!TextUtils.isEmpty(username)) {
            final String user = username;
            final String pass = password != null ? password : "";
            java.net.Authenticator.setDefault(new java.net.Authenticator() {
                @Override
                protected java.net.PasswordAuthentication getPasswordAuthentication() {
                    return new java.net.PasswordAuthentication(user, pass.toCharArray());
                }
            });
        }
        return proxy;
    }

    /**
     * Whether the release is actually <b>newer</b> than the installed build.
     * Same tag means the same build. Otherwise the build date (commit
     * timestamp) decides the ordering; when it is unavailable, a different
     * tag is treated as an update.
     */
    private static boolean isNewerRelease(GitHubRelease release) {
        String currentCommit = BuildConfig.GIT_COMMIT_SHORT;
        if (!TextUtils.isEmpty(currentCommit) && !"unknown".equals(currentCommit)
                && currentCommit.equals(release.tagName)) {
            return false;
        }
        long installedDate = parseDateMillis(BuildConfig.GIT_COMMIT_DATE);
        long releaseDate = parseBodyBuildDateMillis(release.body);
        if (installedDate > 0 && releaseDate > 0) {
            return releaseDate > installedDate;
        }
        return true;
    }

    private static long parseBodyBuildDateMillis(String body) {
        if (TextUtils.isEmpty(body)) {
            return 0;
        }
        try {
            for (String line : body.split("\n")) {
                int idx = line.indexOf("Build Date:");
                if (idx >= 0) {
                    String date = line.substring(idx + "Build Date:".length())
                            .replace("*", "").trim();
                    long millis = parseDateMillis(date);
                    if (millis > 0) {
                        return millis;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return 0;
    }

    private static long parseDateMillis(String value) {
        if (TextUtils.isEmpty(value)) {
            return 0;
        }
        try {
            return java.time.OffsetDateTime.parse(value.trim()).toInstant().toEpochMilli();
        } catch (Throwable ignored) {
            return 0;
        }
    }

    /**
     * Callback for update check results.
     */
    public interface UpdateCallback {
        /**
         * Called when a newer release is found.
         *
         * @param release parsed latest release metadata
         */
        void onUpdateAvailable(GitHubRelease release);

        /**
         * Called when current build matches the latest release.
         */
        void onNoUpdate();

        /**
         * Called on network/parsing errors.
         */
        void onError(String error);
    }

    /**
     * Fetches the latest GitHub release and reports it as an update only when
     * it is actually newer than the installed build (see {@link #isNewerRelease}).
     * Results are delivered on the UI thread.
     *
     * @param callback result callback (never null)
     */
    public static void checkForUpdates(UpdateCallback callback) {
        checkForUpdates(callback, false);
    }

    public static void checkForUpdates(UpdateCallback callback, boolean force) {
        new Thread(() -> {
            try {
                FileLog.d(TAG + (force ? ": force checking for updates..." : ": checking for updates..."));
                GitHubRelease release = fetchLatestRelease();
                if (release == null || TextUtils.isEmpty(release.tagName)) {
                    FileLog.d(TAG + ": release is null or has no tag");
                    AndroidUtilities.runOnUIThread(callback::onNoUpdate);
                    return;
                }

                String apkUrl = findApkDownloadUrl(release);
                if (apkUrl == null) {
                    AndroidUtilities.runOnUIThread(() ->
                            callback.onError("No arm64 build for this release"));
                    return;
                }

                if (!force) {
                    // Tags alone can't establish ordering: a local/beta build
                    // (or an older release) has a different commit hash than
                    // the latest release. Use build dates instead — the CI
                    // writes the commit timestamp into the release body
                    // ("Build Date: ...") and into BuildConfig.GIT_COMMIT_DATE.
                    if (!isNewerRelease(release)) {
                        FileLog.d(TAG + ": release is not newer than current build, no update");
                        AndroidUtilities.runOnUIThread(callback::onNoUpdate);
                        return;
                    }
                }

                // Use release body as changelog (don't rely on flaky git commit fetch)
                FileLog.d(TAG + ": update available, apk=" + apkUrl);
                AndroidUtilities.runOnUIThread(() -> callback.onUpdateAvailable(release));
            } catch (Exception e) {
                FileLog.e(TAG, e);
                String msg = e.getMessage();
                AndroidUtilities.runOnUIThread(() ->
                        callback.onError(msg != null ? msg : "Unknown error"));
            }
        }, "XenonUpdateCheck").start();
    }

    /**
     * Fetches the latest <b>stable</b> (main) release regardless of the current
     * build channel. This always calls {@link #fetchLatestRelease()} (the
     * {@code /releases/latest} endpoint) and <strong>always</strong> reports the
     * release as an available update — no hash comparison.
     *
     * <p>Intended for the "Switch to main" button.
     */
    public static void checkForMainUpdate(UpdateCallback callback) {
        new Thread(() -> {
            try {
                FileLog.d(TAG + ": checking for main (stable) update...");
                GitHubRelease release = fetchLatestRelease();
                if (release == null || TextUtils.isEmpty(release.tagName)) {
                    AndroidUtilities.runOnUIThread(callback::onNoUpdate);
                    return;
                }
                String apkUrl = findApkDownloadUrl(release);
                if (apkUrl == null) {
                    AndroidUtilities.runOnUIThread(() ->
                            callback.onError("No arm64 build available"));
                    return;
                }
                AndroidUtilities.runOnUIThread(() -> callback.onUpdateAvailable(release));
            } catch (Exception e) {
                FileLog.e(TAG, e);
                String msg = e.getMessage();
                AndroidUtilities.runOnUIThread(() ->
                        callback.onError(msg != null ? msg : "Unknown error"));
            }
        }, "XenonMainUpdateCheck").start();
    }

    /**
     * Fetches the latest <b>ayu-features</b> (posral) release. This calls
     * {@link #fetchLatestPrefixedRelease(String)} with {@link #POSRAL_TAG_PREFIX}
     * and always reports the release as available.
     */
    public static void checkForAyuUpdate(UpdateCallback callback) {
        new Thread(() -> {
            try {
                FileLog.d(TAG + ": checking for ayu-features (posral) update...");
                GitHubRelease release = fetchLatestPrefixedRelease(POSRAL_TAG_PREFIX);
                if (release == null || TextUtils.isEmpty(release.tagName)) {
                    AndroidUtilities.runOnUIThread(callback::onNoUpdate);
                    return;
                }
                String apkUrl = findApkDownloadUrl(release);
                if (apkUrl == null) {
                    AndroidUtilities.runOnUIThread(() ->
                            callback.onError("No arm64 build available"));
                    return;
                }
                AndroidUtilities.runOnUIThread(() -> callback.onUpdateAvailable(release));
            } catch (Exception e) {
                FileLog.e(TAG, e);
                String msg = e.getMessage();
                AndroidUtilities.runOnUIThread(() ->
                        callback.onError(msg != null ? msg : "Unknown error"));
            }
        }, "XenonAyuUpdateCheck").start();
    }

    /**
     * Performs the HTTP request and parses JSON response.
     *
     * @return parsed release or null on failure
     */
    @Nullable
    private static GitHubRelease fetchLatestRelease() throws Exception {
        HttpURLConnection connection = null;
        try {
            connection = openConnection(GITHUB_API_URL);
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Accept", "application/vnd.github+json");
            connection.setRequestProperty("User-Agent", "Xenon-Updater/" + BuildConfig.VERSION_NAME);
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(15000);

            int code = connection.getResponseCode();
            if (code != 200) {
                throw new Exception("GitHub API returned HTTP " + code);
            }

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(connection.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder(4096);
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            reader.close();

            return GSON.fromJson(sb.toString(), GitHubRelease.class);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /**
     * Lists recent releases and returns the newest one whose {@code tag_name}
     * starts with {@code prefix}. Used by the posral channel, whose releases
     * are published as GitHub <i>prereleases</i> and therefore never appear at
     * {@code /releases/latest}. The list endpoint returns releases sorted by
     * {@code created_at} descending, so the first matching tag is the newest.
     *
     * <p>Unlike {@link #fetchLatestRelease()} this needs the unauthenticated
     * list endpoint to see prereleases; {@code per_page=100} covers far more
     * history than the posral stream is ever expected to accumulate.
     *
     * @param prefix tag prefix to match (case-insensitive), e.g. {@code "posral-"}
     * @return the newest matching release, or {@code null} if none matched
     */
    @Nullable
    private static GitHubRelease fetchLatestPrefixedRelease(String prefix) throws Exception {
        HttpURLConnection connection = null;
        try {
            connection = openConnection(GITHUB_API_RELEASES_URL);
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Accept", "application/vnd.github+json");
            connection.setRequestProperty("User-Agent", "Xenon-Updater/" + BuildConfig.VERSION_NAME);
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(15000);

            int code = connection.getResponseCode();
            if (code != 200) {
                throw new Exception("GitHub API returned HTTP " + code);
            }

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(connection.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder(4096);
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            reader.close();

            GitHubRelease[] releases = GSON.fromJson(sb.toString(), GitHubRelease[].class);
            if (releases == null) {
                return null;
            }
            GitHubRelease best = null;
            for (GitHubRelease release : releases) {
                if (release != null && release.tagName != null
                        && release.tagName.toLowerCase().startsWith(prefix)) {
                    if (best == null) {
                        best = release;
                    } else if (release.publishedAt != null && best.publishedAt != null
                            && release.publishedAt.compareTo(best.publishedAt) > 0) {
                        best = release;
                    }
                }
            }
            return best;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /**
     * Fetches the best APK download URL from release assets.
     * @param release the release to search
     * @return download URL of the arm64 APK, or {@code null} if the release
     *         does not contain a suitable arm64 build. Xenon ships arm64-only
     *         APKs by design (see {@code TMessagesProj_App/build.gradle}'s
     *         {@code splits.abi.include "arm64-v8a"}); offering any other ABI
     *         here would silently install something that won't run on a
     *         64-bit-only device, so we deliberately do NOT fall back to a
     *         "universal" or non-arm64 APK — better to surface "no compatible
     *         build" than to push an APK the package installer will reject.
     */
    @Nullable
    public static String findApkDownloadUrl(GitHubRelease release) {
        if (release == null || release.assets == null) {
            return null;
        }
        for (GitHubAsset asset : release.assets) {
            if (asset.name == null || !asset.name.endsWith(".apk")) {
                continue;
            }
            String lower = asset.name.toLowerCase();
            if (lower.contains("debug")) {
                continue;
            }
            if (lower.contains("arm64")) {
                return asset.browserDownloadUrl;
            }
        }
        return null;
    }

    /**
     * Extracts the arm64 APK file size from release assets.
     *
     * @param release the release to search
     * @return file size in bytes, or {@code -1} if no arm64 APK is present
     *         (Xenon is arm64-only — see {@link #findApkDownloadUrl}).
     */
    public static long findApkSize(GitHubRelease release) {
        if (release == null || release.assets == null) {
            return -1;
        }
        for (GitHubAsset asset : release.assets) {
            if (asset.name == null || !asset.name.endsWith(".apk")) continue;
            String lower = asset.name.toLowerCase();
            if (lower.contains("debug")) continue;
            if (lower.contains("arm64")) return asset.size;
        }
        return -1;
    }

    /**
     * GitHub Release JSON model.
     */
    public static class GitHubRelease {
        @SerializedName("tag_name")
        public String tagName;

        @SerializedName("name")
        public String name;

        @SerializedName("body")
        public String body;

        @SerializedName("prerelease")
        public boolean prerelease;

        @SerializedName("published_at")
        public String publishedAt;

        @SerializedName("html_url")
        public String htmlUrl;

        @SerializedName("assets")
        public List<GitHubAsset> assets;
    }

    /**
     * GitHub Release Asset JSON model.
     */
    public static class GitHubAsset {
        @SerializedName("name")
        public String name;

        @SerializedName("browser_download_url")
        public String browserDownloadUrl;

        @SerializedName("size")
        public long size;

        @SerializedName("content_type")
        public String contentType;
    }
}
