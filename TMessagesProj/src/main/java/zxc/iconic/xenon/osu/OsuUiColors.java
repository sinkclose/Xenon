package zxc.iconic.xenon.osu;

public final class OsuUiColors {
    private OsuUiColors() { }
    private static final double[] STARS={.1,1.25,2,2.5,3.3,4.2,4.9,5.8,6.7,7.7,9};
    private static final int[] COLORS={0xff4290fb,0xff4fc0ff,0xff4fffd5,0xff7cff4f,0xfff6f05c,0xffff8068,0xffff4e6f,0xffc645b8,0xff6563de,0xff18158e,0xff000000};
    public static int difficulty(double stars) {
        if(!Double.isFinite(stars)||stars<.1)return 0xffaaaaaa;
        for(int i=1;i<STARS.length;i++)if(stars<STARS[i]) {
            double p=(stars-STARS[i-1])/(STARS[i]-STARS[i-1]);int a=COLORS[i-1],b=COLORS[i],out=0xff000000;
            for(int shift:new int[]{0,8,16})out|=((int)Math.round(((a>>shift)&255)*(1-p)+((b>>shift)&255)*p))<<shift;
            return out;
        } return 0xff000000;
    }
    private static double channel(int value) { double s=value/255.0;return s<=.04045?s/12.92:Math.pow((s+.055)/1.055,2.4); }
    private static double luminance(int c) {return .2126*channel((c>>16)&255)+.7152*channel((c>>8)&255)+.0722*channel(c&255);}
    public static double contrast(int a,int b) {double x=luminance(a),y=luminance(b);return (Math.max(x,y)+.05)/(Math.min(x,y)+.05);}
    /** Keep the source hue when possible, shading toward readable theme text on pale surfaces. */
    public static int readable(int foreground,int background,int text,double minimum) {
        if(contrast(foreground,background)>=minimum)return foreground;
        int target=contrast(text,background)>=minimum?text:icon(0xffffffff,background);
        for(int step=1;step<=32;step++) {
            int mixed=0xff000000;
            for(int shift:new int[]{0,8,16})mixed|=((((foreground>>shift)&255)*(32-step)+((target>>shift)&255)*step)/32)<<shift;
            if(contrast(mixed,background)>=minimum)return mixed;
        }
        return target;
    }
    public static int icon(int foreground,int background) {
        if(contrast(foreground,background)>=3)return foreground;
        return contrast(0xff202020,background)>contrast(0xffffffff,background)?0xff202020:0xffffffff;
    }
}
