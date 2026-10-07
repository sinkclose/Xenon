package org.telegram.messenger.feed;

import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserConfig;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/** Account-local feed folders. All keeps the existing channel exclusion preference. */
public final class FeedFolders {
    private static final FeedFolders[] instances = new FeedFolders[UserConfig.MAX_ACCOUNT_COUNT];
    public static final int ALL = 0;
    private final SharedPreferences preferences;
    private final ArrayList<Folder> folders = new ArrayList<>();
    private int nextId = 1;
    private int defaultId;
    private int activeId;
    private boolean hideAll;

    public static final class Folder {
        public final int id;
        public final String name;
        private final HashSet<Long> channels;
        private Folder(int id, String name, HashSet<Long> channels) {
            this.id = id;
            this.name = name;
            this.channels = channels;
        }
    }

    public static synchronized FeedFolders getInstance(int account) {
        if (instances[account] == null) instances[account] = new FeedFolders(account);
        return instances[account];
    }

    private FeedFolders(int account) {
        preferences = MessagesController.getMainSettings(account);
        folders.add(new Folder(ALL, preferences.getString("feed_all_name", "All"), new HashSet<>()));
        try {
            JSONArray data = new JSONArray(preferences.getString("feed_folders", "[]"));
            HashSet<Integer> seen = new HashSet<>();
            for (int i = 0; i < data.length(); i++) {
                JSONObject item = data.getJSONObject(i);
                int id = item.getInt("id");
                if (id <= 0 || !seen.add(id)) continue;
                HashSet<Long> channels = new HashSet<>();
                JSONArray ids = item.optJSONArray("channels");
                if (ids != null) for (int j = 0; j < ids.length(); j++) channels.add(ids.getLong(j));
                folders.add(new Folder(id, item.getString("name"), channels));
                nextId = Math.max(nextId, id + 1);
            }
        } catch (Exception e) {
            org.telegram.messenger.FileLog.e(e);
        }
        nextId = Math.max(nextId, preferences.getInt("feed_folder_next_id", nextId));
        hideAll = preferences.getBoolean("feed_hide_all", false) && folders.size() > 1;
        defaultId = visibleId(preferences.getInt("feed_default_folder", ALL));
        activeId = defaultId;
    }

    public synchronized ArrayList<Folder> getFolders() { return new ArrayList<>(folders); }
    public synchronized ArrayList<Folder> getVisibleFolders() {
        ArrayList<Folder> result = new ArrayList<>(folders);
        if (hideAll && result.size() > 1) result.remove(0);
        return result;
    }
    public synchronized Folder getFolder(int id) {
        for (Folder folder : folders) if (folder.id == id) return folder;
        return null;
    }
    private int visibleId(int id) {
        if (getFolder(id) != null && (id != ALL || !hideAll)) return id;
        return hideAll && folders.size() > 1 ? folders.get(1).id : ALL;
    }
    public synchronized int getDefaultId() { return defaultId; }
    public synchronized int getActiveId() { return activeId; }
    public synchronized boolean isAllHidden() { return hideAll; }
    public synchronized boolean setActive(int id) {
        int next = visibleId(id);
        if (activeId == next) return false;
        activeId = next;
        return true;
    }
    public synchronized boolean isIncluded(int folderId, long dialogId) {
        Folder folder = getFolder(folderId);
        if (folder == null) return false;
        if (folderId == ALL) return !preferences.getStringSet("feed_excluded_channels", new HashSet<>()).contains(Long.toString(dialogId));
        return folder.channels.contains(dialogId);
    }
    public synchronized Folder add(String name) {
        Folder folder = new Folder(nextId++, name.trim(), new HashSet<>());
        folders.add(folder);
        save();
        return folder;
    }
    public synchronized void rename(int id, String name) {
        Folder old = getFolder(id);
        if (old == null || name.trim().isEmpty()) return;
        folders.set(folders.indexOf(old), new Folder(id, name.trim(), new HashSet<>(old.channels)));
        save();
    }
    public synchronized void delete(int id) {
        if (id == ALL) return;
        Folder folder = getFolder(id);
        if (folder == null) return;
        folders.remove(folder);
        if (folders.size() == 1) hideAll = false;
        defaultId = visibleId(defaultId);
        activeId = visibleId(activeId);
        save();
    }
    public synchronized void setDefault(int id) {
        if (id == ALL) hideAll = false;
        defaultId = visibleId(id);
        save();
    }
    public synchronized void setAllHidden(boolean hidden) {
        hideAll = hidden && folders.size() > 1;
        defaultId = visibleId(defaultId);
        activeId = visibleId(activeId);
        save();
    }
    public synchronized void reorder(List<Integer> order) {
        ArrayList<Folder> result = new ArrayList<>();
        result.add(folders.get(0));
        for (int id : order) {
            Folder folder = getFolder(id);
            if (folder != null && id != ALL && !result.contains(folder)) result.add(folder);
        }
        for (Folder folder : folders) if (!result.contains(folder)) result.add(folder);
        folders.clear();
        folders.addAll(result);
        save();
    }
    public synchronized void setChannels(int id, List<Long> dialogIds, boolean included) {
        Folder folder = getFolder(id);
        if (folder == null) return;
        if (id == ALL) {
            HashSet<String> excluded = new HashSet<>(preferences.getStringSet("feed_excluded_channels", new HashSet<>()));
            for (long dialogId : dialogIds) {
                if (included) excluded.remove(Long.toString(dialogId));
                else excluded.add(Long.toString(dialogId));
            }
            preferences.edit().putStringSet("feed_excluded_channels", excluded).apply();
        } else {
            HashSet<Long> channels = new HashSet<>(folder.channels);
            if (included) channels.addAll(dialogIds); else channels.removeAll(dialogIds);
            folders.set(folders.indexOf(folder), new Folder(id, folder.name, channels));
            save();
        }
    }
    private void save() {
        try {
            JSONArray data = new JSONArray();
            for (Folder folder : folders) {
                if (folder.id == ALL) continue;
                JSONObject item = new JSONObject();
                item.put("id", folder.id);
                item.put("name", folder.name);
                item.put("channels", new JSONArray(folder.channels));
                data.put(item);
            }
            preferences.edit().putString("feed_folders", data.toString())
                    .putString("feed_all_name", folders.get(0).name)
                    .putInt("feed_default_folder", defaultId).putInt("feed_folder_next_id", nextId)
                    .putBoolean("feed_hide_all", hideAll).apply();
        } catch (Exception e) {
            org.telegram.messenger.FileLog.e(e);
        }
    }
}
