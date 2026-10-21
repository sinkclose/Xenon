package zxc.iconic.xenon.osu;

import java.util.*;

/** Sample-timed result sequence, rendered in small blocks without waiting for the outro. */
final class OsuResultsPcm {
    static final int RATE=48000,CHANNELS=2;
    private static final class Voice {
        int begin,end;OsuWave wave;OsuWave.PitchReader reader;float volume,rate,peak;
    }
    static final class Sequence {
        private final ArrayList<Voice> voices=new ArrayList<>();
        final int frames;
        private final float gain;
        Sequence(OsuReplay replay,Map<String,OsuWave> samples,float focusRate){
            OsuResultsAudioTimeline timeline=new OsuResultsAudioTimeline(false);
            int end=5*RATE;
            for(int time=0;time<=5000;time++)for(OsuResultsAudioTimeline.Event event:timeline.update(time,replay)){
                OsuWave wave=samples.get(event.name);if(wave==null)continue;
                Voice v=new Voice();v.begin=time*RATE/1000;v.wave=wave;v.volume=event.volume;
                v.rate=event.name.equals("score-panel-focus")?focusRate:event.rate;
                v.end=Math.min(20*RATE,v.begin+(int)Math.ceil(wave.durationMs()*RATE/1000/v.rate));
                v.reader=wave.pitchReader(CHANNELS,RATE,v.rate);
                v.peak=v.volume*v.reader.peakBound();
                voices.add(v);end=Math.max(end,v.end);
            }
            frames=end;
            // One fixed gain for the entire sequence. Bound simultaneous voices, including
            // filter overshoot, so streaming neither clips nor pumps volume between blocks.
            float[] envelope=new float[(frames+47)/48];
            for(Voice v:voices)for(int millisecond=v.begin/48;millisecond*48<v.end;millisecond++){
                double first=Math.max(0,millisecond*48-v.begin)*1000.0/RATE*v.rate;
                double last=(Math.min(v.end,(millisecond+1)*48)-v.begin)*1000.0/RATE*v.rate;
                envelope[millisecond]+=v.peak*v.wave.peak(first,last);
            }
            float peak=1;for(float value:envelope)peak=Math.max(peak,value/.95f);
            gain=1/peak;
        }
        int render(int firstFrame,short[] output){
            int count=Math.min(output.length/CHANNELS,Math.max(0,frames-firstFrame));
            float[] mix=new float[count*CHANNELS];
            for(Voice v:voices){
                int begin=Math.max(firstFrame,v.begin),end=Math.min(firstFrame+count,v.end);
                for(int frame=begin;frame<end;frame++)for(int channel=0;channel<CHANNELS;channel++)
                    mix[(frame-firstFrame)*CHANNELS+channel]+=v.volume*v.reader.value((frame-v.begin)*1000.0/RATE*v.rate,channel);
            }
            for(int i=0;i<mix.length;i++)output[i]=(short)Math.round(Math.max(-.95f,Math.min(.95f,mix[i]*gain))*32767);
            return count*CHANNELS;
        }
    }
    static short[] render(OsuReplay replay,Map<String,OsuWave> samples,float focusRate){
        Sequence sequence=new Sequence(replay,samples,focusRate);
        short[] result=new short[sequence.frames*CHANNELS];sequence.render(0,result);return result;
    }
}
