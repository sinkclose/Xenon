package zxc.iconic.xenon.osu;
import java.util.*;
/** Playback-time transforms: deterministic at every refresh rate and after seeking. */
final class OsuArgonTimeline {
    static final class Tween {
        final ArrayList<Double> times=new ArrayList<>();final ArrayList<Float> from=new ArrayList<>(),to=new ArrayList<>(),durations=new ArrayList<>();final ArrayList<Integer> powers=new ArrayList<>();final int power;final float initial;
        Tween(float initial,int power){this.initial=initial;this.power=power;}
        void add(double t,float target,float duration){add(t,target,duration,power);}
        void add(double t,float target,float duration,int easing){while(!times.isEmpty()&&times.get(times.size()-1)>t){int i=times.size()-1;times.remove(i);from.remove(i);to.remove(i);durations.remove(i);powers.remove(i);}
            float current=at(t);times.add(t);from.add(current);to.add(target);durations.add(duration);powers.add(easing);}
        float at(double t){int lo=0,hi=times.size();while(lo<hi){int m=(lo+hi)>>>1;if(times.get(m)<=t)lo=m+1;else hi=m;}int i=lo-1;if(i<0)return initial;if(durations.get(i)<=0)return to.get(i);float p=(float)Math.max(0,Math.min(1,(t-times.get(i))/Math.max(.001,durations.get(i))));return from.get(i)+(to.get(i)-from.get(i))*(1-(float)Math.pow(1-p,powers.get(i)));}
    }
    final Tween combo=new Tween(1,5),arrow=new Tween(0,5),score=new Tween(0,5),accuracy=new Tween(100,5),pp=new Tween(0,5);
    final Tween[] keyY=new Tween[4],keyAlpha=new Tween[4],keyWhite=new Tween[4];
    final ArrayList<Double> meterTimes=new ArrayList<>();final ArrayList<Float> meterPositions=new ArrayList<>();final ArrayList<Integer> meterValues=new ArrayList<>();
    OsuArgonTimeline(OsuReplaySession s){
        for(double t:s.hud.times){score.add(t,(float)s.hud.score(t),250);accuracy.add(t,(float)(s.hud.accuracy(t)*100),250);}
        if(s.performance!=null)for(double t:s.performance.times)pp.add(t,(float)s.performance.at(t),250);
        int old=0;for(int i=0;i<s.hud.times.length;i++){int next=s.hud.combos[i];if(next==old)continue;double t=s.hud.times[i];float scale=Math.max(.6f,Math.min(1.4f,combo.at(t)*(next>old?1.1f:.8f)));combo.add(t,scale,0);combo.add(t,1,old>1&&next==0?2000:500);old=next;}
        for(int k=0;k<4;k++){keyY[k]=new Tween(0,4);keyAlpha[k]=new Tween(.5f,4);keyWhite[k]=new Tween(0,4);}
        int previous=0;
        for(int i=0;i<s.frames.size;i++){int raw=s.frames.rawKeys(i),buttons=s.replay.lazer?((raw&5)!=0?1:0)|((raw&10)!=0?2:0)|((raw&16)!=0?4:0):((raw&4)!=0?1:0)|((raw&8)!=0?2:0)|((raw&1)!=0&&(raw&4)==0?4:0)|((raw&2)!=0&&(raw&8)==0?8:0);int changed=previous^buttons;double t=s.frames.time(i);
            for(int k=0;k<4;k++)if((changed&(1<<k))!=0){boolean down=(buttons&(1<<k))!=0;keyAlpha[k].add(t,down?1:.5f,down?10:250,down?5:4);keyWhite[k].add(t,down?1:0,down?10:200,down?5:4);if(down){keyY[k].add(t,0,0);keyY[k].add(t+10,4,60,5);}else keyY[k].add(t,0,250);}
            previous=buttons;
        }
        float average=0,meh=(float)Math.max(1,200-10*s.map.overallDifficulty(s.replay.mods));
        ArrayList<OsuReplayJudgements.Result> hits=new ArrayList<>(s.judgements.results);
        hits.sort(Comparator.comparingDouble(result->s.judgements.headTimes[result.object]));
        for(OsuReplayJudgements.Result result:hits){double tap=s.judgements.headTimes[result.object];OsuPlaybackMap.Hit h=s.map.objects.get(result.object);if(!Double.isFinite(tap)||h.type==8)continue;float pos=(float)Math.max(-100,Math.min(100,(tap-h.time)/meh*100));average=average*.9f+pos*.1f;arrow.add(tap,average,800);meterTimes.add(tap);meterPositions.add(pos);double error=Math.abs(tap-h.time),od=s.map.overallDifficulty(s.replay.mods);meterValues.add(error<80-6*od?300:error<140-8*od?100:50);}
    }
}
