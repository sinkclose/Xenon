// Particle equations adapted from ppy/osu (c) ppy Pty Ltd, MIT licence.
// See assets/licenses/osu-kiai-fountain.txt.
package zxc.iconic.xenon.osu;

/** GameplayStarFountain / ParticleSpewer, evaluated on the audio clock for pause and seek. */
final class OsuKiaiFountain {
    static final double SPAWN_INTERVAL = 1000.0 / 180;
    static final double SHOOT_DURATION = 400, MAX_DURATION = 1000;
    static final int CAPACITY = 180;

    static final class Particle {
        float x, y, scale, angle, alpha;
        int side;
    }

    final Particle[] particles = new Particle[CAPACITY * 2];
    private final double[] starts, ends;
    private final int intervals;

    OsuKiaiFountain(OsuBeatmap map) {
        double[] shots = map.kiaiStarts();
        starts = new double[shots.length];
        ends = new double[shots.length];
        int count = 0;
        for (double shot : shots) {
            // Re-triggering an active spewer extends the shot without resetting its spawn clock.
            if (count > 0 && shot < ends[count - 1]) ends[count - 1] = shot + SHOOT_DURATION;
            else { starts[count] = shot; ends[count++] = shot + SHOOT_DURATION; }
        }
        intervals = count;
        for (int i = 0; i < particles.length; i++) particles[i] = new Particle();
    }

    int at(double time) {
        if (!Double.isFinite(time)) return 0;
        int lo = 0, hi = intervals;
        while (lo < hi) { int mid = (lo + hi) >>> 1; if (starts[mid] <= time) lo = mid + 1; else hi = mid; }
        int count = 0, emitted = 0;
        for (int interval = lo - 1; interval >= 0 && emitted < CAPACITY; interval--) {
            long last = Math.min((long)Math.floor((time - starts[interval]) / SPAWN_INTERVAL),
                    (long)Math.ceil((ends[interval] - starts[interval]) / SPAWN_INTERVAL) - 1);
            for (long index = last; index >= 0 && emitted < CAPACITY; index--, emitted++) {
                double spawn = starts[interval] + index * SPAWN_INTERVAL;
                float age = (float)(time - spawn);
                if (age >= MAX_DURATION) return count;
                for (int side = 0; side < 2; side++) {
                    long seed = mix(Double.doubleToLongBits(starts[interval]) ^ index * 0x9e3779b97f4a7c15L ^ side);
                    float duration = 300 + random(seed) * 700;
                    if (age < 0 || age >= duration) continue;
                    float progress = age / duration, seconds = age / 1000;
                    float vx = 450 + random(seed + 1) * 150;
                    float vy = -1500 + random(seed + 2) * 200;
                    float startAngle = -4 + random(seed + 3) * 8;
                    float endAngle = -2 + random(seed + 4) * 4;
                    float endScale = 1.8f + random(seed + 5) * .8f;
                    Particle p = particles[count++];
                    p.side = side;
                    p.x = (side == 0 ? 1 : -1) * (75 + vx * seconds);
                    // FallingParticle.PositionAtTime: not the conventional g*t*t/2 equation.
                    p.y = 50 + vy * seconds + 800 * seconds * seconds;
                    p.scale = 1 + (endScale - 1) * progress;
                    p.angle = startAngle + (endAngle - startAngle) * progress;
                    p.alpha = 1 - progress;
                }
            }
        }
        return count;
    }

    private static long mix(long value) {
        value = (value ^ (value >>> 30)) * 0xbf58476d1ce4e5b9L;
        value = (value ^ (value >>> 27)) * 0x94d049bb133111ebL;
        return value ^ (value >>> 31);
    }
    private static float random(long seed) { return (mix(seed) >>> 40) * 0x1.0p-24f; }
}
