package com.google.android.material.loadingindicator;

public final class LoadingIndicatorSpecHelper {

    private LoadingIndicatorSpecHelper() {
    }

    public static void configure(
            LoadingIndicatorSpec spec,
            int indicatorSize,
            int containerWidth,
            int containerHeight,
            int[] indicatorColors,
            int containerColor) {
        spec.indicatorSize = indicatorSize;
        spec.containerWidth = containerWidth;
        spec.containerHeight = containerHeight;
        spec.indicatorColors = indicatorColors;
        spec.containerColor = containerColor;
    }
}
