package zxc.iconic.xenon.triangles;

/** The normal (non-Kiai) OsuLogo beat/amplitude/ripple transforms, driven by detected onsets. */
public final class OsuLogoMusicMotion {
    public float amplitudeScale = 1, velocity = 0.5f;
    private long beatStart = -1;
    private float beatFrom = 1, strength, period = 500, rippleBase = 1;
    private boolean kickApplied;
    private boolean kiaiBeat;

    public void reset() {
        amplitudeScale = 1; velocity = 0.5f; beatStart = -1; kickApplied = false;
    }

    public void onBeat(long now, float amplitude, float beatLength) {
        onBeat(now, amplitude, TriangleMath.clamp(beatLength, 200, 1500), false);
    }
    public void onBeat(long now, float amplitude, float beatLength, boolean kiai) {
        beatFrom = beatScale(now);
        beatStart = now;
        strength = Math.min(1, 0.4f + TriangleMath.clamp(amplitude, 0, 1));
        period = TriangleMath.clamp(beatLength, 1, 60000);
        rippleBase = amplitudeScale;
        kickApplied = false;
        kiaiBeat = kiai;
    }

    public void update(long now, float elapsedMs, float amplitude, boolean playing) {
        update(now, elapsedMs, amplitude, playing, false, false);
    }
    public void update(long now, float elapsedMs, float amplitude, boolean playing, boolean beatmap, boolean kiai) {
        elapsedMs = TriangleMath.clamp(elapsedMs, 0, 100);
        amplitude = TriangleMath.clamp(amplitude, 0, 1);
        float targetScale = 1 - Math.max(0, amplitude - 0.4f) * 0.04f;
        amplitudeScale = damp(amplitudeScale, playing ? targetScale : 1, 0.9f, elapsedMs);
        // External music has no beatmap timing. Retain audible energy response
        // between detected beats (including music without distinct bass kicks).
        float targetVelocity = beatmap ? (kiai ? 2 : 1) : 1 + 2 * amplitude;
        velocity = damp(velocity, playing ? targetVelocity : 0.5f, playing ? 0.995f : 0.9f, elapsedMs);
        if (playing && beatStart >= 0 && !kickApplied && now - beatStart >= 60) {
            velocity += strength * (kiaiBeat ? 6 : 3);
            kickApplied = true;
        }
    }

    private static float damp(float from, float to, float smoothing, float ms) {
        return to + (from - to) * (float) Math.pow(smoothing, ms);
    }

    private static float outQuint(float t) {
        t = 1 - TriangleMath.clamp(t, 0, 1);
        return 1 - t * t * t * t * t;
    }

    public float beatScale(long now) {
        if (beatStart < 0) return 1;
        float elapsed = Math.max(0, now - beatStart), peak = 1 - 0.02f * strength;
        return elapsed < 60 ? beatFrom + (peak - beatFrom) * OsuLogoModel.press(elapsed / 60)
                : peak + (1 - peak) * outQuint((elapsed - 60) / (period * 2));
    }

    public float scale(long now) { return amplitudeScale * beatScale(now); }
    public float rippleAlpha(long now) {
        return beatStart < 0 ? 0 : 0.15f * strength * (1 - outQuint((now - beatStart) / period));
    }
    public float rippleScale(long now) {
        return rippleBase * (1 + 0.04f * strength * outQuint((now - beatStart) / period));
    }
    public float kiaiFlash(long now) {
        if (!kiaiBeat || beatStart < 0) return 0;
        float t = Math.max(0, now - beatStart);
        float peak = 0.2f * strength;
        return t < 60 ? peak * OsuLogoModel.press(t / 60) : peak * (1 - TriangleMath.clamp((t - 60) / period, 0, 1));
    }
    public float spectrumAlpha(long now) {
        if (!kiaiBeat || beatStart < 0) return 1;
        float t = Math.max(0, now - beatStart), peak = 1.8f * strength;
        return t < 60 ? 1 + (peak - 1) * OsuLogoModel.press(t / 60)
                : peak + (1 - peak) * TriangleMath.clamp((t - 60) / period, 0, 1);
    }
}
