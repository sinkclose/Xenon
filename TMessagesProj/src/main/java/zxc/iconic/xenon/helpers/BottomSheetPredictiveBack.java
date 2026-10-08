package zxc.iconic.xenon.helpers;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.window.BackEvent;
import android.window.OnBackAnimationCallback;

import androidx.activity.BackEventCompat;
import androidx.annotation.RequiresApi;
import androidx.interpolator.view.animation.FastOutSlowInInterpolator;

import com.google.android.material.motion.MaterialBottomContainerBackHelper;
import com.google.android.material.motion.MotionUtils;

import org.telegram.ui.ActionBar.BottomSheet;

import java.util.ArrayList;

import zxc.iconic.xenon.NekoConfig;

@RequiresApi(api = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
public final class BottomSheetPredictiveBack {
    public static OnBackAnimationCallback createCallback(BottomSheet sheet) {
        return new Callback(sheet);
    }

    public static void releaseCallback(OnBackAnimationCallback callback) {
        if (callback instanceof Callback) ((Callback) callback).release();
    }

    private static final class ChildTransform {
        final View view;
        final float scaleY, pivotY;

        ChildTransform(View view) {
            this.view = view;
            scaleY = view.getScaleY();
            pivotY = view.getPivotY();
        }
    }

    private static final class Callback implements OnBackAnimationCallback {
        private final BottomSheet sheet;
        private MaterialBottomContainerBackHelper helper;
        private ViewGroup view;
        private AnimatorSet runningAnim;
        private final ArrayList<ChildTransform> children = new ArrayList<>();
        private boolean started, progressed;
        private float lastProgress;
        private float scaleX, scaleY, pivotY;

        Callback(BottomSheet sheet) {
            this.sheet = sheet;
        }

        @Override
        public void onBackStarted(BackEvent event) {
            if (sheet.isDismissed()) return;
            if (runningAnim != null) {
                runningAnim.cancel();
                runningAnim = null;
                restoreTransforms();
            }
            view = sheet.getSheetContainer();
            if (view == null) return;
            scaleX = view.getScaleX();
            scaleY = view.getScaleY();
            pivotY = view.getPivotY();
            children.clear();
            for (int i = 0; i < view.getChildCount(); i++) {
                children.add(new ChildTransform(view.getChildAt(i)));
            }
            sheet.setPredictiveBackTransformActive(true);
            helper = new MaterialBottomContainerBackHelper(view);
            helper.startBackProgress(new BackEventCompat(event));
            started = true;
            progressed = false;
            lastProgress = 0f;
        }

        @Override
        public void onBackProgressed(BackEvent event) {
            if (!started || sheet.isDismissed()) return;
            lastProgress = Math.max(0f, Math.min(1f, event.getProgress()));
            progressed |= lastProgress > 0f;
            if (view.getWidth() <= 0 || view.getHeight() <= 0) return;
            float intensity = Math.max(0f, NekoConfig.predictiveBackIntensity / 10f);
            float progress = Math.max(0f, Math.min(1f, lastProgress * intensity));
            // Use the helper's easing and bottom anchor, but scale the entire sheet
            // uniformly so its background and content share the same geometry.
            helper.updateBackProgress(progress);
            float sheetScale = view.getScaleX();
            view.setScaleX(sheetScale * scaleX);
            view.setScaleY(sheetScale * scaleY);
            for (ChildTransform child : children) {
                // Uniform parent scaling already preserves the content's aspect ratio.
                // Undo the helper's child compensation, including its pivot change.
                child.view.setPivotY(child.pivotY);
                child.view.setScaleY(child.scaleY);
            }
            sheet.getContainer().invalidate();
        }

        @Override
        public void onBackCancelled() {
            if (!started) return;
            started = false;
            helper.onHandleBackInvoked();
            animateReset();
        }

        @Override
        public void onBackInvoked() {
            if (sheet.isDismissed()) return;
            if (runningAnim != null) {
                runningAnim.cancel();
                runningAnim = null;
                restoreTransforms();
            }
            if (!started || !progressed) {
                if (started) {
                    helper.onHandleBackInvoked();
                    restoreTransforms();
                    started = false;
                }
                sheet.onBackPressed();
                return;
            }
            started = false;
            helper.onHandleBackInvoked();
            // Keep subclass Back handling (nested pages, selection, dismissal veto).
            sheet.dispatchPredictiveBack(lastProgress);
            if (!sheet.isDismissed()) animateReset();
        }

        private void release() {
            started = false;
            if (runningAnim != null) {
                runningAnim.cancel();
                runningAnim = null;
            }
            restoreTransforms();
            helper = null;
            view = null;
            children.clear();
        }

        private void restoreTransforms() {
            if (view == null) return;
            view.setScaleX(scaleX);
            view.setScaleY(scaleY);
            view.setPivotY(pivotY);
            for (ChildTransform child : children) {
                child.view.setScaleY(child.scaleY);
                child.view.setPivotY(child.pivotY);
            }
            sheet.setPredictiveBackProgress(0f);
            sheet.setPredictiveBackTransformActive(false);
        }

        private void animateReset() {
            if (view == null || sheet.isDismissed()) return;
            AnimatorSet animation = new AnimatorSet();
            ArrayList<Animator> animators = new ArrayList<>();
            animators.add(ObjectAnimator.ofFloat(view, View.SCALE_X, scaleX));
            animators.add(ObjectAnimator.ofFloat(view, View.SCALE_Y, scaleY));
            for (ChildTransform child : children) {
                animators.add(ObjectAnimator.ofFloat(child.view, View.SCALE_Y, child.scaleY));
            }
            animation.playTogether(animators);
            animation.setDuration(MotionUtils.resolveThemeDuration(view.getContext(),
                    com.google.android.material.R.attr.motionDurationShort2, 100));
            animation.setInterpolator(new FastOutSlowInInterpolator());
            animation.addListener(new AnimatorListenerAdapter() {
                private boolean cancelled;
                @Override
                public void onAnimationCancel(Animator animator) {
                    cancelled = true;
                }
                @Override
                public void onAnimationEnd(Animator animator) {
                    if (!cancelled) restoreTransforms();
                    if (runningAnim == animation) runningAnim = null;
                }
            });
            runningAnim = animation;
            animation.start();
        }
    }
}
