package zxc.iconic.xenon.settings;

public final class BlurIntensity {
    private BlurIntensity() {}

    private static int interpolate(int value, int[] x, int[] y) {
        if (value <= x[0]) return y[0];
        for (int i = 1; i < x.length; i++) {
            if (value <= x[i]) return Math.round(y[i - 1] + (y[i] - y[i - 1])
                    * (value - x[i - 1]) / (float) (x[i] - x[i - 1]));
        }
        return y[y.length - 1];
    }

    public static int samples(int blur) {
        return interpolate(blur, new int[]{0, 3, 10, 15, 20, 30, 40},
                new int[]{3, 9, 15, 15, 17, 21, 25});
    }

    public static int headerPixelation(int blur) {
        return interpolate(blur, new int[]{0, 10, 15, 40}, new int[]{1, 1, 2, 2});
    }

    public static int sheetPixelation(int blur) {
        return interpolate(blur, new int[]{2, 5, 10, 20}, new int[]{0, 30, 75, 100});
    }
}
