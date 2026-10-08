package org.telegram.ui.Components;

import android.animation.StateListAnimator;
import android.view.View;
import android.view.ViewGroup;

import java.util.IdentityHashMap;
import java.util.Map;

/** Temporarily removes press animators from the controls inside a liquid surface. */
public final class LiquidPressAnimationSuppressor {
    private final IdentityHashMap<View, StateListAnimator> saved = new IdentityHashMap<>();

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
    }
}
