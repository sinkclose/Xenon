package zxc.iconic.xenon.osu;

import java.nio.*;
import java.util.*;

/** Hit events and music share sample timestamps, tempo processing, output and recording capture. */
final class OsuReplayPcmMixer {
    private final OsuReplaySoundEvents events;
    private final OsuBeatmap map;
    private final double end;
    private final OsuBeatmap.State state=new OsuBeatmap.State();
    private volatile Map<String,OsuWave> samples;
    private final OsuWave[] voices=new OsuWave[256];
    private final double[] onsets=new double[256];
    private final float[] gains=new float[256];
    volatile float musicVolume=1;
    private float musicGain=Float.NaN,effectGain=Float.NaN;
    OsuReplayPcmMixer(OsuReplaySoundEvents events,OsuBeatmap map,double end,Map<String,OsuWave> samples){this.events=events;this.map=map;this.end=end;this.samples=samples;}
    void setSamples(Map<String,OsuWave> samples){this.samples=samples;}
    void reset(){musicGain=effectGain=Float.NaN;}
    void mix(ByteBuffer pcm,long presentationUs,int rate,int channels,int encoding,float effects){
        Map<String,OsuWave> bank=samples;
        double tail=0;for(OsuWave wave:bank.values())tail=Math.max(tail,wave.durationMs());
        double start=presentationUs/1000.0;
        int first=events.after(start-tail),bytes=OsuPcmMixer.bytesPerSample(encoding),base=pcm.position();
        int frames=pcm.remaining()/(channels*bytes),segmentEnd=0;
        int last=events.after(start+frames*1000.0/rate);
        int voiceCount=0;
        for(int i=first;i<last;i++){
            OsuReplaySoundEvents.Event event=events.events.get(i);OsuWave wave=bank.get(event.name);
            if(wave==null||event.time+wave.durationMs()<=start)continue;
            int slot=voiceCount++%voices.length;voices[slot]=wave;onsets[slot]=event.time;gains[slot]=event.volume;
        }
        voiceCount=Math.min(voices.length,voiceCount);
        for(int i=voiceCount;i<voices.length;i++)voices[i]=null;
        OsuWave shot=bank.get("fountain-shoot"),loop=bank.get("fountain-loop");
        pcm.order(ByteOrder.LITTLE_ENDIAN);
        float music=Math.max(0,Math.min(1,musicVolume));effects=Math.max(0,Math.min(1,effects));
        if(Float.isNaN(musicGain)){musicGain=music;effectGain=effects;}
        float ramp=1f/(rate*.02f);
        for(int frame=0;frame<frames;frame++){
            double time=start+frame*1000.0/rate;
            if(frame>=segmentEnd){
                map.at(time,state);double next=map.nextControlPointAfter(time);
                segmentEnd=Double.isFinite(next)?Math.min(frames,frame+Math.max(1,(int)Math.ceil((next-time)*rate/1000))):frames;
            }
            musicGain+=Math.max(-ramp,Math.min(ramp,music-musicGain));
            effectGain+=Math.max(-ramp,Math.min(ramp,effects-effectGain));
            for(int channel=0;channel<channels;channel++){
                float extra=0;
                if(time<end){
                    for(int i=0;i<voiceCount;i++)extra+=gains[i]*voices[i].value(time-onsets[i],channel,channels,false);
                    if(shot!=null&&Double.isFinite(state.fountainStart))extra+=shot.value(time-state.fountainStart,channel,channels,false);
                    if(loop!=null&&Double.isFinite(state.fountainLoopStart)){
                        float fade=(float)Math.max(0,Math.min(1,(state.fountainLastTrigger+1000-time)/500));
                        extra+=fade*loop.value(time-state.fountainLoopStart,channel,channels,true);
                    }
                }
                int at=base+(frame*channels+channel)*bytes;
                float mixed=OsuPcmMixer.read(pcm,at,encoding)*musicGain+extra*effectGain;
                OsuPcmMixer.write(pcm,at,encoding,Math.max(-1,Math.min(1,mixed)));
            }
        }
    }
}
