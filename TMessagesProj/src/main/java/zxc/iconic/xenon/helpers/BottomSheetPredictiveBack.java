package zxc.iconic.xenon.helpers;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.os.Build;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.window.BackEvent;
import android.window.OnBackAnimationCallback;

import androidx.annotation.RequiresApi;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.BottomSheet;

import zxc.iconic.xenon.NekoConfig;

@RequiresApi(api = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
public final class BottomSheetPredictiveBack {

    private static final float LAZY_START = 0.02f;

    public static OnBackAnimationCallback createCallback(BottomSheet sheet) {
        return new Callback(sheet);
    }

    private static final class Callback implements OnBackAnimationCallback {
        private final BottomSheet sheet;
        private boolean attached = false;

        private static float clamp(float v, float min, float max) {
            return v < min ? min : Math.min(v, max);
        }
        private boolean isButton = false;
        private AnimatorSet runningAnim = null;
        private float maxTranslateY = 0f;
        private float lastP = 0f;
        private float currentEased = 0f;

        Callback(BottomSheet sheet) {
            this.sheet = sheet;
        }

        @Override
        public void onBackStarted(BackEvent backEvent) {
            if (runningAnim != null) {
                runningAnim.cancel();
                runningAnim = null;
            }
            attached = false;
            isButton = false;
            lastP = 0f;
            currentEased = 0f;
            View cv = sheet.getSheetContainer();
            if (cv == null) return;
            maxTranslateY = cv.getHeight()
                    + (sheet.getKeyboardHeight() > 0 ? sheet.getKeyboardHeight() : 0)
                    + AndroidUtilities.dp(10)
                    + Math.max(0, Math.min(AndroidUtilities.navigationBarHeight, sheet.getBottomInset()));
        }

        @Override
        public void onBackProgressed(BackEvent backEvent) {
            if (!attached) {
                if (backEvent.getProgress() <= LAZY_START) return;
                attached = true;
                if (backEvent.getProgress() >= 0.99f) {
                    isButton = true;
                }
            }
            if (isButton) return;
            View cv = sheet.getSheetContainer();
            if (cv == null) return;
            float p = Math.min((backEvent.getProgress() - LAZY_START) / (1f - LAZY_START), 1f);
            if (p <= lastP && p >= 0.99f) return;
            lastP = p;
            float intensity = Math.max(NekoConfig.predictiveBackIntensity / 10f, 0.001f);
            float effectiveP = clamp(p * intensity, 0f, 1f);
            float eased = 1f - (1f - effectiveP) * (1f - effectiveP);
            currentEased = eased;
            cv.setTranslationY(maxTranslateY * eased);
            sheet.setPredictiveBackProgress(eased);
            sheet.getContainer().invalidate();
        }

        @Override
        public void onBackCancelled() {
            isButton = false;
            if (!attached) {
                return;
            }
            runFinishAnim();
        }

        @Override
        public void onBackInvoked() {
            if (!attached || isButton) {
                sheet.onBackPressed();
                return;
            }
            sheet.onBackPressed();
            if (!sheet.isDismissed()) {
                runFinishAnim();
            }
        }

        private void runFinishAnim() {
            View cv = sheet.getSheetContainer();
            if (cv == null) {
                return;
            }
            float startTranslation = cv.getTranslationY();
            float startBlur = currentEased;

            runningAnim = new AnimatorSet();
            runningAnim.playTogether(
                    ObjectAnimator.ofFloat(cv, View.TRANSLATION_Y, startTranslation, 0f)
            );
            ValueAnimator blurAnim = ValueAnimator.ofFloat(startBlur, 0f);
            blurAnim.addUpdateListener(a -> sheet.setPredictiveBackProgress((float) a.getAnimatedValue()));
            runningAnim.playTogether(blurAnim);
            runningAnim.setDuration(200);
            runningAnim.setInterpolator(new DecelerateInterpolator());
            runningAnim.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    runningAnim = null;
                }
            });
            runningAnim.start();
        }
    }
}
