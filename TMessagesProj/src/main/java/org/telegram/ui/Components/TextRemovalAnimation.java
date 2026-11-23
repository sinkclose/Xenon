package org.telegram.ui.Components;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.SystemClock;
import android.text.Layout;
import android.view.View;
import android.view.WindowManager;

import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import org.telegram.messenger.AndroidUtilities;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Random;

import zxc.iconic.xenon.NekoConfig;
import zxc.iconic.xenon.TextRemovalAnimationSettings;
import zxc.iconic.xenon.TextRemovalAnimationSettings.Phase;
import zxc.iconic.xenon.TextRemovalAnimationSettings.Slider;
import zxc.iconic.xenon.TextRemovalAnimationSettings.Toggle;

final class TextRemovalAnimation extends Drawable {
    private static final int MAX_GLYPHS = 50;
    private static final int MAX_BITMAP_SIDE = 192;
    private static final float SPIN_DAMPING = 6f;
    private static final long RELEASE_AFTER_IMPACT_MS = 120;
    private static final long MAX_FALL_MS = 2500;

    private final TextAnimationEditText owner;
    private final ArrayList<Glyph> glyphs = new ArrayList<>();
    private final Random random = new Random();
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Path selection = new Path();
    private final RectF selectionBounds = new RectF();
    private final RectF destination = new RectF();
    private final Rect viewport = new Rect();
    private final Rect visibleFrame = new Rect();
    private final int[] hostLocation = new int[2];
    private final int[] ownerLocation = new int[2];
    private final Runnable nextFrame = this::invalidateOverlay;
    private View host;

    TextRemovalAnimation(TextAnimationEditText owner) {
        this.owner = owner;
    }

    void addGlyph(Layout layout, int start, int end, long now, TextRemovalAnimationSettings settings) {
        if (!owner.isAttachedToWindow() || layout.getText().length() < end ||
                Character.isWhitespace(Character.codePointAt(layout.getText(), start))) {
            return;
        }
        int line = layout.getLineForOffset(start);
        selection.reset();
        layout.getSelectionPath(start, Math.min(end, layout.getLineEnd(line)), selection);
        selection.computeBounds(selectionBounds, true);
        int left = (int) Math.floor(selectionBounds.left);
        int right = (int) Math.ceil(selectionBounds.right);
        int top = layout.getLineTop(line);
        int bottom = layout.getLineBottom(line);
        if (right <= left || bottom <= top) return;

        owner.getLocationOnScreen(ownerLocation);
        int x = ownerLocation[0] + owner.getCompoundPaddingLeft() - owner.getScrollX();
        int y = ownerLocation[1] + owner.getTotalPaddingTop() - owner.getScrollY();
        if (right + x <= ownerLocation[0] + owner.getCompoundPaddingLeft() ||
                left + x >= ownerLocation[0] + owner.getWidth() - owner.getCompoundPaddingRight() ||
                bottom + y <= ownerLocation[1] + owner.getExtendedPaddingTop() ||
                top + y >= ownerLocation[1] + owner.getHeight() - owner.getExtendedPaddingBottom()) {
            return;
        }

        float scale = Math.min(1f, MAX_BITMAP_SIDE / (float) Math.max(right - left, bottom - top));
        int width = Math.max(1, (int) Math.ceil((right - left) * scale));
        int height = Math.max(1, (int) Math.ceil((bottom - top) * scale));
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas capture = new Canvas(bitmap);
        capture.scale(scale, scale);
        capture.translate(-left, -top);
        capture.clipPath(selection);
        owner.captureRemovalGlyph(layout, capture);

        Glyph glyph;
        if (settings.mode() == TextRemovalAnimationSettings.Mode.EXPLOSION) {
            glyph = fracture(bitmap, left + x, top + y, scale, now, settings);
            bitmap.recycle();
            if (glyph == null) return;
        } else {
            glyph = new Glyph(now, settings, random);
            glyph.shards.add(new Shard(bitmap, left + x, top + y, scale));
        }

        if (host == null) {
            host = owner.getRootView();
            host.getOverlay().add(this);
        }
        if (glyphs.size() == MAX_GLYPHS) glyphs.remove(0);
        glyphs.add(glyph);
        invalidateOverlay();
    }

    private Glyph fracture(Bitmap bitmap, float x, float y, float scale, long now, TextRemovalAnimationSettings settings) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int[] pixels = new int[width * height];
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
        int[] ink = new int[pixels.length];
        int inkCount = 0;
        Rect bounds = new Rect(width, height, 0, 0);
        for (int i = 0; i < pixels.length; i++) {
            if ((pixels[i] >>> 24) == 0) continue;
            ink[inkCount++] = i;
            include(bounds, i % width, i / width);
        }
        if (inkCount == 0) return null;

        // Partition the original glyph's pixels by distance to irregularly placed seeds.
        // Each seed is on the ink and every pixel is assigned to exactly one shard.
        int count = Math.min(settings.get(Slider.SHARDS), inkCount);
        int[] assignment = new int[inkCount];
        int[] distances = new int[inkCount];
        Arrays.fill(distances, Integer.MAX_VALUE);
        int seed = ink[random.nextInt(inkCount)];
        Rect[] pieces = new Rect[count];
        for (int piece = 0; piece < count; piece++) {
            pieces[piece] = new Rect(width, height, 0, 0);
            int seedX = seed % width;
            int seedY = seed / width;
            int farthest = -1;
            for (int j = 0; j < inkCount; j++) {
                int dx = ink[j] % width - seedX;
                int dy = ink[j] / width - seedY;
                int distance = dx * dx + dy * dy;
                if (distance < distances[j]) {
                    distances[j] = distance;
                    assignment[j] = piece;
                }
                if (distances[j] > farthest) {
                    farthest = distances[j];
                    seed = ink[j];
                }
            }
        }
        for (int j = 0; j < inkCount; j++) {
            include(pieces[assignment[j]], ink[j] % width, ink[j] / width);
        }
        int[][] shardPixels = new int[count][];
        for (int piece = 0; piece < count; piece++) {
            Rect rect = pieces[piece];
            shardPixels[piece] = new int[rect.width() * rect.height()];
        }
        for (int j = 0; j < inkCount; j++) {
            int piece = assignment[j];
            Rect rect = pieces[piece];
            int pixel = ink[j];
            shardPixels[piece][(pixel / width - rect.top) * rect.width() + pixel % width - rect.left] = pixels[pixel];
        }

        Glyph glyph = new Glyph(now, settings, random);
        float force = settings.get(Slider.FORCE) * AndroidUtilities.density;
        for (int piece = 0; piece < count; piece++) {
            Rect rect = pieces[piece];
            // The mutable bitmap preserves its alpha, including the antialiased edges.
            Bitmap shardBitmap = Bitmap.createBitmap(rect.width(), rect.height(), Bitmap.Config.ARGB_8888);
            shardBitmap.setPixels(shardPixels[piece], 0, rect.width(), 0, 0, rect.width(), rect.height());
            Shard shard = new Shard(shardBitmap, x + rect.left / scale, y + rect.top / scale, scale);
            double direction = Math.atan2(rect.exactCenterY() - bounds.exactCenterY(),
                    rect.exactCenterX() - bounds.exactCenterX()) + (random.nextFloat() - .5f) * .5f;
            float speed = force * (.65f + .55f * random.nextFloat());
            shard.vx = speed * (float) Math.cos(direction);
            shard.vy = speed * (float) Math.sin(direction);
            shard.spinDirection = random.nextBoolean() ? 1f : -1f;
            shard.angularVelocity = shard.spinDirection * glyph.spinImpulse(speed) * (.5f + .5f * random.nextFloat());
            glyph.shards.add(shard);
        }
        return glyph;
    }

    private static void include(Rect bounds, int x, int y) {
        bounds.left = Math.min(bounds.left, x);
        bounds.top = Math.min(bounds.top, y);
        bounds.right = Math.max(bounds.right, x + 1);
        bounds.bottom = Math.max(bounds.bottom, y + 1);
    }

    private void invalidateOverlay() {
        if (host != null) {
            setBounds(0, 0, host.getWidth(), host.getHeight());
            invalidateSelf();
        }
    }

    void clear() {
        owner.removeCallbacks(nextFrame);
        glyphs.clear();
        if (host != null) {
            host.getOverlay().remove(this);
            host = null;
        }
    }

    @Override
    public void draw(Canvas canvas) {
        if (host == null || glyphs.isEmpty()) return;
        if (!NekoConfig.textAnimationEnabled || !owner.isShown()
                || NekoConfig.textRemovalAnimation.isDisabled(Phase.REMOVAL)) {
            owner.post(this::clear);
            return;
        }
        host.getLocationOnScreen(hostLocation);
        updateViewport();

        long now = SystemClock.uptimeMillis();
        canvas.save();
        canvas.translate(-hostLocation[0], -hostLocation[1]);
        canvas.clipRect(viewport);
        for (int i = 0; i < glyphs.size(); ) {
            Glyph glyph = glyphs.get(i);
            boolean alive = glyph.slide == null ? drawExplosion(canvas, glyph, now) : drawSlide(canvas, glyph, now);
            if (alive) {
                i++;
            } else {
                glyphs.remove(i);
            }
        }
        canvas.restore();
        owner.removeCallbacks(nextFrame);
        if (glyphs.isEmpty()) {
            owner.post(() -> {
                if (glyphs.isEmpty()) clear();
            });
        } else {
            owner.postOnAnimation(nextFrame);
        }
    }

    private void updateViewport() {
        viewport.set(hostLocation[0], hostLocation[1], hostLocation[0] + host.getWidth(), hostLocation[1] + host.getHeight());
        WindowInsetsCompat insets = ViewCompat.getRootWindowInsets(host);
        int ime = insets == null ? 0 : insets.getInsets(WindowInsetsCompat.Type.ime()).bottom;
        if (ime <= 0) return;

        if (Build.VERSION.SDK_INT >= 30) {
            WindowManager manager = host.getContext().getSystemService(WindowManager.class);
            if (manager != null) {
                int keyboardTop = manager.getCurrentWindowMetrics().getBounds().bottom - ime;
                viewport.bottom = Math.min(viewport.bottom, Math.max(viewport.top, keyboardTop));
                return;
            }
        }
        host.getWindowVisibleDisplayFrame(visibleFrame);
        if (!visibleFrame.isEmpty()) {
            viewport.bottom = Math.min(viewport.bottom, Math.max(viewport.top, visibleFrame.bottom));
        }
    }

    private boolean drawSlide(Canvas canvas, Glyph glyph, long now) {
        float t = (now - glyph.start) / (float) glyph.duration;
        if (t >= 1f) return false;
        float motion = TextSlideAnimation.ease(t);
        Shard slide = glyph.shards.get(0);
        drawShard(canvas, slide, slide.x + glyph.slide.dx * motion, slide.y + glyph.slide.dy * motion, 1f - motion);
        return true;
    }

    private boolean drawExplosion(Canvas canvas, Glyph glyph, long now) {
        long age = now - glyph.start;
        if (age >= glyph.duration + (glyph.fall ? MAX_FALL_MS : 0)) return false;
        boolean dropping = glyph.fall && age >= glyph.duration;
        float opacity = glyph.fall ? 1f : Math.min(1f, (glyph.duration - age) / (glyph.duration * .45f));
        float remaining = Math.min(48, now - glyph.lastFrame) / 1000f;
        glyph.lastFrame = now;
        while (remaining > 0f) {
            float dt = Math.min(.008f, remaining);
            for (Shard shard : glyph.shards) {
                advanceShard(shard, glyph, viewport, now, dropping, dt);
            }
            remaining -= dt;
        }

        boolean alive = !dropping;
        for (Shard shard : glyph.shards) {
            if (shard.released && hasExited(shard, glyph, viewport)) continue;
            drawShard(canvas, shard, shard.x, shard.y, opacity);
            alive = true;
        }
        return alive;
    }

    private static void advanceShard(Shard shard, Glyph glyph, Rect viewport, long now, boolean dropping, float dt) {
        boolean bounced = shard.impactTime != 0 && now - shard.impactTime >= RELEASE_AFTER_IMPACT_MS;
        if (dropping && (glyph.ignoreCollisions || bounced)) {
            shard.released = true;
        }
        float gravity = dropping ? Math.max(glyph.gravity, AndroidUtilities.dp(1250)) : glyph.gravity;
        float gravityX = glyph.directionX * gravity;
        float gravityY = glyph.directionY * gravity;
        shard.vx += gravityX * dt;
        shard.vy += gravityY * dt;
        shard.x += shard.vx * dt;
        shard.y += shard.vy * dt;
        shard.angle += shard.angularVelocity * dt;
        shard.angularVelocity *= 1f - SPIN_DAMPING * dt;
        if (glyph.ignoreCollisions || viewport.isEmpty()) return;

        double radians = Math.toRadians(shard.angle);
        float sin = (float) Math.abs(Math.sin(radians));
        float cos = (float) Math.abs(Math.cos(radians));
        float extentX = Math.min(viewport.width() / 2f, cos * shard.halfWidth + sin * shard.halfHeight);
        float extentY = Math.min(viewport.height() / 2f, sin * shard.halfWidth + cos * shard.halfHeight);
        float left = viewport.left + extentX;
        float right = viewport.right - extentX;
        float top = viewport.top + extentY;
        float bottom = viewport.bottom - extentY;

        int wallX = hitWall(shard.x, left, right, shard.released, glyph.directionX);
        if (wallX != 0) {
            shard.x = wallX < 0 ? left : right;
            if (shard.vx * wallX > 0f) {
                if (wallX * glyph.directionX > 0f && shard.impactTime == 0) shard.impactTime = now;
                shard.vx = rebound(shard, glyph, shard.vx, shard.vy);
                if (shard.vx != 0f) shard.vy *= .9f;
            }
        }

        int wallY = hitWall(shard.y, top, bottom, shard.released, glyph.directionY);
        if (wallY != 0) {
            shard.y = wallY < 0 ? top : bottom;
            if (shard.vy * wallY > 0f) {
                if (wallY * glyph.directionY > 0f && shard.impactTime == 0) shard.impactTime = now;
                shard.vy = rebound(shard, glyph, shard.vy, shard.vx);
                if (shard.vy != 0f) {
                    shard.vx *= .9f;
                } else if (gravityX == 0f && gravityY * wallY >= 0f) {
                    shard.vx = 0f;
                }
            }
        }
    }

    private static int hitWall(float position, float min, float max, boolean released, float direction) {
        if (position <= min && (!released || direction >= 0f)) return -1;
        if (position >= max && (!released || direction <= 0f)) return 1;
        return 0;
    }

    private static boolean hasExited(Shard shard, Glyph glyph, Rect viewport) {
        return (glyph.directionX < 0f && shard.x + shard.radius < viewport.left)
                || (glyph.directionX > 0f && shard.x - shard.radius > viewport.right)
                || (glyph.directionY < 0f && shard.y + shard.radius < viewport.top)
                || (glyph.directionY > 0f && shard.y - shard.radius > viewport.bottom);
    }

    private static float rebound(Shard shard, Glyph glyph, float velocity, float tangent) {
        float speed = Math.abs(velocity) * glyph.bounce;
        if (speed < AndroidUtilities.dp(22)) {
            shard.angularVelocity = 0f;
            return 0f;
        }
        float direction = Math.abs(tangent) > AndroidUtilities.dp(8) ? Math.signum(tangent) : shard.spinDirection;
        shard.angularVelocity = direction * glyph.spinImpulse(speed);
        return -Math.copySign(speed, velocity);
    }

    private void drawShard(Canvas canvas, Shard shard, float x, float y, float opacity) {
        paint.setAlpha(Math.round(255 * opacity));
        canvas.save();
        canvas.rotate(shard.angle, x, y);
        destination.set(x - shard.halfWidth, y - shard.halfHeight, x + shard.halfWidth, y + shard.halfHeight);
        canvas.drawBitmap(shard.bitmap, null, destination, paint);
        canvas.restore();
    }

    @Override
    public void setAlpha(int alpha) {}

    @Override
    public void setColorFilter(ColorFilter colorFilter) {}

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }

    private static final class Glyph {
        final ArrayList<Shard> shards = new ArrayList<>();
        final long start;
        final int duration;
        final TextSlideAnimation slide;
        final boolean fall, ignoreCollisions;
        final float directionX, directionY, gravity, bounce, rotationStrength;
        long lastFrame;

        Glyph(long now, TextRemovalAnimationSettings settings, Random random) {
            lastFrame = start = now;
            if (settings.mode() == TextRemovalAnimationSettings.Mode.EXPLOSION) {
                slide = null;
                duration = randomTime(settings, random, Slider.LIFETIME_MIN, Slider.LIFETIME_MAX);
                fall = settings.isEnabled(Toggle.FALL);
                ignoreCollisions = settings.isEnabled(Toggle.IGNORE_FLOOR);
                double angle = Math.toRadians(settings.get(Slider.DIRECTION));
                directionX = gravityComponent(Math.sin(angle));
                directionY = gravityComponent(Math.cos(angle));
                gravity = settings.get(Slider.GRAVITY) * AndroidUtilities.density;
                bounce = settings.get(Slider.BOUNCE) / 100f;
                rotationStrength = settings.get(Slider.SPIN);
            } else {
                slide = new TextSlideAnimation(settings);
                duration = slide.duration;
                fall = ignoreCollisions = false;
                directionX = directionY = gravity = bounce = rotationStrength = 0;
            }
        }

        float spinImpulse(float speed) {
            return rotationStrength * Math.min(1f, speed / AndroidUtilities.dp(340));
        }

        private static float gravityComponent(double component) {
            return Math.abs(component) < .001 ? 0f : (float) component;
        }

        private static int randomTime(TextRemovalAnimationSettings settings, Random random, Slider min, Slider max) {
            int lower = settings.get(min);
            return lower + random.nextInt(settings.get(max) - lower + 1);
        }
    }

    private static final class Shard {
        final Bitmap bitmap;
        final float halfWidth, halfHeight, radius;
        float x, y, vx, vy, angle, angularVelocity, spinDirection;
        long impactTime;
        boolean released;

        Shard(Bitmap bitmap, float left, float top, float scale) {
            this.bitmap = bitmap;
            halfWidth = bitmap.getWidth() / (2f * scale);
            halfHeight = bitmap.getHeight() / (2f * scale);
            radius = (float) Math.hypot(halfWidth, halfHeight);
            x = left + halfWidth;
            y = top + halfHeight;
        }
    }
}
