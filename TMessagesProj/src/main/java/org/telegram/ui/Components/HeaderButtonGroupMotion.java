package org.telegram.ui.Components;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** HeaderGroupPresentation + HeaderGroup geometry from GlasmioNavigationScaffold.kt.
 * Apache-2.0, revision 1a0652509071c36fedb35300abdfd12316cc3c54.
 * Values are in pixels. Rendering and Android action ownership stay outside this model. */
public final class HeaderButtonGroupMotion {
    public static final class Layer {
        public final HeaderButtonSpring visibility = new HeaderButtonSpring(0);
        public final HeaderButtonSpring x = new HeaderButtonSpring(0);
        public float width;
        private boolean positioned;
    }

    public final HeaderButtonSpring width = new HeaderButtonSpring(0);
    public final HeaderButtonSpring presence = new HeaderButtonSpring(0);
    public final HeaderButtonSpring kick = new HeaderButtonSpring(0);
    public HeaderButtonPulse pulse = new HeaderButtonPulse();
    public final LinkedHashMap<String, Layer> layers = new LinkedHashMap<>();
    private final LinkedHashMap<String, Float> positions = new LinkedHashMap<>();
    private final boolean anchorRight;
    private List<String> signature;
    private float returnIn;

    public HeaderButtonGroupMotion() {
        this(false);
    }

    public HeaderButtonGroupMotion(boolean anchorRight) {
        this.anchorRight = anchorRight;
    }

    public float layerLeft(Layer layer) {
        return anchorRight ? width.value - layer.x.value - layer.width : layer.x.value;
    }

    public void retarget(LinkedHashMap<String, Float> actions, float gap) {
        List<String> keys = new ArrayList<>(actions.keySet());
        if (signature == null && keys.isEmpty()) return;
        boolean changed = !keys.equals(signature);
        boolean initialized = signature != null;
        float targetWidth = gap * Math.max(0, actions.size() - 1);
        for (float buttonWidth : actions.values()) targetWidth += buttonWidth;
        if (changed && initialized) {
            pulse.trigger();
            float sum = 0;
            int matches = 0;
            float nextX = 0;
            for (Map.Entry<String, Float> action : actions.entrySet()) {
                Float oldX = positions.get(action.getKey());
                if (oldX != null) {
                    sum += anchorRight ? oldX - (targetWidth - nextX - action.getValue()) : nextX - oldX;
                    matches++;
                }
                nextX += action.getValue() + gap;
            }
            kick.target = matches == 0 ? 0 : sum / matches / 6;
            kick.spring(.25f, 0);
            kick.delay = .05f;
            returnIn = .15f;
        }
        signature = keys;
        float nextX = 0;
        for (Map.Entry<String, Float> action : actions.entrySet()) {
            Layer layer = layers.get(action.getKey());
            if (layer == null) { layer = new Layer(); layers.put(action.getKey(), layer); }
            layer.width = action.getValue();
            layer.x.target = anchorRight ? targetWidth - nextX - layer.width : nextX;
            if (!layer.positioned) { layer.x.snap(layer.x.target); layer.positioned = true; }
            layer.visibility.target = 1;
            nextX += layer.width + gap;
        }
        for (Map.Entry<String, Layer> entry : layers.entrySet()) {
            if (!actions.containsKey(entry.getKey())) entry.getValue().visibility.target = 0;
        }
        if (!actions.isEmpty()) width.target = Math.max(0, targetWidth);
        presence.target = actions.isEmpty() ? 0 : 1;
        if (!initialized) width.snap(width.target);
        positions.clear();
        for (String key : keys) positions.put(key, layers.get(key).x.target);
    }

    public boolean advance(float dt, boolean animate) {
        boolean moving = width.advance(dt, animate) | presence.advance(dt, animate)
                | pulse.advance(dt, animate) | kick.advance(dt, animate);
        if (!animate) returnIn = 0;
        if (returnIn > 0) {
            returnIn -= dt;
            if (returnIn <= 0) {
                kick.target = 0;
                kick.spring(.5f, .166f);
                kick.delay = 0;
            }
            moving = true;
        }
        for (Layer layer : layers.values()) moving |= layer.visibility.advance(dt, animate) | layer.x.advance(dt, animate);
        return moving;
    }

    public float shellScale(float height, float pointLimit, boolean horizontal, boolean animate) {
        if (!animate) return 1;
        return pulse.scale(horizontal ? width.value : height, pointLimit, horizontal) * presenceScale(presence.value, animate);
    }

    public static float presenceScale(float presence, boolean animate) {
        return animate ? 1.2f - .2f * Math.max(0, Math.min(1, presence)) : 1f;
    }

    /** Equivalent to disposing a retained HeaderGroup after all of its tracks settle. */
    public boolean disposeIfGone() {
        if (signature == null || presence.target != 0 || !presence.settled() || !layers.isEmpty()) return false;
        signature = null;
        positions.clear();
        width.snap(0);
        kick.snap(0);
        returnIn = 0;
        pulse = new HeaderButtonPulse();
        return true;
    }

    public static float foregroundScale(float visibility, boolean animate) { return animate ? .2f + .8f * visibility : 1; }
    public static float foregroundBlur(float visibility, float density, boolean animate) {
        return animate ? 10 * density * Math.max(0, Math.min(1, 1 - visibility)) : 0;
    }
}
