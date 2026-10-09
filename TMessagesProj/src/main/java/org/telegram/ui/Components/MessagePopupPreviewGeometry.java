package org.telegram.ui.Components;

/** Geometry shared by the scroll viewport and the moving message, independent of chat snapshots. */
public final class MessagePopupPreviewGeometry {
    private MessagePopupPreviewGeometry() {}

    public static int contentTop(int available, int messageHeight, int menuHeight, int gap, float anchorY) {
        int total = messageHeight + gap + menuHeight;
        return Math.round(Math.max(0, Math.min(anchorY - messageHeight - gap, available - total)));
    }

    public static boolean needsScroll(int available, int messageHeight, int menuHeight, int gap) {
        return messageHeight + gap + menuHeight > available;
    }

    public static int openingScroll(int contentHeight, int viewportHeight) {
        return Math.max(0, contentHeight - viewportHeight);
    }

    public static float overscroll(float offset, float distance, float limit) {
        if (limit <= 0) return 0;
        float resistance = .35f * Math.max(.08f, 1f - Math.abs(offset) / limit);
        return Math.max(-limit, Math.min(limit, offset + distance * resistance));
    }

    public static float flingSpring(float progress, float start, float velocity, float limit) {
        if (progress <= 0f) return start;
        if (progress >= 1f) return 0f;
        // One critically damped spring, driven by the incoming velocity. Its speed
        // naturally reaches zero at the peak and changes direction without a seam.
        float stiffness = 12f;
        float maxVelocity = Math.max(0f, limit) * stiffness * (float) Math.E;
        float impulse = -Math.max(-maxVelocity, Math.min(velocity, maxVelocity));
        float seconds = progress * .7f;
        return (start + (impulse + stiffness * start) * seconds)
                * (float) Math.exp(-stiffness * seconds);
    }

    public static float destinationTop(float viewportTop, float placeholderTop, int scrollY) {
        // A newly opened preview must show its header; only scrolling may move it above the viewport.
        return scrollY == 0 ? Math.max(viewportTop, placeholderTop) : placeholderTop;
    }

    public static float followFraction(long elapsed, float progress, boolean closing) {
        // Land on the current source at the end, rather than leaving a trailing position error.
        float timeConstant = closing ? 40f * progress : 40f;
        return timeConstant < 1f ? 1f : 1f - (float) Math.exp(-Math.max(1, elapsed) / timeConstant);
    }

    public static float position(float liveSource, float liveDestination, float progress) {
        return liveSource + (liveDestination - liveSource) * progress;
    }

    public static float horizontalPosition(float liveSource, float windowLeft) {
        return liveSource - windowLeft;
    }
}
