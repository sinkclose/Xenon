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
        paint.setXfermode(additive);
        try {
            if(count>0)skin.drawKiaiStars(canvas,paint,rect,fountain.particles,count,width,height,opacity);
        } finally { paint.setXfermode(null); }
    }
}
