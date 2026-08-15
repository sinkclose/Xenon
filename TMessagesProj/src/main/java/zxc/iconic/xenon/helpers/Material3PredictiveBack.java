package zxc.iconic.xenon.helpers;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Path;
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
import android.view.animation.LinearInterpolator;
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
import org.telegram.ui.Components.CubicBezierInterpolator;

import java.util.ArrayList;
import java.util.List;

import zxc.iconic.xenon.NekoConfig;

/**
 * Material 3 predictive-back animation, ported from Inugram.
 *
 * <p>Replaces the stock Telegram predictive-back transition with the AOSP Material 3 one: the
 * leaving screen shrinks, slides toward the gesture edge and follows the finger vertically, while
 * the entering screen is revealed underneath behind a dimming scrim. On commit the leaving screen
 * slides off-edge and fades; on cancel everything settles back in place.
 *
 * <p>It drives {@link ActionBarLayout} directly through the same hooks the stock callback uses
 * ({@code onBackStarted}, {@code predictiveInput}, {@code onSlideAnimationEnd}), so it must run on
 * API 34+ where {@link OnBackAnimationCallback} is available.
 */
@RequiresApi(api = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
public final class Material3PredictiveBack {

    private static final float LAZY_START = 0.015f;
    private static final float MAX_SCALE = 0.9f;
    private static final float EDGE_MARGIN_DP = 16f;
    // Entering screen starts this far off the left edge; closing slides this far off on commit.
    private static final float ENTER_OFFSET_DP = 96f;
    private static final int SCRIM_ALPHA_BYTE = 77; // ~0.3 * 255
    private static final float CLOSING_ALPHA_FADE = 0.2f; // leaving screen fully faded by this much of commit progress
    private static final float SCRIM_FADE = 0.5f; // scrim lifts by this much of commit progress
    private static final long COMMIT_DURATION = 450L;
    private static final long CANCEL_DURATION = 200L;

    private static final Interpolator GESTURE_INTERP = CubicBezierInterpolator.StandardDecelerate;
    private static final Interpolator VERTICAL_INTERP = new DecelerateInterpolator();
    private static final Interpolator EMPHASIZED_DECELERATE = new PathInterpolator(0.05f, 0.7f, 0.1f, 1f);
    private static final Interpolator EMPHASIZED = makeEmphasized();

    private Material3PredictiveBack() {
    }

    private static Interpolator makeEmphasized() {
        Path path = new Path();
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

    private interface ChildAction {
        void apply(View v);
    }

    private interface ChildFloatApply {
        void apply(View v, float value);
    }

    private static void eachChild(ViewGroup vg, ChildAction action) {
        for (int i = 0; i < vg.getChildCount(); i++) {
            action.apply(vg.getChildAt(i));
        }
    }

    private static float clamp(float v, float min, float max) {
        return v < min ? min : (Math.min(v, max));
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

    private static final class Callback implements OnBackAnimationCallback {

        private final Activity activity;
        private final ActionBarLayout layout;
        private final Runnable plainBack;

        private boolean attached = false;
        private boolean invoked = false;
        private boolean finishCancel = false;
        private float startTouchY = 0f;
        private int swipeEdge = BackEvent.EDGE_LEFT;
        private int deviceCornerPx = 0;
        private float edgeMarginPx = 0f;
        private float enterOffsetPx = 0f;
        private AnimatorSet runningAnim = null;
        private ViewOutlineProvider savedOutlineProvider = null;
        private boolean savedClipToOutline = false;
        private Drawable savedCvbBackground = null;
        private Drawable savedCvbForeground = null;
        private final ColorDrawable scrim = new ColorDrawable(Color.BLACK);

        private final ViewOutlineProvider outlineProvider = new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                float sx = Math.max(view.getScaleX(), 0.01f);
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), deviceCornerPx / sx);
            }
        };

        Callback(Activity activity, ActionBarLayout layout, Runnable plainBack) {
            this.activity = activity;
            this.layout = layout;
            this.plainBack = plainBack;
            scrim.setAlpha(SCRIM_ALPHA_BYTE);
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
            swipeEdge = backEvent.getSwipeEdge();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                WindowInsets insets = activity.getWindow().getDecorView().getRootWindowInsets();
                RoundedCorner rc = insets != null ? insets.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT) : null;
                deviceCornerPx = rc != null ? rc.getRadius() : AndroidUtilities.dp(24);
            } else {
                deviceCornerPx = AndroidUtilities.dp(24);
            }
            edgeMarginPx = AndroidUtilities.dpf2(EDGE_MARGIN_DP);
            enterOffsetPx = AndroidUtilities.dpf2(ENTER_OFFSET_DP);
            // Run stock's heavy prep (attach previous fragment, relayout, onResume, HW layer) during the
            // pre-LAZY_START invisible phase so the first visible frame is just a transform.
            layout.onBackStarted(backEvent.getTouchX(), backEvent.getTouchY());
        }

        @Override
        public void onBackProgressed(BackEvent backEvent) {
            if (invoked) {
                return;
            }
            if (!layout.predictiveInput) {
                return;
            }
            float rawP = backEvent.getProgress();
            if (!attached) {
                if (rawP <= LAZY_START) {
                    return;
                }
                attached = true;
                layout.m3PredictiveActive = true;
                layout.invalidate();
                attachOverlays();
            }
            float p = GESTURE_INTERP.getInterpolation(
                    clamp((rawP - LAZY_START) / (1f - LAZY_START), 0f, 1f));
            applyFrame(p, backEvent.getTouchY());
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

            // Translating cvb's children leaves the parent in place; paint it with the entering
            // fragment's own background so the gap matches it, then overlay a black scrim.
            ViewGroup cvb = layout.containerViewBack;
            if (cvb == null) {
                return;
            }
            // cvb itself must be at identity before we start offsetting its children — a stale
            // translationX left over from an interrupted alternativeTransition open/close (which
            // animates containerViewBack directly, see ActionBarLayout#startLayoutAnimation) would
            // otherwise stack with the per-child offset below and throw the whole reveal off by the
            // leftover amount.
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
            // Promote entering children to HW layers so per-frame scale/translate is texture-only.
            eachChild(cvb, v -> v.setLayerType(View.LAYER_TYPE_HARDWARE, null));
        }

        private void applyFrame(float p, float touchY) {
            ViewGroup cv = layout.containerView;
            ViewGroup cvb = layout.containerViewBack;
            if (cv == null || cvb == null) {
                return;
            }
            float w = cv.getWidth();
            float h = cv.getHeight();
            if (w <= 0f || h <= 0f) {
                return;
            }

            // Predictive-back intensity (slider /10 → 0.1..2.0, 1.0 default) scales how far the
            // leaving screen shrinks away: 0.1 is nearly imperceptible, 2.0 pulls it twice as far.
            float intensity = Math.max(NekoConfig.predictiveBackIntensity / 10f, 0.001f);
            float scale = 1f - (1f - MAX_SCALE) * intensity * p;
            // AOSP keeps the closing window centered for a right-edge swipe and only pushes it toward
            // the right edge for a left-edge swipe; the off-edge slide happens on commit either way.
            float maxDx = Math.max((w - scale * w) / 2f - edgeMarginPx, 0f);
            float tx = swipeEdge == BackEvent.EDGE_RIGHT ? 0f : maxDx * p;

            // Vertical follow tracks touch-Y, capped by the room the shrink frees up.
            float deltaY = touchY - startTouchY;
            float dyCap = Math.max((h - scale * h) / 2f - edgeMarginPx, 0f);
            float ySign = deltaY >= 0f ? 1f : -1f;
            float yNorm = clamp(Math.abs(deltaY) / (h / 2f), 0f, 1f);
            final float ty = ySign * dyCap * VERTICAL_INTERP.getInterpolation(yNorm);

            cv.setPivotX(w / 2f);
            cv.setPivotY(h / 2f);
            cv.setScaleX(scale);
            cv.setScaleY(scale);
            cv.setTranslationX(tx);
            cv.setTranslationY(ty);
            cv.invalidateOutline();

            // Entering screen shrinks in sync with the closing one (same scale) at a fixed off-edge
            // offset, then grows to full on commit.
            final float fScale = scale;
            eachChild(cvb, v -> {
                v.setTranslationX(-enterOffsetPx);
                v.setTranslationY(ty);
                v.setScaleX(fScale);
                v.setScaleY(fScale);
            });
        }

        private ValueAnimator childFloatAnim(ViewGroup cvb, float from, float to, ChildFloatApply apply) {
            ValueAnimator va = ValueAnimator.ofFloat(from, to);
            va.addUpdateListener(a -> {
                float v = (float) a.getAnimatedValue();
                eachChild(cvb, c -> apply.apply(c, v));
            });
            return va;
        }

        private void runFinishAnim(boolean cancel) {
            finishCancel = cancel;
            final ViewGroup cv = layout.containerView;
            if (cv == null) {
                finalizeStock(cancel);
                return;
            }
            ViewGroup cvb = layout.containerViewBack;
            if (cvb == null) {
                finalizeStock(cancel);
                return;
            }

            // Both paths scale back to full size: cancel settles in place, commit grows back to 1
            // while sliding off-edge + fading (M3 — the leaving screen leaves at full size, not shrunk).
            float cvTargetTx = cancel ? 0f : cv.getTranslationX() + enterOffsetPx;
            float childTargetTx = cancel ? -enterOffsetPx : 0f;

            View first = cvb.getChildAt(0);
            // Spatial motion rides the emphasized curve.
            ObjectAnimator scaleX = ObjectAnimator.ofFloat(cv, View.SCALE_X, 1f);
            scaleX.addUpdateListener(a -> cv.invalidateOutline());
            List<Animator> spatial = new ArrayList<>();
            spatial.add(scaleX);
            spatial.add(ObjectAnimator.ofFloat(cv, View.SCALE_Y, 1f));
            spatial.add(ObjectAnimator.ofFloat(cv, View.TRANSLATION_X, cvTargetTx));
            spatial.add(ObjectAnimator.ofFloat(cv, View.TRANSLATION_Y, 0f));
            spatial.add(childFloatAnim(cvb, first != null ? first.getTranslationX() : 0f, childTargetTx, (c, v) -> c.setTranslationX(v)));
            spatial.add(childFloatAnim(cvb, first != null ? first.getTranslationY() : 0f, 0f, (c, v) -> c.setTranslationY(v)));
            spatial.add(childFloatAnim(cvb, first != null ? first.getScaleX() : 1f, 1f, (c, v) -> {
                c.setScaleX(v);
                c.setScaleY(v);
            }));

            List<Animator> animators = new ArrayList<>(spatial);
            if (!cancel) {
                // AOSP fades the leaving screen out over just the first CLOSING_ALPHA_FADE of progress,
                // and the scrim linearly — both on raw progress, not the emphasized spatial curve.
                final int startScrim = scrim.getAlpha();
                ValueAnimator fade = ValueAnimator.ofFloat(0f, 1f);
                fade.setInterpolator(new LinearInterpolator());
                fade.addUpdateListener(a -> {
                    float f = (float) a.getAnimatedValue();
                    cv.setAlpha(clamp(1f - f / CLOSING_ALPHA_FADE, 0f, 1f));
                    scrim.setAlpha((int) (startScrim * Math.max(1f - f / SCRIM_FADE, 0f)));
                });
                animators.add(fade);
            }

            AnimatorSet set = new AnimatorSet();
            set.playTogether(animators);
            set.setDuration(cancel ? CANCEL_DURATION : COMMIT_DURATION);
            if (cancel) {
                set.setInterpolator(EMPHASIZED_DECELERATE);
            } else {
                for (Animator a : spatial) {
                    a.setInterpolator(EMPHASIZED);
                }
            }
            set.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    runningAnim = null;
                    finalizeStock(cancel);
                }
            });
            runningAnim = set;
            set.start();
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
                cv.setClipToOutline(savedClipToOutline);
                cv.setOutlineProvider(savedOutlineProvider != null ? savedOutlineProvider : ViewOutlineProvider.BACKGROUND);
            }
            ViewGroup cvb = layout.containerViewBack;
            if (cvb != null) {
                eachChild(cvb, v -> {
                    v.setTranslationX(0f);
                    v.setTranslationY(0f);
                    v.setScaleX(1f);
                    v.setScaleY(1f);
                    v.setLayerType(View.LAYER_TYPE_NONE, null);
                });
                cvb.setBackground(savedCvbBackground);
                cvb.setForeground(savedCvbForeground);
            }
            savedCvbBackground = null;
            savedCvbForeground = null;
            scrim.setAlpha(SCRIM_ALPHA_BYTE);
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
