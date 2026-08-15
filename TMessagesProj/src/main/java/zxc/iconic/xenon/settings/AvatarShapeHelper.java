package zxc.iconic.xenon.settings;

import android.graphics.Matrix;
import android.graphics.Path;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.ShapeDrawable;
import android.graphics.drawable.shapes.PathShape;

import androidx.graphics.shapes.RoundedPolygon;
import androidx.graphics.shapes.Shapes_androidKt;

import com.google.android.material.shape.MaterialShapes;

public final class AvatarShapeHelper {

    public static final RoundedPolygon[] SHAPES = {
            MaterialShapes.CIRCLE, MaterialShapes.SQUARE, MaterialShapes.SLANTED_SQUARE,
            MaterialShapes.ARCH, MaterialShapes.FAN, MaterialShapes.ARROW,
            MaterialShapes.SEMI_CIRCLE, MaterialShapes.OVAL, MaterialShapes.PILL,
            MaterialShapes.TRIANGLE, MaterialShapes.DIAMOND, MaterialShapes.CLAM_SHELL,
            MaterialShapes.PENTAGON, MaterialShapes.GEM, MaterialShapes.SUNNY,
            MaterialShapes.VERY_SUNNY, MaterialShapes.COOKIE_4, MaterialShapes.COOKIE_6,
            MaterialShapes.COOKIE_7, MaterialShapes.COOKIE_9, MaterialShapes.COOKIE_12,
            MaterialShapes.GHOSTISH, MaterialShapes.CLOVER_4, MaterialShapes.CLOVER_8,
            MaterialShapes.BURST, MaterialShapes.SOFT_BURST, MaterialShapes.BOOM,
            MaterialShapes.SOFT_BOOM, MaterialShapes.FLOWER, MaterialShapes.PUFFY,
            MaterialShapes.PUFFY_DIAMOND, MaterialShapes.PIXEL_CIRCLE, MaterialShapes.PIXEL_TRIANGLE,
            MaterialShapes.BUN, MaterialShapes.HEART,
    };

    public static final String[] SHAPE_NAMES = {
            "Circle", "Square", "Slanted Square",
            "Arch", "Fan", "Arrow",
            "Semi Circle", "Oval", "Pill",
            "Triangle", "Diamond", "Clam Shell",
            "Pentagon", "Gem", "Sunny",
            "Very Sunny", "Cookie 4", "Cookie 6",
            "Cookie 7", "Cookie 9", "Cookie 12",
            "Ghostish", "Clover 4", "Clover 8",
            "Burst", "Soft Burst", "Boom",
            "Soft Boom", "Flower", "Puffy",
            "Puffy Diamond", "Pixel Circle", "Pixel Triangle",
            "Bun", "Heart",
    };

    private AvatarShapeHelper() {
    }

    public static int shapeCount() {
        return SHAPES.length;
    }

    public static RoundedPolygon shapeAt(int index) {
        if (index < 0 || index >= SHAPES.length) return MaterialShapes.CIRCLE;
        return SHAPES[index];
    }

    public static String nameAt(int index) {
        if (index < 0 || index >= SHAPE_NAMES.length) return "Circle";
        return SHAPE_NAMES[index];
    }

    public static Path pathForShape(int index, float size) {
        RoundedPolygon polygon = shapeAt(index);
        Path path = Shapes_androidKt.toPath(polygon);
        float[] bounds = polygon.calculateBounds();
        float polyW = bounds[2] - bounds[0];
        float polyH = bounds[3] - bounds[1];
        float scale = size / Math.max(polyW, polyH);
        Matrix m = new Matrix();
        m.postTranslate(-bounds[0], -bounds[1]);
        m.postScale(scale, scale);
        m.postTranslate((size - polyW * scale) / 2f, (size - polyH * scale) / 2f);
        path.transform(m);
        return path;
    }

    public static Drawable iconForShape(int index, int sizePx, int color) {
        Path path = pathForShape(index, sizePx);
        PathShape shape = new PathShape(path, sizePx, sizePx);
        ShapeDrawable drawable = new ShapeDrawable(shape);
        drawable.getPaint().setColor(color);
        drawable.setIntrinsicWidth(sizePx);
        drawable.setIntrinsicHeight(sizePx);
        return drawable;
    }
}
