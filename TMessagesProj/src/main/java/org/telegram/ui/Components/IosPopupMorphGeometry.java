package org.telegram.ui.Components;

/** Reference-timed travel and expansion with a small, smoothly joined size rebound. */
public final class IosPopupMorphGeometry {
    private IosPopupMorphGeometry() { }

    public static final int OPEN_DURATION = 490;
    public static final int CLOSE_DURATION = 290;

    public static float dragOffset(float distance, float limit) {
        if (limit <= 0) return 0;
        return limit * distance / (Math.abs(distance) + limit * 2);
    }

    /** Vertical input drives both axes, with the original touch point as the pivot. */
    public static float applyDrag(float offset, float pivotX, float pivotY, float[] out) {
        float pull = Math.max(-out[3] * .18f, Math.min(out[3] * .18f, offset));
        float scale = (out[3] + pull) / out[3];
        // Like the bottom navigation lens, elongating one axis compresses the other.
        float scaleX = 1 / (1 + (scale - 1) * .45f);
        pivotX = Math.max(0, Math.min(1, pivotX));
        pivotY = Math.max(0, Math.min(1, pivotY));
        out[0] += (pivotX - .5f) * out[2] * (1 - scaleX);
        out[1] += (pivotY - .5f) * out[3] * (1 - scale);
        out[2] *= scaleX;
        out[3] += pull;
        float half = Math.min(out[2], out[3]) / 2;
        for (int corner = 0; corner < 4; corner++) out[5 + corner] = Math.min(half, out[5 + corner]);
        return scale;
    }

    public static float surfaceOpacity(float t, boolean closing) {
        return closing ? 1 - smooth(t) : smooth(t / .22f);
    }

    /** A dedicated monotonic collapse; reversing the opening spring causes an idle/rebound. */
    public static void evaluateClosing(float t, float[] start, float x, float y, float width, float height,
                                       float radius, float[] out) {
        float size = smooth(t / .82f), travel = smooth((t - .04f) / .96f);
        out[0] = start[0] + (x - start[0]) * travel;
        out[1] = start[1] + (y - start[1]) * travel;
        out[2] = Math.max(1, start[2] + (width - start[2]) * size);
        out[3] = Math.max(1, start[3] + (height - start[3]) * size);
        out[4] = 0;
        float half = Math.min(out[2], out[3]) / 2;
        for (int corner = 0; corner < 4; corner++) {
            float lens = start[5 + corner] + (half - start[5 + corner]) * smooth(t / .45f);
            out[5 + corner] = Math.max(0, Math.min(half, lens + (radius - lens) * smooth((t - .55f) / .45f)));
        }
    }

    public static int clampMenuPosition(int value, int size, int start, int end, int margin) {
        int low = start + margin, high = Math.max(low, end - margin - size);
        return Math.max(low, Math.min(value, high));
    }

    public static float centeredContentOffset(float shapeCenter, float finalCenter) {
        return shapeCenter - finalCenter;
    }

    private static float smooth(float t) {
        double u = Math.max(0, Math.min(1, t));
        return (float) (u * u * u * (10 + u * (-15 + 6 * u)));
    }

    private static float spring(float phase, double damping, double frequency, boolean rebound) {
        if (phase <= 0) return 0;
        if (phase >= 1) return 1;
        // Ease the initial acceleration over a few milliseconds, without a
        // second easing stage over the fast part of the spring.
        phase -= (float) (.01 * (1 - Math.exp(-phase / .01)));
        double damped = frequency * Math.sqrt(1 - damping * damping);
        double peakTime = Math.PI / damped;
        if (phase < peakTime) {
            // Use the spring's clock directly: easing time first made the small lens
            // linger at the button, with most of the growth happening much too late.
            return (float) (1 - Math.exp(-damping * frequency * phase)
                    * (Math.cos(damped * phase) + damping * frequency / damped * Math.sin(damped * phase)));
        }
        double overshoot = Math.exp(-damping * frequency * peakTime);
        double tailDuration = Math.sqrt(12) / frequency;
        double u = Math.min(1, (phase - peakTime) / tailDuration);
        double remaining = 1 - u;
        // Match position, velocity AND acceleration at the first peak, then settle
        // with a size undershoot of about 0.6%. The u^3 term leaves the peak
        // acceleration unchanged, and the cubic remainder makes the finish C2 continuous.
        return (float) (1 + overshoot * remaining * remaining * remaining * (1 + 3 * u - (rebound ? 18 * u * u * u : 0)));
    }

    public static float travelProgress(float t) {
        return spring(t, .70, 12.5, false);
    }

    public static float expansionProgress(float t) {
        // ~22 ms of travel before expansion, then a later, slightly stronger size peak.
        return spring(t - .045f, .64, 10.8, true);
    }

    public static float opticalScale(float t) {
        return 1 + 1.8f * (1 - smooth(t / .70f));
    }

    /** Output: center x/y, width/height, zero rotation, four equal corner radii. */
    public static void evaluate(float t, float sx, float sy, float sw, float sh, float tx, float ty,
                                float tw, float th, float sourceRadius, float finalRadius, float[] out) {
        t = Math.max(0, Math.min(1, t));
        float travel = travelProgress(t), expansion = expansionProgress(t);
        out[0] = sx + (tx - sx) * travel;
        out[1] = sy + (ty - sy) * travel;
        out[2] = Math.max(1, sw + (tw - sw) * expansion);
        out[3] = Math.max(1, sh + (th - sh) * expansion);
        out[4] = 0;
        float half = Math.min(out[2], out[3]) * .5f;
        float roundedLens = sourceRadius + (half - sourceRadius) * smooth(t / .10f);
        float settle = smooth((t - .14f) / .46f);
        float radius = Math.max(0, Math.min(half, roundedLens + (finalRadius - roundedLens) * settle));
        for (int corner = 0; corner < 4; corner++) out[5 + corner] = radius;
    }
}
