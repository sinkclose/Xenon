package zxc.iconic.xenon.osu;

/** One rank impact per crossed marker; quiet score ticks never cover rank impacts. */
public final class OsuScoreHaptics {
    private static final float[] MARKERS = {0, .7f, .8f, .9f, .95f, .99f};
    private int crossed;
    private boolean finalRankShown;
    private long previousScore = -1, lastPulse = -1000;
    public boolean finalRank(long elapsed) {
        if (finalRankShown || elapsed < 1950) return false;
        finalRankShown = true;
        lastPulse = elapsed;
        return true;
    }
    public int update(float gauge, String rank, long score, long elapsed) {
        boolean impact = false;
        for (int i = 0; i <= OsuScoreAnimation.rankIndex(rank); i++) {
            int bit = 1 << i;
            if ((crossed & bit) == 0 && gauge > 0 && gauge >= MARKERS[i]) {
                crossed |= bit;
                impact = true;
            }
        }
        boolean changed = previousScore >= 0 && previousScore != score;
        previousScore = score;
        if (impact) { lastPulse = elapsed; return 2; }
        // Coalesce rapid digit changes into short, distinct pulses instead of a continuous buzz.
        if (changed && elapsed >= 450 && elapsed - lastPulse >= 90) { lastPulse = elapsed; return 1; }
        return 0;
    }
}
