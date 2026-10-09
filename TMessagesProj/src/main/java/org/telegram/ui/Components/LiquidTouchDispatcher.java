package org.telegram.ui.Components;

import android.graphics.Matrix;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;

import java.util.ArrayList;

/** Observes liquid gestures at the chat root before any controls can intercept them. */
public final class LiquidTouchDispatcher {
    public interface Target {
        void observeLiquidTouch(MotionEvent event);
    }

    public interface Dispatch {
        boolean run();
    }

    // Touch dispatch is confined to the UI thread. Depth also supports nested chat views.
    private static int controlsDispatchDepth;
    private final ArrayList<View> targets = new ArrayList<>();
    private final ArrayList<View> path = new ArrayList<>();
    private final Matrix inverse = new Matrix();

    public static boolean isDispatchingControls() {
        return controlsDispatchDepth != 0;
    }

    public void cancel(ViewGroup root) {
        MotionEvent event = MotionEvent.obtain(0, 0, MotionEvent.ACTION_CANCEL, 0, 0, 0);
        try {
            cancel(root, event);
        } finally {
            event.recycle();
        }
    }

    public boolean dispatchTouchEvent(ViewGroup root, MotionEvent event, boolean enabled, Dispatch dispatch) {
        if (!enabled) {
            cancel(root, event);
            return dispatch.run();
        }
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            cancel(root, event);
            collect(root, event);
        } else {
            observe(root, event);
        }
        boolean handled = false;
        controlsDispatchDepth++;
        try {
            handled = dispatch.run();
            return handled;
        } finally {
            controlsDispatchDepth--;
            if (!handled || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                cancel(root, event);
            } else if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                targets.clear();
            }
        }
    }

    private void collect(View view, MotionEvent local) {
        if (view.getVisibility() != View.VISIBLE || view.getAlpha() <= 0f || !view.isEnabled()) return;
        if (view instanceof Target) {
            targets.add(view);
            ((Target) view).observeLiquidTouch(local);
        }
        if (!(view instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) view;
        for (int i = group.getChildCount() - 1; i >= 0; i--) {
            View child = group.getChildAt(i);
            if (child.getVisibility() != View.VISIBLE || child.getAlpha() <= 0f || !child.isEnabled()) continue;
            MotionEvent childEvent = MotionEvent.obtain(local);
            try {
                if (toChild(group, child, childEvent)
                        && childEvent.getX() >= 0 && childEvent.getX() < child.getWidth()
                        && childEvent.getY() >= 0 && childEvent.getY() < child.getHeight()) {
                    collect(child, childEvent);
                }
            } finally {
                childEvent.recycle();
            }
        }
    }

    private boolean toChild(View parent, View child, MotionEvent event) {
        event.offsetLocation(parent.getScrollX() - child.getLeft(), parent.getScrollY() - child.getTop());
        if (!child.getMatrix().isIdentity()) {
            if (!child.getMatrix().invert(inverse)) return false;
            event.transform(inverse);
        }
        return true;
    }

    private void observe(ViewGroup root, MotionEvent event) {
        for (View target : targets) {
            path.clear();
            View current = target;
            while (current != root) {
                path.add(current);
                ViewParent parent = current.getParent();
                if (!(parent instanceof View)) break;
                current = (View) parent;
            }
            MotionEvent local = MotionEvent.obtain(event);
            try {
                boolean attached = current == root && target.isShown() && target.isEnabled();
                if (attached) {
                    View parent = root;
                    for (int i = path.size() - 1; i >= 0; i--) {
                        View child = path.get(i);
                        if (!toChild(parent, child, local)) {
                            attached = false;
                            break;
                        }
                        parent = child;
                    }
                }
                if (!attached) local.setAction(MotionEvent.ACTION_CANCEL);
                ((Target) target).observeLiquidTouch(local);
            } finally {
                local.recycle();
            }
        }
    }

    private void cancel(ViewGroup root, MotionEvent event) {
        if (targets.isEmpty()) return;
        MotionEvent cancelled = MotionEvent.obtain(event);
        try {
            cancelled.setAction(MotionEvent.ACTION_CANCEL);
            observe(root, cancelled);
        } finally {
            cancelled.recycle();
            targets.clear();
        }
    }
}
