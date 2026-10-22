package zxc.iconic.xenon.osu;

/** Replay-time transforms from ppy/osu ArgonMainCirclePiece and ArgonReverseArrow (MIT). */
public final class OsuArgonAnimation {
    private OsuArgonAnimation(){}
    public static double followPreempt(double objectPreempt){return 800*Math.min(1,objectPreempt/450);}
    public static float followAlpha(double time,double at,double preempt){return progress(time-(at-followPreempt(preempt)),400)*(1-progress(time-at,400));}
    public static float followMove(double time,double at,double preempt){float t=progress(time-(at-followPreempt(preempt)),400);return 1-(1-t)*(1-t);}
    public static float progress(double age,double duration){return (float)Math.max(0,Math.min(1,age/duration));}
    public static float out(double age,double duration){return OsuScoreAnimation.outQuint(progress(age,duration));}
    public static float hitAlpha(double age){return age<=0?1:(float)Math.pow(1-progress(age,800),2);}
    public static float numberAlpha(double age){return 1-progress(age,75);}
    public static float spinnerCompletion(double time,double completedAt){return 1-(float)Math.exp(-Math.max(0,time-completedAt)/40);}
    public static float spinnerDiscScale(double elapsed,double preempt){
        if(elapsed<0)return .2f*out(elapsed+preempt/2,preempt/4);
        return .2f+.8f*out(elapsed,preempt/2);
    }
    // osu-framework Easing.OutElasticHalf: half the usual elastic oscillations.
    public static float elasticHalf(double age,double duration){
        float t=progress(age,duration);if(t==0||t==1)return t;
        return (float)(Math.pow(2,-10*t)*Math.sin((.5*t-.075)*2*Math.PI/.3)+1-Math.pow(2,-10)*Math.sin((.5-.075)*2*Math.PI/.3)*t);
    }
    public static float ringScale(double age){return 1-.2f*elasticHalf(age,400);}
    public static float progressHeight(long elapsed,float from,boolean expanded){float t=progress(elapsed,expanded?200:800);float easing=expanded?1-(1-t)*(1-t):OsuScoreAnimation.outQuint(t);return from+((expanded?35:10)-from)*easing;}
    public static float cursorScale(boolean held,double age){return held?1+.2f*elasticHalf(age,400):1+.2f*(float)Math.pow(1-progress(age,400),2);}
    public static float reversePulse(double age){double t=Math.max(0,age)%300;return t<35?1+.3f*(1-(float)Math.pow(1-t/35,2)):1+.3f*(float)Math.pow(1-Math.min(1,(t-35)/250),2);}
}
