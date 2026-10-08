package zxc.iconic.xenon.helpers;

/** Geometry from AndroidLiquidGlass's LiquidBottomTabs, independent of Android. */
public final class LiquidNavigationMath {
    private LiquidNavigationMath() {}

    public static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    public static float lensScaleX(float scale, float velocity, float slotWidth) {
        float v = slotWidth > 0 ? velocity / slotWidth / 10f : 0f;
        return scale / (1f - clamp(v * 0.75f, -0.2f, 0.2f));
    }

    public static float lensScaleY(float scale, float velocity, float slotWidth) {
        float v = slotWidth > 0 ? velocity / slotWidth / 10f : 0f;
        return scale * (1f - clamp(v * 0.25f, -0.2f, 0.2f));
    }

    public static float panelOffset(float drag, float width, float maxOffset) {
        float fraction = width > 0 ? clamp(drag / width, -1f, 1f) : 0f;
        return Math.signum(fraction) * maxOffset * easeOut(Math.abs(fraction));
    }

    // Compose EaseOut = CubicBezierEasing(0, 0, 0.58, 1).
    private static float easeOut(float fraction) {
        if (fraction <= 0f || fraction >= 1f) return fraction;
        float low = 0f, high = 1f;
        for (int i = 0; i < 20; i++) {
            float t = (low + high) / 2f;
            float x = 3f * (1f - t) * t * t * 0.58f + t * t * t;
            if (x < fraction) low = t; else high = t;
        }
        float t = (low + high) / 2f;
        return 3f * (1f - t) * t * t + t * t * t;
    }

    public static float panelScale(float press, float width, float growth) {
        return 1f + (width > 0 ? growth / width : 0f) * clamp(press, 0f, 1f);
    }

    public static float contentScale(float press) {
        return 1f + 0.2f * clamp(press, 0f, 1f);
    }
}
