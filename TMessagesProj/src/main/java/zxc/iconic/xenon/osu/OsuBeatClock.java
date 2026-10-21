package zxc.iconic.xenon.osu;

/** Stateless map lookups plus one-pulse-per-beat delivery; seeks do not replay missed beats. */
public final class OsuBeatClock {
    public final OsuBeatmap.State state = new OsuBeatmap.State(), upcoming = new OsuBeatmap.State();
    private double previous = Double.NaN, offset = Double.NaN;
    private long index = Long.MIN_VALUE;
    public boolean jumped, pulse;
    public void reset() { previous = offset = Double.NaN; index = Long.MIN_VALUE; }
    public void update(OsuBeatmap map, double position, float elapsedMs, float rate, boolean playing) {
        jumped = Double.isNaN(previous) || Math.abs(position - previous - (playing ? elapsedMs * rate : 0)) > 150;
        previous = position;
        map.at(position, state);
        map.at(position + 60 * rate, upcoming);
        pulse = playing && upcoming.hasBeat && (jumped || index != upcoming.beatIndex || offset != upcoming.offset);
        if (playing) { index = upcoming.beatIndex; offset = upcoming.offset; }
    }
}
