package zxc.iconic.xenon.helpers;

/** Spring parameters and deformation ported from AndroidLiquidGlass DampedDragAnimation.
 * Springs use an exact damped-oscillator solution, independent of display refresh rate.
 */
public final class IosSwitchDynamics {
    public static final float TRACK_WIDTH = 64f, TRACK_HEIGHT = 28f;
    public static final float THUMB_WIDTH = 40f, THUMB_HEIGHT = 24f, TRAVEL = 20f;
    public static final float VIEW_WIDTH = 84f, VIEW_HEIGHT = 48f;
    private final Spring position = new Spring(1f, 1000f);
    private final Spring press = new Spring(1f, 1000f);
    private final Spring scaleX = new Spring(.6f, 250f);
    private final Spring scaleY = new Spring(.7f, 250f);
    private final Spring velocity = new Spring(.5f, 300f);
    private boolean held, releasePending;

    public IosSwitchDynamics(float initialValue) {
        snapTo(initialValue);
    }

    public static float clamp(float value, float low, float high) {
        return Math.max(low, Math.min(high, value));
    }

    public static float dragFraction(float initial, float deltaPixels, float density, boolean rtl) {
        return clamp(initial + (rtl ? -deltaPixels : deltaPixels) / (TRAVEL * density), 0f, 1f);
    }

    // AndroidLiquidGlass scales the track backdrop before the thumb layer transform.
    public static float backdropScaleX(float press) { return 2f / 3f + (0.75f - 2f / 3f) * clamp(press, 0f, 1f); }
    public static float glassBlurRadiusDp(float press) { return 10f * (1f - clamp(press, 0f, 1f)); }
    public static float backdropScaleY(float press) { return .75f * clamp(press, 0f, 1f); }

    public static boolean releaseChecked(boolean initialChecked, boolean dragged, float fraction) {
        return dragged ? fraction >= .5f : !initialChecked;
    }

    public void snapTo(float value) {
        position.snap(clamp(value, 0f, 1f));
        press.snap(0f);
        scaleX.snap(1f);
        scaleY.snap(1f);
        velocity.snap(0f);
        held = releasePending = false;
    }

    public void press() {
        held = true;
        releasePending = false;
        press.target = 1f;
        scaleX.target = scaleY.target = 1.5f;
    }

    public void animateTo(float value, boolean simulatePress) {
        position.target = clamp(value, 0f, 1f);
        if (simulatePress) {
            press();
            release();
        }
    }

    public void release() {
        held = false;
        releasePending = true;
    }

    public void advance(float seconds) {
        position.advance(seconds);
        velocity.target = position.velocity;
        velocity.advance(seconds);
        if (releasePending && !held && Math.abs(position.value - position.target) < .025f) {
            releasePending = false;
            press.target = 0f;
            scaleX.target = scaleY.target = 1f;
        }
        press.advance(seconds);
        scaleX.advance(seconds);
        scaleY.advance(seconds);
    }

    public float fraction() { return clamp(position.value, 0f, 1f); }
    public float target() { return position.target; }
    public float pressProgress() { return clamp(press.value, 0f, 1f); }
    public float thumbScaleX() { return thumbScaleX(50f); }
    public float thumbScaleX(float velocityDivisor) {
        return scaleX.value / (1f - clamp(velocity.value / velocityDivisor * .75f, -.2f, .2f));
    }
    public float thumbScaleY() { return thumbScaleY(50f); }
    public float thumbScaleY(float velocityDivisor) {
        return scaleY.value * (1f - clamp(velocity.value / velocityDivisor * .25f, -.2f, .2f));
    }
    public boolean isRunning() {
        return releasePending || !position.settled() || !press.settled()
                || !scaleX.settled() || !scaleY.settled() || !velocity.settled();
    }

    private static final class Spring {
        final float damping, stiffness;
        float value, velocity, target;
        Spring(float damping, float stiffness) { this.damping = damping; this.stiffness = stiffness; }
        void snap(float target) { this.target = value = target; velocity = 0f; }
        boolean settled() { return Math.abs(value - target) < .001f && Math.abs(velocity) < .01f; }
        void advance(float dt) {
            if (dt <= 0f) return;
            if (settled()) { snap(target); return; }
            double w = Math.sqrt(stiffness), x = value - target, v = velocity;
            double decay = Math.exp(-damping * w * dt), nextX, nextV;
            if (damping == 1f) {
                double b = v + w * x;
                nextX = decay * (x + b * dt);
                nextV = decay * (v - w * b * dt);
            } else {
                double wd = w * Math.sqrt(1 - damping * damping);
                double a = damping * w, b = (v + a * x) / wd;
                double sin = Math.sin(wd * dt), cos = Math.cos(wd * dt);
                nextX = decay * (x * cos + b * sin);
                nextV = decay * (-a * (x * cos + b * sin) + wd * (-x * sin + b * cos));
            }
            value = target + (float) nextX;
            velocity = (float) nextV;
        }
    }
}
