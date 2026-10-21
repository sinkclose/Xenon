// Adapted from ppy/osu LegacyKiaiFlashingDrawable / LegacyMainCirclePiece (MIT).
// See assets/licenses/osu-kiai-fountain.txt.
package zxc.iconic.xenon.osu;

import java.util.ArrayList;

/** Beat-synced additive copy of the skin's circle, with deterministic seeking. */
final class OsuKiaiFlash {
    private static final class Segment {
        double first, end, offset, interval, fade;
    }
    private final Segment[] segments;

    OsuKiaiFlash(OsuBeatmap map) {
        ArrayList<Segment> result = new ArrayList<>();
        OsuBeatmap.State state = new OsuBeatmap.State();
        if (map != null) for (double start = map.nextControlPointAfter(Double.NEGATIVE_INFINITY); Double.isFinite(start);) {
            double end = map.nextControlPointAfter(start);
            map.at(start, state);
            if (state.kiai && state.timingSection >= 0) {
                double interval = state.beatLength;
                while (interval < 50) interval *= 2;
                double first = state.offset + Math.max(0, Math.ceil((start - state.offset) / interval)) * interval;
                if (first < end) {
                    Segment s = new Segment();
                    s.first = first; s.end = end; s.offset = state.offset; s.interval = interval;
                    s.fade = Math.max(80, state.beatLength - 80);
                    result.add(s);
                }
            }
            start = end;
        }
        segments = result.toArray(new Segment[0]);
    }

    float alpha(double time, int intensity) {
        if (!Double.isFinite(time) || intensity <= 0) return 0;
        int lo = 0, hi = segments.length;
        while (lo < hi) { int mid = (lo + hi) >>> 1; if (segments[mid].first <= time) lo = mid + 1; else hi = mid; }
        if (lo == 0) return 0;
        Segment s = segments[lo - 1];
        double index = Math.floor((time - s.offset) / s.interval);
        if (Double.isFinite(s.end)) index = Math.min(index, Math.floor((Math.nextDown(s.end) - s.offset) / s.interval));
        double beat = s.offset + index * s.interval;
        double progress = Math.max(0, Math.min(1, (time - beat) / s.fade));
        return (float)(.3 * (1 - Math.sin(progress * Math.PI / 2)) * Math.min(200, intensity) / 100);
    }

    static int glowColour(int combo) {
        int r = (combo >>> 16) & 255, g = (combo >>> 8) & 255, b = combo & 255;
        int add = Math.max(25, 300 - r - g - b);
        return 0xff000000 | (Math.min(255, r + add) << 16) | (Math.min(255, g + add) << 8) | Math.min(255, b + add);
    }

    static int tintedGlowColour(int combo) {
        int glow = glowColour(combo);
        // The proxied FlashingDrawable still inherits its CircleSprite parent's combo tint.
        int r = (((combo >>> 16) & 255) * ((glow >>> 16) & 255) + 127) / 255;
        int g = (((combo >>> 8) & 255) * ((glow >>> 8) & 255) + 127) / 255;
        int b = ((combo & 255) * (glow & 255) + 127) / 255;
        return 0xff000000 | (r << 16) | (g << 8) | b;
    }
}
