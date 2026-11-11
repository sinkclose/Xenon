package zxc.iconic.xenon.helpers;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.window.BackEvent;
import android.window.OnBackAnimationCallback;

import org.telegram.ui.Components.CubicBezierInterpolator;

import java.util.function.Supplier;

/** Drives reversible UI transitions before considering a fragment transition. Android 14+. */
public final class PredictiveTabBack implements OnBackAnimationCallback {
    public interface Target {
        boolean begin();
        void update(float progress);
        void end(boolean commit);
    }

    private final OnBackAnimationCallback fallback;
    private final Supplier<Target> destination;
    private Target target;
    private ValueAnimator settle;
    private float progress;
    private boolean committing;

    public PredictiveTabBack(OnBackAnimationCallback fallback, Supplier<Target> destination) {
        this.fallback = fallback;
        this.destination = destination;
    }

    public boolean isRunning() { return target != null; }

    @Override public void onBackStarted(BackEvent event) {
        if (target != null) complete(settle != null && committing);
        target = zxc.iconic.xenon.NekoConfig.predictiveBackIntensity > 0 ? destination.get() : null;
        progress = 0;
        if (target != null && !target.begin()) target = null;
        if (target == null) fallback.onBackStarted(event);
    }

    @Override public void onBackProgressed(BackEvent event) {
        if (target == null) {
            fallback.onBackProgressed(event);
        } else if (settle == null) {
            progress = gestureProgress(event.getProgress(), zxc.iconic.xenon.NekoConfig.predictiveBackIntensity);
            target.update(progress);
        }
    }

    @Override public void onBackCancelled() {
        if (target == null) fallback.onBackCancelled();
        else finish(false);
    }

    @Override public void onBackInvoked() {
        if (target == null) fallback.onBackInvoked();
        else finish(true);
    }

    private void finish(boolean commit) {
        if (settle != null) return;
        committing = commit;
        float end = commit ? 1 : 0;
        if (Math.abs(end - progress) < .001f) {
            complete(commit);
            return;
        }
        settle = ValueAnimator.ofFloat(progress, end);
        settle.setDuration(Math.max(80, Math.round(240 * Math.abs(end - progress))));
        settle.setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT);
        settle.addUpdateListener(animation -> {
            progress = (float) animation.getAnimatedValue();
            if (target != null) target.update(progress);
        });
        settle.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) { complete(commit); }
        });
        settle.start();
    }

    private void complete(boolean commit) {
        if (settle != null) {
            settle.removeAllListeners();
            settle.removeAllUpdateListeners();
            settle.cancel();
            settle = null;
        }
        if (target != null) {
            Target finished = target;
            target = null;
            finished.end(commit);
        }
    }

    public void cancel() { complete(false); }

    public static float gestureProgress(float raw, int intensity) {
        return Math.max(0, Math.min(1, raw * Math.max(0, intensity) / 10f));
    }
}
