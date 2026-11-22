package org.telegram.ui.Components;

import org.telegram.messenger.AndroidUtilities;

import java.util.concurrent.ThreadLocalRandom;

import zxc.iconic.xenon.TextRemovalAnimationSettings;
import zxc.iconic.xenon.TextRemovalAnimationSettings.Slider;

final class TextSlideAnimation {
    final float dx;
    final float dy;
    final int duration;

    TextSlideAnimation(TextRemovalAnimationSettings settings) {
        double radians = Math.toRadians(settings.get(Slider.SLIDE_ANGLE));
        int min = settings.get(Slider.SLIDE_DISTANCE_MIN);
        int max = settings.get(Slider.SLIDE_DISTANCE_MAX);
        float distance = AndroidUtilities.dp(ThreadLocalRandom.current().nextInt(min, max + 1));
        dx = distance * (float) Math.cos(radians);
        dy = distance * (float) Math.sin(radians);
        duration = settings.get(Slider.SLIDE_DURATION);
    }

    static float ease(float t) {
        t = Math.max(0f, Math.min(1f, t));
        return t * t * (3f - 2f * t);
    }
}
