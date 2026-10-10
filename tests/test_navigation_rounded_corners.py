"""Exercise the transition corner setup across Android API versions."""
import unittest

from test_glass_blur import ROOT, method
from test_glass_chat_refresh import run_java


class NavigationRoundedCornersTest(unittest.TestCase):
    def test_legacy_fallback_and_modern_display_corners(self):
        source = (ROOT / 'org/telegram/ui/ActionBar/ActionBarLayout.java').read_text()
        setup = method(source, 'private void setupRoundedCorners(View view)')
        harness = r'''
public class NavigationCornersHarness {
    static void check(boolean value) { if (!value) throw new AssertionError(); }
    static int dp(int value) { return value * 2; }
    static class Build {
        static class VERSION { static int SDK_INT; }
        static class VERSION_CODES { static final int S = 31; }
    }
    static class RoundedCorner {
        static final int POSITION_TOP_LEFT = 0, POSITION_TOP_RIGHT = 1,
                POSITION_BOTTOM_RIGHT = 2, POSITION_BOTTOM_LEFT = 3;
        final int radius;
        RoundedCorner(int radius) { this.radius = radius; }
        int getRadius() { return radius; }
    }
    static class WindowInsets {
        RoundedCorner[] corners = new RoundedCorner[4];
        int reads;
        RoundedCorner getRoundedCorner(int position) {
            if (Build.VERSION.SDK_INT < 31) {
                throw new NoSuchMethodError("WindowInsets.getRoundedCorner");
            }
            reads++;
            return corners[position];
        }
    }
    static class Outline {
        float radius;
        void setRoundRect(int l, int t, int r, int b, float radius) {
            check(l == 0 && t == 0 && r == 1080 && b == 2400);
            this.radius = radius;
        }
    }
    static abstract class ViewOutlineProvider {
        public abstract void getOutline(View view, Outline outline);
    }
    static class View {
        ViewOutlineProvider provider;
        boolean clip;
        int getWidth() { return 1080; }
        int getHeight() { return 2400; }
        void setOutlineProvider(ViewOutlineProvider provider) { this.provider = provider; }
        void setClipToOutline(boolean value) { clip = value; }
    }
    static class Layout {
        WindowInsets insets = new WindowInsets();
        WindowInsets getRootWindowInsets() { return insets; }
        SETUP
    }
    static void checkRadius(View view, float expected) {
        check(view.clip && view.provider != null);
        Outline outline = new Outline();
        view.provider.getOutline(view, outline);
        check(outline.radius == expected);
    }
    public static void main(String[] args) {
        // Both entering and leaving the chat use this setup, including when
        // root insets already exist on the Android 11 device from the report.
        for (int api : new int[]{24, 29, 30, 31, 36}) {
            Build.VERSION.SDK_INT = api;
            Layout layout = new Layout();
            layout.insets.corners = new RoundedCorner[]{new RoundedCorner(10),
                    null, new RoundedCorner(42), new RoundedCorner(20)};
            for (int transition = 0; transition < 2; transition++) {
                View view = new View();
                layout.setupRoundedCorners(view);
                checkRadius(view, api < 31 ? dp(28) : 42);
            }
            check(layout.insets.reads == (api < 31 ? 0 : 8));
            layout.insets = null;
            View beforeInsets = new View();
            layout.setupRoundedCorners(beforeInsets);
            checkRadius(beforeInsets, dp(28));
            if (api >= 31) {
                layout.insets = new WindowInsets();
                View squareDisplay = new View();
                layout.setupRoundedCorners(squareDisplay);
                check(!squareDisplay.clip && squareDisplay.provider == null);
            }
        }
    }
}
'''.replace('SETUP', setup)
        run_java(self, 'NavigationCornersHarness', harness)


if __name__ == '__main__':
    unittest.main()
