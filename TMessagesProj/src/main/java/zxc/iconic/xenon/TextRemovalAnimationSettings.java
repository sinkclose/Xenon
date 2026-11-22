package zxc.iconic.xenon;

import android.content.SharedPreferences;

import org.telegram.messenger.R;

import java.util.EnumMap;
import java.util.EnumSet;

public final class TextRemovalAnimationSettings {
    private static final String PREFIX = "textRemovalAnimation.";

    public enum Mode {
        BLUR("blur", R.string.TextRemovalBlur),
        EXPLOSION("explosion", R.string.TextRemovalExplosion),
        SLIDE("slide", R.string.TextRemovalSlide);

        final String key;
        public final int title;

        Mode(String key, int title) {
            this.key = key;
            this.title = title;
        }
    }

    public enum Slider {
        FORCE("force", R.string.TextRemovalForce, 0, 1200, 10, 340, "dp/s"),
        GRAVITY("gravity", R.string.TextRemovalGravity, 0, 4000, 50, 1300, "dp/s²"),
        BOUNCE("bounce", R.string.TextRemovalBounce, 0, 100, 1, 40, "%"),
        SPIN("spin", R.string.TextRemovalSpin, 0, 1440, 30, 540, "°/s"),
        SHARDS("shards", R.string.TextRemovalShards, 2, 24, 1, 9, ""),
        LIFETIME_MIN("lifetimeMin", R.string.TextRemovalLifetimeMin, 100, 3000, 25, 500, "ms"),
        LIFETIME_MAX("lifetimeMax", R.string.TextRemovalLifetimeMax, 100, 3000, 25, 850, "ms"),
        DIRECTION("direction", R.string.TextRemovalDirection, 0, 360, 5, 0, "°"),
        SLIDE_ANGLE("slideAngle", R.string.TextRemovalSlideAngle, 0, 360, 5, 0, "°"),
        SLIDE_DISTANCE_MIN("slideDistanceMin", R.string.TextRemovalSlideDistanceMin, 0, 120, 1, 16, "dp"),
        SLIDE_DISTANCE_MAX("slideDistanceMax", R.string.TextRemovalSlideDistanceMax, 0, 120, 1, 36, "dp"),
        SLIDE_DURATION("slideDuration", R.string.TextRemovalSlideDuration, 50, 1500, 25, 250, "ms");

        final String key;
        public final int title, min, max, step;
        final int defaultValue;
        public final String unit;

        Slider(String key, int title, int min, int max, int step, int defaultValue, String unit) {
            this.key = key;
            this.title = title;
            this.min = min;
            this.max = max;
            this.step = step;
            this.defaultValue = defaultValue;
            this.unit = unit;
        }

        int clamp(int value) {
            return Math.max(min, Math.min(max, value));
        }

        public Slider otherBound() {
            switch (this) {
                case LIFETIME_MIN: return LIFETIME_MAX;
                case LIFETIME_MAX: return LIFETIME_MIN;
                case SLIDE_DISTANCE_MIN: return SLIDE_DISTANCE_MAX;
                case SLIDE_DISTANCE_MAX: return SLIDE_DISTANCE_MIN;
                default: return null;
            }
        }
    }

    public enum Toggle {
        FALL("fall", R.string.TextRemovalFall),
        IGNORE_FLOOR("ignoreFloor", R.string.TextRemovalIgnoreFloor);

        final String key;
        public final int title;

        Toggle(String key, int title) {
            this.key = key;
            this.title = title;
        }
    }

    public enum Phase {
        APPEARANCE("disableAppearance", R.string.TextAnimationDisableAppearance),
        REMOVAL("disableRemoval", R.string.TextAnimationDisableRemoval),
        BLUR_APPEARANCE("disableBlurAppearance", R.string.TextAnimationSkipBlurAppearance);

        final String key;
        public final int title;

        Phase(String key, int title) {
            this.key = key;
            this.title = title;
        }

        String preferenceKey(Mode mode) {
            return PREFIX + mode.key + "." + key;
        }
    }

    private final EnumMap<Slider, Integer> values = new EnumMap<>(Slider.class);
    private final EnumMap<Toggle, Boolean> toggles = new EnumMap<>(Toggle.class);
    private final EnumMap<Mode, EnumSet<Phase>> disabledPhases = new EnumMap<>(Mode.class);
    private SharedPreferences preferences;
    private Mode mode = Mode.BLUR;

    void load(SharedPreferences preferences) {
        this.preferences = preferences;
        String key = preferences.getString(PREFIX + "mode", Mode.BLUR.key);
        mode = Mode.BLUR;
        for (Mode candidate : Mode.values()) {
            if (candidate.key.equals(key)) {
                mode = candidate;
                break;
            }
        }
        for (Slider slider : Slider.values()) {
            values.put(slider, slider.clamp(preferences.getInt(PREFIX + slider.key, slider.defaultValue)));
        }
        values.put(Slider.LIFETIME_MAX, Math.max(get(Slider.LIFETIME_MIN), get(Slider.LIFETIME_MAX)));
        values.put(Slider.SLIDE_DISTANCE_MAX, Math.max(get(Slider.SLIDE_DISTANCE_MIN), get(Slider.SLIDE_DISTANCE_MAX)));
        for (Toggle toggle : Toggle.values()) {
            toggles.put(toggle, preferences.getBoolean(PREFIX + toggle.key, false));
        }
        for (Mode mode : Mode.values()) {
            EnumSet<Phase> disabled = EnumSet.noneOf(Phase.class);
            for (Phase phase : Phase.values()) {
                if (preferences.getBoolean(phase.preferenceKey(mode), false)) disabled.add(phase);
            }
            disabledPhases.put(mode, disabled);
        }
    }

    public Mode mode() {
        return mode;
    }

    public void setMode(Mode mode) {
        this.mode = mode;
        preferences.edit().putString(PREFIX + "mode", mode.key).apply();
    }

    public int get(Slider slider) {
        Integer value = values.get(slider);
        return value == null ? slider.defaultValue : value;
    }

    public void set(Slider slider, int value) {
        value = slider.clamp(value);
        if (get(slider) == value) return;
        values.put(slider, value);
        SharedPreferences.Editor editor = preferences.edit().putInt(PREFIX + slider.key, value);
        Slider other = slider.otherBound();
        if (other != null) {
            boolean isMin = slider == Slider.LIFETIME_MIN || slider == Slider.SLIDE_DISTANCE_MIN;
            int previous = get(other);
            if (isMin ? value > previous : value < previous) {
                values.put(other, value);
                editor.putInt(PREFIX + other.key, value);
            }
        }
        editor.apply();
    }

    public boolean isEnabled(Toggle toggle) {
        return Boolean.TRUE.equals(toggles.get(toggle));
    }

    public void setEnabled(Toggle toggle, boolean enabled) {
        toggles.put(toggle, enabled);
        preferences.edit().putBoolean(PREFIX + toggle.key, enabled).apply();
    }

    public boolean isDisabled(Phase phase) {
        EnumSet<Phase> disabled = disabledPhases.get(mode);
        return disabled != null && disabled.contains(phase);
    }

    public boolean isAppearanceDisabled() {
        return isDisabled(Phase.APPEARANCE)
                && (mode != Mode.SLIDE || isDisabled(Phase.BLUR_APPEARANCE));
    }

    public void setDisabled(Phase phase, boolean disabled) {
        EnumSet<Phase> phases = disabledPhases.get(mode);
        if (disabled) {
            phases.add(phase);
        } else {
            phases.remove(phase);
        }
        preferences.edit().putBoolean(phase.preferenceKey(mode), disabled).apply();
    }
}
