package zxc.iconic.xenon.triangles;

import java.util.Random;

/** osu! logo presets, independent of chat preferences. See assets/licenses/osu-logo.txt. */
public final class OsuLogoModel {
    // Menu/logo.png is 1024px; osu-framework TextureStore uses scaleAdjust=2.
    public static final float SIZE = 512;
    public static final int PRESS_MS = 1000, RELEASE_MS = 900, FLASH_MS = 1500;

    /** Release detection must survive Android consuming a long press as a non-click. */
    public static final class ReleaseGesture {
        private boolean active;
        public void down() { active = true; }
        public void cancel() { active = false; }
        public boolean up(boolean inside) {
            boolean released = active && inside;
            active = false;
            return released;
        }
    }
    public final boolean legacy;
    public final float diameter;
    public final int count;
    public final float[] x, y, side, speed;
    public final int[] colour;
    private final Random random;

    public OsuLogoModel(boolean legacy, long seed) {
        this.legacy = legacy;
        random = new Random(seed);
        diameter = SIZE * (legacy ? 0.96f : 0.94f);
        // Triangles: TriangleScale=4. TrianglesV2: ScaleAdjust=3, SpawnRatio=1.4.
        count = legacy ? (int) (diameter * diameter * 0.002f / 16)
                : Math.max(1, (int) (diameter * 0.02f * 1.4f));
        x = new float[count]; y = new float[count]; side = new float[count];
        speed = new float[count]; colour = new int[count];
        for (int i = 0; i < count; i++) configureParticle(i);
        // The old renderer draws large triangles first, small triangles on top.
        sortLegacy();
        for (int i = 0; i < count; i++) {
            x[i] = random.nextFloat() * diameter;
            y[i] = random.nextFloat() * (diameter + side[i] * 0.866f) - side[i] * 0.866f;
        }
    }

    private void configureParticle(int i) {
        float sample = (float) (0.5 + random.nextGaussian() * 0.16);
        side[i] = legacy ? Math.max(0.1f, sample * 4) * 100 : 300;
        speed[i] = legacy ? Math.max(0.5f, side[i] / 100) / 4 : Math.max(0.5f, sample);
        colour[i] = TriangleMath.mix(0xffde5b95, 0xffff7db7, random.nextFloat());
    }

    private void sortLegacy() {
        if (legacy) {
            for (int i = 0; i < count; i++) for (int j = i + 1; j < count; j++) {
                if (side[j] > side[i]) {
                    float s = side[i]; side[i] = side[j]; side[j] = s;
                    s = speed[i]; speed[i] = speed[j]; speed[j] = s;
                    s = x[i]; x[i] = x[j]; x[j] = s;
                    s = y[i]; y[i] = y[j]; y[j] = s;
                    int c = colour[i]; colour[i] = colour[j]; colour[j] = c;
                }
            }
        }
    }

    public void advance(float seconds) {
        advance(seconds, 0.5f);
    }

    public void advance(float seconds, float velocity) {
        // OsuLogo settles Velocity at triangles_paused_velocity=0.5 without music.
        boolean respawned = false;
        for (int i = 0; i < count; i++) {
            y[i] -= Math.max(0, seconds) * 50 * TriangleMath.clamp(velocity, 0, 10) * speed[i];
            if (y[i] + side[i] * 0.866f < 0) {
                configureParticle(i);
                y[i] = diameter;
                x[i] = random.nextFloat() * diameter;
                respawned = true;
            }
        }
        if (respawned) sortLegacy();
    }

    public static float press(float t) {
        t = TriangleMath.clamp(t, 0, 1);
        return 1 - (1 - t) * (1 - t); // Easing.Out (quadratic).
    }

    public static float release(float t) {
        t = TriangleMath.clamp(t, 0, 1);
        if (t == 0 || t == 1) return t;
        return (float) (1 + Math.pow(2, -10 * t) * Math.sin((t - 0.075) * 2 * Math.PI / 0.3) - Math.pow(2, -11) * t);
    }

    public static float flash(float t) {
        t = TriangleMath.clamp(t, 0, 1);
        return 0.4f * (float) (Math.pow(2, -10 * t) - Math.pow(2, -10) * t);
    }
}
