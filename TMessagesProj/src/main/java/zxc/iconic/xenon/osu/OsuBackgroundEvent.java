package zxc.iconic.xenon.osu;
import java.util.*;
/** The background/video subset of osu Events CSV, including quoted commas. */
public final class OsuBackgroundEvent {
    public final boolean video;public final String file;public final double offset;
    private OsuBackgroundEvent(boolean video,String file,double offset){this.video=video;this.file=file;this.offset=offset;}
    public static OsuBackgroundEvent parse(String line){
        ArrayList<String> fields=new ArrayList<>();StringBuilder field=new StringBuilder();boolean quoted=false;
        for(int i=0;i<line.length();i++){char c=line.charAt(i);if(c=='"'){if(quoted&&i+1<line.length()&&line.charAt(i+1)=='"'){field.append(c);i++;}else quoted=!quoted;}else if(c==','&&!quoted){fields.add(field.toString().trim());field.setLength(0);}else field.append(c);}
        fields.add(field.toString().trim());if(quoted||fields.size()<3)return null;
        String type=fields.get(0);boolean video=type.equals("1")||type.equalsIgnoreCase("Video");
        if(!video&&!type.equals("0")&&!type.equalsIgnoreCase("Background"))return null;
        try{double offset=Double.parseDouble(fields.get(1));if(!Double.isFinite(offset)||Math.abs(offset)>86400000||fields.get(2).isEmpty())return null;return new OsuBackgroundEvent(video,fields.get(2),offset);}catch(NumberFormatException error){return null;}
    }
    public static double videoPosition(double song,double offset){return song-offset;}
    public static float videoAlpha(double song,double offset,double duration){double position=videoPosition(song,offset);if(position<0||duration<=0||position>=duration)return 0;return (float)Math.max(0,Math.min(1,Math.min(position/1000,(duration-position)/1000)));}
}
