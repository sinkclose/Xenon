package org.telegram.ui.Components.blur3;

/** Optical fit to isolated native Figma GLASS coordinate charts; see docs/ios27-liquid-glass.md. */
public final class Ios27GlassMaterial {
    private Ios27GlassMaterial() {}

    public static final float THIN_AMPLITUDE_DP = 8.75969f;
    public static final float CAPSULE_AMPLITUDE_DP = 25.38086f;
    public static final float CIRCLE_AMPLITUDE_DP = 27.57047f;
    public static final float THIN_EXPONENT = .73f;
    public static final float CAPSULE_EXPONENT = 1.15f;
    public static final float CIRCLE_EXPONENT = 1.11f;
    public static final float THIN_RADIAL = .70f;
    public static final float CAPSULE_RADIAL = .36f;

    /** Sharp backdrop, a two-pixel guard, then the same backdrop filtered at half resolution. */
    public static int packedBackdropWidth(int width) {
        return width + 2 + (width + 1) / 2;
    }

    /** Signed inner displacement, inner depth, outer displacement and outer depth, in pixels. */
    public static void renderLens(float width, float height, float density, float strength, boolean clearControl,
                                  float[] out, int offset) {
        if (clearControl) {
            out[offset] = -16f * density;
            out[offset + 1] = 8f * density;
            out[offset + 2] = 0f;
            out[offset + 3] = 0f;
            return;
        }
        float extent = Math.max(0f, Math.min(width, height) / density);
        float gain = strength / .7f * density;
        out[offset] = -Math.min(extent * .5f, 60f) * gain;
        out[offset + 1] = Math.min(extent * .25f, 20f) * density;
        out[offset + 2] = Math.max(16f, extent * .25f) * gain;
        out[offset + 3] = Math.max(16f, extent * .2f) * density;
    }

    /** Amplitude in pixels, profile exponent, radial normal weight, lens depth in pixels. */
    public static void lens(float width, float height, float density, float[] out, int offset) {
        float shortSide = Math.min(width, height);
        float t = Math.max(0f, Math.min(1f, (shortSide / density - 19f) / 29f));
        float elongated = Math.max(0f, Math.min(1f,
                (Math.max(width, height) / Math.max(shortSide, 1f) - 1f) / 2f));
        float regularAmplitude = CIRCLE_AMPLITUDE_DP
                + (CAPSULE_AMPLITUDE_DP - CIRCLE_AMPLITUDE_DP) * elongated;
        float regularExponent = CIRCLE_EXPONENT
                + (CAPSULE_EXPONENT - CIRCLE_EXPONENT) * elongated;
        // Below the measured 19 dp control scale all lengths down, not the shape itself.
        float smallScale = Math.min(1f, shortSide / (19f * density));
        out[offset] = density * smallScale * (THIN_AMPLITUDE_DP
                + (regularAmplitude - THIN_AMPLITUDE_DP) * t);
        out[offset + 1] = THIN_EXPONENT + (regularExponent - THIN_EXPONENT) * t;
        out[offset + 2] = THIN_RADIAL + (CAPSULE_RADIAL - THIN_RADIAL) * t;
        out[offset + 3] = Math.min(30f * density, shortSide * .5f);
    }
}
