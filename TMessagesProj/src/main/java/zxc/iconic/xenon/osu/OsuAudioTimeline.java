package zxc.iconic.xenon.osu;

import java.util.ArrayList;

/** Timestamped audio features; lookup is independent of seek direction and wall-clock time. */
public class OsuAudioTimeline {
    protected final ArrayList<float[]> frames = new ArrayList<>();
    protected final ArrayList<Long> times = new ArrayList<>();
    public volatile long durationMs;
    public volatile boolean complete = true;

    public synchronized void append(long timeMs, float[] values) {
        if (!times.isEmpty() && timeMs <= times.get(times.size() - 1)) return;
        times.add(timeMs); frames.add(values); durationMs = Math.max(durationMs, timeMs);
    }
    public synchronized float sample(long positionMs, float[] bands) {
        int lo = 0, hi = times.size() - 1, found = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (times.get(mid) <= positionMs) { found = mid; lo = mid + 1; } else hi = mid - 1;
        }
        if (found < 0 || positionMs > durationMs + 100 || (!complete && positionMs > times.get(found) + 100)) {
            java.util.Arrays.fill(bands, 0); return 0;
        }
        float[] current = frames.get(found);
        float mix = found + 1 < times.size() ? Math.min(1, (positionMs - times.get(found)) /
                (float) Math.max(1, times.get(found + 1) - times.get(found))) : 0;
        float[] next = mix > 0 ? frames.get(found + 1) : current;
        for (int i = 0; i < bands.length; i++) bands[i] = current[i + 1] + (next[i + 1] - current[i + 1]) * mix;
        return current[0] + (next[0] - current[0]) * mix;
    }
}
