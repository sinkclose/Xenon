package zxc.iconic.xenon.helpers;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.view.RoundedCorner;
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
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.DialogsActivity;
import org.telegram.ui.ProfileActivity;
import org.telegram.ui.ViewPagerActivity;

import java.util.ArrayList;

import zxc.iconic.xenon.NekoConfig;

/**
 * Predictive-back animation ported from AOSP Android 17
 * (android-17.0.0_r1, frameworks/base/libs/WindowManager/Shell/src/com/android/wm/shell/back/
 * {@code CrossActivityBackAnimation.kt} + {@code DefaultCrossActivityBackAnimation.kt}).
 *
 * <p>At intensity 1.0 the gesture geometry follows the system cross-activity animation: the closing window scales down
 * to 0.85 (sliding toward the right edge for a left-edge gesture), the entering window starts
 * offset off the left edge and both rects shrink together while following the touch Y, over a
 * black scrim (0.2 light / 0.8 dark).
 *
 * <p>Commit deliberately deviates from {@code DefaultCrossActivityBackAnimation} in one point:
 * the AOSP fling spring is not started. Its shrink-dip in the first frames after the finger lifts
 * read as a backward jerk. Everything else matches the system: the closing window grows back to
 * full size while sliding off to the right and fades within the first 20% of the 450ms emphasized
 * animation, and the entering window expands to full screen.
 *
 * <p>Cancel springs the raw progress back to 0 ({@code BackProgressAnimator}) — which the system
 * dispatcher already does before delivering {@link #onBackCancelled()}, so on real dispatchers
 * cancel settles through the regular progress frames.
 *
 * <p>The SurfaceControl rect math is re-expressed as View transforms (scale around the origin +
 * translation, outline clip in local space). This is an in-app fragment adaptation, rather than a
 * system window transition (system bars, letterboxing and freeform tasks remain platform-owned). It drives
 * {@link ActionBarLayout} through the same hooks the stock callback uses ({@code onBackStarted},
 * {@code predictiveInput}, {@code onSlideAnimationEnd}), so it must run on API 34+ where
 * {@link OnBackAnimationCallback} is available.
 */
@RequiresApi(api = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
public final class Material3PredictiveBack {

    // ==== AOSP android-17.0.0_r1 com.android.wm.shell.back constants ====

    /** CrossActivityBackAnimation.MAX_SCALE — max scale of the closing window. */
    private static final float MAX_SCALE = 0.85f;
    /** CrossActivityBackAnimation.INITIAL_ENTERING_SCALE. */
    private static final float INITIAL_ENTERING_SCALE = 0.95f;
    /** Shell R.dimen.cross_task_back_vertical_margin — 8dp. */
    private static final float DISPLAY_BOUNDS_MARGIN_DP = 8f;
    /** Shell R.dimen.cross_activity_back_entering_start_offset — 96dp. */
    private static final float ENTERING_START_OFFSET_DP = 96f;
    /** DefaultCrossActivityBackAnimation.POST_COMMIT_DURATION. */
    private static final long POST_COMMIT_DURATION = 450L;
    /** CrossActivityBackAnimation.MAX_SCRIM_ALPHA_LIGHT / MAX_SCRIM_ALPHA_DARK. */
    private static final float MAX_SCRIM_ALPHA_LIGHT = 0.2f;
    private static final float MAX_SCRIM_ALPHA_DARK = 0.8f;
    /** DefaultCrossActivityBackAnimation: closing alpha = max(1 - 5 * linearProgress, 0). */
    private static final float CLOSING_ALPHA_FADE_RATE = 5f;
    /** BackProgressAnimator progress spring scale (progress runs in 0..100 spring units). */
    private static final float SPRING_SCALE = 100f;
    /** BackProgressAnimator gesture spring: SpringForce.STIFFNESS_MEDIUM / NO_BOUNCY. */
    private static final float CANCEL_SPRING_STIFFNESS = 1500f;
    /** Safety cap for the fallback cancel spring. */
    private static final long CANCEL_SPRING_MAX_DURATION = 600L;
    /** Client-only: the stock prep (attach previous fragment) runs under this progress. */
    private static final float LAZY_START = 0.015f;

    /** Interpolators.BACK_GESTURE — android.view.animation.BackGestureInterpolator. */
    private static final Interpolator BACK_GESTURE = new PathInterpolator(0.1f, 0.1f, 0f, 1f);
    /** CrossActivityBackAnimation.verticalMoveInterpolator. */
    private static final Interpolator VERTICAL_INTERP = new DecelerateInterpolator();
    /** Shell Interpolators.EMPHASIZED — post-commit spatial motion. */
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

    /** RectF.setInterpolatedRectF — element-wise lerp from {@code start} into {@code out}. */
    private static void setInterpolatedRectF(RectF out, RectF start, RectF target, float progress) {
        out.set(
                start.left + (target.left - start.left) * progress,
                start.top + (target.top - start.top) * progress,
                start.right + (target.right - start.right) * progress,
                start.bottom + (target.bottom - start.bottom) * progress
        );
    }

    /** RectF.scaleCentered — uniform scale around the rect's own center. */
    private static void scaleCentered(RectF rect, float scale) {
        float pivotX = rect.left + rect.width() / 2f;
        float pivotY = rect.top + rect.height() / 2f;
        rect.offset(-pivotX, -pivotY);
        rect.left *= scale;
        rect.right *= scale;
        rect.top *= scale;
        rect.bottom *= scale;
        rect.offset(pivotX, pivotY);
    }

    // The entering fragment's own background to fill the M3 gap. ViewPagerActivity (e.g.
    // MainTabsActivity) sets hasOwnBackground but draws nothing itself — the visible color comes
    // from the current tab's inner fragment, so descend into it.
    private static Drawable enteringBackground(BaseFragment fragment) {
        BaseFragment f = fragment;
        while (f instanceof ViewPagerActivity) {
            f = ((ViewPagerActivity) f).getCurrentVisibleFragment();
        }
        // ProfileActivity and DialogsActivity (the Chats tab) both keep fragmentView background
        // null/transparent and paint via their children (gray listView) instead — descending into
        // fragmentView.getBackground() below would find nothing and fall back to the wrong
        // (white) fill, showing up as a mismatched strip around the shrinking chat while the
        // gesture is held. Use the gray window background directly for these.
        if (f instanceof ProfileActivity || f instanceof DialogsActivity) {
            return new ColorDrawable(Theme.getColor(Theme.key_windowBackgroundGray));
        }
        Drawable bg = (f != null && f.getFragmentView() != null) ? f.getFragmentView().getBackground() : null;
        // A transparent fill can't fill the gap — it would show the black window behind. Reject it so
        // the caller falls back to a solid color.
        if (bg instanceof ColorDrawable && Color.alpha(((ColorDrawable) bg).getColor()) == 0) {
            return null;
        }
        return bg;
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
        private final RectF startClosingRect = new RectF();
        private final RectF targetClosingRect = new RectF();
        private final RectF currentClosingRect = new RectF();
        private final RectF startEnteringRect = new RectF();
        private final RectF targetEnteringRect = new RectF();
        private final RectF currentEnteringRect = new RectF();
        private final RectF tmpRect = new RectF();
        private final ArrayList<SavedChildState> savedChildStates = new ArrayList<>();

        private boolean attached = false;
        private boolean invoked = false;
        private boolean finishCancel = false;
        private int swipeEdge = BackEvent.EDGE_LEFT;
        private float startTouchY = 0f;
        private float lastTouchY = 0f;
        private int deviceCornerPx = 0;
        private float displayBoundsMarginPx = 0f;
        private float enteringStartOffsetPx = 0f;
        private float maxScrimAlpha = MAX_SCRIM_ALPHA_LIGHT;
        private float lastRawProgress = 0f;

        private ValueAnimator runningAnim = null;
        private ViewOutlineProvider savedOutlineProvider = null;
        private boolean savedClipToOutline = false;
        private Drawable savedCvbBackground = null;
        private Drawable savedCvbForeground = null;
        private final ColorDrawable scrim = new ColorDrawable(Color.BLACK);

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

        // All children share one window outline, expressed in each child's local coordinates.
        // Rounding each child independently would round the seam below the action bar too.
        private final ViewOutlineProvider enteringOutlineProvider = new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                ViewGroup parent = layout.containerViewBack;
                if (parent != null) {
                    outline.setRoundRect(-view.getLeft(), -view.getTop(),
                            parent.getWidth() - view.getLeft(), parent.getHeight() - view.getTop(),
                            deviceCornerPx);
                }
            }
        };

        Callback(Activity activity, ActionBarLayout layout, Runnable plainBack) {
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
            startTouchY = backEvent.getTouchY();
            lastTouchY = startTouchY;
            swipeEdge = backEvent.getSwipeEdge();
            lastRawProgress = 0f;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                WindowInsets insets = activity.getWindow().getDecorView().getRootWindowInsets();
                RoundedCorner rc = insets != null ? insets.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT) : null;
                deviceCornerPx = rc != null ? rc.getRadius() : AndroidUtilities.dp(24);
            } else {
                deviceCornerPx = AndroidUtilities.dp(24);
            }
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
            // uses. Apply the gesture interpolator once, without another local progress spring.
            float p = BACK_GESTURE.getInterpolation(rawP);
            // Predictive-back intensity (slider /10 → 0.1..2.0, 1.0 default) is a Xenon-only knob:
            // at 10 the progress is unmodified and gesture geometry follows AOSP.
            float intensity = Math.max(NekoConfig.predictiveBackIntensity / 10f, 0.001f);
            onGestureProgress(clamp(p * intensity, 0f, 1f), lastTouchY);
        }

        @Override
        public void onBackCancelled() {
            invoked = false;
            if (!attached) {
                undoStockPrep();
                return;
            }
            runFinishAnim(true);
        }

        @Override
        public void onBackInvoked() {
            invoked = true;
            if (!attached) {
                undoStockPrep();
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
            // CrossActivityBackAnimation#ensureScrimLayer — black scrim, dimmer in dark theme.
            maxScrimAlpha = isDarkTheme() ? MAX_SCRIM_ALPHA_DARK : MAX_SCRIM_ALPHA_LIGHT;
            scrim.setAlpha((int) (maxScrimAlpha * 255f));

            savedOutlineProvider = cv.getOutlineProvider();
            savedClipToOutline = cv.getClipToOutline();
            cv.setOutlineProvider(outlineProvider);
            cv.setClipToOutline(true);

            // Promote leaving screen to HW layer
            cv.setLayerType(View.LAYER_TYPE_HARDWARE, null);

            // Translating cvb's children leaves the parent in place; paint it with the entering
            // fragment's own background so the gap matches it, then overlay a black scrim.
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
            Drawable enterBg = enteringBackground(layout.getBackgroundFragment());
            Drawable newBg = null;
            if (enterBg != null && enterBg.getConstantState() != null) {
                newBg = enterBg.getConstantState().newDrawable();
            }
            cvb.setBackground(newBg != null ? newBg : new ColorDrawable(Theme.getColor(Theme.key_actionBarDefault)));
            cvb.setForeground(scrim);
            // The entering window is rounded just like the closing one (AOSP rounds both leashes);
            // promote children to HW layers so per-frame scale/translate is texture-only.
            savedChildStates.clear();
            for (int i = 0; i < cvb.getChildCount(); i++) {
                View child = cvb.getChildAt(i);
                savedChildStates.add(new SavedChildState(child, child.getOutlineProvider(), child.getClipToOutline()));
                child.setOutlineProvider(enteringOutlineProvider);
                child.setClipToOutline(true);
                child.setLayerType(View.LAYER_TYPE_HARDWARE, null);
            }
        }

        /** DefaultCrossActivityBackAnimation#preparePreCommit{Closing,Entering}RectMovement. */
        private void prepareRects() {
            ViewGroup cv = layout.containerView;
            if (cv == null || cv.getWidth() <= 0 || cv.getHeight() <= 0) {
                return;
            }
            backAnimRect.set(0f, 0f, cv.getWidth(), cv.getHeight());
            displayBoundsMarginPx = AndroidUtilities.dpf2(DISPLAY_BOUNDS_MARGIN_DP);
            enteringStartOffsetPx = AndroidUtilities.dpf2(ENTERING_START_OFFSET_DP);

            // Closing target: scaled to MAX_SCALE in place, pushed toward the right edge (minus the
            // 8dp display margin) for a left-edge swipe; right-edge swipes stay centered.
            startClosingRect.set(backAnimRect);
            targetClosingRect.set(startClosingRect);
            scaleCentered(targetClosingRect, MAX_SCALE);
            if (swipeEdge != BackEvent.EDGE_RIGHT) {
                targetClosingRect.offset(startClosingRect.right - targetClosingRect.right - displayBoundsMarginPx, 0f);
            }

            // Entering start: the same rect shifted left by max(15% width, 96dp), then scaled in
            // sync with the closing window down to MAX_SCALE.
            startEnteringRect.set(startClosingRect);
            // fixCrossActivityBackAnimationInBubbles: the entering window starts slightly smaller
            // than full so its top/bottom edges stay tucked behind the closing window's rounded
            // corners while it is revealed.
            float nonRoundedHeight = startClosingRect.height() - 2f * deviceCornerPx;
            float startScale = Math.min(INITIAL_ENTERING_SCALE, nonRoundedHeight / startEnteringRect.height());
            scaleCentered(startEnteringRect, startScale);
            float startOffset = Math.max(startEnteringRect.width() * (1f - MAX_SCALE), enteringStartOffsetPx);
            startEnteringRect.offset(-startOffset, 0f);
            targetEnteringRect.set(startEnteringRect);
            scaleCentered(targetEnteringRect, MAX_SCALE);
        }

        /** CrossActivityBackAnimation#onGestureProgress. */
        private void onGestureProgress(float p, float touchY) {
            ViewGroup cv = layout.containerView;
            ViewGroup cvb = layout.containerViewBack;
            if (cv == null || cvb == null || backAnimRect.width() <= 0f) {
                return;
            }
            setInterpolatedRectF(currentClosingRect, startClosingRect, targetClosingRect, p);
            float yOffset = computeYOffset(currentClosingRect, touchY);
            currentClosingRect.offset(0f, yOffset);
            applyTransform(cv, currentClosingRect, 1f);
            setInterpolatedRectF(currentEnteringRect, startEnteringRect, targetEnteringRect, p);
            currentEnteringRect.offset(0f, yOffset);
            applyTransformChildren(cvb, currentEnteringRect, 1f);
        }

        /** CrossActivityBackAnimation#getYOffset — vertical follow capped by an 8dp screen margin. */
        private float computeYOffset(RectF centeredRect, float touchY) {
            float screenHeight = backAnimRect.height();
            if (screenHeight <= 0f) {
                return 0f;
            }
            // Base the window movement in the Y axis on the touch movement in the Y axis.
            float rawYDelta = touchY - startTouchY;
            float yDirection = rawYDelta < 0 ? -1f : 1f;
            // limit yDelta interpretation to 1/2 of screen height in either direction
            float deltaYRatio = Math.min(screenHeight / 2f, Math.abs(rawYDelta)) / (screenHeight / 2f);
            float interpolatedYRatio = VERTICAL_INTERP.getInterpolation(deltaYRatio);
            // limit y-shift so the surface never passes the 8dp screen margin
            float room = Math.max(0f, (screenHeight - centeredRect.height()) / 2f - displayBoundsMarginPx);
            return room * interpolatedYRatio * yDirection;
        }

        /**
         * CrossActivityBackAnimation#applyTransform — the AOSP matrix is postScale(scale, scale, 0, 0)
         * followed by postTranslate(rect.left, rect.top); with the view pivot at the origin this is
         * exactly scale + translation.
         */
        private void applyTransform(View view, RectF rect, float alpha) {
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
            view.setAlpha(alpha);
        }

        private void applyTransformChildren(ViewGroup cvb, RectF rect, float alpha) {
            if (backAnimRect.width() <= 0f) {
                return;
            }
            float scale = rect.width() / backAnimRect.width();
            for (int i = 0; i < cvb.getChildCount(); i++) {
                View child = cvb.getChildAt(i);
                applyTransform(child, rect, alpha);
                // Match a single SurfaceControl matrix: the child's layout origin scales too.
                child.setTranslationX(rect.left + child.getLeft() * (scale - 1f));
                child.setTranslationY(rect.top + child.getTop() * (scale - 1f));
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

            // DefaultCrossActivityBackAnimation#onGestureCommitted — phase 2 starts from the
            // gesture-driven state: the closing window grows back to full size while sliding off to
            // the right, the entering window expands to full screen. The AOSP fling spring is
            // intentionally NOT started here — its shrink-dip in the first frames read as a
            // backward jerk the moment the finger lifts.
            startClosingRect.set(currentClosingRect);
            startEnteringRect.set(currentEnteringRect);
            targetEnteringRect.set(backAnimRect);
            targetClosingRect.set(backAnimRect);
            targetClosingRect.offset(currentClosingRect.left + enteringStartOffsetPx, 0f);

            ValueAnimator animator = ValueAnimator.ofFloat(1f, 0f);
            animator.setDuration(POST_COMMIT_DURATION);
            animator.addUpdateListener(animation -> {
                float linearProgress = animation.getAnimatedFraction();
                // AOSP fades the closing window out over the first 20% of linear progress and lifts
                // the scrim linearly; the spatial motion rides the emphasized curve.
                float closingAlpha = Math.max(1f - CLOSING_ALPHA_FADE_RATE * linearProgress, 0f);
                float progress = EMPHASIZED.getInterpolation(linearProgress);
                setInterpolatedRectF(tmpRect, startClosingRect, targetClosingRect, progress);
                applyTransform(cv, tmpRect, closingAlpha);
                setInterpolatedRectF(tmpRect, startEnteringRect, targetEnteringRect, progress);
                applyTransformChildren(cvb, tmpRect, 1f);
                scrim.setAlpha((int) (maxScrimAlpha * 255f * (1f - linearProgress)));
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
        }

        private void cleanupViews() {
            ViewGroup cv = layout.containerView;
            if (cv != null) {
                cv.setScaleX(1f);
                cv.setScaleY(1f);
                cv.setTranslationX(0f);
                cv.setTranslationY(0f);
                cv.setAlpha(1f);
                cv.setPivotX(cv.getWidth() / 2f);
                cv.setPivotY(cv.getHeight() / 2f);
                cv.setClipToOutline(savedClipToOutline);
                cv.setOutlineProvider(savedOutlineProvider != null ? savedOutlineProvider : ViewOutlineProvider.BACKGROUND);
            }
            ViewGroup cvb = layout.containerViewBack;
            if (cvb != null) {
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
                    child.setAlpha(1f);
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
            scrim.setAlpha((int) (maxScrimAlpha * 255f));
        }

        private boolean isDarkTheme() {
            int uiMode = activity.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
            return uiMode == Configuration.UI_MODE_NIGHT_YES;
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
