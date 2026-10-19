package zxc.iconic.xenon.triangles;

/** Platform-independent geometry, motion and colour helpers. Units are dp and seconds. */
public final class TriangleMath {
    public static final int MAX_PARTICLES = 512;
    private TriangleMath() {}

    public static float clamp(float value, float min, float max) {
        return !Float.isNaN(value) && !Float.isInfinite(value) ? Math.max(min, Math.min(max, value)) : min;
    }

    public static int count(float widthDp, int density) {
        return (int) clamp(widthDp * 0.02f * density / 50f, 1, MAX_PARTICLES);
    }

    public static float sizeMultiplier(float random, int variance) {
        return Math.max(0.05f, 1 + random * variance * 0.0085f);
    }

    public static float speedMultiplier(float random, int variance) {
        return Math.max(0.01f, 1 + random * variance / 100f);
    }

    /** Vertex radius offset for a centred equilateral outline (inradius = side / sqrt(12)). */
    public static float outlineScale(float side, float thickness, boolean outer) {
        float offset = thickness * 1.7320508f / Math.max(0.001f, side);
        return outer ? 1 + offset : Math.max(0, 1 - offset);
    }

    public static float elapsed(long previous, long now) {
        return previous == 0 ? 0 : clamp((now - previous) / 1000f, 0, 0.1f);
    }

    public static float fade(float y, float top, float bottom, float power) {
        y = clamp(y, 0, 1);
        float a = top <= 0 ? 1 : clamp(y / top, 0, 1);
        float b = bottom <= 0 ? 1 : clamp((1 - y) / bottom, 0, 1);
        return (float) Math.pow(a * b, power);
    }

    public static int mix(int a, int b, float t) {
        t = clamp(t, 0, 1);
        int r = Math.round(((a >> 16) & 255) * (1-t) + ((b >> 16) & 255) * t);
        int g = Math.round(((a >> 8) & 255) * (1-t) + ((b >> 8) & 255) * t);
        int bl = Math.round((a & 255) * (1-t) + (b & 255) * t);
        return 0xff000000 | (r << 16) | (g << 8) | bl;
    }

    /** Keep wallpaper hue, making the overlay lighter on dark backgrounds and darker on light ones. */
    public static int automaticColor(int wallpaper) {
        float brightness = (0.2126f * ((wallpaper >> 16) & 255)
                + 0.7152f * ((wallpaper >> 8) & 255) + 0.0722f * (wallpaper & 255)) / 255f;
        return mix(wallpaper, brightness < 0.5f ? 0xffffffff : 0xff000000, 0.38f);
    }
}
