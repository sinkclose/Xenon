package zxc.iconic.xenon.helpers;

import android.graphics.RectF;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.core.graphics.ColorUtils;

import com.google.android.material.color.utilities.Hct;
import com.google.android.material.color.utilities.MaterialDynamicColors;
import com.google.android.material.color.utilities.SchemeTonalSpot;

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
        paint.setColor(Theme.multAlpha(colorSelected, Math.max(0f, Math.min(1f, interpolation))));
    }

    public static void setTabSelectedIndicatorBounds(RectF rectF, float width, float height) {
        setNavigationIndicatorBounds(rectF, width, height, false, true, 0f);
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
        return m3 ? Math.max(0f, selectedFactor) : lerp(0.6f, 1.0f, selectedFactor);
    }

    public static float getSelectedBackgroundScaleY(boolean m3, float selectedFactor) {
        if (m3) return 1.0f;
        return getSelectedBackgroundScaleX(false, selectedFactor);
    }

    // ShortNavigationBar / NavigationBarTokens from AndroidX Material 3.
    public static boolean useHorizontalItems(int availableWidth) {
        return availableWidth >= AndroidUtilities.dp(600);
    }

    public static void setNavigationIndicatorBounds(RectF rect, float width, float height,
            boolean horizontal, boolean showTitle, float labelWidth) {
        float indicatorWidth = horizontal && showTitle
                ? AndroidUtilities.dp(24 + 4 + 32) + labelWidth : AndroidUtilities.dp(56);
        indicatorWidth = Math.min(indicatorWidth, Math.max(0f, width));
        float indicatorHeight = Math.min(AndroidUtilities.dp(horizontal ? 40 : 32), height);
        float top = horizontal || !showTitle ? (height - indicatorHeight) / 2f : AndroidUtilities.dp(6);
        rect.set((width - indicatorWidth) / 2f, top, (width + indicatorWidth) / 2f, top + indicatorHeight);
    }

    public static final class NavigationColors {
        public final int surface, indicator, icon, label, inactive;

        private NavigationColors(int seed, boolean dark) {
            SchemeTonalSpot scheme = new SchemeTonalSpot(Hct.fromInt(seed), dark, 0.0);
            MaterialDynamicColors roles = new MaterialDynamicColors();
            surface = roles.surfaceContainer().getArgb(scheme);
            indicator = roles.secondaryContainer().getArgb(scheme);
            icon = roles.onSecondaryContainer().getArgb(scheme);
            label = roles.secondary().getArgb(scheme);
            inactive = roles.onSurfaceVariant().getArgb(scheme);
        }
    }

    private static int lastSeed;
    private static boolean lastDark;
    private static NavigationColors cachedColors;

    public static NavigationColors getNavigationColors(Theme.ResourcesProvider provider) {
        int seed = Theme.getColor(Theme.key_glass_tabSelected, provider);
        boolean dark = ColorUtils.calculateLuminance(Theme.getColor(Theme.key_windowBackgroundWhite, provider)) < 0.5;
        if (cachedColors == null || lastSeed != seed || lastDark != dark) {
            lastSeed = seed;
            lastDark = dark;
            cachedColors = new NavigationColors(seed, dark);
        }
        return cachedColors;
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }
}