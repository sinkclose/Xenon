package org.telegram.ui.Components;

/** Window placement and the ordinary native popup pull gesture. Glass motion uses GlasmioMenuMotion. */
public final class IosPopupMorphGeometry {
    private IosPopupMorphGeometry() { }

    public static final int OPEN_DURATION = 490;
    public static final int CLOSE_DURATION = 290;

    public static float dragOffset(float distance, float limit) {
        if (limit <= 0) return 0;
        return limit * distance / (Math.abs(distance) + limit * 2);
    }

    public static int clampMenuPosition(int value, int size, int start, int end, int margin) {
        int low = start + margin, high = Math.max(low, end - margin - size);
        return Math.max(low, Math.min(value, high));
    }

}
