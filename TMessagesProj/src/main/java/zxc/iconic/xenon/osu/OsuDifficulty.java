package zxc.iconic.xenon.osu;

import java.io.File;
import java.io.FileInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/** Offline, no-mod star rating from the pinned rosu-pp osu!lazer algorithm. */
public final class OsuDifficulty {
    private static final boolean available;
    static {
        boolean loaded;
        try { System.loadLibrary("xenon_osu_difficulty"); loaded = true; }
        catch (LinkageError error) { loaded = false; }
        available = loaded;
    }
    private OsuDifficulty() { }
    private static native double nativeStars(byte[] data);
    private static native String nativeReplayJson(byte[] data);
    private static native String nativeReplayFrames(byte[] data);
    public static String replayFrames(byte[] data) { return available && data != null ? nativeReplayFrames(data) : null; }
    private static native double[] nativeReplayPerformance(byte[] data, long mods, int[] counts, boolean lazer, double rate);
    public static String replayJson(byte[] data) { return available ? nativeReplayJson(data) : null; }
    public static double[] replayPerformance(File map, long mods, int[] counts, boolean lazer, double rate) throws IOException {
        byte[] data = read(map);
        return data == null ? null : nativeReplayPerformance(data, mods, counts, lazer, rate);
    }
    public static double calculate(File file) throws IOException {
        byte[] data = read(file);
        return data == null ? Double.NaN : nativeStars(data);
    }
    private static byte[] read(File file) throws IOException {
        if (!available || file.length() > 8 * 1024 * 1024) return null;
        try (FileInputStream input = new FileInputStream(file); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] block = new byte[8192];
            for (int count; (count = input.read(block)) >= 0;) {
                if (bytes.size() + count > 8 * 1024 * 1024) return null;
                bytes.write(block, 0, count);
            }
            return bytes.toByteArray();
        }
    }
}
