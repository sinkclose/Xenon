package zxc.iconic.xenon.osu;

/** SkipOverlay's fade/press transforms, in wall-clock milliseconds. */
public final class OsuLazerSkipAnimation {
    private OsuLazerSkipAnimation(){}
    public static float outExpo(float value){float t=Math.max(0,Math.min(1,value));return t==0?0:t==1?1:1-(float)Math.pow(2,-10*t)+(float)Math.pow(2,-10)*t;}
    public static float opacity(long sinceShow){return outExpo(sinceShow/500f)*(1-outExpo((sinceShow-1000)/1000f));}
    public static float pressScale(long elapsed){return 1-.25f*OsuScoreAnimation.outQuint(OsuArgonAnimation.progress(elapsed,2000));}
    public static float releaseScale(float from,long elapsed){float t=OsuArgonAnimation.progress(elapsed,1000);float easing=t==0?0:t==1?1:(float)(Math.pow(2,-10*t)*Math.sin((t-.075)*2*Math.PI/.3)+1-Math.pow(2,-11)*t);return from+(1-from)*easing;}
    public static float remaining(double time,double shownAt,double end){return (float)Math.max(0,Math.min(1,(end-time)/Math.max(1,end-shownAt)));}
}
