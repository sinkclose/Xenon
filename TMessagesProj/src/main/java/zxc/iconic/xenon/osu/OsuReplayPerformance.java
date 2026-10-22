package zxc.iconic.xenon.osu;
import java.io.*;
/** Gradual rosu-pp calculations prepared once off the UI thread, indexed by playback time. */
public final class OsuReplayPerformance {
    final double[] times;
    private final double[] values;
    private OsuReplayPerformance(double[] times,double[] values){this.times=times;this.values=values;}
    static OsuReplayPerformance prepare(OsuReplaySession s)throws IOException{
        if(!s.replay.performanceSupported||s.map.objects.size()>50000)return null;
        int recorded=s.replay.great+s.replay.ok+s.replay.meh+s.replay.miss;
        boolean complete=!s.replay.autoplay&&recorded==s.map.objects.size()&&recorded>0;
        double completedAt=s.map.duration();for(OsuReplayJudgements.Result result:s.judgements.results)completedAt=Math.max(completedAt,result.time);
        double[] times=complete?java.util.stream.DoubleStream.concat(java.util.Arrays.stream(s.hud.times),java.util.stream.DoubleStream.of(completedAt)).sorted().distinct().toArray():java.util.Arrays.stream(s.hud.times).distinct().toArray();int n=times.length;if(n>300000)return null;
        int[] states=new int[n*7];int great=0,ok=0,meh=0,miss=0,next=0;
        for(int i=0;i<n;i++){
            while(next<s.judgements.results.size()&&s.judgements.results.get(next).time<=times[i]){int value=s.judgements.results.get(next++).value;switch(value){case 300:great++;break;case 100:ok++;break;case 50:meh++;break;default:miss++;}}
            int[] state=s.hud.performanceState(times[i]);int j=i*7;states[j]=state[0];states[j+1]=great;states[j+2]=ok;states[j+3]=meh;states[j+4]=miss;states[j+5]=state[1];states[j+6]=state[2];}
        double[] values=OsuDifficulty.replayPpTimeline(s.assets.map,s.replay.mods,states,s.replay.lazer,s.replay.clockRate);
        // The export stores exact aggregate results, but not the judgement time of
        // each object. At completion calculate from those authoritative counts;
        // do not rescale or redistribute errors across the reconstructed timeline.
        if(values!=null&&complete){double[] finalPerformance=OsuDifficulty.replayPerformance(s.assets.map,s.replay.mods,s.replay.performanceCounts(),s.replay.lazer,s.replay.clockRate);if(finalPerformance!=null)for(int i=0;i<times.length;i++)if(times[i]>=completedAt)values[i]=finalPerformance[1];}
        return values==null||values.length!=n?null:new OsuReplayPerformance(times,values);
    }
    public double at(double time){int lo=0,hi=times.length;while(lo<hi){int mid=(lo+hi)>>>1;if(times[mid]<=time)lo=mid+1;else hi=mid;}return lo==0?0:values[lo-1];}
}
