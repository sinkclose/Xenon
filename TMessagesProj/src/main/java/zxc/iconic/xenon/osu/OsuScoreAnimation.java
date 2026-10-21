package zxc.iconic.xenon.osu;

/** ppy/osu AccuracyCircle: appear 200ms, rank fill 150+800ms, accuracy fill 450+3000ms. */
public final class OsuScoreAnimation {
    private OsuScoreAnimation() { }
    public static float progress(long elapsed, long delay, long duration) {
        return Math.max(0, Math.min(1, (elapsed - delay) / (float) duration));
    }
    public static float outQuint(float p) { return 1 - (float) Math.pow(1 - p, 5); }
    public static float outQuad(float p) { return 1-(1-p)*(1-p); }
    public static float outPow10(float p) { return 1 - (float) Math.pow(1 - p, 10); }
    public static float scale(long elapsed) { return outQuint(progress(elapsed, 0, 200)); }
    public static float grades(long elapsed) { return outPow10(progress(elapsed, 150, 800)); }
    public static float accuracy(long elapsed) { return outPow10(progress(elapsed, 450, 3000)); }
    public static float rank(long elapsed) { return outQuint(progress(elapsed, 1950, 200)); }
    public static int rankIndex(String rank) {
        switch(rank) {case "SS":return 5;case "S":return 4;case "A":return 3;case "B":return 2;case "C":return 1;case "D":return 0;default:return -1;}
    }
    public static float gaugeTarget(double accuracy,String rank) {
        if(rank.equals("SS"))return 1;
        double target=accuracy,spacing=2.0/360;
        for(double notch:new double[]{.95,.9,.8,.7})if(Math.abs(target-notch)<spacing/2) {target=notch+(target>=notch?1:-1)*spacing/2;break;}
        target=Math.min(1-.01-spacing/2,target);
        return (float)Math.max(0,target>=.001?target-.001:target);
    }
    public static float gauge(double accuracy,String rank,long elapsed) {
        float target=gaugeTarget(accuracy,rank);
        if(rank.equals("A")&&accuracy>=.95) target+=(.95f-1f/360-.001f-target)*outQuint(progress(elapsed,1950,70));
        return target*accuracy(elapsed);
    }
    public static float statistic(long elapsed,int index) {return outPow10(progress(elapsed,450+index*200,3000));}
    public static float statisticAlpha(long elapsed,int index){return progress(elapsed,450+index*200,100);}
    public static float accuracyStatistic(long elapsed){return outQuad(progress(elapsed,450,1500));}
    public static double accuracyPercent(double accuracy,long elapsed){double p=Math.max(0,Math.min(1,(elapsed-450)/1500.0));return Math.floor(Math.max(0,accuracy)*(1-(1-p)*(1-p))*10000)/100;}
    public static float lateStatistic(long elapsed){return outPow10(progress(elapsed,0,3000));}
    private static double counterProgress(long elapsed,long delay){double p=Math.max(0,Math.min(1,(elapsed-delay)/3000.0));return 1-Math.pow(1-p,10);}
    public static long counter(long target,long elapsed,int index){return (long)Math.rint(target*counterProgress(elapsed,450+index*200));}
    public static long lateCounter(long target,long elapsed){return (long)Math.rint(target*counterProgress(elapsed,0));}
    public static float perfectCombo(long elapsed){return progress(elapsed,650+1500,50);}
    public static long badgeTime(double accuracy,String rank,int index){
        float target=gaugeTarget(accuracy,rank);double[] thresholds={0,.7,.8,.9,.95,.99};
        if(index>rankIndex(rank)||target<=0||thresholds[index]>target)return Long.MAX_VALUE;
        double progress=Math.min(1,thresholds[index]/target);
        return 450+(long)Math.ceil((1-Math.pow(1-progress,.1))*3000);
    }
    public static float outBounce(float p){
        if(p<1/2.75f)return 7.5625f*p*p;
        if(p<2/2.75f){p-=1.5f/2.75f;return 7.5625f*p*p+.75f;}
        if(p<2.5f/2.75f){p-=2.25f/2.75f;return 7.5625f*p*p+.9375f;}
        p-=2.625f/2.75f;return 7.5625f*p*p+.984375f;
    }
    public static float rankY(long age,String rank){int index=rankIndex(rank);if(index>=3)return 0;float y=-20*(1-outBounce(progress(age,0,200)));float fall=progress(age,700,150);return y+(index==0?3*fall*fall:0);}
    public static float rankRotation(long age,String rank){float p=progress(age,700,150);return rankIndex(rank)==0?5*p*p:0;}
    public static float rankFlash(long age,String rank){return rankIndex(rank)>=5?1-progress(age,0,3000):1-outQuint(progress(age,0,1200));}
}
