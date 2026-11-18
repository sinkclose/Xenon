package org.telegram.ui.Components;

/** Adapted from Glasmio HeaderMotion.kt (Apache-2.0), revision 1a065250.
 * Exact damped oscillator; a new destination preserves position and velocity. */
public final class HeaderButtonSpring {
    public float value, target;
    private float velocity;
    public float duration = .416f, bounce = .25f, delay;

    public HeaderButtonSpring(float initial) { snap(initial); }

    public void snap(float value) {
        this.value = target = value;
        velocity = 0;
        delay = 0;
    }

    public HeaderButtonSpring spring(float duration, float bounce) {
        this.duration = duration;
        this.bounce = bounce;
        return this;
    }

    public boolean settled() {
        return Math.abs(value - target) < .0005f && Math.abs(velocity) < .005f && delay <= 0;
    }

    public boolean advance(float seconds, boolean animate) {
        if (!animate) { snap(target); return false; }
        final float dt = Math.max(0, seconds - Math.max(0, delay));
        delay = Math.max(0, delay - seconds);
        if (dt == 0) return !settled();
        final double w = 2 * Math.PI / duration, z = bounce >= 0 ? 1 - bounce : 1 / (1 + bounce);
        final double x = value - target, v = velocity;
        final double decay = Math.exp(-z * w * dt);
        if (z < 1) {
            final double wd = w * Math.sqrt(1 - z * z);
            final double c = Math.cos(wd * dt), s = Math.sin(wd * dt);
            value = (float) (target + decay * (x * c + (v + z * w * x) / wd * s));
            velocity = (float) (decay * (v * c - (z * w * v + w * w * x) / wd * s));
        } else {
            final double b = v + w * x;
            value = (float) (target + decay * (x + b * dt));
            velocity = (float) (decay * (v - w * b * dt));
        }
        if (settled()) {
            snap(target);
            return false;
        }
        return true;
    }
}
