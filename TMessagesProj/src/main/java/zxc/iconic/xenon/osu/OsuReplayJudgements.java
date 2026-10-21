package zxc.iconic.xenon.osu;

import java.util.*;

/** Reconstructs visible standard judgements from recorded key edges and cursor positions. */
public final class OsuReplayJudgements {
    public static final class Result {
        public final int object, value;
        public final double time, x, y;
        Result(int object,int value,double time,double x,double y){this.object=object;this.value=value;this.time=time;this.x=x;this.y=y;}
    }
    public final ArrayList<Result> results=new ArrayList<>();
    public final double[] headTimes;
    public static OsuReplayJudgements calculate(OsuPlaybackMap map,OsuReplayFrames frames,OsuReplay replay){
        return new OsuReplayJudgements(map,frames,replay);
    }
    private OsuReplayJudgements(OsuPlaybackMap map,OsuReplayFrames frames,OsuReplay replay){
        int count=map.objects.size();int[] heads=new int[count];Arrays.fill(heads,-1);headTimes=new double[count];Arrays.fill(headTimes,Double.POSITIVE_INFINITY);
        double od=map.overallDifficulty(replay.mods);
        double great=80-6*od,ok=140-8*od,meh=200-10*od,radius=map.radius(replay.mods);
        int first=0,previous=0;
        for(int frame=0;frame<frames.size;frame++){
            int time=frames.time(frame),buttons=frames.buttons(frame),edges=buttons&~previous;previous=buttons;
            while(first<count && map.objects.get(first).time+meh<time){first++;}
            for(int press=0;press<Integer.bitCount(edges);press++)for(int i=first;i<count;i++){
                OsuPlaybackMap.Hit hit=map.objects.get(i);if(hit.time-meh>time)break;
                if(hit.type==8||heads[i]>=0)continue;
                double y=(replay.mods&16)!=0?384-hit.y:hit.y;
                if(Math.hypot(hit.x-frames.x(frame),y-frames.y(frame))>radius)continue;
                double error=Math.abs(time-hit.time);
                heads[i]=error<great?300:error<ok?100:50;headTimes[i]=time;break;
            }
        }
        OsuReplaySpinners spinners=new OsuReplaySpinners(map,frames,replay);
        OsuReplayFrames.Sample sample=new OsuReplayFrames.Sample();double[] position=new double[2];
        for(int i=0;i<count;i++){
            OsuPlaybackMap.Hit hit=map.objects.get(i);int value=heads[i];double judged=headTimes[i];
            if((replay.mods&(128|2048))!=0&&hit.type!=8){frames.at(hit.time,sample);double y=(replay.mods&16)!=0?384-hit.y:hit.y;if(Math.hypot(hit.x-sample.x,y-sample.y)<=radius){value=300;judged=hit.time;headTimes[i]=judged;}}
            if(hit.type==8){
                OsuReplaySpinners.Spin spin=spinners.objects.get(i);
                double turns=spin.turns(hit.end),required=spin.required;
                value=turns>=required?300:turns>=required-1&&turns>0?100:turns>=required*.25&&turns>0?50:0;judged=hit.end;
            }else if(hit.type==2&&!replay.lazer){
                // Stable sliders judge completion, unlike lazer's separate head/tick/tail judgements.
                int parts=1,hits=value>0?1:0;double span=(hit.end-hit.time)/hit.repeats;
                for(int part=0;part<hit.repeats&&parts<8192;part++){
                    int ticks=Math.min(8192-parts,Math.max(0,(int)Math.floor(span/Math.max(1,hit.tickInterval)-.01)));
                    for(int tick=1;tick<=ticks;tick++){
                        double fraction=tick*hit.tickInterval/Math.max(1,span);hit.position(part%2==0?fraction:1-fraction,position);
                        frames.at(hit.time+part*span+tick*hit.tickInterval,sample);parts++;
                        if(held(sample,position,radius,replay.mods))hits++;
                    }
                    hit.position(part%2==0?1:0,position);frames.at(hit.time+(part+1)*span-(part==hit.repeats-1?36:0),sample);parts++;
                    if(Double.isFinite(OsuSliderTracking.tailTime(hit,frames,replay.mods,radius))&&part==hit.repeats-1||part<hit.repeats-1&&held(sample,position,radius,replay.mods))hits++;
                }
                int completion=hits==parts?300:hits*2>=parts?100:hits>0?50:0;
                value=(replay.mods&536870912)!=0&&value>0?Math.min(value,completion):completion;judged=hit.end;
            } else if(value<0){value=0;judged=hit.time+meh;}
            results.add(new Result(i,Math.max(0,value),judged,hit.type==8?256:hit.x,hit.type==8?192:hit.y));
        }
        results.sort(Comparator.comparingDouble(result->result.time));
    }
    private static boolean held(OsuReplayFrames.Sample sample,double[] position,double radius,long mods){return ((mods&128)!=0||(sample.keys&15)!=0)&&Math.hypot(sample.x-position[0],sample.y-((mods&16)!=0?384-position[1]:position[1]))<=radius*2.4;}
}
