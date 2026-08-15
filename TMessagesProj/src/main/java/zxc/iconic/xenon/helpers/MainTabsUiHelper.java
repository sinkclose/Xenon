package zxc.iconic.xenon.helpers;

import android.graphics.RectF;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.Theme;

public class MainTabsUiHelper {

    public static boolean isMaterial3NavigationBar() {
        return zxc.iconic.xenon.NekoConfig.material3BottomNavigationBar;
    }

    public static int getTabsViewHeightDp() {
        return isMaterial3NavigationBar() ? 64 : 72;
    }

    public static int getAdditionalNavigationBarHeight(boolean hasMainTabs) {
        if (!hasMainTabs || !isMaterial3NavigationBar()) {
            return 0;
        }
        return AndroidUtilities.dp(getTabsViewHeightDp());
    }

    public static float getBackgroundRadius() {
        if (isMaterial3NavigationBar()) {
            return 0;
        }
        return AndroidUtilities.dp(28);
    }

    public static int getBackgroundInset() {
        if (isMaterial3NavigationBar()) {
            return 0;
        }
        return AndroidUtilities.dp(7.666f);
    }

    public static float getMaterial3MainTabIconTopDp() {
        return 10.0f;
    }

    public static float getMaterial3MainTabAvatarTopDp() {
        return getMaterial3MainTabIconTopDp() + 1.0f;
    }

    public static void applyTabSelectedIndicatorColor(android.graphics.Paint paint, int colorSelected, float interpolation) {
        paint.setColor(org.telegram.ui.ActionBar.Theme.multAlpha(colorSelected, interpolation * 0.125f));
    }

    public static void setTabSelectedIndicatorBounds(RectF rectF, float width, float height) {
        float minWidth = Math.min(AndroidUtilities.dp(56), Math.max(0, width - AndroidUtilities.dp(8)));
        float maxHeight = Math.min(AndroidUtilities.dp(32), height);
        float left = (width - minWidth) / 2f;
        float top = AndroidUtilities.dp(6);
        rectF.set(left, top, minWidth + left, maxHeight + top);
    }

    public static void setMaterial3MainTabSelectedV2(me.vkryl.android.animator.BoolAnimator isSelectedAnimator, me.vkryl.android.animator.BoolAnimator selectedIndicatorAlphaAnimator, boolean selected, boolean animated) {
        isSelectedAnimator.setValue(selected, animated);
        selectedIndicatorAlphaAnimator.setDuration(selected ? 100L : 200L);
        selectedIndicatorAlphaAnimator.setInterpolator(selected ? org.telegram.ui.Components.CubicBezierInterpolator.Emphasized : org.telegram.ui.Components.CubicBezierInterpolator.EmphasizedAccelerate);
        selectedIndicatorAlphaAnimator.setValue(selected, animated);
    }

    public static float getMainTabCounterCenterY(boolean m3) {
        return m3 ? AndroidUtilities.dp(getMaterial3MainTabIconTopDp() + 6) : AndroidUtilities.dpf2(10f);
    }

    public static void applyMaterial3MainTabStyle(TextView textView) {
        textView.setIncludeFontPadding(false);
        textView.setLetterSpacing(0.04166667f);
        textView.setPadding(0, 0, 0, 0);
        ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) textView.getLayoutParams();
        lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
        lp.height = AndroidUtilities.dp(16);
        lp.topMargin = AndroidUtilities.dp(42);
        lp.leftMargin = 0;
        lp.rightMargin = 0;
        textView.setLayoutParams(lp);
    }

    public static float getSelectedBackgroundScaleX(boolean m3, float selectedFactor) {
        return lerp(m3 ? 0.4f : 0.6f, 1.0f, selectedFactor);
    }

    public static float getSelectedBackgroundScaleY(boolean m3, float selectedFactor) {
        if (m3) return 1.0f;
        return getSelectedBackgroundScaleX(false, selectedFactor);
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }
}