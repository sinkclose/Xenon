package org.telegram.messenger.feed;

import android.content.SharedPreferences;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

public final class FeedConfig {

    private static final FeedConfig[] instances = new FeedConfig[UserConfig.MAX_ACCOUNT_COUNT];
    private static final Object[] lockObjects = new Object[UserConfig.MAX_ACCOUNT_COUNT];
    static {
        for (int i = 0; i < lockObjects.length; i++) lockObjects[i] = new Object();
    }

    public static FeedConfig getInstance(int account) {
        FeedConfig c = instances[account];
        if (c != null) return c;
        synchronized (lockObjects[account]) {
            c = instances[account];
            if (c == null) {
                c = new FeedConfig(account);
                instances[account] = c;
            }
            return c;
        }
    }

    private volatile Set<Long> excludedChannels = new HashSet<>();
    private volatile int generation;
    private volatile boolean includeArchived;
    private final SharedPreferences preferences;

    private FeedConfig(int account) {
        SharedPreferences prefs = ApplicationLoader.applicationContext.getSharedPreferences("feedconfig" + account, 0);
        this.preferences = prefs;
        this.includeArchived = prefs.getBoolean("includeArchived", false);
        Set<String> stored = prefs.getStringSet("excludedChannels", null);
        if (stored != null) {
            Set<Long> set = new HashSet<>();
            for (String s : stored) {
                try { set.add(Long.parseLong(s)); } catch (NumberFormatException ignored) {}
            }
            this.excludedChannels = set;
        }
    }

    public static final class Snapshot {
        public final boolean includeArchived;
        public final Set<Long> excludedChannels;
        public final int generation;

        public Snapshot(boolean includeArchived, Set<Long> excluded, int generation) {
            this.includeArchived = includeArchived;
            this.excludedChannels = excluded;
            this.generation = generation;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Snapshot)) return false;
            Snapshot s = (Snapshot) o;
            return this.includeArchived == s.includeArchived
                && this.excludedChannels.equals(s.excludedChannels)
                && this.generation == s.generation;
        }

        @Override
        public int hashCode() {
            return ((Boolean.hashCode(includeArchived) * 31) + excludedChannels.hashCode()) * 31 + Integer.hashCode(generation);
        }
    }

    public boolean isIncludeArchived() {
        return includeArchived;
    }

    public synchronized void setIncludeArchived(boolean archived) {
        if (includeArchived == archived) return;
        includeArchived = archived;
        generation++;
        preferences.edit().putBoolean("includeArchived", archived).apply();
    }

    public boolean isExcluded(long dialogId) {
        return excludedChannels.contains(dialogId);
    }

    public synchronized void setExcluded(long dialogId, boolean excluded) {
        HashSet<Long> set = new HashSet<>(excludedChannels);
        boolean changed = excluded ? set.add(dialogId) : set.remove(dialogId);
        if (changed) applyExcluded(set);
    }

    public Set<Long> getExcludedSnapshot() {
        return Collections.unmodifiableSet(excludedChannels);
    }

    public synchronized void removeExcluded(Set<Long> ids) {
        if (ids.isEmpty()) return;
        HashSet<Long> set = new HashSet<>(excludedChannels);
        if (set.removeAll(ids)) applyExcluded(set);
    }

    public synchronized void clearExcluded() {
        if (excludedChannels.isEmpty()) return;
        applyExcluded(new HashSet<>());
    }

    public synchronized void excludeAll(Collection<Long> ids) {
        HashSet<Long> set = new HashSet<>(excludedChannels);
        if (set.addAll(ids)) applyExcluded(set);
    }

    private void applyExcluded(Set<Long> set) {
        this.excludedChannels = set;
        this.generation++;
        HashSet<String> store = new HashSet<>();
        Iterator<Long> it = set.iterator();
        while (it.hasNext()) store.add(String.valueOf(it.next()));
        preferences.edit().putStringSet("excludedChannels", store).apply();
        NotificationCenter.getInstance(UserConfig.selectedAccount)
                .postNotificationName(NotificationCenter.feedNeedReload, true);
    }

    public int getGeneration() {
        return generation;
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(includeArchived, new HashSet<>(excludedChannels), generation);
    }
}