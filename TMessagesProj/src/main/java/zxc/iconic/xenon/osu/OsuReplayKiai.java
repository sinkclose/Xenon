package zxc.iconic.xenon.osu;

import android.graphics.*;

/** Audio-clock-based osu! gameplay star fountains. */
final class OsuReplayKiai {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final RectF rect = new RectF();
    private final PorterDuffXfermode additive = new PorterDuffXfermode(PorterDuff.Mode.ADD);
    private OsuBeatmap source;
    private OsuKiaiFountain fountain;

    void draw(Canvas canvas, OsuBeatmap map, double time, float width, float height, OsuReplaySkin skin, float opacity) {
        if (map == null || opacity <= 0) return;
        if (source != map) { source = map; fountain = new OsuKiaiFountain(map); }
        int count = fountain.at(time);
        // osu!'s global coordinate system is 768 units high, independent of the hitobject playfield.
        float unit = height / 768f;
        paint.setXfermode(additive);
        try {
            for (int i = 0; i < count; i++) {
                OsuKiaiFountain.Particle p = fountain.particles[i];
                float x = (p.side == 0 ? 0 : width) + p.x * unit, y = height + p.y * unit;
                canvas.save();
                // ParticleSpewer rotates its quads in radians with the opposite Canvas orientation.
                canvas.rotate((float)(-p.angle * 180 / Math.PI), x, y);
                skin.natural(canvas, paint, rect, "star2", x, y, p.scale * unit, p.alpha * opacity, null, 0);
                canvas.restore();
            }
        } finally { paint.setXfermode(null); }
    }
}
