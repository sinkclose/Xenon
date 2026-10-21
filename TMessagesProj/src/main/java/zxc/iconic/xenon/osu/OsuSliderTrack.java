package zxc.iconic.xenon.osu;

/** Radial profile from danser-go slidercolor.fsh; sampled by Canvas stroke bands.
 * Adapted under GPL-3.0; see danser-autoplay-GPL-3.0.txt.
 */
public final class OsuSliderTrack {
    private OsuSliderTrack(){}
    public static int color(float inward,int border,int body){
        float start=34f/512,end=start+65f/512,blend=.01f;
        int shadow=(Math.round(Math.min(1,inward/start)*.5f*(border>>>24))<<24);
        int outer=shade(body,false),inner=shade(body,true);
        int fill=mix(outer,inner,Math.max(0,Math.min(1,(inward-end)/(1-end))));
        if(inward<=start-blend)return shadow;
        if(inward<start+blend)return mix(shadow,border,(inward-start+blend)/(2*blend));
        if(inward<=end-blend)return border;
        if(inward<end+blend)return mix(border,fill,(inward-end+blend)/(2*blend));
        return fill;
    }
    private static int shade(int color,boolean light){int result=color&0xff000000;for(int shift=16;shift>=0;shift-=8){float c=(color>>shift)&255;c=light?Math.min(255,c*1.125f+63.75f):c/1.1f;result|=Math.round(c)<<shift;}return result;}
    private static int mix(int a,int b,float p){int result=0;for(int shift=24;shift>=0;shift-=8)result|=Math.round(((a>>>shift)&255)*(1-p)+((b>>>shift)&255)*p)<<shift;return result;}
}
