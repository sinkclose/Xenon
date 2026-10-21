package zxc.iconic.xenon.triangles;

/** ppy/osu LogoVisualisation normal-mode spectrum envelope and five overlapping rounds (MIT). */
public final class OsuLogoVisualisation {
    public static final int BARS = 200, ROUNDS = 5, MAX_LINE_FLOATS = BARS * ROUNDS * 4;
    private static final float LENGTH_GAIN = 0.45f;
    private final float[] heights = new float[BARS], cos = new float[BARS], sin = new float[BARS];
    private int offset;
    private float elapsed;
    private float lengthGain = LENGTH_GAIN;

    public OsuLogoVisualisation() {
        for (int i = 0; i < BARS; i++) {
            cos[i] = (float) Math.cos(i * Math.PI * 2 / BARS);
            sin[i] = (float) Math.sin(i * Math.PI * 2 / BARS);
        }
    }

    public void reset() { java.util.Arrays.fill(heights, 0); offset = 0; elapsed = 0; }

    public void update(float ms, float[] spectrum, boolean playing) {
        update(ms, spectrum, playing, false, false);
    }
    public void update(float ms, float[] spectrum, boolean playing, boolean beatmap, boolean kiai) {
        lengthGain = beatmap ? 1 : LENGTH_GAIN;
        ms = TriangleMath.clamp(ms, 0, 100);
        float decay = (float) Math.exp(-ms * 0.0024);
        for (int i = 0; i < BARS; i++) heights[i] = Math.max(0, (heights[i] + 0.03f) * decay - 0.03f);
        if (!playing) { elapsed = 0; return; }
        elapsed += ms;
        if (elapsed < 50) return;
        elapsed %= 50; // Do not replay missed updates after a stall.
        for (int i = 0; i < BARS; i++) {
            heights[i] = Math.max(heights[i], TriangleMath.clamp(spectrum[(i + offset) % BARS], 0, 1) * (kiai ? 1 : 0.5f));
        }
        offset = (offset + 5) % BARS;
    }

    /** One batched drawLines call can paint all the source quads with equal width and alpha. */
    public int writeLines(float radius, float[] lines) {
        int count = 0;
        for (int round = 0; round < ROUNDS; round++) for (int i = 0; i < BARS; i++) {
            if (heights[i] < 1f / 600) continue;
            int direction = (i + round * (BARS / ROUNDS)) % BARS;
            float length = 600 * heights[i] * lengthGain;
            if (length <= 0) continue;
            float c = cos[direction], s = sin[direction];
            lines[count++] = 256 + c * radius; lines[count++] = 256 + s * radius;
            lines[count++] = 256 + c * (radius + length); lines[count++] = 256 + s * (radius + length);
        }
        return count;
    }
}
