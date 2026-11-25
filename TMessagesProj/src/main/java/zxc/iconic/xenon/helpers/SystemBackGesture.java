package zxc.iconic.xenon.helpers;

import android.graphics.Insets;
import android.os.Build;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;

import zxc.iconic.xenon.NekoConfig;

public final class SystemBackGesture {
    private SystemBackGesture() {}

    /** Whether this touch starts in a horizontal system gesture inset. */
    public static boolean startsAtEdge(View view, MotionEvent event) {
        if (Build.VERSION.SDK_INT < 34 || !NekoConfig.predictiveBackAnimation) return false;
        WindowInsets insets = view.getRootWindowInsets();
        if (insets == null) return false;
        Insets gestures = insets.getInsets(WindowInsets.Type.systemGestures());
        if (gestures.left == 0 && gestures.right == 0) return false;

        View root = view.getRootView();
        int[] location = new int[2];
        root.getLocationOnScreen(location);
        float x = event.getRawX() - location[0];
        return (gestures.left > 0 && x < gestures.left)
                || (gestures.right > 0 && x >= root.getWidth() - gestures.right);
    }
}
