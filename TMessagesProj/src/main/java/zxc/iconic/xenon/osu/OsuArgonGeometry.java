package zxc.iconic.xenon.osu;

/** Lazer's counter glyph units and rounded health path, independent of Android. */
public final class OsuArgonGeometry {
    private OsuArgonGeometry(){}
    public static float hudScale(float width,float height){return Math.max(.01f,Math.min(width/1024f,height/576f));}
    public static float safeInset(float width){return width*.05f;}
    public static float judgementScale(float radius){return radius/64;}
    /** OsuColour.ForHitResult used by BarHitErrorMeter.JudgementLine. */
    public static int hitErrorColour(int value){return value==300?0xff66ccff:value==100?0xff88b300:0xffffcc22;}
    public static float glyphWidth(char value){return value=='.'?6.5f:30;}
    public static float counterWidth(String text,float scale){float width=0;for(int i=0;i<text.length();i++)width+=glyphWidth(text.charAt(i))-2;return (width+(text.isEmpty()?0:2))*scale;}
    /** sh_ArgonBarPathUtils: 70/40 offsets, 10-unit fillets; sampled circular arcs. */
    public static float[] healthPath(float width,float height){
        float x1=10,y1=10,x4=width-10,y4=height-10;
        float x2=width-80,x3=width-50;
        double angle=Math.atan2(y4-y1,x3-x2),offset=10,radius=offset/Math.tan(angle/2);
        float cx1=(float)(x2-offset),cy1=(float)(y1+radius),cx2=(float)(x3+offset),cy2=(float)(y4-radius);
        float[] points=new float[88];int index=0;points[index++]=x1;points[index++]=y1;
        for(int i=0;i<=20;i++){double a=-Math.PI/2+angle*i/20;points[index++]=cx1+(float)(Math.cos(a)*radius);points[index++]=cy1+(float)(Math.sin(a)*radius);}
        for(int i=0;i<=20;i++){double a=Math.PI/2+angle*(1-i/20.0);points[index++]=cx2+(float)(Math.cos(a)*radius);points[index++]=cy2+(float)(Math.sin(a)*radius);}
        points[index++]=x4;points[index]=y4;return points;
    }
    public static float[] errorStops(double od){
        float meh=(float)Math.max(1,200-10*od),ok=(float)Math.max(0,Math.min(meh,140-8*od)),great=(float)Math.max(0,Math.min(ok,80-6*od));
        float[] stops={0,.08f,.5f-ok/meh*.5f,.5f-great/meh*.5f,.5f+great/meh*.5f,.5f+ok/meh*.5f,.92f,1};
        for(int i=1;i<stops.length-1;i++)stops[i]=Math.max(stops[i-1]+.0001f,Math.min(1-(stops.length-1-i)*.0001f,stops[i]));
        return stops;
    }
    /** Same 200 object-density buckets used by ArgonSongProgressGraph. */
    public static int[] density(OsuPlaybackMap map){
        int[] values=new int[200];if(map.objects.isEmpty())return values;
        double first=map.objects.get(0).time,interval=(map.duration()-first+1)/values.length;
        for(OsuPlaybackMap.Hit hit:map.objects){int start=(int)((hit.time-first)/interval),end=(int)((hit.end-first)/interval);for(int i=Math.max(0,start);i<=Math.min(values.length-1,end);i++)values[i]++;}return values;
    }
}
