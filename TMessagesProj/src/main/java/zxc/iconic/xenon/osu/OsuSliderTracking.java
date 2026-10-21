package zxc.iconic.xenon.osu;

/** Tail leniency uses the moving follow circle, not a key snapshot at the exact end. */
public final class OsuSliderTracking {
    private OsuSliderTracking(){}
    public static double tailTime(OsuPlaybackMap.Hit hit,OsuReplayFrames frames,long mods,double radius){
        return tailTime(hit,frames,mods,radius,hit.time);
    }
    public static double tailTime(OsuPlaybackMap.Hit hit,OsuReplayFrames frames,long mods,double radius,double headJudgedAt){
        double span=(hit.end-hit.time)/hit.repeats;
        int ticks=Math.max(0,(int)Math.floor(span/Math.max(1,hit.tickInterval)-.01));
        double last=hit.time+(hit.repeats-1)*span+ticks*hit.tickInterval;
        double start=Math.max(headJudgedAt,Math.max(last,Math.max(hit.time+(hit.end-hit.time)/2,hit.end-36)));
        if(start>hit.end)return Double.NaN;
        OsuReplayFrames.Sample sample=new OsuReplayFrames.Sample();double[] point=new double[2];
        if(held(hit,frames,start,mods,radius,sample,point))return start;
        int low=0,high=frames.size;while(low<high){int mid=(low+high)>>>1;if(frames.time(mid)<=start)low=mid+1;else high=mid;}
        for(int i=low;i<frames.size&&frames.time(i)<=hit.end;i++)if(held(hit,frames,frames.time(i),mods,radius,sample,point))return frames.time(i);
        return held(hit,frames,hit.end,mods,radius,sample,point)?hit.end:Double.NaN;
    }
    private static boolean held(OsuPlaybackMap.Hit hit,OsuReplayFrames frames,double time,long mods,double radius,OsuReplayFrames.Sample sample,double[] point){
        double p=(time-hit.time)/Math.max(1,hit.end-hit.time)*hit.repeats;int span=Math.min(hit.repeats-1,(int)p);double fraction=Math.min(1,p-span);hit.position(span%2==0?fraction:1-fraction,point);frames.at(time,sample);
        return((mods&128)!=0||(sample.keys&15)!=0)&&Math.hypot(sample.x-point[0],sample.y-((mods&16)!=0?384-point[1]:point[1]))<=radius*2.4;
    }
}
