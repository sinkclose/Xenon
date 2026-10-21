package zxc.iconic.xenon.triangles;

/** Allocation-free, causal bass-onset detector. Does not pretend to know beatmap timing/Kiai. */
public final class OsuBeatDetector {
    private final float highPassDecay, lowPassMix;
    private float previousSample, highPass, lowPass, meanBass, previousBass;
    private long lastBeat = -10000;
    private boolean haveInterval;
    private boolean armed = true;
    public float amplitude, beatLength = 500;

    public OsuBeatDetector(int sampleRate) {
        highPassDecay = (float) Math.exp(-2 * Math.PI * 35 / sampleRate);
        lowPassMix = 1 - (float) Math.exp(-2 * Math.PI * 180 / sampleRate);
    }

    public boolean process(short[] samples, int count, long nowMillis, int sampleRate) {
        if (count <= 0) return false;
        double bassEnergy = 0, fullEnergy = 0;
        float peak = 0;
        for (int i = 0; i < count; i++) {
            float input = samples[i] / 32768f;
            peak = Math.max(peak, Math.abs(input));
            highPass = highPassDecay * (highPass + input - previousSample);
            previousSample = input;
            lowPass += lowPassMix * (highPass - lowPass);
            bassEnergy += lowPass * lowPass;
            fullEnergy += input * input;
        }
        float bass = (float) Math.sqrt(bassEnergy / count);
        float rms = (float) Math.sqrt(fullEnergy / count);
        return processEnergy(peak, bass, rms, nowMillis, count * 1000f / sampleRate);
    }

    public boolean processEnergy(float peak, float bass, float rms, long nowMillis, float elapsedMs) {
        amplitude = TriangleMath.clamp(peak, 0, 1);
        bass = TriangleMath.clamp(bass, 0, 1);
        rms = TriangleMath.clamp(rms, 0, 1);
        // Require a return toward baseline before accepting another attack.
        // This keeps sustained bass from retriggering at the more sensitive threshold.
        if (bass <= meanBass * 1.1f + 0.0015f) armed = true;
        boolean beat = armed && nowMillis - lastBeat >= 200 && rms > 0.002f && bass > rms * 0.15f
                && bass > meanBass * 1.25f + 0.0015f && bass > previousBass * 1.04f;
        float mix = 1 - (float) Math.exp(-TriangleMath.clamp(elapsedMs, 0, 100) / 700f);
        meanBass += mix * (bass - meanBass);
        previousBass = bass;
        if (beat) {
            armed = false;
            long interval = nowMillis - lastBeat;
            if (interval >= 200 && interval <= 1500) {
                beatLength = haveInterval ? beatLength * 0.65f + interval * 0.35f : interval;
                haveInterval = true;
            }
            lastBeat = nowMillis;
        }
        return beat;
    }
}
