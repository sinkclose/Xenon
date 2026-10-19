package zxc.iconic.xenon.triangles;

import android.content.SharedPreferences;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.R;

/** One persisted global wallpaper effect; changes are visible to chats and the settings background. */
public final class TriangleSettings {
    public enum Value {
        SIZE("size", R.string.TriangleSize, 5, 600, 100),
        DENSITY("density", R.string.TriangleDensity, 1, 500, 200),
        SPEED("speed", R.string.TriangleSpeed, 0, 300, 25),
        THICKNESS("thickness", R.string.TriangleThickness, 1, 100, 10),
        OPACITY("opacity", R.string.TriangleOpacity, 0, 100, 24),
        SIZE_VARIANCE("size_variance", R.string.TriangleSizeVariance, 0, 200, 0),
        SPEED_VARIANCE("speed_variance", R.string.TriangleSpeedVariance, 0, 200, 32),
        DIRECTION("direction", R.string.TriangleDirection, 0, 360, 270),
        ROTATION("rotation", R.string.TriangleRotation, 0, 360, 0),
        SPIN("spin", R.string.TriangleSpin, -180, 180, 0),
        FPS("fps", R.string.TriangleFps, 10, 240, 60),
        HUE("hue", R.string.TriangleHue, 0, 360, 330),
        SATURATION("saturation", R.string.TriangleSaturation, 0, 100, 65),
        BRIGHTNESS("brightness", R.string.TriangleBrightness, 0, 100, 80),
        GRADIENT_HUE("gradient_hue", R.string.TriangleGradientHue, -180, 180, 30),
        GRADIENT_ANGLE("gradient_angle", R.string.TriangleGradientAngle, 0, 360, 90),
        FADE_TOP("fade_top", R.string.TriangleFadeTop, 0, 200, 100),
        FADE_BOTTOM("fade_bottom", R.string.TriangleFadeBottom, 0, 200, 0),
        FADE_POWER("fade_power", R.string.TriangleFadePower, 10, 500, 150);

        public final String key;
        public final int title, min, max, initial;
        Value(String key, int title, int min, int max, int initial) {
            this.key = key; this.title = title; this.min = min; this.max = max; this.initial = initial;
        }
    }

    public enum Flag {
        ENABLED("enabled", R.string.EnableTriangles, false),
        ANIMATE("animate", R.string.TriangleAnimate, true),
        RANDOM_ROTATION("random_rotation", R.string.TriangleRandomRotation, false),
        FILLED("filled", R.string.TriangleFilled, false),
        CUSTOMIZE_COLOR("customize_color", R.string.TriangleCustomizeColor, true),
        AUTO_COLOR("auto_color", R.string.TriangleAutoColor, true),
        GRADIENT("gradient", R.string.TriangleGradient, false),
        FADE("fade", R.string.TriangleFade, true),
        SYNC_REFRESH("sync_refresh", R.string.TriangleSyncRefresh, true);

        public final String key;
        public final int title;
        public final boolean initial;
        Flag(String key, int title, boolean initial) {
            this.key = key; this.title = title; this.initial = initial;
        }
    }

    private static TriangleSettings instance;
    private static final String PREFIX = "triangles_";
    private final SharedPreferences prefs;
    private final int[] values = new int[Value.values().length];
    private final boolean[] flags = new boolean[Flag.values().length];
    public int revision;

    public static TriangleSettings get() {
        if (instance == null) instance = new TriangleSettings();
        return instance;
    }

    private TriangleSettings() {
        prefs = ApplicationLoader.applicationContext.getSharedPreferences("nekoconfig", 0);
        SharedPreferences legacy = ApplicationLoader.applicationContext.getSharedPreferences("triangle_wallpaper", 0);
        if (!legacy.getAll().isEmpty()) {
            SharedPreferences.Editor editor = prefs.edit();
            for (Value v : Value.values()) {
                if (!prefs.contains(PREFIX + v.key) && legacy.contains(v.key))
                    editor.putInt(PREFIX + v.key, legacy.getInt(v.key, v.initial));
            }
            for (Flag f : Flag.values()) {
                if (!prefs.contains(PREFIX + f.key) && legacy.contains(f.key))
                    editor.putBoolean(PREFIX + f.key, legacy.getBoolean(f.key, f.initial));
            }
            // Retire the old store only after the migrated configuration is durable.
            if (editor.commit()) legacy.edit().clear().apply();
        }
        reload();
    }

    private void reload() {
        for (Value v : Value.values()) values[v.ordinal()] = (int) TriangleMath.clamp(prefs.getInt(PREFIX + v.key, v.initial), v.min, v.max);
        for (Flag f : Flag.values()) flags[f.ordinal()] = prefs.getBoolean(PREFIX + f.key, f.initial);
        revision++;
    }

    /** Imports replace the backing preferences; refresh renderers without restarting the process. */
    public static void reloadFromConfig() {
        // An explicit import wins even if the effect has never been opened in this process.
        ApplicationLoader.applicationContext.getSharedPreferences("triangle_wallpaper", 0).edit().clear().apply();
        if (instance != null) instance.reload();
    }

    public int value(Value v) { return values[v.ordinal()]; }
    public boolean flag(Flag f) { return flags[f.ordinal()]; }
    public void set(Value v, int value) {
        value = (int) TriangleMath.clamp(value, v.min, v.max);
        if (values[v.ordinal()] == value) return;
        values[v.ordinal()] = value; revision++;
        prefs.edit().putInt(PREFIX + v.key, value).apply();
    }
    public void toggle(Flag f) {
        flags[f.ordinal()] = !flag(f); revision++;
        prefs.edit().putBoolean(PREFIX + f.key, flag(f)).apply();
    }
    public void reset() {
        for (Value v : Value.values()) values[v.ordinal()] = v.initial;
        for (Flag f : Flag.values()) flags[f.ordinal()] = f.initial;
        SharedPreferences.Editor editor = prefs.edit();
        for (Value v : Value.values()) editor.remove(PREFIX + v.key);
        for (Flag f : Flag.values()) editor.remove(PREFIX + f.key);
        editor.apply(); revision++;
    }
}
