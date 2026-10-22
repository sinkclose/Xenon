package zxc.iconic.xenon.osu;

/** BreakTracker + BreakOverlay timing, evaluated in map time so seeks are reversible. */
public final class OsuBreakAnimation {
    private OsuBreakAnimation(){}
    public static final double MIN_DURATION=650, FADE=325;
    private static float progress(double t){return (float)Math.max(0,Math.min(1,t));}
    public static boolean visible(double start,double end,double time){return end-start>=MIN_DURATION&&time>=start&&time<end;}
    public static float alpha(double start,double end,double time){return visible(start,end,time)?Math.min(progress((time-start)/FADE),progress((end-time)/FADE)):0;}
    public static int seconds(double end,double time){return (int)Math.ceil(Math.max(0,end-time)/1000);}
    public static float enter(double start,double time){return OsuScoreAnimation.outQuint(progress((time-start)/FADE));}
    public static float exit(double end,double time){return OsuScoreAnimation.outQuint(progress((time-(end-FADE))/FADE));}
    /** UserDimContainer: 800ms OutQuint, ending when BreakTracker leaves the period. */
    public static float lightening(double start,double end,double time){
        if(end-start<MIN_DURATION||time<start)return 0;
        double exitAt=end-FADE;
        if(time<exitAt)return OsuScoreAnimation.outQuint(progress((time-start)/800));
        float from=OsuScoreAnimation.outQuint(progress((exitAt-start)/800));
        return from*(1-OsuScoreAnimation.outQuint(progress((time-exitAt)/800)));
    }
    public static float lightening(Iterable<double[]> periods,double time){
        float value=0;for(double[] b:periods)value=Math.max(value,lightening(b[0],b[1],time));return value;
    }
    /** RemainingTimeBox follows the linear target with DampContinuously(..., 40).
     * Its analytic solution avoids stale smoothing state after a seek. */
    public static float remaining(double start,double end,double time){
        if(!visible(start,end,time))return 0;
        double duration=end-start-FADE,initial=Math.max(0,(duration-FADE)/duration),elapsed=time-start;
        double rampEnd=Math.max(0,duration-FADE),t=Math.min(elapsed,rampEnd),k=Math.log(2)/40;
        double value=initial-t/duration+1/(duration*k)-(initial+1/(duration*k))*Math.exp(-k*t);
        return progress(value*Math.exp(-k*Math.max(0,elapsed-rampEnd)));
    }
}
