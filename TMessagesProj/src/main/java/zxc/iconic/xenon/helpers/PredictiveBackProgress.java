package zxc.iconic.xenon.helpers;

import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;

import org.telegram.ui.Components.CubicBezierInterpolator;

import zxc.iconic.xenon.NekoConfig;

public final class PredictiveBackProgress {
    static final Interpolator FULL_SCREEN = new PathInterpolator(.1f, .1f, 0f, 1f);
    private static final float FADE_THROUGH_THRESHOLD = .35f;

    private PredictiveBackProgress() {}

    public static float interpolate(float progress) {
        return NekoConfig.predictiveBackDecelerate
                ? CubicBezierInterpolator.StandardDecelerate.getInterpolation(progress) : progress;
    }

    static float exitAlpha(float progress) {
        return 1f - Math.min(1f, progress / FADE_THROUGH_THRESHOLD);
    }

    static float enterAlpha(float progress) {
        return Math.max(0f, (progress - FADE_THROUGH_THRESHOLD) / (1f - FADE_THROUGH_THRESHOLD));
    }
}
