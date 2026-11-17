package org.telegram.ui.Components.blur3;

/** Maps Glasmio's native mip radius to a Gaussian with the same second moment. */
public final class GlasmioBlurProfile {
    private GlasmioBlurProfile() {}

    public static float lod(float radiusPixels) {
        float r = Math.max(0f, radiusPixels) * 1.6f;
        return (float) (Math.log(r < 2f ? 1f + r * .5f : r) / Math.log(2.0));
    }

    public static float variance(float lod) {
        double level = Math.max(0.0, Math.min(20.0, lod));
        int lower = (int) Math.floor(level);
        double t = level - lower;
        // Compute13Tap: 2*inner + 4*diagonal + 8*outer = .939453125.
        // Output-grid offsets double every level; bilinear source reconstruction
        // contributes another .25 previous-level pixel squared to each axis.
        double a = 4.0078125 * (Math.pow(4.0, lower) - 1.0) / 3.0;
        double b = 4.0078125 * (Math.pow(4.0, lower + 1) - 1.0) / 3.0;
        return (float) (a + (b - a) * t);
    }

    public static float sigmaPixels(float radiusDp, float density, float captureScale) {
        if (radiusDp <= 0f) return 0f;
        float scale = Math.max(.001f, captureScale);
        return (float) Math.sqrt(variance(lod(radiusDp * density * scale))) / scale;
    }
}
