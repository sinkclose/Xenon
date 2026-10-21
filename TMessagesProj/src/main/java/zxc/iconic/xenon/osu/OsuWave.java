package zxc.iconic.xenon.osu;

import java.io.*;
import java.nio.*;

/** Immutable PCM16 WAV sample, resampled on read without allocations. */
public final class OsuWave {
    public final int sampleRate, channels;
    private final short[] samples;
    public OsuWave(int rate, int channels, short[] samples) { this.sampleRate = rate; this.channels = channels; this.samples = samples; }
    public double durationMs() { return samples.length * 1000.0 / channels / sampleRate; }
    public float value(double ms, int outputChannel, int outputChannels, boolean loop) {
        if (ms < 0 || samples.length == 0) return 0;
        double position = ms * sampleRate / 1000;
        int frames = samples.length / channels;
        if (loop) position %= frames;
        if (position >= frames) return 0;
        int a = (int) position, b = a + 1 < frames ? a + 1 : loop ? 0 : a;
        float fraction = (float) (position - a);
        if (outputChannels == 1 && channels == 2) return (sample(a, 0) + sample(a, 1)
                + fraction * (sample(b, 0) + sample(b, 1) - sample(a, 0) - sample(a, 1))) / 65536f;
        int channel = outputChannel % channels;
        return (sample(a, channel) + fraction * (sample(b, channel) - sample(a, channel))) / 32768f;
    }
    float peak(double firstMs,double endMs){
        // Include the sinc neighbourhood (and linear interpolation's next sample).
        int first=Math.max(0,(int)Math.floor(firstMs*sampleRate/1000)-8);
        int end=Math.min(samples.length/channels,(int)Math.ceil(endMs*sampleRate/1000)+9);
        int peak=0;for(int i=first*channels;i<end*channels;i++)peak=Math.max(peak,Math.abs((int)samples[i]));return peak/32768f;
    }
    private short sample(int frame, int channel) { return samples[frame * channels + channel]; }
    /** Band-limit pitched UI samples before downsampling, rather than aliasing high ticks. */
    float pitched(double ms,int channel,int outputChannels,int outputRate,float speed){
        double cutoff=Math.min(1,outputRate/(sampleRate*(double)speed));
        if(cutoff>=1)return value(ms,channel,outputChannels,false);
        double position=ms*sampleRate/1000;
        if(position<0||position>=samples.length/channels)return 0;
        int centre=(int)position;double value=0,weight=0;
        for(int i=centre-7;i<=centre+8;i++){
            double distance=position-i;if(Math.abs(distance)>=8)continue;
            double x=Math.PI*distance*cutoff;
            double w=(Math.abs(x)<1e-8?cutoff:Math.sin(x)/(Math.PI*distance))*(.5+.5*Math.cos(Math.PI*distance/8));
            weight+=w;
            if(i<0||i>=samples.length/channels)continue;
            double sample=outputChannels==1&&channels==2?(sample(i,0)+sample(i,1))/65536.0:sample(i,channel%channels)/32768.0;
            value+=w*sample;
        }
        return weight==0?0:(float)(value/weight);
    }
    /** Phase-table version of the same filter: compute its trigonometry once per voice. */
    PitchReader pitchReader(int outputChannels,int outputRate,float speed){return new PitchReader(outputChannels,outputRate,speed);}
    final class PitchReader {
        private static final int PHASES=256,TAPS=16;
        private final int outputChannels;
        private final double[] weights;
        PitchReader(int outputChannels,int outputRate,float speed){
            this.outputChannels=outputChannels;double cutoff=Math.min(1,outputRate/(sampleRate*(double)speed));
            weights=cutoff>=1?null:new double[(PHASES+1)*TAPS];
            if(weights==null)return;
            for(int phase=0;phase<=PHASES;phase++){
                double sum=0;
                for(int tap=0;tap<TAPS;tap++){
                    double d=phase/(double)PHASES+7-tap;if(Math.abs(d)>=8)continue;
                    double x=Math.PI*d*cutoff;
                    double w=(Math.abs(x)<1e-8?cutoff:Math.sin(x)/(Math.PI*d))*(.5+.5*Math.cos(Math.PI*d/8));
                    weights[phase*TAPS+tap]=w;sum+=w;
                }
                if(sum!=0)for(int tap=0;tap<TAPS;tap++)weights[phase*TAPS+tap]/=sum;
            }
        }
        float peakBound(){
            if(weights==null)return 1;
            double bound=1;
            for(int phase=0;phase<=PHASES;phase++){double sum=0;for(int tap=0;tap<TAPS;tap++)sum+=Math.abs(weights[phase*TAPS+tap]);bound=Math.max(bound,sum);}
            return (float)bound;
        }
        float value(double ms,int channel){
            if(weights==null)return OsuWave.this.value(ms,channel,outputChannels,false);
            double position=ms*sampleRate/1000;if(position<0||position>=samples.length/channels)return 0;
            int centre=(int)position;double phasePosition=(position-centre)*PHASES;
            int phase=Math.min(PHASES-1,(int)phasePosition);double fraction=phasePosition-phase,result=0;
            for(int tap=0;tap<TAPS;tap++){
                int frame=centre-7+tap;if(frame<0||frame>=samples.length/channels)continue;
                double a=weights[phase*TAPS+tap],w=a+(weights[(phase+1)*TAPS+tap]-a)*fraction;
                double sample=outputChannels==1&&channels==2?(sample(frame,0)+sample(frame,1))/65536.0:sample(frame,channel%channels)/32768.0;
                result+=w*sample;
            }
            return (float)result;
        }
    }
    public static OsuWave read(InputStream source) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); byte[] block = new byte[8192];
        for (int n; (n = source.read(block)) >= 0;) { if (bytes.size() + n > 8 * 1024 * 1024) throw new IOException("Sample too large"); bytes.write(block, 0, n); }
        ByteBuffer data = ByteBuffer.wrap(bytes.toByteArray()).order(ByteOrder.LITTLE_ENDIAN);
        if (data.remaining() < 12 || data.getInt() != 0x46464952 || data.getInt(8) != 0x45564157) throw new IOException("Not a WAV sample");
        int channels = 0, rate = 0, payload = -1, size = 0;
        for (int offset = 12; offset + 8 <= data.limit();) {
            int type = data.getInt(offset), length = data.getInt(offset + 4);
            if (length < 0 || length > data.limit() - offset - 8) throw new IOException("Truncated WAV");
            if (type == 0x20746d66) {
                if (length < 16 || data.getShort(offset + 8) != 1 || data.getShort(offset + 22) != 16) throw new IOException("Expected PCM16");
                channels = data.getShort(offset + 10); rate = data.getInt(offset + 12);
            } else if (type == 0x61746164) { payload = offset + 8; size = length; }
            offset += 8 + length + (length & 1);
        }
        if (payload < 0 || channels < 1 || channels > 2 || rate < 8000 || rate > 192000 || size % (2 * channels) != 0) throw new IOException("Invalid WAV format");
        short[] samples = new short[size / 2]; data.position(payload); for (int i = 0; i < samples.length; i++) samples[i] = data.getShort();
        return new OsuWave(rate, channels, samples);
    }
}
