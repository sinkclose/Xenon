"""Exercise production pager touch handling with subpixel motion events."""

import re
import unittest

from test_glass_blur import ROOT, method
from test_glass_chat_refresh import run_java


class ViewPagerDragTest(unittest.TestCase):
    def test_slow_drag_reversal_and_animation_takeover_preserve_coordinates(self):
        source = (ROOT / 'org/telegram/ui/Components/ViewPagerFixed.java').read_text()
        fields = '\n'.join(re.findall(
            r'private (?:int|float) startedTracking[XY];', source))
        methods = '\n'.join(method(source, signature) for signature in (
            'private boolean prepareForMoving(MotionEvent ev, boolean forward)',
            'public boolean onTouchEventInternal(MotionEvent ev)',
            'protected void setTranslationX(View view, float tx)'))
        harness = r'''
import java.util.*;
public class PagerDragHarness {
    static void check(boolean condition, String reason) {
        if (!condition) throw new AssertionError(reason);
    }
    static void near(float actual, float expected, String reason) {
        check(Math.abs(actual - expected) < 0.00001f,
                reason + ": expected " + expected + ", got " + actual);
    }
    static class MotionEvent {
        static final int ACTION_DOWN = 0, ACTION_UP = 1, ACTION_MOVE = 2,
                ACTION_CANCEL = 3, ACTION_POINTER_UP = 6;
        int action; float x, y;
        MotionEvent(int action, float x, float y) { this.action = action; this.x = x; this.y = y; }
        int getAction() { return action; }
        int getPointerId(int index) { return 0; }
        float getX() { return x; }
        float getY() { return y; }
    }
    static class View {
        static final int GONE = 8;
        float tx;
        int getMeasuredWidth() { return 1080; }
        float getTranslationX() { return tx; }
        float getX() { return tx; }
        void setTranslationX(float value) { tx = value; }
        void setVisibility(int value) { }
        boolean canScrollHorizontally(int direction) { return false; }
    }
    static class Parent { void requestDisallowInterceptTouchEvent(boolean value) { } }
    static class Adapter {
        int getItemCount() { return 3; }
        boolean canScrollTo(int position) { return true; }
    }
    static class Tabs {
        boolean animatingIndicator;
        void setEnabled(boolean value) { }
        void selectTab(int next, int current, float progress) { }
    }
    static class Locker { void lock() { } void unlock() { } }
    static class VelocityTracker {
        static VelocityTracker obtain() { return new VelocityTracker(); }
        void addMovement(MotionEvent event) { }
        void computeCurrentVelocity(int units, int maximum) { }
        float getXVelocity() { return 0; }
        float getYVelocity() { return 0; }
        void recycle() { }
    }
    static class Animator { }
    static class AnimatorListenerAdapter { public void onAnimationEnd(Animator animator) { } }
    static class AnimatorSet extends Animator {
        void playTogether(Animator... animators) { }
        void setInterpolator(Object interpolator) { }
        void setDuration(int duration) { }
        void addListener(AnimatorListenerAdapter listener) { }
        void removeAllListeners() { }
        void cancel() { }
        void start() { }
    }
    interface UpdateListener { void update(ValueAnimator animator); }
    static class ValueAnimator extends Animator {
        static ValueAnimator ofFloat(float... values) { return new ValueAnimator(); }
        void addUpdateListener(UpdateListener listener) { }
        Object getAnimatedValue() { return 0f; }
    }
    static class Pager {
        FIELDS
        int startedTrackingPointerId, currentPosition = 1, nextPosition, maximumVelocity = 8000;
        int[] viewTypes = {0, 0};
        float additionalOffset, backProgress, currentProgress = 1f;
        final float touchSlop = 8f;
        boolean startedTracking, maybeStartTracking, animatingForward, backAnimation, tabsAnimationInProgress;
        Object manualScrolling, interpolator;
        Tabs tabsView = new Tabs();
        Adapter adapter = new Adapter();
        Locker notificationsLocker = new Locker();
        VelocityTracker velocityTracker;
        AnimatorSet tabsAnimation;
        UpdateListener updateTabProgress = animator -> {};
        View[] viewPages = {new View(), null};
        Map<Integer, View> viewsByType = new HashMap<>();
        Parent getParent() { return new Parent(); }
        int getMeasuredWidth() { return 1080; }
        boolean canScroll(MotionEvent event) { return true; }
        boolean canScrollForward(MotionEvent event) { return true; }
        boolean canScrollBackward(MotionEvent event) { return true; }
        boolean onBackProgress(float value) { return false; }
        void onStartTracking() { }
        void updateViewForIndex(int index) { viewPages[index] = new View(); }
        void onTabAnimationUpdate(boolean manual) { }
        View findScrollingChild(Pager pager, float x, float y) { return null; }
        boolean checkTabsAnimationInProgress() { return tabsAnimationInProgress; }
        void swapViews() {
            View view = viewPages[0]; viewPages[0] = viewPages[1]; viewPages[1] = view;
            int position = currentPosition; currentPosition = nextPosition; nextPosition = position;
        }
        Animator translateAnimator(View view, float target) { return new Animator(); }
        float distanceInfluenceForSnapDuration(float distance) { return distance; }
        void onBack() { }
        void removeView(View view) { }
        void onScrollEnd() { }
        void event(int action, float x, float y) {
            onTouchEventInternal(new MotionEvent(action, x, y));
        }
        METHODS
    }
    public static void main(String[] args) {
        for (int direction : new int[]{-1, 1}) {
            Pager pager = new Pager();
            pager.event(MotionEvent.ACTION_DOWN, 500.75f, 100.5f);
            pager.event(MotionEvent.ACTION_MOVE, 500.75f + direction * 7.875f, 100.5f);
            check(!pager.startedTracking, "movement below slop must not start paging");
            float anchor = 500.75f + direction * 8.5f;
            pager.event(MotionEvent.ACTION_MOVE, anchor, 100.5f);
            check(pager.startedTracking, "horizontal movement above slop must start paging");
            for (int step = 1; step <= 24; step++) {
                float delta = direction * step * 0.125f;
                pager.event(MotionEvent.ACTION_MOVE, anchor + delta, 100.5f);
                near(pager.viewPages[0].tx, delta, "every subpixel move must move the current page");
                near(pager.viewPages[1].tx, -direction * 1080 + delta,
                        "neighboring page must stay aligned");
                near(pager.currentProgress, 1f - Math.abs(delta) / 1080f,
                        "progress must retain fractional motion");
            }
            // Cross the origin and continue in the opposite direction.
            float reverseAnchor = anchor - direction * 0.25f;
            pager.event(MotionEvent.ACTION_MOVE, reverseAnchor, 100.5f);
            pager.event(MotionEvent.ACTION_MOVE, reverseAnchor - direction * 0.125f, 100.5f);
            near(pager.viewPages[0].tx, -direction * 0.125f,
                    "direction reversal must retain its fractional origin");
            near(pager.viewPages[1].tx, direction * 1080 - direction * 0.125f,
                    "reversed neighboring page must stay aligned");
        }
        Pager vertical = new Pager();
        vertical.event(MotionEvent.ACTION_DOWN, 500.75f, 100.5f);
        vertical.event(MotionEvent.ACTION_MOVE, 509.25f, 120.5f);
        check(!vertical.startedTracking, "vertical scrolling must not start paging");

        // Grab a fractional position during the settling animation.
        Pager takeover = new Pager();
        takeover.animatingForward = true;
        takeover.nextPosition = 2;
        takeover.viewPages[1] = new View();
        takeover.viewPages[0].tx = -123.375f;
        takeover.viewPages[1].tx = 956.625f;
        takeover.tabsAnimation = new AnimatorSet();
        takeover.tabsAnimationInProgress = true;
        takeover.event(MotionEvent.ACTION_DOWN, 100.875f, 100.5f);
        takeover.event(MotionEvent.ACTION_MOVE, 100.875f, 100.5f);
        near(takeover.viewPages[0].tx, -123.375f, "grabbing an animation must not jump");
        takeover.event(MotionEvent.ACTION_MOVE, 100.75f, 100.5f);
        near(takeover.viewPages[0].tx, -123.5f, "takeover must preserve fractional displacement");
        near(takeover.viewPages[1].tx, 956.5f, "takeover must keep page edges aligned");
    }
}
'''.replace('FIELDS', fields).replace('METHODS', methods)
        run_java(self, 'PagerDragHarness', harness)


if __name__ == '__main__':
    unittest.main()
