"""Verify actual folder background drawing geometry without an Android SDK."""
import subprocess
import tempfile
import unittest
from pathlib import Path

from test_glass_blur import ROOT, method


class FolderBackgroundTest(unittest.TestCase):
    def test_narrow_tabs_and_continuous_selection(self):
        source = (ROOT / 'zxc/iconic/xenon/helpers/Md3FilterTabsHelper.java').read_text()
        methods = '\n'.join(method(source, signature) for signature in (
            'public static boolean isEnabled()', 'public static void drawTabBackground('))
        harness = r'''
public class FolderBackgroundHarness {
    static void check(boolean condition) { if (!condition) throw new AssertionError(); }
    static class AndroidUtilities { static int dp(int n) { return n; } }
    static class NekoConfig { static boolean md3Folders = true; }
    static class ColorUtils {
        static int blendARGB(int a, int b, float progress) { return Math.round(a + (b - a) * progress); }
    }
    static class Paint {
        static final int ANTI_ALIAS_FLAG = 1;
        enum Style { FILL }
        int color;
        Paint(int flags) {}
        void setColor(int c) { color = c; }
        void setAlpha(int a) { check(a == 255); }
    }
    static class RectF {
        float left, top, right, bottom;
        void set(float l, float t, float r, float b) { left = l; top = t; right = r; bottom = b; }
    }
    static class Path {
        enum Direction { CW }
        void rewind() {}
        void addRoundRect(RectF rect, float[] radii, Direction direction) {
            float w = rect.right - rect.left, h = rect.bottom - rect.top;
            check(w > 0 && h > 0);
            // All four edges must fit directly, without Path's global radius scaling.
            check(radii[0] + radii[2] <= w + .0001f);
            check(radii[6] + radii[4] <= w + .0001f);
            check(radii[1] + radii[7] <= h + .0001f);
            check(radii[3] + radii[5] <= h + .0001f);
            for (float r : radii) check(r >= 0 && Float.isFinite(r));
        }
    }
    static class Canvas {
        int draws, color;
        void drawPath(Path p, Paint paint) { draws++; color = paint.color; }
    }
    static final Paint backgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    static final Path path = new Path();
    static final RectF rectF = new RectF();
    static final float[] radii = new float[8];
    METHODS
    public static void main(String[] args) {
        Canvas canvas = new Canvas();
        // Cover text-only narrow tabs, icons, mixed titles and stretched tabs.
        for (int w : new int[]{3, 16, 24, 32, 40, 48, 80, 140}) {
            for (int h : new int[]{36, 41}) {
                for (int position = 0; position < 3; position++) {
                    float previousRadius = -1;
                    for (int i = 0; i <= 100; i++) {
                        drawTabBackground(canvas, w, h, position, 3, i / 100f, 240, 20, 40);
                        check(radii[0] >= previousRadius);
                        previousRadius = radii[0];
                    }
                    for (float r : radii) check(Math.abs(r - radii[0]) < .0001f);
                    check(canvas.color == 240);
                }
            }
        }
        drawTabBackground(canvas, 100, 41, 1, 3, .49f, 240, 20, 40);
        int before = canvas.color;
        drawTabBackground(canvas, 100, 41, 1, 3, .51f, 240, 20, 40);
        check(Math.abs(canvas.color - before) < 10);
        drawTabBackground(canvas, 100, 41, 1, 3, -1, 240, 20, 40);
        check(radii[0] == 7);
        drawTabBackground(canvas, 100, 41, 1, 3, 2, 240, 20, 40);
        check(radii[0] == 19.5f);
        int draws = canvas.draws;
        drawTabBackground(canvas, 2, 41, 0, 3, 1, 240, 20, 40);
        check(canvas.draws == draws);
        NekoConfig.md3Folders = false;
        drawTabBackground(canvas, 100, 41, 0, 3, 1, 240, 20, 40);
        check(canvas.draws == draws);
    }
}
'''.replace('METHODS', methods)
        with tempfile.TemporaryDirectory(prefix='folder-background-') as directory:
            java = Path(directory) / 'FolderBackgroundHarness.java'
            java.write_text(harness)
            result = subprocess.run(['java', '-m', 'jdk.compiler/com.sun.tools.javac.Main', str(java)], capture_output=True, text=True)
            self.assertEqual(0, result.returncode, result.stderr)
            result = subprocess.run(['java', '-cp', directory, 'FolderBackgroundHarness'], capture_output=True, text=True)
            self.assertEqual(0, result.returncode, result.stderr)


if __name__ == '__main__':
    unittest.main()
