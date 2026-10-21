package zxc.iconic.xenon.osu;

/** Visibility schedule from danser slider.SetDifficulty / NewSliderEndCircle. */
public final class OsuSliderEndpoints {
    private OsuSliderEndpoints(){}
    public static double appear(OsuPlaybackMap.Hit hit,int index,double preempt){double span=(hit.end-hit.time)/hit.repeats;return index==1?hit.time-Math.floor(preempt):hit.time+Math.floor(span*index)-Math.floor(span*2);}
    public static float alpha(OsuPlaybackMap.Hit hit,int index,double time,double preempt){double end=hit.time+Math.floor((hit.end-hit.time)/hit.repeats*index),start=appear(hit,index,preempt);return time>=end||time<start?0:(float)Math.min(1,(time-start)/Math.min(400,preempt));}
    public static float bounce(OsuPlaybackMap.Hit hit,int index,double time,double preempt){double elapsed=Math.max(0,time-appear(hit,index,preempt));return 1.3f-.3f*(float)(elapsed%300/300);}
}
