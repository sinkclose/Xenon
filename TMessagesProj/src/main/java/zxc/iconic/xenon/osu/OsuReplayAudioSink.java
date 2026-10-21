package zxc.iconic.xenon.osu;

import androidx.media3.common.*;
import androidx.media3.exoplayer.audio.*;
import java.nio.*;
import zxc.iconic.xenon.NekoConfig;

/** PCM hits are inserted before ExoPlayer's single AudioTrack and Sonic processing. */
final class OsuReplayAudioSink extends ForwardingAudioSink {
    private final OsuReplayPcmMixer mixer;
    private Format format;
    private long offset;
    private ByteBuffer source,mixed=ByteBuffer.allocateDirect(0);
    OsuReplayAudioSink(AudioSink sink,OsuReplayPcmMixer mixer){super(sink);this.mixer=mixer;}
    @Override public boolean supportsFormat(Format format){return getFormatSupport(format)!=SINK_FORMAT_UNSUPPORTED;}
    @Override public int getFormatSupport(Format format){return MimeTypes.AUDIO_RAW.equals(format.sampleMimeType)?super.getFormatSupport(format):SINK_FORMAT_UNSUPPORTED;}
    @Override public void configure(AudioSinkConfig config)throws ConfigurationException{
        format=config.format;
        if(OsuPcmMixer.bytesPerSample(format.pcmEncoding)==0||format.sampleRate<=0||format.channelCount<=0)throw new ConfigurationException("Expected decoded replay PCM",format);
        super.configure(config);
    }
    @Override public AudioOffloadSupport getFormatOffloadSupport(Format format){return AudioOffloadSupport.DEFAULT_UNSUPPORTED;}
    @Override public void setOffloadMode(int mode){super.setOffloadMode(AudioSink.OFFLOAD_MODE_DISABLED);}
    @Override public void setOutputStreamOffsetUs(long offset){this.offset=offset;super.setOutputStreamOffsetUs(offset);}
    @Override public boolean handleBuffer(ByteBuffer input,long presentationUs,int units)throws InitializationException,WriteException{
        if(source!=input){
            source=input;
            if(mixed.capacity()<input.remaining())mixed=ByteBuffer.allocateDirect(input.remaining()).order(ByteOrder.LITTLE_ENDIAN);
            mixed.clear();mixed.put(input.duplicate());mixed.flip();
            mixer.mix(mixed,presentationUs-offset,format.sampleRate,format.channelCount,format.pcmEncoding,NekoConfig.osuHitsoundVolume/100f);
        }
        int before=mixed.position();boolean consumed=super.handleBuffer(mixed,presentationUs,units);
        input.position(input.position()+mixed.position()-before);if(consumed)source=null;return consumed;
    }
    @Override public void flush(){source=null;mixer.reset();super.flush();}
    @Override public void reset(){source=null;offset=0;mixer.reset();super.reset();}
}
