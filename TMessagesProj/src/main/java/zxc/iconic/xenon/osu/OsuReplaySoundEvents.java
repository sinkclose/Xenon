package zxc.iconic.xenon.osu;

import java.util.*;

/** Successful heads, tracked slider parts, and combo breaks in beatmap time. */
public final class OsuReplaySoundEvents {
    public static final class Event {
        public final double time;public final String name;public final float volume;
        Event(double time,String name,float volume){this.time=time;this.name=name;this.volume=volume;}
    }
    public final ArrayList<Event> events=new ArrayList<>();
    public OsuReplaySoundEvents(OsuPlaybackMap map,OsuReplayFrames frames,OsuReplay replay,OsuReplayJudgements judgements){this(map,frames,replay,judgements,new OsuReplayHud(map,frames,replay,judgements));}
    public OsuReplaySoundEvents(OsuPlaybackMap map,OsuReplayFrames frames,OsuReplay replay,OsuReplayJudgements judgements,OsuReplayHud hud){
        this(map,frames,replay,judgements,hud,new OsuReplaySpinners(map,frames,replay));
    }
    public OsuReplaySoundEvents(OsuPlaybackMap map,OsuReplayFrames frames,OsuReplay replay,OsuReplayJudgements judgements,OsuReplayHud hud,OsuReplaySpinners spinners){
        for(Map.Entry<Integer,OsuReplaySpinners.Spin> entry:spinners.objects.entrySet()){
            OsuReplaySpinners.Spin spin=entry.getValue();OsuPlaybackMap.Hit hit=map.objects.get(entry.getKey());
            for(int turn=spin.required+2;turn<spin.completed();turn++)events.add(new Event(spin.turnTime(turn),spin.bonusTurn(turn)?"spinnerbonus":"spinnerbonus-max",hit.volume/100f));
        }
        for(double time:hud.comboBreakTimes)events.add(new Event(time,"combobreak",1));
        for(OsuReplayJudgements.Result result:judgements.results){OsuPlaybackMap.Hit hit=map.objects.get(result.object);if(hit.type==1&&result.value>0||hit.type==8&&result.value>0)addHit(hit,result.time);}
        OsuReplayFrames.Sample sample=new OsuReplayFrames.Sample();double[] position=new double[2];double radius=map.radius(replay.mods)*2.4;
        for(int i=0;i<map.objects.size();i++){
            OsuPlaybackMap.Hit hit=map.objects.get(i);if(hit.type!=2)continue;
            if(Double.isFinite(judgements.headTimes[i]))addHit(hit,judgements.headTimes[i],0);
            double span=(hit.end-hit.time)/hit.repeats;int budget=8192;
            for(int repeat=0;repeat<hit.repeats&&budget>0;repeat++){
                int ticks=Math.min(budget,Math.max(0,(int)Math.floor(span/Math.max(1,hit.tickInterval)-.01)));
                for(int tick=1;tick<=ticks&&budget-->0;tick++){
                    double time=hit.time+repeat*span+tick*hit.tickInterval,f=tick*hit.tickInterval/Math.max(1,span);hit.position(repeat%2==0?f:1-f,position);frames.at(time,sample);
                    if(held(sample,position,radius,replay.mods))events.add(new Event(time,bank(hit.normalSet)+"-slidertick",hit.volume/100f));
                }
                if(budget--<=0)break;
                double time=hit.time+(repeat+1)*span-(repeat==hit.repeats-1&&!replay.lazer?36:0);hit.position(repeat%2==0?1:0,position);frames.at(time,sample);if(repeat==hit.repeats-1?Double.isFinite(OsuSliderTracking.tailTime(hit,frames,replay.mods,map.radius(replay.mods),Double.isFinite(judgements.headTimes[i])?judgements.headTimes[i]:hit.time+200-10*map.overallDifficulty(replay.mods))):held(sample,position,radius,replay.mods))addHit(hit,time,repeat+1);
            }
        }
        events.sort(Comparator.comparingDouble(event->event.time));
    }
    private static boolean held(OsuReplayFrames.Sample sample,double[] point,double radius,long mods){return((mods&128)!=0||(sample.keys&15)!=0)&&Math.hypot(sample.x-point[0],sample.y-((mods&16)!=0?384-point[1]:point[1]))<=radius;}
    private static String bank(int value){return value==2?"soft":value==3?"drum":"normal";}
    private void addHit(OsuPlaybackMap.Hit hit,double time){
        addHit(hit,time,-1);
    }
    private void addHit(OsuPlaybackMap.Hit hit,double time,int edge){
        events.add(new Event(time,bank(hit.normalSet)+"-hitnormal",hit.volume/100f));
        int flags=edge>=0&&hit.edgeSounds!=null&&edge<hit.edgeSounds.length?hit.edgeSounds[edge]:hit.hitSound;
        int[] masks={2,4,8};String[] names={"hitwhistle","hitfinish","hitclap"};for(int i=0;i<3;i++)if((flags&masks[i])!=0)events.add(new Event(time,bank(hit.additionSet)+"-"+names[i],hit.volume/100f));
    }
    public int after(double time){int lo=0,hi=events.size();while(lo<hi){int mid=(lo+hi)>>>1;if(events.get(mid).time<=time)lo=mid+1;else hi=mid;}return lo;}
}
