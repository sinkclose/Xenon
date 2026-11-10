package org.telegram.ui.Components;

import android.animation.StateListAnimator;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.DrawableWrapper;

import android.view.View;
import android.view.ViewGroup;

import java.util.IdentityHashMap;
import java.util.Map;

/** Temporarily suppresses native press animations and selectors inside a liquid surface. */
public final class LiquidPressAnimationSuppressor {
    private final IdentityHashMap<View, StateListAnimator> saved = new IdentityHashMap<>();

    private final IdentityHashMap<View, PresslessDrawable> backgrounds = new IdentityHashMap<>();
    private final IdentityHashMap<View, PresslessDrawable> foregrounds = new IdentityHashMap<>();

    private static final class PresslessDrawable extends DrawableWrapper {
        PresslessDrawable(Drawable drawable) {
            super(drawable);
            onStateChange(drawable.getState());
            drawable.jumpToCurrentState();
        }

        @Override
        protected boolean onStateChange(int[] state) {
            int count = 0;
            for (int value : state) if (value != android.R.attr.state_pressed) count++;
            if (count == state.length) return getDrawable().setState(state);
            int[] filtered = new int[count];
            int index = 0;
            for (int value : state) if (value != android.R.attr.state_pressed) filtered[index++] = value;
            return getDrawable().setState(filtered);
        }
    }

    public void sync(View view, boolean liquid) {
        if (!liquid) {
            restore();
            return;
        }
        StateListAnimator animator = view.getStateListAnimator();
        if (animator != null) {
            saved.put(view, animator);
            animator.jumpToCurrentState();
            view.setStateListAnimator(null);
        }
        Drawable background = view.getBackground();
        if (background != null && background.isStateful() && !(background instanceof PresslessDrawable)) {
            PresslessDrawable wrapper = new PresslessDrawable(background);
            backgrounds.put(view, wrapper);
            view.setBackground(wrapper);
        }
        Drawable foreground = view.getForeground();
        if (foreground != null && foreground.isStateful() && !(foreground instanceof PresslessDrawable)) {
            PresslessDrawable wrapper = new PresslessDrawable(foreground);
            foregrounds.put(view, wrapper);
            view.setForeground(wrapper);
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) sync(group.getChildAt(i), true);
        }
    }

    public void restore() {
        for (Map.Entry<View, StateListAnimator> entry : saved.entrySet()) {
            if (entry.getKey().getStateListAnimator() == null) entry.getKey().setStateListAnimator(entry.getValue());
        }
        saved.clear();
        for (Map.Entry<View, PresslessDrawable> entry : backgrounds.entrySet()) {
            if (entry.getKey().getBackground() == entry.getValue()) {
                entry.getKey().setBackground(entry.getValue().getDrawable());
            }
        }
        backgrounds.clear();
        for (Map.Entry<View, PresslessDrawable> entry : foregrounds.entrySet()) {
            if (entry.getKey().getForeground() == entry.getValue()) {
                entry.getKey().setForeground(entry.getValue().getDrawable());
            }
        }
        foregrounds.clear();
    }
}
