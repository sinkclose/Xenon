package zxc.iconic.xenon.osu;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Asset-family precedence and bounded decoding shared by the skin loader and regression tests. */
public final class OsuSkinAssets {
    public static final long PIXEL_BUDGET=16L*1024*1024;
    private OsuSkinAssets(){}
    public static String name(String file){String name=file.replace('\\','/').toLowerCase(Locale.ROOT);if(name.endsWith(".png"))name=name.substring(0,name.length()-4);if(name.endsWith("@2x"))name=name.substring(0,name.length()-3);return name;}
    public static String family(String name){
        if(name.matches("sliderb[0-9]+"))return "sliderb";
        String stem=name.replaceFirst("-[0-9]+$","");
        return stem.matches("hit(?:0|50|100|300)[kg]?|scorebar-colour|sliderfollowcircle|hitcircleoverlay|reversearrow|followpoint|play-skip|spinner-.+")?stem:name;
    }
    public static boolean fallbackAllowed(String name,Set<String> selected){
        if(selected.contains(name))return false;
        String stem=family(name);
        return !selected.contains(stem)&&!selected.contains(stem+"-0")&&!(stem.equals("sliderb")&&selected.contains("sliderb0"));
    }
    public static String circle(String requested,Set<String> selected,Set<String> available){
        if(selected.contains(requested))return requested;
        if(selected.contains("hitcircle"))return "hitcircle";
        return available.contains(requested)?requested:"hitcircle";
    }
    public static int sampleSize(int width,int height,long remaining){
        if(width<1||height<1||width>16384||height>16384||(long)width*height>64L*1024*1024||remaining<1)return 0;
        int sample=1;
        // Preserve logical dimensions independently; large textures cost resolution, never the selected skin.
        long limit=Math.min(remaining,PIXEL_BUDGET);
        while((width+sample-1)/sample>8192||(height+sample-1)/sample>8192||decodedPixels(width,height,sample)>limit)sample*=2;
        return sample;
    }
    public static long decodedPixels(int width,int height,int sample){return (long)((width+sample-1)/sample)*((height+sample-1)/sample);}
    public static int[] pngSize(InputStream input)throws IOException{
        DataInputStream in=new DataInputStream(input);
        if(in.readLong()!=0x89504e470d0a1a0aL||in.readInt()!=13||in.readInt()!=0x49484452)throw new IOException("Invalid skin PNG");
        int width=in.readInt(),height=in.readInt();
        if(sampleSize(width,height,PIXEL_BUDGET)==0)throw new IOException("Skin PNG dimensions exceed limits");
        return new int[]{width,height};
    }
    public static Map<String,List<String>> animations(Set<String> names){
        HashMap<String,List<String>> result=new HashMap<>();
        for(String name:names){String stem=family(name);String separator=stem.equals("sliderb")?"":"-";
            if(result.containsKey(stem)||!names.contains(stem+separator+"0"))continue;
            ArrayList<String> frames=new ArrayList<>();for(int i=0;i<1024&&names.contains(stem+separator+i);i++)frames.add(stem+separator+i);
            result.put(stem,Collections.unmodifiableList(frames));
        }
        return Collections.unmodifiableMap(result);
    }
    public static int frameIndex(int count,double time,float fps,boolean loop){int frame=(int)Math.max(0,time*(fps>0?fps:count)/1000);return loop?frame%count:Math.min(count-1,frame);}
    public static String glyph(String prefix,char ch,Set<String> names){
        String suffix=ch=='.'?"dot":ch==','?"comma":ch=='%'?"percent":String.valueOf(ch);
        String name=prefix+"-"+suffix;return names.contains(name)?name:null;
    }
    public static int priority(String file){
        String name=name(file);
        if(name.matches("hit(?:0|50|100|300)[kg]?(?:-0)?")||name.startsWith("scorebar-")||name.startsWith("inputoverlay-"))return 0;
        if(name.matches(".*-(?:[0-9]|dot|comma|percent|x)")&&!name.startsWith("hit"))return 0;
        if(name.matches("hitcircle|hitcircleoverlay|approachcircle|sliderstartcircle(?:overlay)?|sliderendcircle(?:overlay)?|reversearrow|cursor|cursormiddle|cursortrail"))return 1;
        return name.startsWith("spinner-")?3:2;
    }
    public static List<File> files(File directory)throws IOException{
        if(directory==null||!directory.isDirectory())return Collections.emptyList();
        ArrayList<File> files=new ArrayList<>();
        try(var paths=Files.walk(directory.toPath(),8)){paths.filter(Files::isRegularFile).filter(path->path.toString().toLowerCase(Locale.ROOT).endsWith(".png")).limit(8192).forEach(path->files.add(path.toFile()));}
        files.sort(Comparator.comparingInt((File file)->priority(directory.toPath().relativize(file.toPath()).toString())).thenComparing(File::getPath));
        return files;
    }
    public static boolean fontAsset(String name,String prefix){return name.startsWith(prefix+"-")&&name.substring(prefix.length()+1).matches("[0-9]|dot|comma|percent|x");}
}
