package zxc.iconic.xenon.triangles;

import android.graphics.*;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.SystemClock;
import android.view.Choreographer;
import android.view.View;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ChatBackgroundDrawable;
import org.telegram.ui.Components.BackgroundGradientDrawable;
import org.telegram.ui.Components.MotionBackgroundDrawable;
import java.util.Random;
import static zxc.iconic.xenon.triangles.TriangleSettings.Flag.*;
import static zxc.iconic.xenon.triangles.TriangleSettings.Value.*;

/** Drawn once after the wallpaper, before messages. No view, bitmap or path allocation per particle/frame. */
public final class TriangleWallpaperRenderer {
    private final TriangleSettings settings;
    private final float densityOverride;
    private final Random random = new Random();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final Path[] outlineEdges = new Path[TriangleMath.MAX_PARTICLES * 3];
    private final float[] outlineSizes = new float[TriangleMath.MAX_PARTICLES];
    private float outlineThickness = -1;
    private final float[] outlineX = new float[3], outlineY = new float[3];
    private final Matrix outlineShaderMatrix = new Matrix();
    private final float[] x = new float[TriangleMath.MAX_PARTICLES], y = new float[TriangleMath.MAX_PARTICLES],
            sizeRandom = new float[TriangleMath.MAX_PARTICLES], speedRandom = new float[TriangleMath.MAX_PARTICLES],
            angle = new float[TriangleMath.MAX_PARTICLES], baseAngle = new float[TriangleMath.MAX_PARTICLES];
    private final float[] hsv = new float[3];
    private int width, height, count, shaderRevision = -1, wallpaperColor, lastSampleColor, lastFallbackColor;
    private Drawable sampledDrawable;
    private long lastSample;
    private final TriangleAnimationClock animationClock = new TriangleAnimationClock();
    private View animationHost;
    private boolean framePosted;
    private final Choreographer.FrameCallback frameCallback = this::onAnimationFrame;
    private int paletteTop,paletteBottom;
    /** Optional fixed surface palette for embedded effects such as replay Skip. */
    public void setGradientColors(int top,int bottom){if(paletteTop==top&&paletteBottom==bottom)return;paletteTop=top;paletteBottom=bottom;shaderRevision=-1;}
    private Shader shader;
    private boolean legacyFade;

    public TriangleWallpaperRenderer() {this(TriangleSettings.get(),0);}
    public TriangleWallpaperRenderer(TriangleSettings settings,float densityOverride) {
        this.settings=settings;this.densityOverride=densityOverride;
        for (int i = 0; i < x.length; i++) {
            sizeRandom[i] = random.nextFloat() * 2 - 1;
            speedRandom[i] = (float) Math.max(-1, Math.min(1, random.nextGaussian() * 0.5));
            baseAngle[i] = random.nextFloat() * 360;
        }
    }

    public void suspend() {
        if (framePosted) Choreographer.getInstance().removeFrameCallback(frameCallback);
        framePosted = false;
        animationHost = null;
        animationClock.reset();
    }

    private boolean animationEnabled() {
        return settings.flag(ENABLED) && settings.flag(ANIMATE)
                && (settings.value(SPEED) > 0 || settings.value(SPIN) != 0);
    }

    private void onAnimationFrame(long frameTimeNanos) {
        framePosted = false;
        View host = animationHost;
        if (host == null || !animationEnabled() || !host.isAttachedToWindow()
                || !host.isShown() || host.getWindowVisibility() != View.VISIBLE) {
            suspend();
            return;
        }
        float dt = animationClock.step(frameTimeNanos, settings.flag(SYNC_REFRESH) ? 0 : settings.value(FPS));
        if (dt >= 0) {
            advance(dt);
            host.invalidate();
        }
        framePosted = true;
        Choreographer.getInstance().postFrameCallback(frameCallback);
    }

    private void updateAnimation(View host, boolean active) {
        if (!active || !animationEnabled()) { suspend(); return; }
        animationHost = host;
        if (!framePosted) {
            framePosted = true;
            Choreographer.getInstance().postFrameCallback(frameCallback);
        }
    }

    private void advance(float dt) {
        float density = (densityOverride>0?densityOverride:AndroidUtilities.density);
        float direction = (float) Math.toRadians(settings.value(DIRECTION));
        float vx = (float) Math.cos(direction) * settings.value(SPEED) * density;
        float vy = (float) Math.sin(direction) * settings.value(SPEED) * density;
        float margin = settings.value(SIZE) * density * TriangleMath.sizeMultiplier(1, settings.value(SIZE_VARIANCE));
        for (int i = 0; i < count; i++) {
            float speed = TriangleMath.speedMultiplier(speedRandom[i], settings.value(SPEED_VARIANCE));
            x[i] += vx * dt * speed;
            y[i] += vy * dt * speed;
            if (x[i] < -margin) { x[i] = width + margin; y[i] = random.nextFloat() * height; }
            else if (x[i] > width + margin) { x[i] = -margin; y[i] = random.nextFloat() * height; }
            if (y[i] < -margin) { y[i] = height + margin; x[i] = random.nextFloat() * width; }
            else if (y[i] > height + margin) { y[i] = -margin; x[i] = random.nextFloat() * width; }
            angle[i] = (angle[i] + settings.value(SPIN) * dt) % 360;
        }
    }

    public void draw(Canvas canvas, View host, Drawable wallpaper, int fallbackColor, int w, int h, boolean active) {
        draw(canvas, host, wallpaper, fallbackColor, w, h, active, false);
    }

    /** Replay the same particles into blur sources without advancing or stopping their animation. */
    public void drawSnapshot(Canvas canvas, View host, Drawable wallpaper, int fallbackColor, int w, int h) {
        draw(canvas, host, wallpaper, fallbackColor, w, h, false, true);
    }

    private void draw(Canvas canvas, View host, Drawable wallpaper, int fallbackColor, int w, int h, boolean active, boolean snapshot) {
        if (!settings.flag(ENABLED) || w <= 0 || h <= 0) { if (!snapshot) suspend(); return; }
        final long now = SystemClock.uptimeMillis();
        final float density = (densityOverride>0?densityOverride:AndroidUtilities.density);
        int target = TriangleMath.count(w / density, settings.value(DENSITY));
        if (width != w || height != h) {
            width = w; height = h; count = 0; shaderRevision = -1;
        }
        for (int i = count; i < target; i++) {
            x[i] = random.nextFloat() * w;
            y[i] = random.nextFloat() * h;
        }
        count = target;
        boolean custom = settings.flag(CUSTOMIZE_COLOR);
        boolean automatic = !custom || settings.flag(AUTO_COLOR);
        Drawable source = wallpaper instanceof ChatBackgroundDrawable ? ((ChatBackgroundDrawable) wallpaper).getFullDrawable() : wallpaper;
        // Solid Monet/theme colours can mutate in place during a theme switch.
        // Reading their colour is cheap and must not wait for the photo-sampling interval.
        boolean livePalette = source instanceof ColorDrawable || source instanceof MotionBackgroundDrawable || source instanceof BackgroundGradientDrawable;
        if (automatic && (livePalette || sampledDrawable != source || fallbackColor != lastFallbackColor || now - lastSample > 2000 || lastSample == 0)) {
            wallpaperColor = sample(source, fallbackColor);
            sampledDrawable = source;
            lastSample = now;
            lastFallbackColor = fallbackColor;
        }
        if (shaderRevision != settings.revision || lastSampleColor != wallpaperColor) {
            rebuildShader(automatic, custom);
            if(paletteTop!=0)shader=new LinearGradient(0,0,0,height,paletteTop,paletteBottom,Shader.TileMode.CLAMP);
            shaderRevision = settings.revision;
            lastSampleColor = wallpaperColor;
        }
        if (!snapshot) updateAnimation(host, active);
        float baseSize = settings.value(SIZE) * density;
        boolean filled = settings.flag(FILLED);
        float thickness = settings.value(THICKNESS) * density / 10f;
        if (outlineThickness != thickness) {
            java.util.Arrays.fill(outlineSizes, 0);
            outlineThickness = thickness;
        }
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(shader);
        int save = canvas.save();
        canvas.clipRect(0, 0, w, h);
        for (int i = 0; i < count; i++) {
            float rotation = settings.value(ROTATION) + angle[i] + (settings.flag(RANDOM_ROTATION) ? baseAngle[i] : 0);
            float size = baseSize * TriangleMath.sizeMultiplier(sizeRandom[i], settings.value(SIZE_VARIANCE));
            float radius = size * 0.5773503f + (filled ? 0 : thickness);
            if (x[i] + radius < 0 || x[i] - radius > w || y[i] + radius < 0 || y[i] - radius > h) continue;
            float alpha = settings.value(OPACITY) / 100f;
            if (legacyFade) alpha *= fadeAt(y[i] / h);
            if (alpha <= 0) continue;
            paint.setAlpha(Math.round(alpha * 255));
            if (filled) {
                float radians = (float) Math.toRadians(rotation);
                float cos = (float) Math.cos(radians), sin = (float) Math.sin(radians);
                float th = size * 0.8660254f;
                path.rewind();
                vertex(x[i], y[i], 0, -th * 2 / 3, cos, sin, true);
                vertex(x[i], y[i], size / 2, th / 3, cos, sin, false);
                vertex(x[i], y[i], -size / 2, th / 3, cos, sin, false);
                path.close();
                canvas.drawPath(path, paint);
            } else {
                prepareOutline(i, size, thickness);
                int particleSave = canvas.save();
                canvas.translate(x[i], y[i]);
                canvas.rotate(rotation);
                // Keep the gradient and fade in wallpaper coordinates while geometry moves.
                outlineShaderMatrix.setRotate(-rotation);
                outlineShaderMatrix.preTranslate(-x[i], -y[i]);
                shader.setLocalMatrix(outlineShaderMatrix);
                // Immutable convex fills avoid stroking a large, changing path every frame.
                // The segments share only their boundaries, avoiding translucent edge overlap.
                for (int edge = 0; edge < 3; edge++) canvas.drawPath(outlineEdges[i * 3 + edge], paint);
                canvas.restoreToCount(particleSave);
            }
        }
        if (!filled) shader.setLocalMatrix(null);
        canvas.restoreToCount(save);
    }

    private void prepareOutline(int particle, float size, float thickness) {
        if (outlineSizes[particle] == size) return;
        outlineSizes[particle] = size;
        float outer = TriangleMath.outlineScale(size, thickness, true);
        float inner = TriangleMath.outlineScale(size, thickness, false);
        float th = size * 0.8660254f;
        outlineX[0] = 0; outlineY[0] = -th * 2 / 3;
        outlineX[1] = size / 2; outlineY[1] = th / 3;
        outlineX[2] = -size / 2; outlineY[2] = th / 3;
        for (int edge = 0; edge < 3; edge++) {
            int index = particle * 3 + edge, next = (edge + 1) % 3;
            Path segment = outlineEdges[index];
            if (segment == null) outlineEdges[index] = segment = new Path();
            segment.rewind();
            segment.moveTo(outlineX[edge] * outer, outlineY[edge] * outer);
            segment.lineTo(outlineX[next] * outer, outlineY[next] * outer);
            segment.lineTo(outlineX[next] * inner, outlineY[next] * inner);
            segment.lineTo(outlineX[edge] * inner, outlineY[edge] * inner);
            segment.close();
        }
    }

    private void vertex(float cx, float cy, float px, float py, float cos, float sin, boolean first) {
        float dx = cx + px * cos - py * sin, dy = cy + px * sin + py * cos;
        if (first) path.moveTo(dx, dy); else path.lineTo(dx, dy);
    }

    private float fadeAt(float fraction) {
        return TriangleMath.fade(fraction, settings.value(FADE_TOP) / 100f,
                settings.value(FADE_BOTTOM) / 100f, settings.value(FADE_POWER) / 100f);
    }

    private void rebuildShader(boolean automatic, boolean custom) {
        int first;
        if (automatic) first = TriangleMath.automaticColor(wallpaperColor);
        else first = Color.HSVToColor(new float[] { settings.value(HUE), settings.value(SATURATION) / 100f, settings.value(BRIGHTNESS) / 100f });
        int second = first;
        if (custom && settings.flag(GRADIENT)) {
            Color.colorToHSV(first, hsv);
            hsv[0] = (hsv[0] + settings.value(GRADIENT_HUE) + 360) % 360;
            hsv[2] = Math.max(0, hsv[2] * 0.65f);
            second = Color.HSVToColor(hsv);
        }
        double angle = Math.toRadians(settings.value(GRADIENT_ANGLE));
        float dx = (float) Math.cos(angle), dy = (float) Math.sin(angle);
        float extent = Math.max(1, (Math.abs(dx) * width + Math.abs(dy) * height) / 2);
        shader = new LinearGradient(width / 2f - dx * extent, height / 2f - dy * extent,
                width / 2f + dx * extent, height / 2f + dy * extent, first, second, Shader.TileMode.CLAMP);
        legacyFade = custom && settings.flag(FADE) && Build.VERSION.SDK_INT < 28;
        if (custom && settings.flag(FADE) && !legacyFade) {
            int[] colors = new int[33];
            for (int i = 0; i < colors.length; i++) colors[i] = (Math.round(fadeAt(i / 32f) * 255) << 24) | 0xffffff;
            Shader fade = new LinearGradient(0, 0, 0, height, colors, null, Shader.TileMode.CLAMP);
            shader = new ComposeShader(shader, fade, PorterDuff.Mode.DST_IN);
        }
    }

    private int sample(Drawable drawable, int fallback) {
        if (drawable instanceof ColorDrawable) return ((ColorDrawable) drawable).getColor();
        if (drawable instanceof MotionBackgroundDrawable) {
            int[] colors = ((MotionBackgroundDrawable) drawable).getColors();
            if (colors != null && colors.length > 0) return average(colors, fallback);
        }
        if (drawable instanceof BackgroundGradientDrawable) {
            int[] colors = ((BackgroundGradientDrawable) drawable).getColorsList();
            if (colors != null && colors.length > 0) return average(colors, fallback);
        }
        Bitmap bitmap = drawable instanceof BitmapDrawable ? ((BitmapDrawable) drawable).getBitmap()
                : drawable instanceof MotionBackgroundDrawable ? ((MotionBackgroundDrawable) drawable).getBitmap() : null;
        if (bitmap == null || bitmap.isRecycled() || (Build.VERSION.SDK_INT >= 26 && bitmap.getConfig() == Bitmap.Config.HARDWARE)) return fallback;
        try {
            long r = 0, g = 0, b = 0;
            for (int iy = 0; iy < 8; iy++) for (int ix = 0; ix < 8; ix++) {
                int c = bitmap.getPixel(Math.min(bitmap.getWidth() - 1, bitmap.getWidth() * (2 * ix + 1) / 16),
                        Math.min(bitmap.getHeight() - 1, bitmap.getHeight() * (2 * iy + 1) / 16));
                r += Color.red(c); g += Color.green(c); b += Color.blue(c);
            }
            return Color.rgb((int) (r / 64), (int) (g / 64), (int) (b / 64));
        } catch (RuntimeException ignored) { return fallback; }
    }

    private int average(int[] colors, int fallback) {
        int result = fallback, count = 0;
        for (int color : colors) {
            if (Color.alpha(color) != 0) result = TriangleMath.mix(result, color, 1f / ++count);
        }
        return result;
    }
}
