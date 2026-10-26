package org.telegram.ui.Components;

import android.graphics.Canvas;
import android.graphics.Matrix;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.SeekBar;

import androidx.dynamicanimation.animation.FloatValueHolder;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;

import org.telegram.messenger.AndroidUtilities;

/** Shared by native popup windows and the menu embedded in a message preview. */
final class IosPopupInteraction {
    private final View root;
    private final Runnable invalidate;
    private View selected;
    private float downY, pull;
    private boolean tracking;
    private final SpringAnimation deformation;
    private final IosPopupFeedback feedback;
    private float pivotX = .5f, pivotY = .5f;
    private float lastX, lastY;
    private ScrollView scrolling;
    private int scrollDirection;
    private final int[] rootLocation = new int[2], scrollLocation = new int[2];
    private final Runnable scrollFrame = new Runnable() {
        @Override public void run() {
            if (!tracking || scrolling == null || !scrolling.canScrollVertically(scrollDirection)) return;
            scrolling.scrollBy(0, scrollDirection * AndroidUtilities.dp(6));
            select(findAction(root, lastX, lastY));
            invalidate.run();
            root.postDelayed(this, 16);
        }
    };

    IosPopupInteraction(View root, Runnable invalidate) {
        this(root, invalidate, true);
    }

    IosPopupInteraction(View root, Runnable invalidate, boolean drawFeedback) {
        this.root = root;
        this.invalidate = invalidate;
        feedback = drawFeedback ? new IosPopupFeedback(root, invalidate) : null;
        deformation = new SpringAnimation(new FloatValueHolder());
        deformation.setSpring(new SpringForce(0).setDampingRatio(.85f).setStiffness(400f));
        deformation.setMinimumVisibleChange(.1f);
        deformation.addUpdateListener((a, value, velocity) -> { pull = value; invalidate.run(); });
    }

    float pull() { return pull; }
    float pivotX() { return pivotX; }
    float pivotY() { return pivotY; }

    void drawFeedback(Canvas canvas, float centerX, float centerY, float scaleX, float scaleY) {
        if (feedback != null) feedback.draw(canvas, centerX, centerY, scaleX, scaleY);
    }

    boolean touch(MotionEvent event, float x, float y, boolean forwarded) {
        int action = event.getActionMasked();
        lastX = x;
        lastY = y;
        if (action == MotionEvent.ACTION_POINTER_DOWN || action == MotionEvent.ACTION_CANCEL) {
            if (feedback != null) feedback.touch(event, x, y, false);
            boolean wasTracking = tracking;
            finish(false);
            return wasTracking || forwarded;
        }
        View hit = findAction(root, x, y);
        boolean starting = !tracking;
        if (starting) {
            if (action != MotionEvent.ACTION_DOWN && !(forwarded && action == MotionEvent.ACTION_MOVE)) return forwarded;
            if (hit == null && !forwarded) return false;
            tracking = true;
            downY = event.getRawY();
            // The pivot belongs to the inner glass bounds, excluding legacy shadow padding.
            pivotX = Math.max(0, Math.min(1, (x - root.getPaddingLeft())
                    / Math.max(1, root.getWidth() - root.getPaddingLeft() - root.getPaddingRight())));
            pivotY = Math.max(0, Math.min(1, (y - root.getPaddingTop())
                    / Math.max(1, root.getHeight() - root.getPaddingTop() - root.getPaddingBottom())));
        }
        if (feedback != null) feedback.touch(event, x, y, starting);
        if (action == MotionEvent.ACTION_MOVE) {
            deformation.animateToFinalPosition(IosPopupMorphGeometry.dragOffset(event.getRawY() - downY,
                    Math.min(AndroidUtilities.dp(36), root.getHeight() * .14f)));
            invalidate.run();
        }
        select(hit);
        if (action == MotionEvent.ACTION_MOVE) updateScroll();
        if (action == MotionEvent.ACTION_UP) finish(true);
        return true;
    }

    private void select(View hit) {
        if (feedback != null) feedback.select(hit);
        if (selected == hit) return;
        if (selected != null) selected.setPressed(false);
        selected = hit;
        if (selected != null) selected.setPressed(true);
        invalidate.run();
    }

    private void finish(boolean click) {
        View item = selected;
        tracking = false;
        root.removeCallbacks(scrollFrame);
        select(null);
        deformation.animateToFinalPosition(0);
        if (click && item != null && item.isEnabled() && item.isAttachedToWindow()) item.performClick();
    }

    void release() {
        tracking = false;
        root.removeCallbacks(scrollFrame);
        select(null);
        deformation.cancel();
        deformation.setStartValue(0);
        if (feedback != null) feedback.release();
        pull = 0;
    }

    private void updateScroll() {
        root.removeCallbacks(scrollFrame);
        scrolling = findScroll(root);
        if (scrolling == null) return;
        root.getLocationOnScreen(rootLocation);
        scrolling.getLocationOnScreen(scrollLocation);
        float top = scrollLocation[1] - rootLocation[1], bottom = top + scrolling.getHeight();
        scrollDirection = lastY < top + AndroidUtilities.dp(24) ? -1
                : lastY > bottom - AndroidUtilities.dp(24) ? 1 : 0;
        if (scrollDirection != 0 && scrolling.canScrollVertically(scrollDirection)) root.postDelayed(scrollFrame, 16);
    }

    private static ScrollView findScroll(View root) {
        if (root.getVisibility() != View.VISIBLE) return null;
        if (root instanceof ScrollView && (root.canScrollVertically(-1) || root.canScrollVertically(1))) return (ScrollView) root;
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = group.getChildCount() - 1; i >= 0; i--) {
                ScrollView found = findScroll(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    private static boolean isWidget(View view) {
        return view instanceof EditText || view instanceof SeekBar || view instanceof android.widget.CompoundButton;
    }

    static View findAction(View root, float x, float y) {
        if (root.getVisibility() != View.VISIBLE || !root.isEnabled() || x < 0 || y < 0
                || x >= root.getWidth() || y >= root.getHeight() || isWidget(root)) return null;
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = group.getChildCount() - 1; i >= 0; i--) {
                View child = group.getChildAt(i);
                float[] point = {x + root.getScrollX() - child.getLeft(), y + root.getScrollY() - child.getTop()};
                if (!child.getMatrix().isIdentity()) {
                    Matrix inverse = new Matrix();
                    if (!child.getMatrix().invert(inverse)) continue;
                    inverse.mapPoints(point);
                }
                if (isWidget(child) && point[0] >= 0 && point[0] < child.getWidth()
                        && point[1] >= 0 && point[1] < child.getHeight()) return null;
                View hit = findAction(child, point[0], point[1]);
                if (hit != null) return hit;
            }
        }
        return root.isClickable() && root.hasOnClickListeners() && !(root instanceof ScrollView) ? root : null;
    }
}
