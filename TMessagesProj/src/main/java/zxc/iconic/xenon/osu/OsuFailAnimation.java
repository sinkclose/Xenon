package zxc.iconic.xenon.osu;

/** Visual timings ported from ppy/osu FailAnimationContainer (MIT). Wall-clock milliseconds. */
public final class OsuFailAnimation {
    public static final int DURATION=2500;
    public final float progress, scale, rotation, objectScale, drop, objectAlpha, flash, shade, frequency;
    public OsuFailAnimation(double elapsed){
        progress=(float)Math.max(0,Math.min(1,elapsed/DURATION));
        float quart=1-(float)Math.pow(1-progress,4);
        scale=1-.15f*quart;rotation=quart;objectScale=1-.5f*progress;drop=400*progress;
        objectAlpha=Math.max(0,1-(float)elapsed/1250);flash=.6f*Math.max(0,1-(float)elapsed/1000);
        shade=.5f*progress;frequency=1-progress;
    }
    public static float objectRotation(int index){return ((index*1103515245L+12345L)&65535)/65535f*180-90;}
}
