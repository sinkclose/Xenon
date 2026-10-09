"""Exercise story header positioning throughout slow collapse and expansion."""

import subprocess
import tempfile
import unittest
from pathlib import Path

from test_glass_blur import ROOT, method


class StoryHeaderTest(unittest.TestCase):
    def test_titles_clear_moving_stories(self):
        source = (ROOT / "org/telegram/ui/Stories/DialogStoriesCell.java").read_text()
        positioning = source[source.index("            float textWidth = titleView.getDrawable().getCurrentWidth();"):
                             source.index("            subtitleOverlayContainer.setTranslationX(titleView.getTranslationX());")]
        action_bar = (ROOT / "org/telegram/ui/ActionBar/ActionBar.java").read_text()
        animated = (ROOT / "org/telegram/ui/Components/AnimatedFloat.java").read_text()
        animation_methods = "\n".join(method(animated, signature) for signature in (
            "public float set(float mustBe, boolean force)", "public float getValue()"))
        harness = r'''
public class StoryHeaderHarness {
    static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    static int dp(float value) { return Math.round(value); }
    static float dpf2(float value) { return value; }
    static float lerp(float a, float b, float p) { return a + (b - a) * p; }
    static class AndroidUtilities {
        static int dp(float value) { return Math.round(value); }
        static float lerp(float a, float b, float p) { return a + (b - a) * p; }
    }
    static class MathUtils { static float clamp(float v, float min, float max) { return Math.max(min, Math.min(max, v)); } }
    static class SystemClock { static long time; static long elapsedRealtime() { return time; } }
    static class View { void invalidate() {} }
    interface TimeInterpolator { float getInterpolation(float progress); }
    static class AnimatedFloat {
        float value, targetValue, startValue;
        boolean firstSet = true, transition;
        long transitionDuration = 300, transitionDelay, transitionStart;
        TimeInterpolator transitionInterpolator = p -> 1f - (float) Math.pow(1f - p, 5);
        View parent; Runnable invalidate;
        float set(float v) { return set(v, false); }
        ANIMATION_METHODS
    }
    static class NekoConfig { static boolean centerTitle; }
    static class TextView {
        float width, x, y, scaleX = 1f, alpha = 1f;
        TextView(float width) { this.width = width; }
        TextView getDrawable() { return this; }
        float getCurrentWidth() { return width; }
        int getMeasuredHeight() { return 40; }
        int getTextHeight() { return 24; }
        float getScaleX() { return scaleX; }
        float getAlpha() { return alpha; }
        float getTranslationX() { return x; }
        void setScaleX(float value) { scaleX = value; }
        void setScaleY(float value) {}
        void setTranslationX(float value) { x = value; }
        void setTranslationY(float value) { y = value; }
        void setRightPadding(float value) {}
    }
    static class Menu { float getVisibleItemsMeasuredWidthWithAlpha() { return 96; } }
    static class ActionBar {
        Menu menu = new Menu();
        int width = 360;
        int getWidth() { return width; }
        float getTitleRightBoundary() { return width - 96; }
        CENTER_METHOD
        AVAILABLE_METHOD
    }
    static class Visibility {
        boolean animating;
        float getTotalVisibility() { return 0f; }
        boolean isAnimating() { return animating; }
    }
    static class Header {
        static final int FAKE_TOP_PADDING = 4;
        TextView titleView = new TextView(100), telegramLogoView = new TextView(110), emojiStatusView = new TextView(26);
        AnimatedFloat centeredTitlePosition = new AnimatedFloat(), centeredLogoPosition = new AnimatedFloat(), titleFitScale = new AnimatedFloat();
        ActionBar actionBar = new ActionBar();
        Visibility subtitleOverlayContainer = new Visibility(), animatorHasTitleText = new Visibility();
        void position(float lastViewRight) {
            float bottomY = 0f, progress = 0.5f, offset, translationOffset = 0f;
            POSITIONING
        }
    }
    public static void main(String[] args) {
        for (boolean centered : new boolean[]{true, false}) {
            NekoConfig.centerTitle = centered;
            for (int width : new int[]{320, 360, 600}) {
                Header h = new Header(); h.actionBar.width = width;
                // Fully collapsed, then slowly expanding; stop and reverse mid-gesture.
                h.position(80f);
                for (int frame = 0; frame < 240; frame++) {
                    SystemClock.time += 16;
                    float edge = 80f + (frame < 120 ? frame : 240 - frame) * 0.8f;
                    h.animatorHasTitleText.animating = frame >= 90 && frame < 100;
                    h.position(edge);
                    check(h.titleView.x >= edge + (centered ? 8f : 0f), "Overlay title overlaps stories during animation");
                    check(h.telegramLogoView.x >= edge + (centered ? 8f : 1f), "Client name overlaps stories during animation");
                }
                SystemClock.time += 1000;
                h.position(80f);
                SystemClock.time += 1000;
                h.position(80f);
                float expected = centered ? h.actionBar.getCenteredTitlePosition(80, 110) - 55 : 81;
                check(Math.abs(h.telegramLogoView.x - expected) < .001f, "Settled title must retain its normal position");
            }
        }
    }
}
'''.replace("POSITIONING", positioning.replace("zxc.iconic.xenon.NekoConfig", "NekoConfig"))
        harness = harness.replace("ANIMATION_METHODS", animation_methods)
        harness = harness.replace("CENTER_METHOD", method(action_bar, "public float getCenteredTitlePosition("))
        harness = harness.replace("AVAILABLE_METHOD", method(action_bar, "public float getTitleAvailableWidth("))
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "StoryHeaderHarness.java"
            path.write_text(harness)
            compiled = subprocess.run(["java", "-m", "jdk.compiler/com.sun.tools.javac.Main", str(path)],
                                      capture_output=True, text=True)
            self.assertEqual(0, compiled.returncode, compiled.stderr)
            result = subprocess.run(["java", "-cp", directory, "StoryHeaderHarness"], capture_output=True, text=True)
            self.assertEqual(0, result.returncode, result.stderr)


if __name__ == "__main__":
    unittest.main()
