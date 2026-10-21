package zxc.iconic.xenon.osu;

/** Reusable 1024-sample mono Hann FFT, independent of playback volume. */
public final class OsuPcmAnalysis {
    public static final int SIZE = 1024, BANDS = 200;
    private final float[] real = new float[SIZE], imaginary = new float[SIZE];
    public void analyse(float[] samples, float[] output) {
        float peak = 0;
        for (int i = 0; i < SIZE; i++) {
            peak = Math.max(peak, Math.abs(samples[i]));
            real[i] = samples[i] * (float) (0.5 - 0.5 * Math.cos(2 * Math.PI * i / (SIZE - 1)));
            imaginary[i] = 0;
        }
        for (int i = 1, j = 0; i < SIZE; i++) {
            int bit = SIZE >> 1;
            for (; (j & bit) != 0; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) { float value = real[i]; real[i] = real[j]; real[j] = value; }
        }
        for (int size = 2; size <= SIZE; size <<= 1) {
            double angle = -2 * Math.PI / size;
            float stepR = (float) Math.cos(angle), stepI = (float) Math.sin(angle);
            for (int start = 0; start < SIZE; start += size) {
                float wr = 1, wi = 0;
                for (int j = 0; j < size / 2; j++) {
                    int a = start + j, b = a + size / 2;
                    float br = real[b] * wr - imaginary[b] * wi, bi = real[b] * wi + imaginary[b] * wr;
                    real[b] = real[a] - br; imaginary[b] = imaginary[a] - bi;
                    real[a] += br; imaginary[a] += bi;
                    float next = wr * stepR - wi * stepI;
                    wi = wr * stepI + wi * stepR; wr = next;
                }
            }
        }
        output[0] = Math.min(1, peak);
        for (int i = 0; i < BANDS; i++) output[i + 1] = Math.min(1,
                (float) Math.hypot(real[i + 1], imaginary[i + 1]) * 4 / SIZE);
    }
}
