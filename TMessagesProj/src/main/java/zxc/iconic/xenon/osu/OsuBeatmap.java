package zxc.iconic.xenon.osu;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Locale;

/** The audio-relative timing/effect subset of the public .osu format. */
public final class OsuBeatmap {
    public String title = "", artist = "", version = "", audioFilename = "";
    public String background="",video="";
    public double videoOffset;
    public int mode;
    public boolean hasKiai;
    public double starRating = Double.NaN;
    public double overallDifficulty = 5, approachRate = Double.NaN, circleSize = 5, hpDrainRate = 5;
    private int objectCount;
    private double firstObject = Double.POSITIVE_INFINITY, lastObject = Double.NEGATIVE_INFINITY;

    /** Compare calculated stars when available, falling back to local settings. */
    public int compareDifficultyTo(OsuBeatmap other) {
        if (Double.isFinite(starRating) && Double.isFinite(other.starRating)) {
            int stars = Double.compare(starRating, other.starRating);
            if (stars != 0) return stars;
        }
        int result = Double.compare(overallDifficulty, other.overallDifficulty);
        if (result == 0) result = Double.compare(approachRate, other.approachRate);
        if (result == 0) result = Double.compare(objectDensity(), other.objectDensity());
        if (result == 0) result = Double.compare(circleSize, other.circleSize);
        if (result == 0) result = Double.compare(hpDrainRate, other.hpDrainRate);
        return result;
    }
    private double objectDensity() { return objectCount / Math.max(1000, lastObject - firstObject); }
    public String authorLabel() {
        return artist + " • " + (hasKiai ? "★ " : "") + version
                + (Double.isFinite(starRating) && starRating >= 0 ? String.format(Locale.US, " • %.2f", starRating) : "");
    }
    private final ArrayList<Point> points = new ArrayList<>();
    private Point firstTiming;
    private static final class Point {
        double time, length, offset;
        double kiaiStart = Double.NaN, kiaiEnd = Double.POSITIVE_INFINITY;
        double fountainStart = Double.NaN, fountainLoopStart = Double.NaN, fountainLastTrigger = Double.NaN;
        int meter;
        int section;
        boolean timing, kiai;
    }
    public static final class State {
        public double beatLength = 500, offset, beatTime;
        public long beatIndex;
        public int meter = 4, timingSection;
        public boolean kiai, hasBeat;
        public double kiaiStart, kiaiEnd;
        public double fountainStart, fountainLoopStart, fountainLastTrigger;
    }

    public static OsuBeatmap read(Reader input) throws IOException {
        OsuBeatmap map = new OsuBeatmap();
        BufferedReader reader = new BufferedReader(input);
        String line, section = "";
        boolean header = false;
        int chars = 0;
        while ((line = reader.readLine()) != null) {
            chars += line.length();
            if (chars > 8 * 1024 * 1024 || line.length() > 65536) throw new IOException("Beatmap text too large");
            line = line.replace("\ufeff", "").trim();
            if (line.startsWith("osu file format v")) { header = true; continue; }
            if (line.isEmpty() || line.startsWith("//")) continue;
            if (line.startsWith("[") && line.endsWith("]")) { section = line; continue; }
            if (section.equals("[TimingPoints]")) {
                String[] fields = line.split(",", -1);
                if (fields.length < 2) continue;
                try {
                    Point point = new Point();
                    point.time = Double.parseDouble(fields[0].trim());
                    point.length = Double.parseDouble(fields[1].trim());
                    point.meter = fields.length > 2 ? Integer.parseInt(fields[2].trim()) : 4;
                    point.timing = fields.length <= 6 ? point.length > 0 : Integer.parseInt(fields[6].trim()) == 1;
                    point.kiai = fields.length > 7 && (Integer.parseInt(fields[7].trim()) & 1) != 0;
                    if (!Double.isFinite(point.time) || !Double.isFinite(point.length)
                            || Math.abs(point.time) > 1e10 || (point.timing && (point.length <= 0 || point.length > 1e9))) continue;
                    point.meter = Math.max(1, point.meter);
                    map.points.add(point);
                    map.hasKiai |= point.kiai;
                    if (map.points.size() > 100000) throw new IOException("Too many timing points");
                } catch (NumberFormatException ignored) { }
            } else if(section.equals("[Events]")){
                OsuBackgroundEvent event=OsuBackgroundEvent.parse(line);if(event!=null){if(event.video){map.video=event.file;map.videoOffset=event.offset;}else map.background=event.file;}
            } else if (section.equals("[Difficulty]")) {
                int colon = line.indexOf(':');
                if (colon < 0) continue;
                try {
                    double value = Double.parseDouble(line.substring(colon + 1).trim());
                    if (!Double.isFinite(value) || value < 0 || value > 10) continue;
                    switch (line.substring(0, colon).trim()) {
                        case "OverallDifficulty": map.overallDifficulty = value; break;
                        case "ApproachRate": map.approachRate = value; break;
                        case "CircleSize": map.circleSize = value; break;
                        case "HPDrainRate": map.hpDrainRate = value; break;
                    }
                } catch (NumberFormatException ignored) { }
            } else if (section.equals("[HitObjects]")) {
                String[] fields = line.split(",", 4);
                if (fields.length < 4) continue;
                try {
                    double time = Double.parseDouble(fields[2].trim());
                    if (!Double.isFinite(time) || Math.abs(time) > 1e10) continue;
                    map.objectCount++;
                    map.firstObject = Math.min(map.firstObject, time);
                    map.lastObject = Math.max(map.lastObject, time);
                } catch (NumberFormatException ignored) { }
            } else if (section.equals("[General]") || section.equals("[Metadata]")) {
                int colon = line.indexOf(':');
                if (colon < 0) continue;
                String key = line.substring(0, colon).trim(), value = line.substring(colon + 1).trim();
                switch (key) {
                    case "Mode":
                        if (section.equals("[General]")) {
                            try { map.mode = Integer.parseInt(value); }
                            catch (NumberFormatException error) { throw new IOException("Invalid beatmap mode", error); }
                            if (map.mode < 0 || map.mode > 3) throw new IOException("Invalid beatmap mode");
                        }
                        break;
                    case "AudioFilename": map.audioFilename = unquote(value); break;
                    case "Title": if (map.title.isEmpty()) map.title = value; break;
                    case "TitleUnicode": if (!value.isEmpty()) map.title = value; break;
                    case "Artist": if (map.artist.isEmpty()) map.artist = value; break;
                    case "ArtistUnicode": if (!value.isEmpty()) map.artist = value; break;
                    case "Version": map.version = value; break;
                }
            }
        }
        if (!header || map.audioFilename.isEmpty()) throw new IOException("Not an audio beatmap");
        if (Double.isNaN(map.approachRate)) map.approachRate = map.overallDifficulty;
        map.points.sort(Comparator.comparingDouble(p -> p.time));
        double length = 500, offset = 0;
        int meter = 4;
        int sectionIndex = -1;
        boolean wasKiai = false;
        double kiaiStart = Double.NaN;
        for (int i = 0; i < map.points.size(); i++) {
            Point point = map.points.get(i);
            if (point.kiai && !wasKiai) kiaiStart = point.time;
            if (!point.kiai) kiaiStart = Double.NaN;
            point.kiaiStart = kiaiStart; wasKiai = point.kiai;
            if (point.timing) {
                if (map.firstTiming == null) map.firstTiming = point;
                length = point.length; offset = point.time; meter = point.meter; sectionIndex = i;
            }
            point.section = sectionIndex;
            point.length = length; point.offset = offset; point.meter = meter;
        }
        double kiaiEnd = Double.POSITIVE_INFINITY;
        for (int i = map.points.size() - 1; i >= 0; i--) {
            Point point = map.points.get(i);
            if (!point.kiai) kiaiEnd = point.time;
            point.kiaiEnd = kiaiEnd;
        }
        // KiaiMenuFountains shoots once on entry. StarFountainSounds only starts
        // its loop after another shot within 500ms, then fades after the last shot.
        boolean active = false;
        double lastTrigger = Double.NaN, shot = Double.NaN, loop = Double.NaN;
        for (int i = 0; i < map.points.size(); i++) {
            Point point = map.points.get(i);
            boolean effective = i + 1 == map.points.size() || map.points.get(i + 1).time != point.time;
            if (effective) {
                if (point.kiai && !active) {
                    if (Double.isNaN(lastTrigger) || point.time - lastTrigger > 500) {
                        shot = point.time;
                        loop = Double.NaN;
                    } else if (Double.isNaN(loop)) loop = point.time;
                    lastTrigger = point.time;
                }
                active = point.kiai;
            }
            point.fountainStart = shot;
            point.fountainLoopStart = loop;
            point.fountainLastTrigger = lastTrigger;
        }
        return map;
    }

    private static String unquote(String value) {
        return value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")
                ? value.substring(1, value.length() - 1) : value;
    }

    public void at(double positionMs, State state) {
        int lo = 0, hi = points.size() - 1, found = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (points.get(mid).time <= positionMs) { found = mid; lo = mid + 1; } else hi = mid - 1;
        }
        Point point = found < 0 ? null : points.get(found);
        if (point == null) point = firstTiming;
        state.beatLength = point == null ? 500 : point.length;
        state.offset = point == null ? 0 : point.offset;
        state.meter = point == null ? 4 : point.meter;
        state.kiai = found >= 0 && points.get(found).kiai;
        state.kiaiStart = found < 0 ? Double.NaN : points.get(found).kiaiStart;
        state.kiaiEnd = found < 0 ? Double.POSITIVE_INFINITY : points.get(found).kiaiEnd;
        state.fountainStart = found < 0 ? Double.NaN : points.get(found).fountainStart;
        state.fountainLoopStart = found < 0 ? Double.NaN : points.get(found).fountainLoopStart;
        state.fountainLastTrigger = found < 0 ? Double.NaN : points.get(found).fountainLastTrigger;
        state.beatIndex = (long) Math.floor((positionMs - state.offset) / state.beatLength);
        state.beatTime = state.offset + state.beatIndex * state.beatLength;
        state.hasBeat = point != null && point.section >= 0 && state.beatIndex >= 0;
        // Effect-only control points must not restart the metronome.
        state.timingSection = point == null ? -1 : point.section;
    }
    /** Effective off-to-on transitions; earlier control points at the same timestamp are superseded. */
    double[] kiaiStarts() {
        double[] starts = new double[points.size()];
        int count = 0;
        boolean active = false;
        for (int i = 0; i < points.size(); i++) {
            Point point = points.get(i);
            if (i + 1 < points.size() && points.get(i + 1).time == point.time) continue;
            if (point.kiai && !active) starts[count++] = point.time;
            active = point.kiai;
        }
        return java.util.Arrays.copyOf(starts, count);
    }

    public double nextControlPointAfter(double time) {
        int lo = 0, hi = points.size();
        while (lo < hi) { int mid = (lo + hi) >>> 1; if (points.get(mid).time <= time) lo = mid + 1; else hi = mid; }
        return lo == points.size() ? Double.POSITIVE_INFINITY : points.get(lo).time;
    }
}
