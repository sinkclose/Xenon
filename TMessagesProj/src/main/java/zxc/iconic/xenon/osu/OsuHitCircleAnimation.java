package zxc.iconic.xenon.osu;

/** ppy/osu LegacyMainCirclePiece: circle/overlay 240ms, modern number 60ms. */
public final class OsuHitCircleAnimation {
    private OsuHitCircleAnimation(){}
    public static float scale(double age){float p=(float)Math.max(0,Math.min(1,age/240));return 1+.4f*OsuScoreAnimation.outQuad(p);}
    public static float alpha(double age){return (float)Math.max(0,Math.min(1,1-age/240));}
    public static float numberScale(double age,boolean oldSkin){return oldSkin?scale(age):1;}
    public static float numberAlpha(double age,boolean oldSkin){return oldSkin?alpha(age):(float)Math.max(0,Math.min(1,1-age/60));}
}
