package zxc.iconic.xenon.telega;

import android.app.Activity;
import android.content.SharedPreferences;
import android.net.Uri;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.Utilities;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import zxc.iconic.xenon.NekoConfig;

public class TelegaDetector {

    public interface Callback {
        void onResult(int state);
    }

    public static final int STATE_UNKNOWN = 0;
    public static final int STATE_IS_TELEGA = 1;
    public static final int STATE_WAS_TELEGA = 2;
    public static final int STATE_NOT_TELEGA = 3;

    private static final String PREFS_NAME = "nekoconfig";
    private static final String KEY_CACHE = "telega_detector_cache_v2";
    private static final long CACHE_TTL_MS = 6L * 60L * 60L * 1000L;
    private static final int MAX_CACHE_ENTRIES = 1500;

    private static final String CALLS_BASE_URL = "https://calls.okcdn.ru";
    private static final String CALLS_API_KEY = "CHKIPMKGDIHBABABA";
    private static final String SESSION_DATA = "{\"device_id\":\"telega_alert\",\"version\":2,\"client_version\":\"android_8\",\"client_type\":\"SDK_ANDROID\"}";

    private static final Object lock = new Object();
    private static final HashMap<Long, CacheEntry> cache = new HashMap<>();
    private static final HashMap<Long, List<Callback>> pending = new HashMap<>();
    private static final HashSet<Long> inFlight = new HashSet<>();
    private static volatile boolean loaded;

    private static class CacheEntry {
        int state;
        long checkedAt;
    }

    public static int getState(long userId) {
        if (!NekoConfig.telegaDetectorEnabled || userId <= 0) {
            return STATE_UNKNOWN;
        }
        ensureLoaded();
        synchronized (lock) {
            CacheEntry entry = cache.get(userId);
            if (entry == null) {
                return STATE_UNKNOWN;
            }
            if (System.currentTimeMillis() - entry.checkedAt > CACHE_TTL_MS) {
                return STATE_UNKNOWN;
            }
            return entry.state;
        }
    }

    public static void requestState(long userId, boolean force, Callback callback) {
        if (!NekoConfig.telegaDetectorEnabled || userId <= 0) {
            if (callback != null) {
                callback.onResult(STATE_UNKNOWN);
            }
            return;
        }
        ensureLoaded();
        int cachedState = getState(userId);
        if (!force && cachedState != STATE_UNKNOWN) {
            if (callback != null) {
                callback.onResult(cachedState);
            }
            return;
        }

        boolean shouldStartWorker;
        synchronized (lock) {
            if (callback != null) {
                pending.computeIfAbsent(userId, k -> new ArrayList<>()).add(callback);
            }
            shouldStartWorker = inFlight.add(userId);
        }
        if (!shouldStartWorker) {
            return;
        }

        Utilities.globalQueue.postRunnable(() -> {
            int state = detect(userId);
            if (state != STATE_UNKNOWN) {
                saveState(userId, state);
            }
            List<Callback> callbacks;
            synchronized (lock) {
                callbacks = pending.remove(userId);
                inFlight.remove(userId);
            }
            if (callbacks != null && !callbacks.isEmpty()) {
                AndroidUtilities.runOnUIThread(() -> {
                    for (Callback cb : callbacks) {
                        try {
                            cb.onResult(state);
                        } catch (Exception ignore) {
                        }
                    }
                });
            }
        });
    }

    private static int detect(long userId) {
        int previous = getHistoricalState(userId);
        try {
            JSONObject auth = postForm(CALLS_BASE_URL + "/api/auth/anonymLogin", new String[][]{
                    {"application_key", CALLS_API_KEY},
                    {"session_data", SESSION_DATA}
            });
            String sessionKey = auth != null ? auth.optString("session_key") : null;
            if (TextUtils.isEmpty(sessionKey)) {
                return STATE_UNKNOWN;
            }

            String externalIds = "[{\"id\":\"" + userId + "\",\"ok_anonym\":false}]";
            JSONObject result = postForm(CALLS_BASE_URL + "/api/vchat/getOkIdsByExternalIds", new String[][]{
                    {"application_key", CALLS_API_KEY},
                    {"session_key", sessionKey},
                    {"externalIds", externalIds}
            });

            boolean isTelega = false;
            if (result != null) {
                JSONArray ids = result.optJSONArray("ids");
                if (ids != null) {
                    for (int i = 0; i < ids.length(); i++) {
                        JSONObject item = ids.optJSONObject(i);
                        if (item == null) {
                            continue;
                        }
                        JSONObject external = item.optJSONObject("external_user_id");
                        if (external == null) {
                            continue;
                        }
                        if (String.valueOf(userId).equals(external.optString("id"))) {
                            isTelega = true;
                            break;
                        }
                    }
                }
            }

            if (isTelega) {
                return STATE_IS_TELEGA;
            }
            if (previous == STATE_IS_TELEGA || previous == STATE_WAS_TELEGA) {
                return STATE_WAS_TELEGA;
            }
            return STATE_NOT_TELEGA;
        } catch (Exception e) {
            FileLog.e(e);
            return STATE_UNKNOWN;
        }
    }

    private static JSONObject postForm(String url, String[][] params) throws Exception {
        Uri.Builder builder = new Uri.Builder();
        for (String[] pair : params) {
            builder.appendQueryParameter(pair[0], pair[1]);
        }
        byte[] body = builder.build().getEncodedQuery().getBytes(StandardCharsets.UTF_8);

        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(12000);
        connection.setReadTimeout(12000);
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");

        OutputStream output = null;
        BufferedInputStream input = null;
        try {
            output = connection.getOutputStream();
            output.write(body);
            output.flush();

            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) {
                return null;
            }

            input = new BufferedInputStream(connection.getInputStream());
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = input.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            String text = out.toString(StandardCharsets.UTF_8);
            if (TextUtils.isEmpty(text)) {
                return null;
            }
            return new JSONObject(text);
        } finally {
            try {
                if (output != null) {
                    output.close();
                }
            } catch (Exception ignore) {
            }
            try {
                if (input != null) {
                    input.close();
                }
            } catch (Exception ignore) {
            }
            connection.disconnect();
        }
    }

    private static void saveState(long userId, int state) {
        synchronized (lock) {
            CacheEntry entry = new CacheEntry();
            entry.state = state;
            entry.checkedAt = System.currentTimeMillis();
            cache.put(userId, entry);
            pruneCacheLocked();
            persistCacheLocked();
        }
    }

    private static void ensureLoaded() {
        if (loaded) {
            return;
        }
        synchronized (lock) {
            if (loaded) {
                return;
            }
            try {
                String raw = prefs().getString(KEY_CACHE, "{}");
                JSONObject root = new JSONObject(raw);
                Iterator<String> iterator = root.keys();
                while (iterator.hasNext()) {
                    String key = iterator.next();
                    long userId;
                    try {
                        userId = Long.parseLong(key);
                    } catch (Exception ignore) {
                        continue;
                    }
                    JSONObject item = root.optJSONObject(key);
                    if (item == null) {
                        continue;
                    }
                    CacheEntry entry = new CacheEntry();
                    entry.state = item.optInt("state", STATE_UNKNOWN);
                    entry.checkedAt = item.optLong("checked_at", 0L);
                    cache.put(userId, entry);
                }
            } catch (Exception e) {
                FileLog.e(e);
            }
            loaded = true;
        }
    }

    private static void persistCacheLocked() {
        try {
            JSONObject root = new JSONObject();
            for (Map.Entry<Long, CacheEntry> cacheEntry : cache.entrySet()) {
                Long userId = cacheEntry.getKey();
                CacheEntry entry = cacheEntry.getValue();
                if (entry == null) {
                    continue;
                }
                JSONObject item = new JSONObject();
                item.put("state", entry.state);
                item.put("checked_at", entry.checkedAt);
                root.put(String.valueOf(userId), item);
            }
            prefs().edit().putString(KEY_CACHE, root.toString()).apply();
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS_NAME, Activity.MODE_PRIVATE);
    }

    private static int getHistoricalState(long userId) {
        ensureLoaded();
        synchronized (lock) {
            CacheEntry entry = cache.get(userId);
            if (entry == null) {
                return STATE_UNKNOWN;
            }
            return entry.state;
        }
    }

    private static void pruneCacheLocked() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<Long, CacheEntry>> iterator = cache.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Long, CacheEntry> entry = iterator.next();
            CacheEntry value = entry.getValue();
            if (value == null || now - value.checkedAt > CACHE_TTL_MS) {
                iterator.remove();
            }
        }
        if (cache.size() <= MAX_CACHE_ENTRIES) {
            return;
        }
        while (cache.size() > MAX_CACHE_ENTRIES) {
            Long oldestKey = null;
            long oldestTs = Long.MAX_VALUE;
            for (Map.Entry<Long, CacheEntry> entry : cache.entrySet()) {
                CacheEntry value = entry.getValue();
                long ts = value == null ? 0L : value.checkedAt;
                if (ts < oldestTs) {
                    oldestTs = ts;
                    oldestKey = entry.getKey();
                }
            }
            if (oldestKey == null) {
                break;
            }
            cache.remove(oldestKey);
        }
    }
}
