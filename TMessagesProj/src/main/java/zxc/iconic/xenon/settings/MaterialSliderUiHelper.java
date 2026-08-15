package zxc.iconic.xenon.settings;

import android.content.Context;
import android.content.res.ColorStateList;

import androidx.appcompat.view.ContextThemeWrapper;

import com.google.android.material.R;
import com.google.android.material.slider.Slider;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.Theme;

public class MaterialSliderUiHelper {

    public static Slider create(Context context) {
        Slider slider = new Slider(new ContextThemeWrapper(context, R.style.Theme_Material3_DayNight));
        slider.setTrackHeight(AndroidUtilities.dp(8.0f));
        slider.setThumbHeight(AndroidUtilities.dp(24.0f));
        slider.setThumbWidth(AndroidUtilities.dp(3.0f));
        slider.setTrackStopIndicatorSize(0);
        slider.setHaloRadius(0);
        slider.setLabelBehavior(2);
        return slider;
    }

    public static void applyContinuousStyle(Slider slider) {
        if (slider.getTickVisibilityMode() != 2) {
            slider.setTickVisibilityMode(2);
        }
    }

    public static void applyDiscreteStyle(Slider slider, int count) {
        if (slider.getTickVisibilityMode() != 0) {
            slider.setTickVisibilityMode(0);
        }
        if (slider.getTickActiveRadius() != AndroidUtilities.dp(2.0f)) {
            slider.setTickActiveRadius(AndroidUtilities.dp(2.0f));
        }
        if (slider.getTickInactiveRadius() != AndroidUtilities.dp(2.0f)) {
            slider.setTickInactiveRadius(AndroidUtilities.dp(2.0f));
        }
    }

    public static void applyColors(Slider slider, int active, int inactive) {
        if (!hasColor(slider.getTrackActiveTintList(), active)) {
            slider.setTrackActiveTintList(ColorStateList.valueOf(active));
        }
        if (!hasColor(slider.getThumbTintList(), active)) {
            slider.setThumbTintList(ColorStateList.valueOf(active));
        }
        if (!hasColor(slider.getTrackInactiveTintList(), inactive)) {
            slider.setTrackInactiveTintList(ColorStateList.valueOf(inactive));
        }
    }

    public static void applyThemeColors(Slider slider) {
        applyColors(slider,
                Theme.getColor(Theme.key_player_progress),
                Theme.getColor(Theme.key_player_progressBackground));
    }

    private static boolean hasColor(ColorStateList colorStateList, int color) {
        return colorStateList != null && colorStateList.getDefaultColor() == color;
    }
}
