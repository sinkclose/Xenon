package zxc.iconic.xenon.osu;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Mixes chart beats and fountain into music PCM, before a single shared output/tempo pipeline. */
public final class OsuPcmMixer {
    private final OsuBeatmap map;
    private final boolean old;
    private final OsuBeatmap.State state = new OsuBeatmap.State();
    private float musicGain = Float.NaN, effectGain = Float.NaN;
    public OsuPcmMixer(OsuBeatmap map, boolean old) { this.map = map; this.old = old; }
    public void reset() { musicGain = effectGain = Float.NaN; }
    public boolean isNeutral(boolean beats, boolean kiai, float music) {
        return !beats && !kiai && music == 1 && (Float.isNaN(musicGain) || musicGain == 1);
    }
    public void mix(ByteBuffer pcm, long presentationUs, int rate, int channels, int encoding,
                    OsuWave[] sounds, boolean beats, boolean kiai, float music, float effects) {
        music = clamp(music, 0, 1); effects = clamp(effects, 0, 1);
        if (Float.isNaN(musicGain)) { musicGain = music; effectGain = effects; }
        pcm.order(ByteOrder.LITTLE_ENDIAN);
        int bytes = bytesPerSample(encoding), frames = pcm.remaining() / (channels * bytes), start = pcm.position(), segmentEnd = 0;
        double maxTail = 0;
        if (sounds != null) for (int i = 0; i < 4; i++) maxTail = Math.max(maxTail, sounds[i].durationMs());
        float ramp = 1f / (rate * .02f);
        for (int frame = 0; frame < frames; frame++) {
            double time = presentationUs / 1000.0 + frame * 1000.0 / rate;
            if (frame >= segmentEnd) {
                map.at(time, state);
                double next = map.nextControlPointAfter(time);
                segmentEnd = !Double.isFinite(next) ? frames : Math.min(frames, frame + Math.max(1, (int) Math.ceil((next - time) * rate / 1000)));
            }
            musicGain += clamp(music - musicGain, -ramp, ramp);
            effectGain += clamp(effects - effectGain, -ramp, ramp);
            long beatIndex = (long) Math.floor((time - state.offset) / state.beatLength);
            double beatTime = state.offset + beatIndex * state.beatLength;
            for (int channel = 0; channel < channels; channel++) {
                float extra = 0;
                if (sounds != null && beats && state.hasBeat && beatIndex >= 0) {
                    for (int tail = 0; tail < Math.min(4, 1 + (int) (maxTail / state.beatLength)) && beatIndex - tail >= 0; tail++) {
                        long index = beatIndex - tail;
                        boolean downbeat = Math.floorMod(index, (long) state.meter) == 0;
                        OsuWave sample = sounds[(old ? 2 : 0) + (downbeat ? 0 : 1)];
                        float pitch = downbeat ? 1 : .95f + (float) ((index * 1103515245L + 12345) & 65535) / 65535 * .1f;
                        extra += sample.value((time - beatTime + tail * state.beatLength) * pitch, channel, channels, false);
                    }
                }
                if (sounds != null && kiai) {
                    if (!Double.isNaN(state.fountainStart))
                        extra += sounds[4].value(time - state.fountainStart, channel, channels, false);
                    // ppy/osu StarFountainSounds: hold 500ms after the last Play(),
                    // then fade linearly for 500ms and stop. Kiai duration is unrelated.
                    if (!Double.isNaN(state.fountainLoopStart)) {
                        float fade = clamp((float) ((state.fountainLastTrigger + 1000 - time) / 500), 0, 1);
                        if (fade > 0) extra += fade * sounds[5].value(time - state.fountainLoopStart, channel, channels, true);
                    }
                }
                int offset = start + (frame * channels + channel) * bytes;
                float base = read(pcm, offset, encoding);
                float value = clamp(base * musicGain + extra * effectGain, -1, 1);
                write(pcm, offset, encoding, value);
            }
        }
    }
    public static int bytesPerSample(int encoding) {
        return encoding == 2 ? 2 : encoding == 3 ? 1 : encoding == 4 || encoding == 0x30000000 ? 4 : encoding == 0x20000000 ? 3 : 0;
    }
    static float read(ByteBuffer data, int at, int encoding) {
        if (encoding == 4) return data.getFloat(at);
        if (encoding == 2) return data.getShort(at) / 32768f;
        if (encoding == 3) return ((data.get(at) & 255) - 128) / 128f;
        if (encoding == 0x30000000) return data.getInt(at) / 2147483648f;
        return ((data.get(at) & 255) | (data.get(at + 1) & 255) << 8 | data.get(at + 2) << 16) / 8388608f;
    }
    static void write(ByteBuffer data, int at, int encoding, float value) {
        if (encoding == 4) data.putFloat(at, value);
        else if (encoding == 2) data.putShort(at, (short) Math.max(-32768, Math.min(32767, Math.round(value * 32768))));
        else if (encoding == 3) data.put(at, (byte) Math.max(0, Math.min(255, Math.round(value * 128 + 128))));
        else if (encoding == 0x30000000) data.putInt(at, (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, Math.round(value * 2147483648.0))));
        else { int quantized = Math.max(-8388608, Math.min(8388607, Math.round(value * 8388608))); data.put(at, (byte) quantized); data.put(at + 1, (byte) (quantized >> 8)); data.put(at + 2, (byte) (quantized >> 16)); }
    }
    private static float clamp(float value, float min, float max) { return Math.max(min, Math.min(max, value)); }
}
