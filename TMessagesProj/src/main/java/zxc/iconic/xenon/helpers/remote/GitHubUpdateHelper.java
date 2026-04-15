package zxc.iconic.xenon.helpers.remote;

import android.text.TextUtils;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.BuildConfig;
import org.telegram.messenger.FileLog;
import org.telegram.tgnet.TLRPC;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;

public class GitHubUpdateHelper {
    private static final String GITHUB_API_URL = "https://api.github.com/repos/YOUR_USERNAME/Xenon/releases/latest";
    private static final Gson GSON = new Gson();

    public interface Delegate {
        void onUpdateAvailable(TLRPC.TL_help_appUpdate update);
        void onNoUpdate();
        void onError(String error);
    }

    public static void checkForUpdates(Delegate delegate) {
        AndroidUtilities.runOnUIThread(() -> {
            new Thread(() -> {
                try {
                    URL url = new URL(GITHUB_API_URL);
                    HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                    connection.setRequestMethod("GET");
                    connection.setRequestProperty("Accept", "application/vnd.github.v3+json");
                    connection.setConnectTimeout(10000);
                    connection.setReadTimeout(10000);

                    int responseCode = connection.getResponseCode();
                    if (responseCode == 200) {
                        BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
                        StringBuilder response = new StringBuilder();
                        String line;
                        while ((line = reader.readLine()) != null) {
                            response.append(line);
                        }
                        reader.close();

                        GitHubRelease release = GSON.fromJson(response.toString(), GitHubRelease.class);
                        
                        if (release != null && !TextUtils.isEmpty(release.tagName)) {
                            int remoteVersionCode = parseVersionCode(release.tagName);
                            
                            if (remoteVersionCode > BuildConfig.VERSION_CODE) {
                                TLRPC.TL_help_appUpdate update = new TLRPC.TL_help_appUpdate();
                                update.version = release.tagName;
                                update.text = release.body != null ? release.body : "";
                                update.can_not_skip = false;
                                
                                // Найти APK в assets
                                if (release.assets != null) {
                                    for (GitHubAsset asset : release.assets) {
                                        if (asset.name.endsWith(".apk") && !asset.name.contains("debug")) {
                                            update.url = asset.browserDownloadUrl;
                                            update.flags |= 4;
                                            break;
                                        }
                                    }
                                }
                                
                                AndroidUtilities.runOnUIThread(() -> delegate.onUpdateAvailable(update));
                            } else {
                                AndroidUtilities.runOnUIThread(delegate::onNoUpdate);
                            }
                        } else {
                            AndroidUtilities.runOnUIThread(delegate::onNoUpdate);
                        }
                    } else {
                        AndroidUtilities.runOnUIThread(() -> delegate.onError("HTTP " + responseCode));
                    }
                    connection.disconnect();
                } catch (Exception e) {
                    FileLog.e(e);
                    AndroidUtilities.runOnUIThread(() -> delegate.onError(e.getMessage()));
                }
            }).start();
        });
    }

    private static int parseVersionCode(String tagName) {
        try {
            // Убираем "v" из начала (v1.2.3 -> 1.2.3)
            String version = tagName.startsWith("v") ? tagName.substring(1) : tagName;
            // Разбиваем на части (1.2.3 -> [1, 2, 3])
            String[] parts = version.split("\\.");
            if (parts.length >= 3) {
                int major = Integer.parseInt(parts[0]);
                int minor = Integer.parseInt(parts[1]);
                int patch = Integer.parseInt(parts[2]);
                // Формируем version code (1.2.3 -> 10203)
                return major * 10000 + minor * 100 + patch;
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return 0;
    }

    private static class GitHubRelease {
        @SerializedName("tag_name")
        String tagName;
        
        @SerializedName("name")
        String name;
        
        @SerializedName("body")
        String body;
        
        @SerializedName("prerelease")
        boolean prerelease;
        
        @SerializedName("assets")
        List<GitHubAsset> assets;
    }

    private static class GitHubAsset {
        @SerializedName("name")
        String name;
        
        @SerializedName("browser_download_url")
        String browserDownloadUrl;
        
        @SerializedName("size")
        long size;
    }
}
