package org.telegram.ui.Components;

/** Direct View adapter of Glasmio HeaderPulse in HeaderMotion.kt, Apache-2.0.
 * Source revision: 1a0652509071c36fedb35300abdfd12316cc3c54. */
public final class HeaderButtonPulse {
    private final HeaderButtonSpring[] x = {new HeaderButtonSpring(0), new HeaderButtonSpring(1)};
    private final HeaderButtonSpring[] y = {new HeaderButtonSpring(0), new HeaderButtonSpring(1)};
    private int rising;

    public void trigger() {
        int up = rising, down = 1 - up;
        for (HeaderButtonSpring[] channels : new HeaderButtonSpring[][]{x, y}) {
            channels[up].target = 1;
            channels[up].spring(.292f, .5f);
            channels[up].delay = 0;
            channels[down].target = 0;
        }
        x[down].spring(.416f, .5f);
        x[down].delay = .142f;
        y[down].spring(.416f, .416f);
        y[down].delay = .113f;
        rising = down;
    }

    public boolean advance(float dt, boolean animate) {
        boolean moving = false;
        for (HeaderButtonSpring channel : x) moving |= channel.advance(dt, animate);
        for (HeaderButtonSpring channel : y) moving |= channel.advance(dt, animate);
        return moving;
    }

    public float scale(float size, float pointLimit, boolean horizontal) {
        float r = Math.min((float) Math.sqrt(1.2f), (float) Math.sqrt(1 + pointLimit / Math.max(1, size)));
        HeaderButtonSpring[] channels = horizontal ? x : y;
        float a = 1 / r + (r - 1 / r) * channels[0].value;
        float b = 1 / r + (r - 1 / r) * channels[1].value;
        return a * b;
    }
}
