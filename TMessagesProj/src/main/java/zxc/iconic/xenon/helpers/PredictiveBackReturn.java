package zxc.iconic.xenon.helpers;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;
import android.window.BackEvent;
import android.window.OnBackAnimationCallback;

import org.telegram.ui.Components.CubicBezierInterpolator;

import zxc.iconic.xenon.NekoConfig;

/** Shapes rollback frames sent by the Android dispatcher before onBackCancelled. */
public final class PredictiveBackReturn implements OnBackAnimationCallback {
    private static final long RETURN_DURATION_MS = 240;
    private static final Interpolator INVERSE_DECELERATE = new PathInterpolator(0f, 0f, 1f, 0f);

    private final OnBackAnimationCallback delegate;
    private BackEvent latest;
    private ValueAnimator returning;
    private float displayedProgress;
    private float returnProgress;
    private boolean cancelling;

    public PredictiveBackReturn(OnBackAnimationCallback delegate) {
        this.delegate = delegate;
    }

    public OnBackAnimationCallback delegate() {
        return delegate;
    }

    @Override
    public void onBackStarted(BackEvent event) {
        finishPendingCancellation();
        reset();
        latest = event;
        displayedProgress = event.getProgress();
        delegate.onBackStarted(event);
    }

    @Override
    public void onBackProgressed(BackEvent event) {
        if (cancelling) return;
        BackEvent previous = latest;
        latest = event;

        // During Android's rollback the touch position stays at the last reported coordinates.
        // If a stationary finger only moves the spring toward a nonzero target, keep that target.
        boolean sameTouch = previous != null && previous.getTouchX() == event.getTouchX()
                && previous.getTouchY() == event.getTouchY();
        if (!NekoConfig.predictiveBackDecelerate || !sameTouch
                || event.getProgress() > previous.getProgress()) {
            stopAnimator();
            publish(event);
        } else if (returning == null) {
            if (event.getProgress() < displayedProgress) startReturn();
            else publish(event);
        }
    }

    @Override
    public void onBackCancelled() {
        if (!NekoConfig.predictiveBackDecelerate || latest == null || displayedProgress <= 0f) {
            reset();
            delegate.onBackCancelled();
            return;
        }
        cancelling = true;
        if (returning == null || returnProgress < displayedProgress) {
            stopAnimator();
            startReturn();
        }
    }

    @Override
    public void onBackInvoked() {
        finishPendingCancellation();
        reset();
        delegate.onBackInvoked();
    }

    private void finishPendingCancellation() {
        if (cancelling) {
            publishProgress(0f);
            reset();
            delegate.onBackCancelled();
        }
    }

    private void startReturn() {
        // Animate the visible decelerated distance. Convert it back to raw progress before
        // dispatching so the preview curve does not speed up the last part of the return.
        float start = CubicBezierInterpolator.StandardDecelerate.getInterpolation(displayedProgress);
        returnProgress = displayedProgress;
        ValueAnimator animator = ValueAnimator.ofFloat(start, 0f);
        returning = animator;
        animator.setDuration(RETURN_DURATION_MS);
        animator.setInterpolator(CubicBezierInterpolator.EASE_BOTH);
        animator.addUpdateListener(animation -> {
            float remaining = (float) animation.getAnimatedValue();
            returnProgress = Math.min(returnProgress, INVERSE_DECELERATE.getInterpolation(remaining));
            publishProgress(cancelling ? returnProgress : Math.max(returnProgress, latest.getProgress()));
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                returning = null;
                if (cancelling) {
                    reset();
                    delegate.onBackCancelled();
                }
            }
        });
        animator.start();
    }

    private void publishProgress(float progress) {
        if (displayedProgress != progress && latest != null) {
            publish(new BackEvent(latest.getTouchX(), latest.getTouchY(), progress, latest.getSwipeEdge()));
        }
    }

    private void publish(BackEvent event) {
        displayedProgress = event.getProgress();
        delegate.onBackProgressed(event);
    }

    public void reset() {
        stopAnimator();
        latest = null;
        displayedProgress = 0f;
        cancelling = false;
    }

    private void stopAnimator() {
        if (returning != null) {
            returning.removeAllListeners();
            returning.removeAllUpdateListeners();
            returning.cancel();
            returning = null;
        }
    }
}
