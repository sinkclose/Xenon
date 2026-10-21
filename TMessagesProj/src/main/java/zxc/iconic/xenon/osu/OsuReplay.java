package zxc.iconic.xenon.osu;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Bounded legacy .osr header reader, including lazer's compressed score-info trailer. */
public final class OsuReplay {
    public int mode, version, great, ok, meh, geki, katu, miss, combo;
    public long score, mods, playedAt, onlineId = -1, userId;
    public boolean perfect, lazer, autoplay, performanceSupported = true;
    public String beatmapHash, player, rank = "", title = "", artist = "", difficulty = "", mapper = "", modsLabel = "NM", lifeGraph="";
    public double overrideCS=Double.NaN,overrideAR=Double.NaN,overrideOD=Double.NaN,overrideHP=Double.NaN;
    public double flashlightDelay=120,flashlightSize=1;
    public boolean flashlightCombo=true,hiddenOnlyApproach,adjustPitch;
    public int mirrorReflection;
    public double stars = Double.NaN, pp = Double.NaN, clockRate;
    public int maxCombo;
    public byte[] scoreInfo, compressedFrames;
    public final Map<String, Integer> statistics = new LinkedHashMap<>(), maximum = new LinkedHashMap<>();
    public static boolean supports(String name) { return name != null && name.toLowerCase(Locale.ROOT).endsWith(".osr"); }
    public static boolean isOsuFile(String name) {
        if (name == null) return false;
        String s = name.toLowerCase(Locale.ROOT); return s.endsWith(".osu") || s.endsWith(".osz") || s.endsWith(".osr") || s.endsWith(".osk");
    }
    public int count(String key) { return statistics.getOrDefault(key, 0); }
    public int max(String key) { return maximum.getOrDefault(key, 0); }
    public static OsuReplay read(File file) throws IOException {
        return read(file, false);
    }
    public static OsuReplay read(File file, boolean includeFrames) throws IOException {
        if (file.length() > 64L * 1024 * 1024) throw new IOException("Replay too large");
        try (InputStream stream = new BufferedInputStream(new FileInputStream(file))) { return read(stream, includeFrames); }
    }
    public static OsuReplay read(InputStream stream) throws IOException {
        return read(stream, false);
    }
    private static OsuReplay read(InputStream stream, boolean includeFrames) throws IOException {
        Reader in = new Reader(stream);
        OsuReplay r = new OsuReplay();
        r.mode = in.u8(); r.version = in.i32();
        if (r.mode > 3 || r.version < 0) throw new IOException("Invalid replay header");
        r.lazer = r.version >= 30000001;
        r.beatmapHash = in.string(); r.player = in.string(); in.string();
        if (!r.beatmapHash.matches("[a-fA-F0-9]{32}")) throw new IOException("Invalid beatmap hash");
        r.beatmapHash = r.beatmapHash.toLowerCase(Locale.ROOT);
        r.great = in.u16(); r.ok = in.u16(); r.meh = in.u16(); r.geki = in.u16(); r.katu = in.u16(); r.miss = in.u16();
        r.score = Integer.toUnsignedLong(in.i32()); r.combo = in.u16(); r.perfect = in.u8() != 0;
        r.mods = Integer.toUnsignedLong(in.i32()); r.lifeGraph=in.string();
        long ticks = in.i64();
        if (ticks < 0 || ticks > 3155378975999999999L) throw new IOException("Invalid replay date");
        r.playedAt = ticks / 10000 - 62135596800000L;
        int frames = in.i32();
        if (frames < -1 || frames > 64 * 1024 * 1024) throw new IOException("Invalid replay frame length");
        if (includeFrames && frames > 0) {
            if (frames > 16 * 1024 * 1024) throw new IOException("Replay frames too large");
            r.compressedFrames = new byte[frames]; in.bytes(r.compressedFrames);
        } else in.skip(Math.max(0, frames));
        if (r.version >= 20140721) r.onlineId = in.i64();
        else if (r.version >= 20121008) r.onlineId = Integer.toUnsignedLong(in.i32());
        if (r.lazer) {
            int length = in.i32();
            if (length < -1 || length > 1024 * 1024) throw new IOException("Invalid score-info length");
            if (length > 0) { r.scoreInfo = new byte[length]; in.bytes(r.scoreInfo); }
        }
        r.statistics.put("great", r.great); r.statistics.put("ok", r.ok); r.statistics.put("meh", r.meh); r.statistics.put("miss", r.miss);
        r.statistics.put("perfect", r.geki); r.statistics.put("good", r.katu);
        r.modsLabel = legacyMods(r.mods);
        return r;
    }
    public double accuracy() {
        double numerator, denominator;
        switch (mode) {
            case 1: numerator = great + ok * .5; denominator = great + ok + miss; break;
            case 2: numerator = great + ok + meh; denominator = numerator + katu + miss; break;
            case 3: numerator = geki * (lazer ? 320.0 : 300.0) + great * 300.0 + katu * 200.0 + ok * 100.0 + meh * 50.0;
                    denominator = (geki + great + katu + ok + meh + miss) * (lazer ? 320.0 : 300.0); break;
            default:
                numerator = great * 300.0 + ok * 100.0 + meh * 50.0;
                denominator = (great + ok + meh + miss) * 300.0;
                if (lazer) {
                    numerator += count("large_tick_hit") * 30.0 + count("small_tick_hit") * 10.0 + count("slider_tail_hit") * 150.0;
                    denominator += Math.max(max("large_tick_hit"), count("large_tick_hit") + count("large_tick_miss")) * 30.0
                            + Math.max(max("small_tick_hit"), count("small_tick_hit") + count("small_tick_miss")) * 10.0
                            + Math.max(max("slider_tail_hit"), count("slider_tail_hit") + count("slider_tail_miss")) * 150.0;
                }
        }
        return denominator > 0 ? Math.max(0, Math.min(1, numerator / denominator)) : 0;
    }
    public String displayRank() {
        if (!rank.isEmpty()) return rank.replace("X", "SS").replace("H", "");
        double a = accuracy();
        if (!lazer && mode == 2) return a == 1 ? "SS" : a > .98 ? "S" : a > .94 ? "A" : a > .90 ? "B" : a > .85 ? "C" : "D";
        if (!lazer && mode == 1) return a == 1 ? "SS" : a > .95 && miss == 0 ? "S" : (a > .9 || a > .8 && miss == 0) ? "A" : (a > .8 || a > .7 && miss == 0) ? "B" : a > .6 ? "C" : "D";
        if (!lazer && mode == 0) {
            double total = great + ok + meh + miss, ratio = total > 0 ? great / total : 0;
            if (a == 1) return "SS";
            if (ratio > .9 && meh < total * .01 && miss == 0) return "S";
            if ((ratio > .8 && miss == 0) || ratio > .9) return "A";
            if ((ratio > .7 && miss == 0) || ratio > .8) return "B";
            return ratio > .6 ? "C" : "D";
        }
        return a == 1 ? "SS" : a >= .95 && (mode != 0 || miss == 0) ? "S" : a >= .9 ? "A" : a >= .8 ? "B" : a >= .7 ? "C" : "D";
    }
    public int[] performanceCounts() { return new int[]{combo, great, ok, meh, miss, geki, katu, count("large_tick_hit"), count("slider_tail_hit"), mode}; }
    public static String legacyMods(long bits) {
        String[] names = {"NF","EZ","TD","HD","HR","SD","DT","RX","HT","NC","FL","AT","SO","AP","PF","4K","5K","6K","7K","8K","FI","RD","CN","TP","9K","CO","1K","3K","2K","V2","MR"};
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < names.length; i++) if ((bits & (1L << i)) != 0) {
            if ((i == 6 && (bits & 512) != 0) || (i == 5 && (bits & 16384) != 0)) continue;
            if (text.length() > 0) text.append(' '); text.append(names[i]);
        }
        return text.length() == 0 ? "NM" : text.toString();
    }
    private static final class Reader {
        private final InputStream in;
        Reader(InputStream in) { this.in = in; }
        int u8() throws IOException { int b = in.read(); if (b < 0) throw new EOFException("Truncated replay"); return b; }
        int u16() throws IOException { return u8() | u8() << 8; }
        int i32() throws IOException { return u16() | u16() << 16; }
        long i64() throws IOException { return Integer.toUnsignedLong(i32()) | Integer.toUnsignedLong(i32()) << 32; }
        void bytes(byte[] b) throws IOException { int n = 0; while (n < b.length) { int count = in.read(b, n, b.length - n); if (count < 0) throw new EOFException(); n += count; } }
        void skip(int count) throws IOException { while (count > 0) { long n = in.skip(count); if (n == 0) { u8(); n = 1; } count -= n; } }
        String string() throws IOException {
            int tag = u8(); if (tag == 0) return ""; if (tag != 11) throw new IOException("Invalid replay string");
            int size = 0, shift = 0;
            for (int i = 0; i < 4; i++) { int value = u8(); size |= (value & 127) << shift; if ((value & 128) == 0) {
                if (size > 65536) throw new IOException("Replay string too long"); byte[] b = new byte[size]; bytes(b); return new String(b, StandardCharsets.UTF_8);
            } shift += 7; }
            throw new IOException("Invalid replay string length");
        }
    }
}
