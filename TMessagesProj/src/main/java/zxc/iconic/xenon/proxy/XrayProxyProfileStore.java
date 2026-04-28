package zxc.iconic.xenon.proxy;

import android.app.Activity;
import android.content.SharedPreferences;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.ApplicationLoader;

import java.util.ArrayList;

import zxc.iconic.xenon.NekoConfig;

/**
 * Persistent storage for multiple Xray proxy profiles with active profile selection.
 */
public final class XrayProxyProfileStore {

    public static final String DEFAULT_CHECK_URL = "https://www.gstatic.com/generate_204";

    private static final String PREFS_NAME = "nekoconfig";
    private static final String KEY_PROFILES_JSON = "xrayAppProxyProfilesV1";
    private static final String KEY_ACTIVE_PROFILE_ID = "xrayAppProxyActiveProfileIdV1";

    private static final Object LOCK = new Object();

    private static ArrayList<Profile> cachedProfiles;
    private static String cachedActiveProfileId;

    private XrayProxyProfileStore() {
    }

    /**
     * Immutable-like profile model used by UI screens.
     */
    public static final class Profile {
        public String id;
        public String name;
        public int localPort;
        public String checkUrl;
        public String configJson;

        public Profile copy() {
            Profile copy = new Profile();
            copy.id = id;
            copy.name = name;
            copy.localPort = localPort;
            copy.checkUrl = checkUrl;
            copy.configJson = configJson;
            return copy;
        }

        private JSONObject toJson() throws Exception {
            JSONObject json = new JSONObject();
            json.put("id", safe(id));
            json.put("name", safe(name));
            json.put("localPort", sanitizePort(localPort));
            json.put("checkUrl", safeCheckUrl(checkUrl));
            json.put("configJson", safe(configJson));
            return json;
        }

        private static Profile fromJson(JSONObject json) {
            if (json == null) {
                return null;
            }
            Profile profile = new Profile();
            profile.id = safe(json.optString("id", ""));
            profile.name = safe(json.optString("name", ""));
            profile.localPort = sanitizePort(json.optInt("localPort", 10808));
            profile.checkUrl = safeCheckUrl(json.optString("checkUrl", DEFAULT_CHECK_URL));
            profile.configJson = safe(json.optString("configJson", ""));
            if (TextUtils.isEmpty(profile.id)) {
                profile.id = generateId();
            }
            if (TextUtils.isEmpty(profile.name)) {
                profile.name = "Proxy";
            }
            return profile;
        }
    }

    /**
     * Returns a snapshot of all stored profiles.
     */
    public static ArrayList<Profile> getProfiles() {
        synchronized (LOCK) {
            ensureLoadedLocked();
            ArrayList<Profile> result = new ArrayList<>(cachedProfiles.size());
            for (int i = 0; i < cachedProfiles.size(); i++) {
                result.add(cachedProfiles.get(i).copy());
            }
            return result;
        }
    }

    /**
     * Returns currently active profile snapshot.
     */
    public static Profile getActiveProfile() {
        synchronized (LOCK) {
            ensureLoadedLocked();
            Profile active = findActiveLocked();
            return active == null ? null : active.copy();
        }
    }

    /**
     * Creates a prefilled empty profile object.
     */
    public static Profile createEmptyProfile() {
        synchronized (LOCK) {
            ensureLoadedLocked();
            Profile profile = new Profile();
            profile.id = generateId();
            profile.name = "Proxy " + (cachedProfiles.size() + 1);
            profile.localPort = suggestPortLocked();
            profile.checkUrl = DEFAULT_CHECK_URL;
            profile.configJson = "";
            return profile;
        }
    }

    /**
     * Adds a profile and optionally activates it immediately.
     */
    public static void addProfile(Profile profile, boolean setActive) {
        if (profile == null) {
            return;
        }
        synchronized (LOCK) {
            ensureLoadedLocked();
            Profile safeProfile = normalizeProfile(profile);
            cachedProfiles.add(safeProfile);
            if (setActive || cachedProfiles.size() == 1) {
                cachedActiveProfileId = safeProfile.id;
            }
            persistLocked();
            syncLegacyFromActiveLocked();
        }
    }

    /**
     * Updates an existing profile by ID.
     */
    public static boolean updateProfile(Profile profile) {
        if (profile == null || TextUtils.isEmpty(profile.id)) {
            return false;
        }
        synchronized (LOCK) {
            ensureLoadedLocked();
            for (int i = 0; i < cachedProfiles.size(); i++) {
                if (profile.id.equals(cachedProfiles.get(i).id)) {
                    cachedProfiles.set(i, normalizeProfile(profile));
                    persistLocked();
                    syncLegacyFromActiveLocked();
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * Deletes profile by ID. Keeps at least one profile always available.
     */
    public static boolean deleteProfile(String profileId) {
        if (TextUtils.isEmpty(profileId)) {
            return false;
        }
        synchronized (LOCK) {
            ensureLoadedLocked();
            if (cachedProfiles.size() <= 1) {
                return false;
            }
            int removeIndex = -1;
            for (int i = 0; i < cachedProfiles.size(); i++) {
                if (profileId.equals(cachedProfiles.get(i).id)) {
                    removeIndex = i;
                    break;
                }
            }
            if (removeIndex < 0) {
                return false;
            }
            cachedProfiles.remove(removeIndex);
            if (profileId.equals(cachedActiveProfileId)) {
                cachedActiveProfileId = cachedProfiles.get(0).id;
            }
            persistLocked();
            syncLegacyFromActiveLocked();
            return true;
        }
    }

    /**
     * Switches active profile.
     */
    public static boolean setActiveProfile(String profileId) {
        if (TextUtils.isEmpty(profileId)) {
            return false;
        }
        synchronized (LOCK) {
            ensureLoadedLocked();
            Profile target = findByIdLocked(profileId);
            if (target == null) {
                return false;
            }
            cachedActiveProfileId = profileId;
            persistLocked();
            syncLegacyFromActiveLocked();
            return true;
        }
    }

    private static void ensureLoadedLocked() {
        if (cachedProfiles != null) {
            return;
        }
        cachedProfiles = new ArrayList<>();

        SharedPreferences preferences = getPreferences();
        if (preferences == null) {
            addLegacyProfileLocked();
            return;
        }

        String raw = preferences.getString(KEY_PROFILES_JSON, "");
        if (!TextUtils.isEmpty(raw)) {
            try {
                JSONArray array = new JSONArray(raw);
                for (int i = 0; i < array.length(); i++) {
                    Profile profile = Profile.fromJson(array.optJSONObject(i));
                    if (profile != null) {
                        cachedProfiles.add(profile);
                    }
                }
            } catch (Throwable ignore) {
                cachedProfiles.clear();
            }
        }

        if (cachedProfiles.isEmpty()) {
            addLegacyProfileLocked();
        }

        cachedActiveProfileId = safe(preferences.getString(KEY_ACTIVE_PROFILE_ID, ""));
        if (findByIdLocked(cachedActiveProfileId) == null) {
            cachedActiveProfileId = cachedProfiles.get(0).id;
            persistLocked();
        }

        syncLegacyFromActiveLocked();
    }

    private static void addLegacyProfileLocked() {
        Profile legacy = new Profile();
        legacy.id = generateId();
        legacy.name = "Default";
        legacy.localPort = sanitizePort(NekoConfig.xrayAppProxyLocalPort);
        legacy.checkUrl = safeCheckUrl(NekoConfig.xrayAppProxyCheckUrl);
        legacy.configJson = safe(NekoConfig.xrayAppProxyConfigJson);
        cachedProfiles.add(legacy);
        cachedActiveProfileId = legacy.id;
        persistLocked();
    }

    private static Profile normalizeProfile(Profile profile) {
        Profile copy = profile.copy();
        copy.id = TextUtils.isEmpty(copy.id) ? generateId() : copy.id;
        copy.name = TextUtils.isEmpty(copy.name) ? "Proxy" : copy.name.trim();
        copy.localPort = sanitizePort(copy.localPort);
        copy.checkUrl = safeCheckUrl(copy.checkUrl);
        copy.configJson = safe(copy.configJson);
        return copy;
    }

    private static int suggestPortLocked() {
        int base = 10808;
        int candidate = base + cachedProfiles.size();
        if (candidate > 65535) {
            candidate = 10808;
        }
        return candidate;
    }

    private static Profile findActiveLocked() {
        Profile active = findByIdLocked(cachedActiveProfileId);
        if (active == null && !cachedProfiles.isEmpty()) {
            active = cachedProfiles.get(0);
            cachedActiveProfileId = active.id;
            persistLocked();
        }
        return active;
    }

    private static Profile findByIdLocked(String profileId) {
        if (TextUtils.isEmpty(profileId)) {
            return null;
        }
        for (int i = 0; i < cachedProfiles.size(); i++) {
            Profile profile = cachedProfiles.get(i);
            if (profileId.equals(profile.id)) {
                return profile;
            }
        }
        return null;
    }

    private static void syncLegacyFromActiveLocked() {
        Profile active = findByIdLocked(cachedActiveProfileId);
        if (active == null) {
            return;
        }
        NekoConfig.setXrayAppProxyLocalPort(active.localPort);
        NekoConfig.setXrayAppProxyCheckUrl(active.checkUrl);
        NekoConfig.setXrayAppProxyConfigJson(active.configJson);
    }

    private static void persistLocked() {
        SharedPreferences preferences = getPreferences();
        if (preferences == null) {
            return;
        }
        JSONArray array = new JSONArray();
        for (int i = 0; i < cachedProfiles.size(); i++) {
            try {
                array.put(cachedProfiles.get(i).toJson());
            } catch (Throwable ignore) {
            }
        }
        preferences.edit()
                .putString(KEY_PROFILES_JSON, array.toString())
                .putString(KEY_ACTIVE_PROFILE_ID, safe(cachedActiveProfileId))
                .apply();
    }

    private static SharedPreferences getPreferences() {
        if (ApplicationLoader.applicationContext == null) {
            return null;
        }
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS_NAME, Activity.MODE_PRIVATE);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String safeCheckUrl(String value) {
        if (TextUtils.isEmpty(value)) {
            return DEFAULT_CHECK_URL;
        }
        return value.trim();
    }

    private static int sanitizePort(int value) {
        return value >= 1024 && value <= 65535 ? value : 10808;
    }

    private static String generateId() {
        return "xray_" + System.currentTimeMillis() + "_" + ((int) (Math.random() * 100000));
    }
}
