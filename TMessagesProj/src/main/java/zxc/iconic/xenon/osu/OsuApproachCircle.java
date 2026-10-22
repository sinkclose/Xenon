package zxc.iconic.xenon.osu;

/** DrawableHitCircle/DefaultApproachCircle transforms from ppy/osu (MIT). */
public final class OsuApproachCircle {
    private OsuApproachCircle(){}
    private static float progress(double value,double duration){return (float)Math.max(0,Math.min(1,value/Math.max(1,duration)));}
    public static double fadeInDuration(double preempt){return 400*Math.min(1,preempt/450);}
    public static float scale(double time,double start,double preempt){return 4-3*progress(time-start+preempt,preempt);}
    public static float alpha(double time,double start,double preempt,double headTime){
        if(time>=headTime)return 0;
        float alpha=.9f*progress(time-start+preempt,Math.min(fadeInDuration(preempt)*2,preempt));
        if(time>=start)alpha*=1-progress(time-start,50);
        return alpha;
    }
}
