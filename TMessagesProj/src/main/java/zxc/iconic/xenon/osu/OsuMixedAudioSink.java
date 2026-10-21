package zxc.iconic.xenon.osu;

import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.exoplayer.audio.AudioSink;
import androidx.media3.exoplayer.audio.AudioOffloadSupport;
import androidx.media3.exoplayer.audio.ForwardingAudioSink;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import zxc.iconic.xenon.NekoConfig;

/** Every sample, including UI hitsounds, is written through the music AudioTrack. */
public final class OsuMixedAudioSink extends ForwardingAudioSink {
    private final OsuPcmMixer mixer;
    private Format format;
    private long outputStreamOffsetUs;
    private boolean direct;
    private ByteBuffer source, mixed = ByteBuffer.allocateDirect(0);
    public OsuMixedAudioSink(AudioSink sink, OsuPcmMixer mixer) { super(sink); this.mixer = mixer; OsuSampleBank.request(); }
    // Encoded passthrough/offload would skip the PCM mixer entirely.
    @Override public boolean supportsFormat(Format format) { return getFormatSupport(format) != SINK_FORMAT_UNSUPPORTED; }
    @Override public int getFormatSupport(Format format) {
        return MimeTypes.AUDIO_RAW.equals(format.sampleMimeType) ? super.getFormatSupport(format) : SINK_FORMAT_UNSUPPORTED;
    }
    @Override public void configure(AudioSinkConfig config) throws ConfigurationException {
        format = config.format;
        if (OsuPcmMixer.bytesPerSample(format.pcmEncoding) == 0 || format.sampleRate <= 0 || format.channelCount <= 0) throw new ConfigurationException("Expected decoded PCM", format);
        super.configure(config);
    }
    @Override public AudioOffloadSupport getFormatOffloadSupport(Format format) { return AudioOffloadSupport.DEFAULT_UNSUPPORTED; }
    @Override public void setOffloadMode(int mode) { super.setOffloadMode(AudioSink.OFFLOAD_MODE_DISABLED); }
    @Override public void setOutputStreamOffsetUs(long offsetUs) {
        outputStreamOffsetUs = offsetUs;
        super.setOutputStreamOffsetUs(offsetUs);
    }
    @Override public boolean handleBuffer(ByteBuffer input, long presentationUs, int accessUnits) throws InitializationException, WriteException {
        if (source != input) {
            source = input;
            boolean beats = OsuLogoSounds.beatsEnabled();
            // Kiai is a chart effect: its preference must work without tapping or showing a logo.
            boolean kiai = NekoConfig.osuLogoKiaiSound;
            float music = NekoConfig.osuMapMusicVolume / 100f;
            direct = mixer.isNeutral(beats, kiai, music);
            if (!direct) {
                if (mixed.capacity() < input.remaining()) mixed = ByteBuffer.allocateDirect(input.remaining()).order(ByteOrder.LITTLE_ENDIAN);
                mixed.clear(); mixed.put(input.duplicate()); mixed.flip();
                // Renderer timestamps include ExoPlayer's period offset (initially 1e12 us).
                // Chart effects use audio-relative time; the downstream sink still needs renderer time.
                mixer.mix(mixed, presentationUs - outputStreamOffsetUs, format.sampleRate, format.channelCount, format.pcmEncoding, OsuSampleBank.samples,
                        beats, kiai, music, NekoConfig.osuHitsoundVolume / 100f);
            }
        }
        if (direct) {
            boolean consumed = super.handleBuffer(input, presentationUs, accessUnits);
            if (consumed) source = null;
            return consumed;
        }
        int before = mixed.position();
        boolean consumed = super.handleBuffer(mixed, presentationUs, accessUnits);
        input.position(input.position() + mixed.position() - before);
        if (consumed) source = null;
        return consumed;
    }
    @Override public void flush() { source = null; mixer.reset(); super.flush(); }
    @Override public void reset() { source = null; outputStreamOffsetUs = 0; mixer.reset(); super.reset(); }
}
