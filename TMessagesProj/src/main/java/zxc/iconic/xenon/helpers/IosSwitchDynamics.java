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
    private float liftedSeconds;
    private static boolean glasmio() { return zxc.iconic.xenon.NekoConfig.ios27LiquidGlass; }

    public IosSwitchDynamics(float initialValue) {
        snapTo(initialValue);
    }

    public static float clamp(float value, float low, float high) {
        return Math.max(low, Math.min(high, value));
    }

    public static float dragFraction(float initial, float deltaPixels, float density, boolean rtl) {
        return clamp(initial + (rtl ? -deltaPixels : deltaPixels) / ((glasmio() ? 22f : TRAVEL) * density), 0f, 1f);
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
        if (!held && !releasePending) liftedSeconds = 0f;
        held = true;
        releasePending = false;
        if (glasmio()) {
            press.damping = scaleX.damping = scaleY.damping = .625f;
            press.stiffness = scaleX.stiffness = scaleY.stiffness = 541.54208f;
        } else {
            press.damping = 1f; press.stiffness = 1000f;
            scaleX.damping = .6f; scaleY.damping = .7f;
            scaleX.stiffness = scaleY.stiffness = 250f;
        }
        press.target = 1f;
        scaleX.target = scaleY.target = 1.5f;
    }

    public void animateTo(float value, boolean simulatePress) {
        position.target = clamp(value, 0f, 1f);
        if (simulatePress && !glasmio()) {
            press();
            release();
        }
    }

    public void release() {
        held = false;
        releasePending = true;
    }

    public void advance(float seconds) {
        if (held || releasePending) liftedSeconds += seconds;
        position.advance(seconds);
        velocity.target = position.velocity;
        velocity.advance(seconds);
        if (releasePending && !held && (glasmio() ? liftedSeconds >= .220f : Math.abs(position.value - position.target) < .025f)) {
            releasePending = false;
            if (glasmio()) {
                press.damping = scaleX.damping = scaleY.damping = .7f;
                press.stiffness = scaleX.stiffness = scaleY.stiffness = 157.91367f;
            }
            press.target = 0f;
            scaleX.target = scaleY.target = 1f;
        }
        press.advance(seconds);
        scaleX.advance(seconds);
        scaleY.advance(seconds);
    }

    public float fraction() { return clamp(position.value, 0f, 1f); }
    public float target() { return position.target; }
    public float pressProgress() { return glasmio() ? press.value : clamp(press.value, 0f, 1f); }
    public float thumbScaleX() { return thumbScaleX(50f); }
    public float thumbScaleX(float velocityDivisor) {
        float scale = glasmio() ? 1f + (58f / 37f - 1f) * press.value : scaleX.value;
        return scale / (1f - clamp(velocity.value / velocityDivisor * .75f, -.2f, .2f));
    }
    public float thumbScaleY() { return thumbScaleY(50f); }
    public float thumbScaleY(float velocityDivisor) {
        float scale = glasmio() ? 1f + (38.333333f / 24f - 1f) * press.value : scaleY.value;
        return scale * (1f - clamp(velocity.value / velocityDivisor * .25f, -.2f, .2f));
    }
    public boolean isRunning() {
        return releasePending || !position.settled() || !press.settled()
                || !scaleX.settled() || !scaleY.settled() || !velocity.settled();
    }

    private static final class Spring {
        float damping, stiffness;
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
