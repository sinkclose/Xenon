package zxc.iconic.xenon.osu;

/** danser camera.SetOsuViewport: 512x384 occupies 80% of the available screen. */
public final class OsuPlayfieldLayout {
    private OsuPlayfieldLayout(){}
    public static float[] fit(double[] bounds,float radius,float width,float top,float bottom,float inset){
        float height=Math.max(1,bottom-top);
        float scale=.8f*Math.min(Math.max(1,width)/512f,height/384f);
        return new float[]{scale,width/2-256*scale,top+height/2-192*scale};
    }
}
