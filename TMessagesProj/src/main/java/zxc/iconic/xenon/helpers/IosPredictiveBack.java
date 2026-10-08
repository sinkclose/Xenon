package zxc.iconic.xenon.helpers;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ValueAnimator;
import android.view.animation.LinearInterpolator;
import android.os.Build;
import android.graphics.Outline;
import android.graphics.Rect;
import android.view.RoundedCorner;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.WindowInsets;
import android.window.BackEvent;
import android.window.OnBackAnimationCallback;

import androidx.annotation.RequiresApi;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.ActionBarLayout;
import org.telegram.ui.Components.CubicBezierInterpolator;

import zxc.iconic.xenon.NekoConfig;

@RequiresApi(api = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
public final class IosPredictiveBack {

    private static final float LAZY_START = 0.015f;
    private static final long CANCEL_DURATION = 200L;
    private static final int PARALLAX_DP = 96;

    private IosPredictiveBack() {
    }

    public static OnBackAnimationCallback createCallback(ActionBarLayout layout, Runnable plainBack, boolean aospStyle, boolean fadeStyle) {
        return createCallback(layout, plainBack, aospStyle ? NekoConfig.ANIMATION_STYLE_AOSP_ALT
                : fadeStyle ? NekoConfig.ANIMATION_STYLE_FADE : NekoConfig.ANIMATION_STYLE_IOS);
    }

    public static OnBackAnimationCallback createCallback(ActionBarLayout layout, Runnable plainBack, int style) {
        Callback callback = new Callback(layout, plainBack, style);
        layout.m3PredictiveCallbackCancelRunnable = () -> callback.cancelAndCleanup();
        return callback;
    }

    private static float clamp(float v, float min, float max) {
        return v < min ? min : Math.min(v, max);
    }

    private static final class Callback implements OnBackAnimationCallback {

        private final ActionBarLayout layout;
        private final Runnable plainBack;
        private final int style;
        private float progress;
        private boolean attached = false;
        private boolean invoked = false;
        private boolean finishCancel = false;
        private AnimatorSet runningAnim = null;
        private ViewOutlineProvider savedOutlineProvider = null;
        private boolean savedClipToOutline = false;
        private float cornerRadius = 0f;
        private ViewOutlineProvider savedBackOutlineProvider;
        private boolean savedBackClipToOutline;
        private Rect savedClipBounds, savedBackClipBounds;
        private float savedTranslationY, savedBackTranslationY;

        Callback(ActionBarLayout layout, Runnable plainBack, int style) {
            this.layout = layout;
            this.plainBack = plainBack;
            this.style = style;
        }

        @Override
        public void onBackStarted(BackEvent backEvent) {
            if (runningAnim != null) {
                runningAnim.removeAllListeners();
                runningAnim.cancel();
                runningAnim = null;
                finalizeStock(finishCancel);
            }
            if (attached) {
                finalizeStock(true);
            } else if (layout.predictiveInput) {
                undoStockPrep();
            }
            invoked = false;
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
                if (style == NekoConfig.ANIMATION_STYLE_AOSP_ALT) layout.setAospPredictiveTransitionActive(true);
                layout.invalidate();
                if (style == NekoConfig.ANIMATION_STYLE_IOS) {
                    attachRoundedCorners();
                } else if (style == NekoConfig.ANIMATION_STYLE_AOSP_LEGACY) {
                    savedOutlineProvider = layout.containerView.getOutlineProvider();
                    savedClipToOutline = layout.containerView.getClipToOutline();
                    savedBackOutlineProvider = layout.containerViewBack.getOutlineProvider();
                    savedBackClipToOutline = layout.containerViewBack.getClipToOutline();
                } else if (style == NekoConfig.ANIMATION_STYLE_AOSP_9) {
                    savedClipBounds = layout.containerView.getClipBounds();
                    savedBackClipBounds = layout.containerViewBack.getClipBounds();
                    savedTranslationY = layout.containerView.getTranslationY();
                    savedBackTranslationY = layout.containerViewBack.getTranslationY();
                }
            }
            float p = clamp(rawP, 0f, 1f);
            float intensity = Math.max(NekoConfig.predictiveBackIntensity / 10f, 0.001f);
            float effectiveP = clamp(p * intensity, 0f, 1f);
            applyFrame(effectiveP);
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

        private void undoStockPrep() {
            if (!layout.predictiveInput) {
                return;
            }
            layout.predictiveInput = false;
            layout.predictiveBackInProgress = false;
            layout.onSlideAnimationEnd(true);
        }

        private void attachRoundedCorners() {
            ViewGroup cv = layout.containerView;
            if (cv == null) return;
            savedOutlineProvider = cv.getOutlineProvider();
            savedClipToOutline = cv.getClipToOutline();
            WindowInsets insets = cv.getRootWindowInsets();
            if (insets != null) {
                RoundedCorner tl = insets.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT);
                RoundedCorner tr = insets.getRoundedCorner(RoundedCorner.POSITION_TOP_RIGHT);
                RoundedCorner br = insets.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_RIGHT);
                RoundedCorner bl = insets.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_LEFT);
                cornerRadius = Math.max(
                    Math.max(tl == null ? 0 : tl.getRadius(), tr == null ? 0 : tr.getRadius()),
                    Math.max(br == null ? 0 : br.getRadius(), bl == null ? 0 : bl.getRadius())
                );
            } else {
                cornerRadius = AndroidUtilities.dp(28);
            }
            if (cornerRadius > 0) {
                cv.setOutlineProvider(new ViewOutlineProvider() {
                    @Override
                    public void getOutline(View v, Outline outline) {
                        outline.setRoundRect(0, 0, v.getWidth(), v.getHeight(), cornerRadius);
                    }
                });
                cv.setClipToOutline(true);
            }
        }

        private void applyFrame(float p) {
            ViewGroup cv = layout.containerView;
            ViewGroup cvb = layout.containerViewBack;
            if (cv == null || cvb == null) {
                return;
            }
            float w = cv.getWidth();
            if (w <= 0f) {
                return;
            }
            progress = p;
            if (style == NekoConfig.ANIMATION_STYLE_IOS || style == NekoConfig.ANIMATION_STYLE_SLIDE) {
                cv.setTranslationX(w * p);
                float distance = style == NekoConfig.ANIMATION_STYLE_SLIDE ? w : AndroidUtilities.dp(PARALLAX_DP);
                cvb.setTranslationX(-distance * (1f - p));
            } else {
                NavigationTransition.apply(cvb, cv, style, false, p);
            }
            layout.invalidate();
        }

        private void runFinishAnim(boolean cancel) {
            finishCancel = cancel;
            if (runningAnim != null) {
                runningAnim.removeAllListeners();
                runningAnim.cancel();
            }
            if (layout.containerView == null || layout.containerViewBack == null) {
                finalizeStock(cancel);
                return;
            }
            boolean aosp = style == NekoConfig.ANIMATION_STYLE_AOSP_ALT || NavigationTransition.usesBlackSurface(style);
            ValueAnimator frames = ValueAnimator.ofFloat(progress, cancel ? 0f : 1f);
            frames.addUpdateListener(a -> applyFrame((float) a.getAnimatedValue()));
            AnimatorSet set = new AnimatorSet();
            set.playTogether(frames);
            set.setDuration(cancel ? CANCEL_DURATION : Math.max(1L, (long) (NavigationTransition.duration(style) * (1f - progress))));
            set.setInterpolator(cancel ? CubicBezierInterpolator.EmphasizedDecelerate
                    : aosp || style == NekoConfig.ANIMATION_STYLE_FADE ? new LinearInterpolator()
                    : NavigationTransition.interpolator(style));
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
            if (style == NekoConfig.ANIMATION_STYLE_AOSP_ALT) layout.setAospPredictiveTransitionActive(false);
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
                cv.setTranslationX(0f);
                cv.setAlpha(1f);
                cv.setScaleX(1f);
                cv.setScaleY(1f);
                if (style == NekoConfig.ANIMATION_STYLE_AOSP_LEGACY) {
                    cv.setClipToOutline(savedClipToOutline);
                    cv.setOutlineProvider(savedOutlineProvider);
                } else if (style == NekoConfig.ANIMATION_STYLE_AOSP_9) {
                    cv.setTranslationY(savedTranslationY);
                    cv.setClipBounds(savedClipBounds);
                } else if (style == NekoConfig.ANIMATION_STYLE_IOS) {
                    cv.setClipToOutline(savedClipToOutline);
                    cv.setOutlineProvider(savedOutlineProvider != null ? savedOutlineProvider : ViewOutlineProvider.BACKGROUND);
                }
            }
            ViewGroup cvb = layout.containerViewBack;
            if (cvb != null) {
                cvb.setTranslationX(0f);
                cvb.setAlpha(1f);
                cvb.setScaleX(1f);
                cvb.setScaleY(1f);
                if (style == NekoConfig.ANIMATION_STYLE_AOSP_LEGACY) {
                    cvb.setClipToOutline(savedBackClipToOutline);
                    cvb.setOutlineProvider(savedBackOutlineProvider);
                } else if (style == NekoConfig.ANIMATION_STYLE_AOSP_9) {
                    cvb.setTranslationY(savedBackTranslationY);
                    cvb.setClipBounds(savedBackClipBounds);
                }
            }
            savedOutlineProvider = null;
            savedBackOutlineProvider = null;
            savedClipBounds = savedBackClipBounds = null;
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
