package zxc.iconic.xenon.helpers;

import android.graphics.Outline;
import android.graphics.Rect;
import android.os.Build;
import android.provider.Settings;
import android.view.RoundedCorner;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.WindowInsets;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.ui.Components.CubicBezierInterpolator;

import zxc.iconic.xenon.NekoConfig;

/** Shared frames for activity transitions and their predictive back counterparts. */
public final class NavigationTransition {
    private NavigationTransition() {}

    private static final String[] cachedEase = new String[3];
    private static final Interpolator[] cachedInterpolator = new Interpolator[3];
    private static final Interpolator PIE_MOTION = new PathInterpolator(.4f, 0f, .2f, 1f);
    private static final Interpolator PIE_DIM = new PathInterpolator(.33f, 0f, 1f, 1f);

    public static boolean usesBlackSurface(int style) {
        return style == NekoConfig.ANIMATION_STYLE_AOSP_LEGACY || style == NekoConfig.ANIMATION_STYLE_AOSP_9;
    }

    public static Interpolator interpolator(int style) {
        int index = style == NekoConfig.ANIMATION_STYLE_SLIDE ? 1 : style == NekoConfig.ANIMATION_STYLE_FADE ? 2 : 0;
        String ease = index == 1 ? NekoConfig.slideEase : index == 2 ? NekoConfig.fadeEase : NekoConfig.alternativeTransitionEase;
        if (cachedInterpolator[index] == null || !ease.equals(cachedEase[index])) {
            String fallback = index == 1 ? NekoConfig.DEFAULT_SLIDE_EASE : index == 2 ? NekoConfig.DEFAULT_FADE_EASE : NekoConfig.DEFAULT_IOS_EASE;
            cachedInterpolator[index] = parseEase(ease, fallback);
            cachedEase[index] = ease;
        }
        return cachedInterpolator[index];
    }

    private static Interpolator parseEase(String ease, String fallback) {
        try {
            String[] parts = ease.split(",");
            if (parts.length != 4) throw new IllegalArgumentException("Expected four control points");
            float[] values = new float[4];
            for (int i = 0; i < 4; i++) {
                values[i] = Float.parseFloat(parts[i].trim());
                if (!Float.isFinite(values[i])) throw new IllegalArgumentException("Non-finite control point");
            }
            return new PathInterpolator(values[0], values[1], values[2], values[3]);
        } catch (Exception e) {
            if (!fallback.equals(ease)) return parseEase(fallback, fallback);
            return CubicBezierInterpolator.EASE_OUT_QUINT;
        }
    }

    public static float duration(int style) {
        if (style == NekoConfig.ANIMATION_STYLE_AOSP || style == NekoConfig.ANIMATION_STYLE_AOSP_ALT || usesBlackSurface(style)) {
            float scale = 1f;
            try {
                scale = Settings.Global.getFloat(ApplicationLoader.applicationContext.getContentResolver(), "transition_animation_scale", 1f);
            } catch (Exception ignored) {}
            return Math.max(1f, scale * (style == NekoConfig.ANIMATION_STYLE_AOSP_9 ? 425f : style == NekoConfig.ANIMATION_STYLE_AOSP_LEGACY ? 400f : 450f));
        }
        return Math.max(1, style == NekoConfig.ANIMATION_STYLE_SLIDE ? NekoConfig.slideDuration : style == NekoConfig.ANIMATION_STYLE_FADE ? NekoConfig.fadeDuration : NekoConfig.alternativeTransitionSpeed);
    }

    private static float clamp(float p) {
        return Math.max(0f, Math.min(1f, p));
    }

    private static float fade(float p, float duration, float offset, float length) {
        return clamp((p * duration - offset) / length);
    }

    /** Incoming is behind outgoing on close; the callers preserve this stacking order. */
    public static void apply(View incoming, View outgoing, int style, boolean open, float progress) {
        float p = clamp(progress);
        if (style == NekoConfig.ANIMATION_STYLE_AOSP || style == NekoConfig.ANIMATION_STYLE_AOSP_ALT) {
            float slide = AndroidUtilities.dp(96);
            float shifted = Math.round(CubicBezierInterpolator.Emphasized.getInterpolation(p) * slide);
            incoming.setTranslationX(open ? slide - shifted : shifted - slide);
            incoming.setAlpha(open ? fade(p, 450f, 50f, 83f) : 1f);
            outgoing.setTranslationX(open ? -shifted : shifted);
            outgoing.setAlpha(open ? 1f : 1f - fade(p, 450f, 35f, 83f));
        } else if (style == NekoConfig.ANIMATION_STYLE_AOSP_LEGACY) {
            // AOSP Android 10/11/12 core/res/res/anim/activity_{open,close}_{enter,exit}.xml.
            // https://github.com/aosp-mirror/platform_frameworks_base/tree/android-12.0.0_r1/core/res/res/anim
            // Apache-2.0 (https://www.apache.org/licenses/LICENSE-2.0), Copyright 2009 The Android Open Source Project.
            // fast_out_extra_slow_in is the same two-segment path as Emphasized.
            float eased = CubicBezierInterpolator.Emphasized.getInterpolation(p);
            scale(incoming, open ? .85f + .15f * eased : 1.1f - .1f * eased);
            scale(outgoing, open ? 1f + .05f * eased : 1f - .1f * eased);
            incoming.setTranslationX(0f);
            outgoing.setTranslationX(0f);
            incoming.setAlpha(open ? fade(p, 400f, 50f, 50f) : 1f);
            outgoing.setAlpha(open ? 1f - .6f * fade(p, 400f, 83f, 167f) : 1f - fade(p, 400f, 33f, 50f));
        } else if (style == NekoConfig.ANIMATION_STYLE_AOSP_9) {
            // Android 9 activity_{open,close}_{enter,exit}.xml, Apache-2.0, Copyright 2009 AOSP.
            // https://github.com/aosp-mirror/platform_frameworks_base/tree/android-9.0.0_r1/core/res/res/anim
            float motion = PIE_MOTION.getInterpolation(p);
            float reveal = CubicBezierInterpolator.Emphasized.getInterpolation(p);
            incoming.setTranslationX(0f);
            outgoing.setTranslationX(0f);
            incoming.setTranslationY(incoming.getHeight() * (open ? .041f * (1f - motion) : -.02f * (1f - motion)));
            outgoing.setTranslationY(outgoing.getHeight() * (open ? -.02f * motion : .041f * motion));
            View clipped = open ? incoming : outgoing;
            int top = Math.round(clipped.getHeight() * .959f * (open ? 1f - reveal : reveal));
            clipped.setClipBounds(new Rect(0, top, clipped.getWidth(), clipped.getHeight()));
            incoming.setAlpha(open ? 1f : .9f + .1f * PIE_DIM.getInterpolation(p));
            outgoing.setAlpha(open ? 1f - .1f * fade(p, 425f, 0f, 117f) : 1f);
        } else {
            float eased = interpolator(style).getInterpolation(p);
            incoming.setAlpha(1f);
            outgoing.setAlpha(1f);
            if (style == NekoConfig.ANIMATION_STYLE_FADE) {
                incoming.setTranslationX(0f);
                outgoing.setTranslationX(0f);
                if (open) incoming.setAlpha(clamp(eased));
                else outgoing.setAlpha(clamp(1f - eased));
            } else {
                float width = incoming.getWidth();
                float distance = style == NekoConfig.ANIMATION_STYLE_SLIDE ? width : AndroidUtilities.dp(96);
                incoming.setTranslationX(open ? width * (1f - eased) : -distance * (1f - eased));
                outgoing.setTranslationX(open ? -distance * eased : width * eased);
            }
        }
    }

    private static void scale(View view, float scale) {
        if (!(view.getOutlineProvider() instanceof DisplayOutline)) setupRoundedCorners(view);
        view.setPivotX(view.getWidth() / 2f);
        view.setPivotY(view.getHeight() / 2f);
        view.setScaleX(scale);
        view.setScaleY(scale);
    }

    private static void setupRoundedCorners(View view) {
        float radius = AndroidUtilities.dp(28);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            WindowInsets insets = view.getRootWindowInsets();
            if (insets != null) {
                radius = 0f;
                for (int position : new int[]{RoundedCorner.POSITION_TOP_LEFT, RoundedCorner.POSITION_TOP_RIGHT,
                        RoundedCorner.POSITION_BOTTOM_RIGHT, RoundedCorner.POSITION_BOTTOM_LEFT}) {
                    RoundedCorner corner = insets.getRoundedCorner(position);
                    if (corner != null) radius = Math.max(radius, corner.getRadius());
                }
            }
        }
        view.setOutlineProvider(new DisplayOutline(radius));
        view.setClipToOutline(true);
    }

    private static final class DisplayOutline extends ViewOutlineProvider {
        private final float radius;

        DisplayOutline(float radius) {
            this.radius = radius;
        }

        @Override
        public void getOutline(View view, Outline outline) {
            outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
        }
    }
}
