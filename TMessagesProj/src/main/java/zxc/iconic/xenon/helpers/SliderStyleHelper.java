package zxc.iconic.xenon.helpers;

import android.view.View;
import android.view.ViewGroup;
import org.telegram.ui.Components.SeekBarView;
import zxc.iconic.xenon.NekoConfig;
import zxc.iconic.xenon.settings.AltSeekbar;

public final class SliderStyleHelper {
    private SliderStyleHelper() {}
    public static int style() { return NekoConfig.iosSliders ? 2 : NekoConfig.materialSliders ? 1 : 0; }
    public static void invalidateAll(View view) {
        if (view instanceof AltSeekbar) ((AltSeekbar) view).updateStyle();
        if (view instanceof SeekBarView) ((SeekBarView) view).updateStyle();
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) invalidateAll(group.getChildAt(i));
        }
    }
}
