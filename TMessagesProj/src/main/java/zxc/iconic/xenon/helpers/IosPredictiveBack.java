package zxc.iconic.xenon.helpers;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.os.Build;
import android.graphics.Outline;
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
    private static final long COMMIT_DURATION = 350L;
    private static final long CANCEL_DURATION = 200L;
    private static final int PARALLAX_DP = 96;

    private IosPredictiveBack() {
    }

    public static OnBackAnimationCallback createCallback(ActionBarLayout layout, Runnable plainBack, boolean aospStyle, boolean fadeStyle) {
        Callback callback = new Callback(layout, plainBack, aospStyle, fadeStyle);
        layout.m3PredictiveCallbackCancelRunnable = () -> callback.cancelAndCleanup();
        return callback;
    }

    private static float clamp(float v, float min, float max) {
        return v < min ? min : Math.min(v, max);
    }

    private static final class Callback implements OnBackAnimationCallback {

        private final ActionBarLayout layout;
        private final Runnable plainBack;
        private final boolean aospStyle;
        private final boolean fadeStyle;
        private boolean attached = false;
        private boolean invoked = false;
        private boolean finishCancel = false;
        private AnimatorSet runningAnim = null;
        private ViewOutlineProvider savedOutlineProvider = null;
        private boolean savedClipToOutline = false;
        private float cornerRadius = 0f;

        Callback(ActionBarLayout layout, Runnable plainBack, boolean aospStyle, boolean fadeStyle) {
            this.layout = layout;
            this.plainBack = plainBack;
            this.aospStyle = aospStyle;
            this.fadeStyle = fadeStyle;
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
                layout.invalidate();
                if (!aospStyle && !fadeStyle) {
                    attachRoundedCorners();
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
            if (fadeStyle) {
                cv.setAlpha(1f - p);
                cvb.setAlpha(p);
            } else if (aospStyle) {
                cv.setTranslationX(w * 0.2f * p);
                cvb.setTranslationX(-w * 0.2f * (1f - p));
                float alphaP = clamp((p - 0.125f) / 0.25f, 0f, 1f);
                cv.setAlpha(1f - alphaP);
                cvb.setAlpha(alphaP);
            } else {
                cv.setTranslationX(w * p);
                cvb.setTranslationX(-AndroidUtilities.dp(PARALLAX_DP) * (1f - p));
            }
        }

private void runFinishAnim(boolean cancel) {
            finishCancel = cancel;
            ViewGroup cv = layout.containerView;
            ViewGroup cvb = layout.containerViewBack;
            if (cv == null || cvb == null) {
                finalizeStock(cancel);
                return;
            }
            float w = cv.getWidth();
            boolean useAlpha = aospStyle || fadeStyle;
            long duration = cancel ? CANCEL_DURATION : COMMIT_DURATION;

            AnimatorSet set = new AnimatorSet();
            java.util.List<Animator> animators = new java.util.ArrayList<>();
            if (!fadeStyle) {
                float cvTarget = cancel ? 0f : (aospStyle ? w * 0.2f : w);
                animators.add(ObjectAnimator.ofFloat(cv, View.TRANSLATION_X, cvTarget));
                animators.add(ObjectAnimator.ofFloat(cvb, View.TRANSLATION_X, 0f));
            }

            if (useAlpha) {
                float cvAlphaFrom = cv.getAlpha();
                float cvAlphaTo = cancel ? 1f : 0f;
                float cvbAlphaFrom = cvb.getAlpha();
                float cvbAlphaTo = cancel ? 0f : 1f;
                android.animation.ValueAnimator alphaAnim = android.animation.ValueAnimator.ofFloat(0f, 1f);
                alphaAnim.addUpdateListener(a -> {
                    float f = (float) a.getAnimatedValue();
                    cv.setAlpha(cvAlphaFrom + (cvAlphaTo - cvAlphaFrom) * f);
                    cvb.setAlpha(cvbAlphaFrom + (cvbAlphaTo - cvbAlphaFrom) * f);
                });
                alphaAnim.setDuration((long) (duration * 0.3));
                alphaAnim.setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT);
                animators.add(alphaAnim);
            }

            set.playTogether(animators);
            set.setDuration(duration);
            set.setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT);
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
                cv.setTranslationX(0f);
                cv.setAlpha(1f);
                if (!aospStyle && !fadeStyle) {
                    cv.setClipToOutline(savedClipToOutline);
                    cv.setOutlineProvider(savedOutlineProvider != null ? savedOutlineProvider : ViewOutlineProvider.BACKGROUND);
                }
            }
            ViewGroup cvb = layout.containerViewBack;
            if (cvb != null) {
                cvb.setTranslationX(0f);
                cvb.setAlpha(1f);
            }
            savedOutlineProvider = null;
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
