package zxc.iconic.xenon.osu;

/** danser TargetGlider response: score 0.75 and accuracy 0.5 per 60-Hz frame. */
public final class OsuHudSmoothing {
    public double score,accuracy=100;
    private double previous=Double.NaN;
    public void reset(){previous=Double.NaN;}
    public void update(double time,long targetScore,double targetAccuracy){
        double delta=time-previous;
        if(!Double.isFinite(previous)||delta<0||delta>250){score=targetScore;accuracy=targetAccuracy;}
        else{score=glide(score,targetScore,delta,0);accuracy=glide(accuracy,targetAccuracy,delta,2);}
        previous=time;
    }
    public static double glide(double value,double target,double delta,int decimals){return Math.abs(value-target)<.5/Math.pow(10,decimals)?target:target+(value-target)*Math.pow(.75-decimals*.125,Math.max(0,delta)/16.66667);}
}
