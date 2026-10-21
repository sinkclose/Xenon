package zxc.iconic.xenon.osu;

import android.media.AudioAttributes;
import android.media.SoundPool;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.R;
import zxc.iconic.xenon.NekoConfig;
import java.util.HashSet;
import java.util.WeakHashMap;

/** Short local samples, with one shared clock regardless of how many logos are visible. */
public final class OsuLogoSounds {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final WeakHashMap<View, Boolean> visible = new WeakHashMap<>();
    private static final HashSet<Integer> ready = new HashSet<>();
    private static final int[] ids = new int[4], streams = new int[8];
    private static SoundPool pool;
    private static final OsuSoundPlaybackState playback = new OsuSoundPlaybackState();
    private static Runnable pendingBeat;
    private static int streamIndex, systemIndex;
    private static long lastSystemSequence = -1;
    private static OsuArchive.Track current;

    public static boolean toggle() {
        boolean enabled = playback.toggle();
        if (enabled) { OsuSampleBank.request(); if (OsuBeatmapPlayer.current() == null) prepare(); } else cancel();
        return enabled;
    }
    public static boolean beatsEnabled() { return playback.beatsEnabled(NekoConfig.osuKeepHitsoundsPlaying); }
    public static void keepPlayingChanged() {
        playback.keepPlayingChanged(NekoConfig.osuKeepHitsoundsPlaying);
        if (!beatsEnabled()) cancel();
    }
    public static void setVisible(View view, boolean isVisible) {
        if (isVisible) {
            visible.put(view, true);
            if (beatsEnabled() && OsuBeatmapPlayer.current() == null) prepare();
        } else visible.remove(view);
        playback.setVisible(!visible.isEmpty(), NekoConfig.osuKeepHitsoundsPlaying);
        if (visible.isEmpty()) {
            cancel(); current = null; lastSystemSequence = -1; systemIndex = 0;
            if (pool != null) { pool.release(); pool = null; ready.clear(); }
        }
    }
    private static void prepare() {
        if (pool != null) return;
        try {
            SoundPool created = new SoundPool.Builder().setMaxStreams(6).setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build();
            pool = created;
            created.setOnLoadCompleteListener((loaded, id, status) -> { if (pool == loaded && status == 0) ready.add(id); });
            int[] resources = {R.raw.osu_logo_downbeat, R.raw.osu_logo_heartbeat, R.raw.osu_logo_downbeat_old,
                    R.raw.osu_logo_heartbeat_old};
            for (int i = 0; i < resources.length; i++) ids[i] = created.load(ApplicationLoader.applicationContext, resources[i], 1);
        } catch (Exception error) { FileLog.e(error); if (pool != null) pool.release(); pool = null; ready.clear(); }
    }
    private static void cancel() {
        if (pendingBeat != null) { MAIN.removeCallbacks(pendingBeat); pendingBeat = null; }
        if (pool != null) for (int stream : streams) if (stream != 0) pool.stop(stream);
        java.util.Arrays.fill(streams, 0);
    }
    private static void sample(int index, float rate, float volume) {
        if (pool == null || !ready.contains(ids[index])) return;
        streams[streamIndex++ % streams.length] = pool.play(ids[index], volume, volume, 1, 0, rate);
    }
    private static void schedule(long delay, boolean downbeat, boolean old) {
        if (!beatsEnabled()) return;
        if (pendingBeat != null) MAIN.removeCallbacks(pendingBeat);
        pendingBeat = () -> {
            pendingBeat = null;
            if (!beatsEnabled() || visible.isEmpty()) return;
            float pitch = downbeat ? 1 : .95f + (float) Math.random() * .1f;
            sample((old ? 2 : 0) + (downbeat ? 0 : 1), pitch, NekoConfig.osuHitsoundVolume / 100f);
        };
        MAIN.postDelayed(pendingBeat, delay);
    }
    public static void mapFrame(OsuArchive.Track track) {
        // Map sounds are mixed by OsuMixedAudioSink, never dispatched from UI frames.
        if (current != track) { cancel(); current = track; }
    }
    public static void systemFrame(boolean playing) {
        if (current != null) { cancel(); current = null; }
        if (!playing) { cancel(); lastSystemSequence = -1; systemIndex = 0; }
    }
    public static void systemBeat(long sequence, long beatTime, boolean old) {
        if (!beatsEnabled() || sequence == lastSystemSequence) return;
        prepare();
        lastSystemSequence = sequence;
        long delay = beatTime + 60 - SystemClock.uptimeMillis();
        if (delay >= -40) schedule(Math.max(0, delay), systemIndex++ % 4 == 0, old);
    }
}
