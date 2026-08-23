package org.telegram.ui.Components;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.SharedConfig;
import org.telegram.ui.ActionBar.Theme;

/**
 * Animated octagon badge used by Xenon custom badges ({@code badge_id == 0}).
 *
 * <p>Colors are chosen to stay readable on both chat-list backgrounds and the
 * blue action-bar title (solid blue-on-blue was previously effectively invisible
 * while still receiving click hits).
 */
public class OctagonBadgeDrawable extends Drawable {

    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint particlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path octagonPath = new Path();
    private final Path starPath = new Path();
    private final RectF boundsRect = new RectF();

    private float size = AndroidUtilities.dp(22);
    private float lastBoundsWidth = -1;
    private float lastBoundsHeight = -1;
    private final Particle[] particles = new Particle[14];
    private long lastUpdateTime;

    private String badgeText = ":3";

    private final ThrottledDrawableInvalidator throttledInvalidate =
            new ThrottledDrawableInvalidator(this, ThrottledDrawableInvalidator.FRAME_INTERVAL_30_FPS);

    /** Full-opacity base colors; alpha is applied via {@link #setAlpha(int)}. */
    private int baseBgColor;
    private int baseStrokeColor;
    private int baseTextColor;
    private int baseParticleColor;
    private int alpha = 255;

    public OctagonBadgeDrawable() {
        this((Theme.ResourcesProvider) null);
    }

    public OctagonBadgeDrawable(Theme.ResourcesProvider resourcesProvider) {
        init(resourcesProvider);
    }

    public OctagonBadgeDrawable(String text, Theme.ResourcesProvider resourcesProvider) {
        if (text != null && !text.isEmpty()) {
            badgeText = text;
        }
        init(resourcesProvider);
    }

    private void init(Theme.ResourcesProvider resourcesProvider) {
        // Same family as Telegram's verified badge — readable on action bar AND lists.
        int fill = Theme.getColor(Theme.key_chats_verifiedBackground, resourcesProvider);
        if (fill == 0) {
            fill = Theme.getColor(Theme.key_profile_verifiedBackground, resourcesProvider);
        }
        if (fill == 0) {
            fill = 0xFF33A8E6;
        }
        // Force fully opaque fill so 70%-alpha blue never dissolves into the action bar.
        fill = 0xFF000000 | (fill & 0x00FFFFFF);

        baseBgColor = fill;
        // Light edge so the badge pops on blue action bars and dark themes.
        baseStrokeColor = 0xE6FFFFFF;
        double luminance = (0.299 * Color.red(fill)
                + 0.587 * Color.green(fill)
                + 0.114 * Color.blue(fill)) / 255.0;
        baseTextColor = luminance > 0.55 ? 0xFF1A1A1A : 0xFFFFFFFF;
        baseParticleColor = 0xCCFFFFFF;

        bgPaint.setStyle(Paint.Style.FILL);
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(AndroidUtilities.dpf2(1.15f));
        strokePaint.setStrokeJoin(Paint.Join.ROUND);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setFakeBoldText(true);
        particlePaint.setStyle(Paint.Style.FILL);

        applyAlphaToPaints();

        long now = SystemClock.elapsedRealtime();
        for (int i = 0; i < particles.length; i++) {
            particles[i] = new Particle();
            resetParticle(particles[i], now, true);
        }
    }

    private void applyAlphaToPaints() {
        bgPaint.setColor(ColorUtils_setAlpha(baseBgColor, alpha));
        strokePaint.setColor(ColorUtils_setAlpha(baseStrokeColor, alpha));
        textPaint.setColor(ColorUtils_setAlpha(baseTextColor, alpha));
        particlePaint.setColor(ColorUtils_setAlpha(baseParticleColor, alpha));
    }

    private static int ColorUtils_setAlpha(int color, int alpha) {
        int a = (Color.alpha(color) * alpha) / 255;
        return (color & 0x00FFFFFF) | (a << 24);
    }

    public String getText() {
        return badgeText;
    }

    public void setSize(float size) {
        this.size = size;
    }

    @Override
    public int getIntrinsicWidth() {
        return (int) size;
    }

    @Override
    public int getIntrinsicHeight() {
        return (int) size;
    }

    private void buildOctagonPath(RectF bounds) {
        octagonPath.reset();
        float cx = bounds.centerX();
        float cy = bounds.centerY();
        // Inset slightly so the stroke is not clipped by drawable bounds.
        float outerR = Math.min(bounds.width(), bounds.height()) / 2f - AndroidUtilities.dpf2(1f);
        if (outerR <= 0) {
            return;
        }
        float innerR = outerR * 0.62f;
        for (int i = 0; i < 8; i++) {
            float angle = (float) Math.toRadians(-90 + i * 45);
            float r = (i % 2 == 0) ? outerR : innerR;
            float px = cx + r * (float) Math.cos(angle);
            float py = cy + r * (float) Math.sin(angle);
            if (i == 0) {
                octagonPath.moveTo(px, py);
            } else {
                octagonPath.lineTo(px, py);
            }
        }
        octagonPath.close();
    }

    private void buildStarPath(float r) {
        starPath.reset();
        for (int i = 0; i < 8; i++) {
            float radius = i % 2 == 0 ? r : r * 0.35f;
            float angle = (float) Math.toRadians(i * 45);
            float px = radius * (float) Math.cos(angle);
            float py = radius * (float) Math.sin(angle);
            if (i == 0) {
                starPath.moveTo(px, py);
            } else {
                starPath.lineTo(px, py);
            }
        }
        starPath.close();
    }

    private static final float MAX_PARTICLE_RADIUS = 1.35f;

    private static class Particle {
        float x, y, vx, vy, alpha, scale;
        long lifeTime, bornTime;
        float sizeFactor;
    }

    private void resetParticle(Particle p, long now, boolean randomPhase) {
        float angle = (float) (Math.random() * 2 * Math.PI);
        float dist = 0.55f + (float) Math.random() * 0.25f;
        float speed = 0.006f + (float) Math.random() * 0.010f;
        p.x = (float) Math.cos(angle) * dist;
        p.y = (float) Math.sin(angle) * dist;
        p.vx = (float) Math.cos(angle) * speed;
        p.vy = (float) Math.sin(angle) * speed;
        p.alpha = 0.7f + (float) Math.random() * 0.3f;
        p.scale = 0.4f + (float) Math.random() * 0.5f;
        p.lifeTime = 2000 + (long) (Math.random() * 2000);
        p.sizeFactor = 0.16f + (float) Math.random() * 0.10f;
        if (randomPhase) {
            p.bornTime = now - (long) (Math.random() * p.lifeTime);
            float dt = (now - p.bornTime) / 16.67f;
            p.x += p.vx * dt;
            p.y += p.vy * dt;
        } else {
            p.bornTime = now;
        }
    }

    /**
     * Particles run an endless self-invalidation loop that redraws the whole host
     * cell (heavy for chat message cells). Keep them only on capable devices; on
     * low-end hardware the badge renders as a static octagon.
     */
    private static boolean shouldAnimateParticles() {
        return SharedConfig.getDevicePerformanceClass() > SharedConfig.PERFORMANCE_CLASS_LOW;
    }

    @Override
    public void draw(Canvas canvas) {
        boundsRect.set(getBounds());
        if (boundsRect.width() <= 0 || boundsRect.height() <= 0) {
            return;
        }

        long now = SystemClock.elapsedRealtime();
        if (lastUpdateTime == 0) {
            lastUpdateTime = now;
        }
        float frameDt = Math.min(now - lastUpdateTime, 50) / 16.67f;
        lastUpdateTime = now;

        if (shouldAnimateParticles()) {
            drawParticles(canvas, boundsRect, frameDt, now);
            throttledInvalidate.onDrawn();
        }
        drawOctagon(canvas, boundsRect);
    }

    private void drawOctagon(Canvas canvas, RectF bounds) {
        float badgeSize = Math.min(bounds.width(), bounds.height());
        if (badgeSize <= 0) {
            return;
        }
        float textSize = badgeSize * 0.40f;
        textPaint.setTextSize(textSize);
        float innerRadius = badgeSize / 2f * 0.62f;
        float measuredW = textPaint.measureText(badgeText);
        if (measuredW > innerRadius * 1.6f && measuredW > 0) {
            textPaint.setTextSize(textSize * (innerRadius * 1.6f / measuredW));
        }

        if (bounds.width() != lastBoundsWidth || bounds.height() != lastBoundsHeight) {
            buildOctagonPath(bounds);
            lastBoundsWidth = bounds.width();
            lastBoundsHeight = bounds.height();
        }
        if (octagonPath.isEmpty()) {
            return;
        }

        canvas.drawPath(octagonPath, bgPaint);
        canvas.drawPath(octagonPath, strokePaint);

        Paint.FontMetrics fm = textPaint.getFontMetrics();
        float textY = bounds.centerY() - (fm.ascent + fm.descent) / 2f;
        canvas.drawText(badgeText, bounds.centerX(), textY, textPaint);
    }

    private void drawParticles(Canvas canvas, RectF bounds, float frameDt, long now) {
        float cx = bounds.centerX();
        float cy = bounds.centerY();
        float radius = Math.min(bounds.width(), bounds.height()) / 2f;
        if (radius <= 0) {
            return;
        }

        for (Particle p : particles) {
            long elapsed = now - p.bornTime;
            if (elapsed >= p.lifeTime) {
                resetParticle(p, now, false);
                elapsed = 0;
            }

            p.x += p.vx * frameDt;
            p.y += p.vy * frameDt;

            if (p.x * p.x + p.y * p.y > MAX_PARTICLE_RADIUS * MAX_PARTICLE_RADIUS) {
                resetParticle(p, now, false);
                elapsed = 0;
            }

            float progress = elapsed / (float) p.lifeTime;
            float a = p.alpha * (1f - progress * progress);
            float s = p.scale * (1f - progress * 0.3f);

            canvas.save();
            canvas.translate(cx + p.x * radius, cy + p.y * radius);
            canvas.scale(s, s);

            buildStarPath(p.sizeFactor * radius);
            // Particle alpha multiplies over base particle color alpha already set.
            int prev = particlePaint.getAlpha();
            particlePaint.setAlpha(Math.max(0, Math.min(255, (int) (prev * a))));
            canvas.drawPath(starPath, particlePaint);
            particlePaint.setAlpha(prev);
            canvas.restore();
        }
    }

    @Override
    public void setAlpha(int alpha) {
        this.alpha = Math.max(0, Math.min(255, alpha));
        applyAlphaToPaints();
        invalidateSelf();
    }

    @Override
    public int getAlpha() {
        return alpha;
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        // Ignore external color filters — action-bar / theme code often applies
        // MULTIPLY filters meant for vector icons, which would wipe our fills.
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
