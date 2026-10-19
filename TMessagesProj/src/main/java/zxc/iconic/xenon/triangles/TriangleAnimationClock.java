package zxc.iconic.xenon.triangles;

/** Vsync timestamps, with a phase-preserving FPS limit instead of a delayed invalidate timer. */
public final class TriangleAnimationClock {
    private long previousNanos, nextNanos;
    private int previousFps;

    public void reset() {
        previousNanos = nextNanos = 0;
        previousFps = 0;
    }

    /** Returns seconds to advance, or -1 to skip. FPS zero follows every display vsync. */
    public float step(long nowNanos, int fps) {
        fps = fps <= 0 ? 0 : Math.max(10, Math.min(240, fps));
        long interval = fps == 0 ? 0 : 1_000_000_000L / fps;
        if (previousNanos == 0) {
            previousNanos = nowNanos;
            nextNanos = nowNanos + interval;
            previousFps = fps;
            return 0;
        }
        if (nowNanos <= previousNanos) return -1;
        if (fps > 0 && fps == previousFps && nowNanos + 500_000L < nextNanos) return -1;
        float maxElapsed = fps == 0 ? 0.1f : Math.max(0.1f, 1.5f / fps);
        float dt = TriangleMath.clamp((nowNanos - previousNanos) / 1_000_000_000f, 0, maxElapsed);
        previousNanos = nowNanos;
        nextNanos = fps != previousFps ? nowNanos + interval : nextNanos + interval;
        if (nextNanos <= nowNanos) nextNanos = nowNanos + interval;
        previousFps = fps;
        return dt;
    }
}
