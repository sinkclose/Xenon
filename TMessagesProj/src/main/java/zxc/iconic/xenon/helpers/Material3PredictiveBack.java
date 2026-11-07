package zxc.iconic.xenon.helpers;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.graphics.Outline;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.RoundedCorner;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.WindowInsets;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;
import android.window.BackEvent;
import android.window.OnBackAnimationCallback;

import androidx.annotation.RequiresApi;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.ActionBarLayout;

import java.util.ArrayList;

import zxc.iconic.xenon.NekoConfig;

/**
 * Predictive-back animation ported 1:1 from AOSP Android 17
 * (android-17.0.0_r1, frameworks/base/libs/WindowManager/Shell/src/com/android/wm/shell/back/
 * {@code CrossTaskBackAnimation.java}) — the canonical Android predictive back: the closing window
 * and the entering window shrink into two cards side by side (14dp gap) over a dark background,
 * the closing card hugging the gesture edge. On commit the closing card continues into a small
 * centered card while the entering window expands to full screen on top of it — the closing window
 * never moves backwards. On release with velocity a critically damped spring shrinks both cards a
 * bit further (pivoted at the closing card's right edge). Cancel springs the raw progress back to
 * 0 ({@code BackProgressAnimator}) — which the system dispatcher already does before delivering
 * {@link #onBackCancelled()}, so on real dispatchers cancel settles through the regular progress
 * frames.
 *
 * <p>All constants and per-frame math mirror the AOSP source; the SurfaceControl rect math is
 * re-expressed as View transforms (scale around the origin + translation, outline clip in local
 * space), which reproduces the identical on-screen result.
 *
 * <p>It drives {@link org.telegram.ui.ActionBar.ActionBarLayout} directly through the same hooks
 * the stock callback uses ({@code onBackStarted}, {@code predictiveInput},
 * {@code onSlideAnimationEnd}), so it must run on API 34+ where {@link OnBackAnimationCallback}
 * is available.
 */
@RequiresApi(api = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
public final class Material3PredictiveBack {

    // ==== AOSP android-17.0.0_r1 com.android.wm.shell.back.CrossTaskBackAnimation constants ====

    /** Minimum scale of the entering and closing window. */
    private static final float MIN_WINDOW_SCALE = 0.8f;
    /** Duration of post animation after gesture committed. */
    private static final long POST_COMMIT_DURATION = 500L;
    /** Shell R.dimen.cross_task_back_inter_window_margin — gap between the two cards. */
    private static final float INTER_WINDOW_MARGIN_DP = 14f;
    /** Shell R.dimen.cross_task_back_vertical_margin — also the closing card's edge pull-in. */
    private static final float DISPLAY_BOUNDS_MARGIN_DP = 8f;
    /** CrossTaskBackAnimation.BACKGROUNDCOLOR, made opaque for the in-app background layer. */
    private static final int BACKGROUND_COLOR = 0xFF43433A;
    /** CrossTaskBackAnimation.SPRING_SCALE / DEFAULT_FLING_VELOCITY / MAX_FLING_VELOCITY. */
    private static final float SPRING_SCALE = 100f;
    private static final float DEFAULT_FLING_VELOCITY = 320f;
    private static final float MAX_FLING_VELOCITY = 1000f;
    /** Post-commit fling spring: SpringForce(100) stiffness 320, damping ratio 1 (no bounce). */
    private static final float FLING_STIFFNESS = 320f;
    private static final float FLING_DAMPING_RATIO = 1f;
    /** BackProgressAnimator gesture spring: SpringForce.STIFFNESS_MEDIUM / NO_BOUNCY. */
    private static final float CANCEL_SPRING_STIFFNESS = 1500f;
    /** Safety cap for the fallback cancel spring. */
    private static final long CANCEL_SPRING_MAX_DURATION = 600L;
    /** Client-only: the stock prep (attach previous fragment) runs under this progress. */
    private static final float LAZY_START = 0.015f;

    /** Interpolators.BACK_GESTURE — android.view.animation.BackGestureInterpolator. */
    private static final Interpolator BACK_GESTURE = new PathInterpolator(0.1f, 0.1f, 0f, 1f);
    /** CrossTaskBackAnimation.mVerticalMoveInterpolator. */
    private static final Interpolator VERTICAL_INTERP = new DecelerateInterpolator();
    /** Shell Interpolators.EMPHASIZED — post-commit motion (set on the commit animator). */
    private static final Interpolator EMPHASIZED = makeEmphasized();

    private Material3PredictiveBack() {
    }

    private static Interpolator makeEmphasized() {
        Path path = new Path();
        // Doing the same as fast_out_extra_slow_in (Shell Interpolators#createEmphasizedInterpolator)
        path.moveTo(0f, 0f);
        path.cubicTo(0.05f, 0f, 0.133333f, 0.06f, 0.166666f, 0.4f);
        path.cubicTo(0.208333f, 0.82f, 0.25f, 1f, 1f, 1f);
        return new PathInterpolator(path);
    }

    public static OnBackAnimationCallback createCallback(Activity activity, ActionBarLayout layout, Runnable plainBack) {
        Callback callback = new Callback(activity, layout, plainBack);
        layout.m3PredictiveCallbackCancelRunnable = () -> callback.cancelAndCleanup();
        return callback;
    }

    private static float clamp(float v, float min, float max) {
        return v < min ? min : (Math.min(v, max));
    }

    /** CrossActivityBackAnimationKt.scaleCentered — uniform scale around an arbitrary pivot. */
    private static void scaleCentered(RectF rect, float scale, float pivotX, float pivotY) {
        rect.offset(-pivotX, -pivotY);
        rect.left *= scale;
        rect.right *= scale;
        rect.top *= scale;
        rect.bottom *= scale;
        rect.offset(pivotX, pivotY);
    }

    /** BackEvent#getFrameTimeMillis is API 35+; fall back to uptime at 34. */
    private static long frameTimeMillis(BackEvent event) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            return event.getFrameTimeMillis();
        }
        return SystemClock.uptimeMillis();
    }

    /** Saved outline state of one containerViewBack child, restored on cleanup. */
    private static final class SavedChildState {
        final View view;
        final ViewOutlineProvider outlineProvider;
        final boolean clipToOutline;

        SavedChildState(View view, ViewOutlineProvider outlineProvider, boolean clipToOutline) {
            this.view = view;
            this.outlineProvider = outlineProvider;
            this.clipToOutline = clipToOutline;
        }
    }

    private static final class Callback implements OnBackAnimationCallback {

        private final Activity activity;
        private final ActionBarLayout layout;
        private final Runnable plainBack;

        // AOSP rect space: all rects live in the backAnimRect coordinate system.
        private final RectF backAnimRect = new RectF();
        private final RectF currentClosingRect = new RectF();
        private final RectF currentEnteringRect = new RectF();
        private final RectF startClosingRect = new RectF();
        private final RectF startEnteringRect = new RectF();
        private final RectF targetClosingRect = new RectF();
        private final RectF targetEnteringRect = new RectF();
        private final RectF tmpRect = new RectF();
        private final ArrayList<SavedChildState> savedChildStates = new ArrayList<>();

        private boolean attached = false;
        private boolean invoked = false;
        private boolean finishCancel = false;
        private boolean isRightEdge = false;
        private float startTouchY = 0f;
        private float lastTouchY = 0f;
        private int deviceCornerPx = 0;
        private float displayBoundsMarginPx = 0f;
        private float interWindowMarginPx = 0f;
        /** BACK_GESTURE-interpolated progress (AOSP mGestureProgress, used by the fling check). */
        private float gestureProgress = 0f;
        private float lastRawProgress = 0f;
        /** Spring-space start velocity of the post-commit fling (negative = shrink). */
        private float flingStartVelocity = 0f;
        private long flingStartNanos = 0L;

        private VelocityTracker velocityTracker = null;
        private long velocityDownTime = -1L;

        private ValueAnimator runningAnim = null;
        private ViewOutlineProvider savedOutlineProvider = null;
        private boolean savedClipToOutline = false;
        private Drawable savedCvbBackground = null;
        private Drawable savedCvbForeground = null;
        private float savedCvTranslationZ = 0f;
        private float savedCvbTranslationZ = 0f;

        /**
         * AOSP applies the device corner radius on the window surface, so the visible radius scales
         * together with the window. An outline lives in view-local space and the view's scale
         * applies to it, so a constant radius here reproduces the same on-screen result.
         */
        private final ViewOutlineProvider outlineProvider = new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), deviceCornerPx);
            }
        };

        Callback(Activity activity, org.telegram.ui.ActionBar.ActionBarLayout layout, Runnable plainBack) {
            this.activity = activity;
            this.layout = layout;
            this.plainBack = plainBack;
        }

        @Override
        public void onBackStarted(BackEvent backEvent) {
            // A new gesture can arrive before the previous finish animation (or gesture) has settled.
            // Stock's onBackStarted bails out while predictiveInput is still set, so we must finalize the
            // previous one synchronously here — relying on the animator's end-callback is racy.
            if (runningAnim != null) {
                runningAnim.removeAllListeners();
                runningAnim.cancel();
                runningAnim = null;
                finalizeStock(finishCancel);
            }
            if (attached) {
                finalizeStock(true);
            } else if (layout.predictiveInput) {
                // Previous gesture set stock prep but was preempted in the invisible phase — roll it
                // back as a cancel so nav doesn't lock up on the stale predictiveInput flag.
                undoStockPrep();
            }
            invoked = false;
            isRightEdge = backEvent.getSwipeEdge() == BackEvent.EDGE_RIGHT;
            startTouchY = backEvent.getTouchY();
            lastTouchY = startTouchY;
            gestureProgress = 0f;
            lastRawProgress = 0f;
            flingStartVelocity = 0f;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                WindowInsets insets = activity.getWindow().getDecorView().getRootWindowInsets();
                RoundedCorner rc = insets != null ? insets.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT) : null;
                deviceCornerPx = rc != null ? rc.getRadius() : AndroidUtilities.dp(24);
            } else {
                deviceCornerPx = AndroidUtilities.dp(24);
            }
            // AOSP ProgressVelocityTracker — tracks the interpolated progress over event times.
            if (velocityTracker == null) {
                velocityTracker = VelocityTracker.obtain();
            }
            velocityTracker.clear();
            velocityDownTime = -1L;
            // Run stock's heavy prep (attach previous fragment, relayout, onResume, HW layer) during the
            // pre-LAZY_START invisible phase so the first visible frame is just a transform.
            layout.onBackStarted(backEvent.getTouchX(), backEvent.getTouchY());
        }

        @Override
        public void onBackProgressed(BackEvent backEvent) {
            if (invoked || runningAnim != null) {
                return;
            }
            if (!layout.predictiveInput) {
                return;
            }
            float rawP = clamp(backEvent.getProgress(), 0f, 1f);
            lastRawProgress = rawP;
            lastTouchY = backEvent.getTouchY();
            if (!attached) {
                if (rawP <= LAZY_START) {
                    return;
                }
                attached = true;
                layout.m3PredictiveActive = true;
                layout.invalidate();
                attachOverlays();
                prepareRects();
            }
            // The system dispatcher (WindowOnBackInvokedDispatcher -> BackProgressAnimator) already
            // spring-smooths the progress it delivers, exactly like the one the shell-side animator
            // uses. AOSP then applies the gesture interpolator and feeds the interpolated progress
            // (in SPRING_SCALE units) both to the rects and to the velocity tracker.
            float p = BACK_GESTURE.getInterpolation(rawP);
            gestureProgress = p;
            long frameTime = frameTimeMillis(backEvent);
            if (velocityDownTime == -1L) {
                velocityDownTime = frameTime;
            }
            MotionEvent motionEvent = MotionEvent.obtain(velocityDownTime, frameTime, MotionEvent.ACTION_MOVE, p * SPRING_SCALE, 0f, 0);
            velocityTracker.addMovement(motionEvent);
            motionEvent.recycle();
            // Predictive-back intensity (slider /10 → 0.1..2.0, 1.0 default) is a Xenon-only knob:
            // at 10 the progress is unmodified and the animation is exactly AOSP's.
            float intensity = Math.max(NekoConfig.predictiveBackIntensity / 10f, 0.001f);
            onGestureProgress(clamp(p * intensity, 0f, 1f), lastTouchY);
        }

        @Override
        public void onBackCancelled() {
            invoked = false;
            if (!attached) {
                undoStockPrep();
                cleanupViews();
                return;
            }
            runFinishAnim(true);
        }

        @Override
        public void onBackInvoked() {
            invoked = true;
            if (!attached) {
                undoStockPrep();
                cleanupViews();
                plainBack.run();
                return;
            }
            runFinishAnim(false);
        }

        // Quick swipe that never crossed LAZY_START: stock's onBackStarted attached the previous
        // fragment off-screen. Undo that without running stock's commit animator, so plainBack can play
        // the regular cross-fragment transition instead.
        private void undoStockPrep() {
            if (!layout.predictiveInput) {
                return;
            }
            layout.predictiveInput = false;
            layout.predictiveBackInProgress = false;
            layout.onSlideAnimationEnd(true);
        }

        private void attachOverlays() {
            ViewGroup cv = layout.containerView;
            if (cv == null) {
                return;
            }
            savedOutlineProvider = cv.getOutlineProvider();
            savedClipToOutline = cv.getClipToOutline();
            cv.setOutlineProvider(outlineProvider);
            cv.setClipToOutline(true);

            // Promote leaving screen to HW layer
            cv.setLayerType(View.LAYER_TYPE_HARDWARE, null);

            // The AOSP background layer (CrossTaskBackAnimation.BACKGROUNDCOLOR) fills everything the
            // two cards don't cover; no scrim in the cross-task animation.
            ViewGroup cvb = layout.containerViewBack;
            if (cvb == null) {
                return;
            }
            // cvb itself must be at identity before we start offsetting its children — a stale
            // translationX left over from an interrupted alternativeTransition open/close (which
            // animates containerViewBack directly, see ActionBarLayout#startLayoutAnimation) would
            // otherwise stack with the per-child transform below and throw the whole reveal off by
            // the leftover amount.
            cvb.setTranslationX(0f);
            cvb.setTranslationY(0f);
            savedCvbBackground = cvb.getBackground();
            savedCvbForeground = cvb.getForeground();
            cvb.setBackground(new ColorDrawable(BACKGROUND_COLOR));
            cvb.setForeground(null);
            // Both cards are rounded just like the system windows (device corner radius); promote
            // children to HW layers so per-frame scale/translate is texture-only.
            savedChildStates.clear();
            for (int i = 0; i < cvb.getChildCount(); i++) {
                View child = cvb.getChildAt(i);
                savedChildStates.add(new SavedChildState(child, child.getOutlineProvider(), child.getClipToOutline()));
                child.setOutlineProvider(outlineProvider);
                child.setClipToOutline(true);
                child.setLayerType(View.LAYER_TYPE_HARDWARE, null);
            }
        }

        private void prepareRects() {
            ViewGroup cv = layout.containerView;
            if (cv == null || cv.getWidth() <= 0 || cv.getHeight() <= 0) {
                return;
            }
            backAnimRect.set(0f, 0f, cv.getWidth(), cv.getHeight());
            displayBoundsMarginPx = AndroidUtilities.dpf2(DISPLAY_BOUNDS_MARGIN_DP);
            interWindowMarginPx = AndroidUtilities.dpf2(INTER_WINDOW_MARGIN_DP);
        }

        /** CrossTaskBackAnimation#updateGestureBackProgress. */
        private void onGestureProgress(float p, float touchY) {
            ViewGroup cv = layout.containerView;
            ViewGroup cvb = layout.containerViewBack;
            if (cv == null || cvb == null || backAnimRect.width() <= 0f) {
                return;
            }
            float width = backAnimRect.width();
            float height = backAnimRect.height();

            float scale = 1f - (1f - MIN_WINDOW_SCALE) * p;
            float scaledWidth = scale * width;
            float scaledHeight = scale * height;

            // Base the window movement in the Y axis on the touch movement in the Y axis.
            float rawYDelta = touchY - startTouchY;
            float yDirection = rawYDelta < 0 ? -1f : 1f;
            // limit yDelta interpretation to 1/2 of screen height in either direction
            float deltaYRatio = Math.min(height / 2f, Math.abs(rawYDelta)) / (height / 2f);
            float interpolatedYRatio = VERTICAL_INTERP.getInterpolation(deltaYRatio);
            // limit y-shift so the surface never passes the 8dp screen margin
            float deltaY = yDirection * interpolatedYRatio * Math.max(0f, (height - scaledHeight) / 2f - displayBoundsMarginPx);

            float scaledTop = (height - scaledHeight) * 0.5f + deltaY;
            // Move the window along the X axis: a right-edge gesture shrinks the card in place,
            // a left-edge gesture pins it to the right edge (pulling in by up to 8dp).
            float right;
            if (isRightEdge) {
                right = (width - scaledWidth) * 0.5f + scaledWidth;
            } else {
                right = width - p * displayBoundsMarginPx;
            }
            float left = right - scaledWidth;

            currentClosingRect.set(left, scaledTop, right, scaledTop + scaledHeight);
            applyTransform(cv, currentClosingRect);
            // The entering card slides in right next to the closing one, 14dp behind it.
            float enterRight = left - interWindowMarginPx;
            currentEnteringRect.set(enterRight - scaledWidth, scaledTop, enterRight, scaledTop + scaledHeight);
            applyTransformChildren(cvb, currentEnteringRect);
        }

        /** CrossTaskBackAnimation#applyTransform — scale around the origin + translate to the rect. */
        private void applyTransform(View view, RectF rect) {
            if (view == null || backAnimRect.width() <= 0f) {
                return;
            }
            float scale = rect.width() / backAnimRect.width();
            view.setPivotX(0f);
            view.setPivotY(0f);
            view.setScaleX(scale);
            view.setScaleY(scale);
            view.setTranslationX(rect.left);
            view.setTranslationY(rect.top);
        }

        private void applyTransformChildren(ViewGroup cvb, RectF rect) {
            for (int i = 0; i < cvb.getChildCount(); i++) {
                applyTransform(cvb.getChildAt(i), rect);
            }
        }

        private void runFinishAnim(boolean cancel) {
            finishCancel = cancel;
            // a fallback cancel spring (or any stale animator) must not fire after this one
            if (runningAnim != null) {
                runningAnim.removeAllListeners();
                runningAnim.cancel();
                runningAnim = null;
            }
            final ViewGroup cv = layout.containerView;
            final ViewGroup cvb = layout.containerViewBack;
            if (cv == null || cvb == null) {
                finalizeStock(cancel);
                return;
            }
            if (cancel) {
                runCancelAnim();
                return;
            }

            // CrossTaskBackAnimation#onGestureCommitted — kick off the fling spring with the
            // pre-commit velocity; it shrinks both cards a bit further (pivoted at the closing
            // card's right edge), critically damped so they never bounce back.
            float startVelocity = gestureProgress < 0.1f ? -DEFAULT_FLING_VELOCITY : 0f;
            if (velocityTracker != null) {
                velocityTracker.computeCurrentVelocity(1000);
                startVelocity = -velocityTracker.getXVelocity();
            }
            flingStartVelocity = Math.max(-MAX_FLING_VELOCITY, Math.min(0f, startVelocity));
            flingStartNanos = System.nanoTime();

            // Phase 2 starts from the gesture-driven state: the closing card keeps shrinking into a
            // small centered card while the entering window expands to full screen on top of it.
            startClosingRect.set(currentClosingRect);
            startEnteringRect.set(currentEnteringRect);
            float targetWidth = backAnimRect.width() * MIN_WINDOW_SCALE;
            float targetHeight = backAnimRect.height() * MIN_WINDOW_SCALE;
            targetClosingRect.set(
                    (backAnimRect.width() - targetWidth) / 2f,
                    (backAnimRect.height() - targetHeight) / 2f,
                    (backAnimRect.width() + targetWidth) / 2f,
                    (backAnimRect.height() + targetHeight) / 2f);
            targetEnteringRect.set(backAnimRect);

            // The entering window rises above the closing card (AOSP sets the closing leash layer 0).
            savedCvTranslationZ = cv.getTranslationZ();
            savedCvbTranslationZ = cvb.getTranslationZ();
            cv.setTranslationZ(-8f);
            cvb.setTranslationZ(0f);

            ValueAnimator animator = ValueAnimator.ofFloat(1f, 0f);
            animator.setDuration(POST_COMMIT_DURATION);
            animator.setInterpolator(EMPHASIZED);
            animator.addUpdateListener(animation -> {
                // with EMPHASIZED on the animator, the animated fraction is already interpolated
                float progress = animation.getAnimatedFraction();
                float flingFactor = postCommitFlingScale((System.nanoTime() - flingStartNanos) / 1_000_000_000f);
                setInterpolatedRectF(tmpRect, startEnteringRect, targetEnteringRect, progress);
                applyFlingScale(tmpRect, flingFactor);
                applyTransformChildren(cvb, tmpRect);
                setInterpolatedRectF(tmpRect, startClosingRect, targetClosingRect, progress);
                applyFlingScale(tmpRect, flingFactor);
                applyTransform(cv, tmpRect);
            });
            animator.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    runningAnim = null;
                    finalizeStock(false);
                }
            });
            runningAnim = animator;
            animator.start();
        }

        /** CrossTaskBackAnimation#applyFlingScale — dip pivoted at the rect's right edge. */
        private void applyFlingScale(RectF rect, float flingFactor) {
            if (flingFactor >= 1f) {
                return;
            }
            scaleCentered(rect, flingFactor, rect.right, rect.top + rect.height() / 2f);
        }

        /**
         * CrossTaskBackAnimation fling spring — analytic solution of the critically damped spring
         * (rest = SPRING_SCALE, start = SPRING_SCALE, stiffness 320, damping ratio 1): the value
         * dips below SPRING_SCALE with the fling velocity and settles back without overshoot. The
         * applied scale is clamped at 1.
         */
        private float postCommitFlingScale(float tSec) {
            if (flingStartVelocity == 0f) {
                return 1f;
            }
            double omega = Math.sqrt(FLING_STIFFNESS);
            double value = SPRING_SCALE + flingStartVelocity * tSec * Math.exp(-omega * tSec);
            return (float) Math.min(value / SPRING_SCALE, 1f);
        }

        /**
         * AOSP cancels by springing the raw progress back to 0 (BackProgressAnimator,
         * STIFFNESS_MEDIUM / NO_BOUNCY) while the gesture-driven phase keeps rendering. The system
         * dispatcher already runs that spring before onBackCancelled() reaches the app — it feeds
         * the sprung frames through onBackProgressed() — so on real Android 15+ dispatchers this
         * settles immediately; the local spring only covers dispatchers that cancel instantly.
         */
        private void runCancelAnim() {
            if (lastRawProgress <= 0.001f) {
                finalizeStock(true);
                return;
            }
            final double omega = Math.sqrt(CANCEL_SPRING_STIFFNESS);
            final double x0 = lastRawProgress * SPRING_SCALE;
            final long startNanos = System.nanoTime();
            ValueAnimator animator = ValueAnimator.ofFloat(1f, 0f);
            animator.setDuration(CANCEL_SPRING_MAX_DURATION);
            animator.addUpdateListener(animation -> {
                double t = (System.nanoTime() - startNanos) / 1_000_000_000.0;
                // critically damped spring from rest velocity: x(t) = (x0 + ω x0 t) e^{-ωt}
                double value = x0 * (1.0 + omega * t) * Math.exp(-omega * t);
                if (value <= 0.1) {
                    // settle finished — end outside of this frame callback
                    final View anchor = layout.containerView;
                    if (anchor != null) {
                        anchor.post(animation::cancel);
                    }
                    return;
                }
                float pRaw = (float) (value / SPRING_SCALE);
                float p = BACK_GESTURE.getInterpolation(clamp(pRaw, 0f, 1f));
                float intensity = Math.max(NekoConfig.predictiveBackIntensity / 10f, 0.001f);
                onGestureProgress(clamp(p * intensity, 0f, 1f), lastTouchY);
            });
            animator.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    runningAnim = null;
                    finalizeStock(true);
                }
            });
            runningAnim = animator;
            animator.start();
        }

        private void finalizeStock(boolean cancel) {
            cleanupViews();
            layout.m3PredictiveActive = false;
            layout.invalidate();
            if (layout.predictiveInput) {
                layout.predictiveInput = false;
                layout.predictiveBackInProgress = false;
                layout.onSlideAnimationEnd(cancel);
            } else if (!cancel) {
                layout.onBackPressed();
            }
            attached = false;
            if (velocityTracker != null) {
                velocityTracker.recycle();
                velocityTracker = null;
            }
            velocityDownTime = -1L;
        }

        private void cleanupViews() {
            ViewGroup cv = layout.containerView;
            if (cv != null) {
                cv.setScaleX(1f);
                cv.setScaleY(1f);
                cv.setTranslationX(0f);
                cv.setTranslationY(0f);
                cv.setTranslationZ(savedCvTranslationZ);
                cv.setPivotX(cv.getWidth() / 2f);
                cv.setPivotY(cv.getHeight() / 2f);
                cv.setClipToOutline(savedClipToOutline);
                cv.setOutlineProvider(savedOutlineProvider != null ? savedOutlineProvider : ViewOutlineProvider.BACKGROUND);
            }
            ViewGroup cvb = layout.containerViewBack;
            if (cvb != null) {
                cvb.setTranslationZ(savedCvbTranslationZ);
                for (int i = 0; i < savedChildStates.size(); i++) {
                    SavedChildState state = savedChildStates.get(i);
                    state.view.setOutlineProvider(state.outlineProvider);
                    state.view.setClipToOutline(state.clipToOutline);
                }
                for (int i = 0; i < cvb.getChildCount(); i++) {
                    View child = cvb.getChildAt(i);
                    child.setTranslationX(0f);
                    child.setTranslationY(0f);
                    child.setScaleX(1f);
                    child.setScaleY(1f);
                    child.setPivotX(child.getWidth() / 2f);
                    child.setPivotY(child.getHeight() / 2f);
                    child.setLayerType(View.LAYER_TYPE_NONE, null);
                }
                cvb.setBackground(savedCvbBackground);
                cvb.setForeground(savedCvbForeground);
            }
            savedChildStates.clear();
            savedCvbBackground = null;
            savedCvbForeground = null;
        }

        public void cancelAndCleanup() {
            if (runningAnim != null) {
                runningAnim.removeAllListeners();
                runningAnim.cancel();
                runningAnim = null;
            }
            if (attached) {
                finalizeStock(true);
            } else if (layout.predictiveInput) {
                undoStockPrep();
            }
        }
    }
}
