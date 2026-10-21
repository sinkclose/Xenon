package zxc.iconic.xenon.osu;
import java.util.*;

/** Seekable preview statistics reconstructed locally; never interpolates the stored final score. */
public final class OsuReplayHud {
    private static final class Event {double time;int value,max,points,combo;Event(double t,int v,int m,int p,int c){time=t;value=v;max=m;points=p;combo=c;}}
    public final ArrayList<Double> comboBreakTimes=new ArrayList<>();
    public static boolean comboBreakSound(int before,int after){return before>20&&after==0;}
    private final double[] times,accuracy;
    private final long[] scores;
    private final int[] combos;
    private final int[][] keys;
    private final OsuReplayFrames frames;
    public OsuReplayHud(OsuPlaybackMap map,OsuReplayFrames frames,OsuReplay replay,OsuReplayJudgements judgements){
        this(map,frames,replay,judgements,new OsuReplaySpinners(map,frames,replay));
    }
    public OsuReplayHud(OsuPlaybackMap map,OsuReplayFrames frames,OsuReplay replay,OsuReplayJudgements judgements,OsuReplaySpinners spinners){
        this.frames=frames;keys=new int[4][frames.size];int previous=0;
        for(int i=0;i<frames.size;i++){int raw=frames.rawKeys(i),buttons=replay.lazer?((raw&5)!=0?1:0)|((raw&10)!=0?2:0)|((raw&16)!=0?4:0):((raw&4)!=0?1:0)|((raw&8)!=0?2:0)|((raw&1)!=0&&(raw&4)==0?4:0)|((raw&2)!=0&&(raw&8)==0?8:0);for(int k=0;k<4;k++)keys[k][i]=(i==0?0:keys[k][i-1])+(((buttons&~previous)&(1<<k))!=0?1:0);previous=buttons;}
        ArrayList<Event> events=new ArrayList<>();OsuReplayFrames.Sample sample=new OsuReplayFrames.Sample();double[] position=new double[2];double radius=map.radius(replay.mods)*2.4;
        double meh=200-10*map.overallDifficulty(replay.mods);
        for(OsuReplayJudgements.Result result:judgements.results){
            OsuPlaybackMap.Hit hit=map.objects.get(result.object);
            if(hit.type!=2){events.add(new Event(result.time,result.value,300,result.value,result.value==0?-1:1));continue;}
            boolean head=Double.isFinite(judgements.headTimes[result.object]);double headTime=head?judgements.headTimes[result.object]:hit.time+meh;
            events.add(new Event(headTime,replay.lazer?result.value:0,replay.lazer?300:0,head?30:0,head?1:-1));
            double span=(hit.end-hit.time)/hit.repeats;int budget=Math.min(8192,Math.max(0,250000-events.size()));
            for(int repeat=0;repeat<hit.repeats&&budget>0;repeat++){
                int ticks=Math.min(budget,Math.max(0,(int)Math.floor(span/Math.max(1,hit.tickInterval)-.01)));
                for(int tick=1;tick<=ticks&&budget-->0;tick++){double t=hit.time+repeat*span+tick*hit.tickInterval,f=tick*hit.tickInterval/Math.max(1,span);hit.position(repeat%2==0?f:1-f,position);frames.at(t,sample);boolean held=held(sample,position,radius,replay.mods);events.add(new Event(t,replay.lazer&&held?30:0,replay.lazer?30:0,held?10:0,held?1:-1));}
                if(budget--<=0)break;
                boolean tail=repeat==hit.repeats-1;double t=hit.time+(repeat+1)*span-(!replay.lazer&&tail?36:0);hit.position(repeat%2==0?1:0,position);frames.at(t,sample);double tailTime=tail?OsuSliderTracking.tailTime(hit,frames,replay.mods,map.radius(replay.mods),headTime):Double.NaN;boolean held=tail?Double.isFinite(tailTime):held(sample,position,radius,replay.mods);if(tail&&held)t=tailTime;int max=tail?150:30;
                events.add(new Event(t,replay.lazer&&held?max:0,replay.lazer?max:0,held?30:0,held?1:tail&&replay.lazer?0:-1));
            }
            if(!replay.lazer)events.add(new Event(result.time,result.value,300,result.value,0));
        }
        for(OsuReplaySpinners.Spin spin:spinners.objects.values())for(int turn=0;turn<spin.completed()&&events.size()<300000;turn++){
            if(replay.lazer&&turn>=spin.required+2+spin.maxBonus)break;
            int points=replay.lazer?(spin.bonusTurn(turn)?50:10):(spin.bonusTurn(turn)?1100:100);
            events.add(new Event(spin.turnTime(turn),0,-1,points,0));
        }
        events.sort(Comparator.comparingDouble(event->event.time));
        int n=events.size(),potentialCombo=0,accCountMax=0;double comboMax=0;
        for(Event event:events){if(event.combo!=0)potentialCombo++;if(event.max>0){comboMax+=event.max*Math.sqrt(potentialCombo);accCountMax++;}}
        times=new double[n];accuracy=new double[n];scores=new long[n];combos=new int[n];
        double raw=0,max=0,comboPart=0;long bonus=0;int combo=0,accCount=0;long score=0;
        double drain=Math.max(1,map.duration()-(map.objects.isEmpty()?0:map.objects.get(0).time));for(double[] gap:map.breaks)drain-=gap[1]-gap[0];
        double multiplier=Math.rint((map.drain(replay.mods)+map.overallDifficulty(replay.mods)+map.circleSize(replay.mods)+Math.min(16,map.objects.size()/Math.max(1,drain/1000)*8))/38*5);
        double mods=1;if((replay.mods&1)!=0)mods*=.5;if((replay.mods&2)!=0)mods*=.5;if((replay.mods&8)!=0)mods*=1.06;if((replay.mods&16)!=0)mods*=1.06;if((replay.mods&(64|512))!=0)mods*=1.12;if((replay.mods&256)!=0)mods*=.3;if((replay.mods&1024)!=0)mods*=1.12;
        for(int i=0;i<n;i++){Event event=events.get(i);int prior=Math.max(0,combo-1);if(event.combo<0){if(comboBreakSound(combo,0))comboBreakTimes.add(event.time);combo=0;}else if(event.combo>0)combo++;
            if(event.max<0)bonus+=event.points;
            if(event.max>0){raw+=event.value;max+=event.max;accCount++;if(!(event.value==0&&event.max==150))comboPart+=event.max*Math.sqrt(combo);}
            double acc=max==0?1:raw/max;
            if(replay.lazer)score=Math.round((500000*acc*comboPart/Math.max(1,comboMax)+500000*Math.pow(acc,5)*accCount/Math.max(1,accCountMax)+bonus)*mods);
            else score+=event.points+(event.max>0?(long)(event.points*prior*multiplier*mods/25):0);
            times[i]=event.time;accuracy[i]=acc;scores[i]=score;combos[i]=combo;
        }
    }
    private static boolean held(OsuReplayFrames.Sample s,double[] p,double r,long mods){return((mods&128)!=0||(s.keys&15)!=0)&&Math.hypot(s.x-p[0],s.y-((mods&16)!=0?384-p[1]:p[1]))<=r;}
    private int index(double time){int lo=0,hi=times.length;while(lo<hi){int mid=(lo+hi)>>>1;if(times[mid]<=time)lo=mid+1;else hi=mid;}return lo-1;}
    public long score(double time){int i=index(time);return i<0?0:scores[i];}
    public double accuracy(double time){int i=index(time);return i<0?1:accuracy[i];}
    public int combo(double time){int i=index(time);return i<0?0:combos[i];}
    public double lastChange(double time){int i=index(time);return i<0?Double.NEGATIVE_INFINITY:times[i];}
    public int keyCount(double time,int key){int lo=0,hi=frames.size;while(lo<hi){int mid=(lo+hi)>>>1;if(frames.time(mid)<=time)lo=mid+1;else hi=mid;}return lo==0?0:keys[key][lo-1];}
}
