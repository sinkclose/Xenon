package zxc.iconic.xenon.osu;

import android.graphics.*;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.DispatchQueue;
import java.io.*;
import java.util.*;

/** Legacy PNG sprites retain native SD dimensions, transparent padding and premultiplied alpha. */
final class OsuReplaySkin {
    private static final DispatchQueue decoder=new DispatchQueue("osu-skin-decode");
    private static final class Texture {
        final String path;final boolean asset;final int width,height;final float sd;
        Texture(String path,boolean asset,int width,int height,float sd){this.path=path;this.asset=asset;this.width=width;this.height=height;this.sd=sd;}
        InputStream open()throws IOException{return asset?ApplicationLoader.applicationContext.getAssets().open(path):new FileInputStream(path);}
    }
    private final HashMap<String,Texture> textures=new HashMap<>();
    private final OsuSkinTextureCache<Bitmap> images=new OsuSkinTextureCache<>(OsuSkinAssets.PIXEL_BUDGET);
    private final HashSet<String> pending=new HashSet<>(),failed=new HashSet<>();
    private Map<String,List<String>> animations=Collections.emptyMap();
    private final Map<String,List<String>> textureFrames=new HashMap<>();
    private volatile boolean disposed;
    private volatile Runnable invalidation;
    private final HashMap<String,float[]> sizes=new HashMap<>();
    private final HashSet<String> custom=new HashSet<>();
    final ArrayList<Integer> combos=new ArrayList<>();
    int sliderBorder=Color.WHITE,sliderTrack=0xff555555,inputOverlayText=Color.BLACK;
    boolean sliderTrackOverride;
    boolean cursorRotate=true,cursorExpand=true,cursorCentre=true,sliderBallTint,sliderBallFlip,hitCircleOverlayAboveNumber=false;
    private float skinVersion=2;
    boolean oldNumberAnimation(){return skinVersion<=1;}
    private float animationRate=-1,hitOverlap=-2,scoreOverlap,comboOverlap;
    private String hitPrefix="default",scorePrefix="score",comboPrefix="score";
    final boolean argonPro;
    OsuReplaySkin(String key){
        argonPro=OsuSkinStore.isArgonPro(key);
        File directory=OsuSkinStore.directory(key);
        if(directory!=null)readInfo(new File(directory,"skin.ini"));
        try{
            for(File file:OsuSkinAssets.files(directory))if(needed(relative(directory,file)))register(relative(directory,file),file.getAbsolutePath(),false);
            String[] fallback=ApplicationLoader.applicationContext.getAssets().list("osu/skin-default");
            if(fallback!=null)for(String file:fallback)if(OsuSkinAssets.fallbackAllowed(OsuSkinAssets.name(file),custom))register(file,"osu/skin-default/"+file,true);
        }catch(IOException error){org.telegram.messenger.FileLog.e(error);}
        if(argonPro){
            combos.addAll(Arrays.asList(0xfff17400,0xff00f135,0xff0052f1,0xfff10000,0xffe8eb00,0xff5c00f1));
            cursorRotate=false;sliderBallTint=true;
            for(String name:new String[]{"hare","tortoise","cursortrail","repeat-edge-piece","approachcircle"})register("argon-"+name+".png","osu/argon-pro/textures/"+name+".png",true);
            scorePrefix=comboPrefix="argon-counter";
            try{for(String file:ApplicationLoader.applicationContext.getAssets().list("osu/argon-pro/fonts"))register(file,"osu/argon-pro/fonts/"+file,true);}catch(IOException error){org.telegram.messenger.FileLog.e(error);}
        }
        animations=OsuSkinAssets.animations(textures.keySet());
        for(String name:textures.keySet()){List<String> frames=animations.get(OsuSkinAssets.family(name));if(frames!=null)textureFrames.put(name,frames);}
        // Construction runs on the replay worker. Warm visible sprites, not hundreds of future frames.
        ArrayList<String> warm=new ArrayList<>(textures.keySet());Collections.sort(warm);
        for(String name:warm){List<String> frames=textureFrames.get(name);
            if(!name.startsWith("spinner-")&&(frames==null||name.equals(frames.get(0))))decode(name);
        }
    }
    private static String relative(File directory,File file){return directory.toPath().relativize(file.toPath()).toString().replace('\\','/').toLowerCase(Locale.ROOT);}
    private boolean needed(String file){String name=OsuSkinAssets.name(file);return OsuSkinAssets.fontAsset(name,hitPrefix)||OsuSkinAssets.fontAsset(name,scorePrefix)||OsuSkinAssets.fontAsset(name,comboPrefix)||name.matches("(?:hitcircle|hitcircleoverlay(?:-[0-9]+)?|approachcircle|cursor|cursortrail|cursormiddle|sliderfollowcircle(?:-[0-9]+)?|sliderscorepoint|reversearrow(?:-[0-9]+)?|followpoint(?:-[0-9]+)?|sliderb(?:[0-9]+)?|sliderstartcircle(?:overlay)?|sliderendcircle(?:overlay)?|hit(?:0|50|100|300)[kg]?(?:-[0-9]+)?|scorebar-.+|inputoverlay-.+|spinner-.+|play-skip(?:-[0-9]+)?|circularmetre|lighting|star2)");}
    private void register(String file,String path,boolean asset){
        if(!file.endsWith(".png"))return;String name=OsuSkinAssets.name(file);float sd=file.endsWith("@2x.png")?2:1;
        Texture old=textures.get(name);if(old!=null&&old.sd>sd)return;
        try(InputStream input=asset?ApplicationLoader.applicationContext.getAssets().open(path):new FileInputStream(path)){
            int[] size=OsuSkinAssets.pngSize(input);textures.put(name,new Texture(path,asset,size[0],size[1],sd));
            sizes.put(name,new float[]{size[0]/sd,size[1]/sd,sd});if(!asset)custom.add(name);
        }catch(IOException error){org.telegram.messenger.FileLog.e(error);}
    }
    private void decode(String name){
        Texture texture=textures.get(name);if(texture==null||disposed||images.get(name)!=null)return;
        try(InputStream input=texture.open()){
            BitmapFactory.Options options=new BitmapFactory.Options();options.inSampleSize=OsuSkinAssets.sampleSize(texture.width,texture.height,OsuSkinAssets.PIXEL_BUDGET);
            options.inPreferredConfig=Bitmap.Config.ARGB_8888;options.inScaled=false;
            Bitmap image=BitmapFactory.decodeStream(input,null,options);
            if(image!=null&&!disposed)images.put(name,image,(long)image.getWidth()*image.getHeight());
            else synchronized(pending){failed.add(name);}
        }catch(IOException|RuntimeException error){synchronized(pending){failed.add(name);}org.telegram.messenger.FileLog.e(error);}
    }
    void setInvalidationCallback(Runnable callback){invalidation=callback;}
    private void request(String name){
        if(disposed||images.get(name)!=null||!textures.containsKey(name))return;
        synchronized(pending){if(pending.size()>=32||failed.contains(name)||!pending.add(name))return;}
        decoder.postRunnable(()->{
            try{decode(name);}finally{synchronized(pending){pending.remove(name);}}
            if(!disposed)AndroidUtilities.runOnUIThread(()->{Runnable callback=invalidation;if(!disposed&&callback!=null)callback.run();});
        });
    }
    private void readInfo(File file){
        try(BufferedReader reader=new BufferedReader(new StringReader(OsuSkinStore.readInfo(file)))){
            String line;while((line=reader.readLine())!=null){int split=line.indexOf(':');if(split<0)continue;String name=line.substring(0,split).trim().toLowerCase(Locale.ROOT),value=line.substring(split+1).split("//",2)[0].trim();
                try{switch(name){
                    case "version":if(!value.equalsIgnoreCase("latest")){float version=Float.parseFloat(value);if(Float.isFinite(version))skinVersion=version;}break;
                    case "sliderborder":sliderBorder=color(value);break;case "slidertrackoverride":sliderTrack=color(value);sliderTrackOverride=true;break;
                    case "inputoverlaytext":inputOverlayText=color(value);break;
                    case "cursorrotate":cursorRotate=value.equals("1");break;case "cursorexpand":cursorExpand=value.equals("1");break;case "cursorcentre":cursorCentre=value.equals("1");break;
                    case "allowsliderballtint":sliderBallTint=value.equals("1");break;case "sliderballflip":sliderBallFlip=value.equals("1");break;
                    case "hitcircleoverlayabovenumber":hitCircleOverlayAboveNumber=!value.equals("0");break;
                    case "animationframerate":float fps=Float.parseFloat(value);if(Float.isFinite(fps)&&fps>0)animationRate=Math.min(120,fps);break;
                    case "hitcircleprefix":hitPrefix=value.replace('\\','/').toLowerCase(Locale.ROOT);break;case "scoreprefix":scorePrefix=value.replace('\\','/').toLowerCase(Locale.ROOT);break;case "comboprefix":comboPrefix=value.replace('\\','/').toLowerCase(Locale.ROOT);break;
                    case "hitcircleoverlap":hitOverlap=overlap(value);break;case "scoreoverlap":scoreOverlap=overlap(value);break;case "combooverlap":comboOverlap=overlap(value);break;
                    default:if(name.matches("combo[0-9]+"))combos.add(color(value));
                }}catch(IllegalArgumentException ignored){}
            }
        }catch(IOException ignored){}
    }
    private static float overlap(String value){float number=Float.parseFloat(value);return Float.isFinite(number)?Math.max(-100,Math.min(100,number)):0;}
    private static int color(String text){String[] v=text.split(",");int[] rgba={255,255,255,255};for(int i=0;i<Math.min(4,v.length);i++)rgba[i]=Math.max(0,Math.min(255,Integer.parseInt(v[i].trim())));return Color.argb(rgba[3],rgba[0],rgba[1],rgba[2]);}
    String circleName(boolean slider,boolean end){String name=slider?(end?"sliderendcircle":"sliderstartcircle"):"hitcircle";return OsuSkinAssets.circle(name,custom,textures.keySet());}
    float monoWidth(String role,String text,float scale){float width=0,digit=0;int count=0;for(char ch='0';ch<='9';ch++)digit=Math.max(digit,width(glyph(role,ch)));for(char ch:text.toCharArray()){String name=glyph(role,ch);if(name==null)continue;width+=(Character.isDigit(ch)?digit:width(name))-overlapFor(role);count++;}return (width+(count==0?0:overlapFor(role)))*scale;}
    boolean monoText(Canvas canvas,Paint paint,RectF rect,String role,String text,float right,float top,float scale,float alpha){
        float digit=0;for(char ch='0';ch<='9';ch++)digit=Math.max(digit,width(glyph(role,ch)));if(digit<=0)return false;
        float left=right-monoWidth(role,text,scale);
        for(char ch:text.toCharArray()){String name=glyph(role,ch);if(name==null)continue;float advance=(Character.isDigit(ch)?digit:width(name))*scale;naturalFrame(canvas,paint,rect,name,left+advance/2,top+height(name)*scale/2,scale,alpha,null);left+=advance-overlapFor(role)*scale;}return true;
    }
    String sliderBall(double time){return has("sliderb")?frame("sliderb",time,true):"sliderb0";}
    boolean oldSpinnerStyle(){return custom.contains("spinner-background")||has("spinner-background");}
    boolean modernScorebar(){boolean selected=custom.contains("scorebar-colour")||custom.contains("scorebar-colour-0");return selected?custom.contains("scorebar-marker"):has("scorebar-marker");}
    boolean has(String name){return name!=null&&(textures.containsKey(name)||animations.containsKey(name));}
    private String frame(String name,double time,boolean loop){List<String> frames=animations.get(name);return frames==null?name:frames.get(OsuSkinAssets.frameIndex(frames.size(),time,animationRate,loop));}
    float width(String name){float[] size=sizes.get(frame(name,0,true));return size==null?0:size[0];}
    float height(String name){float[] size=sizes.get(frame(name,0,true));return size==null?0:size[1];}
    boolean natural(Canvas canvas,Paint paint,RectF rect,String name,float x,float y,float scale,float alpha,Integer tint,double time){return naturalFrame(canvas,paint,rect,frame(name,time,true),x,y,scale,alpha,tint);}
    private boolean naturalFrame(Canvas canvas,Paint paint,RectF rect,String name,float x,float y,float scale,float alpha,Integer tint){
        if(!textures.containsKey(name))return false;
        Bitmap image=images.get(name);String resolved=name;List<String> frames=textureFrames.get(name);
        if(image==null){request(name);if(frames!=null){int index=frames.indexOf(name);for(int offset=1;offset<=frames.size();offset++){String previous=frames.get(Math.floorMod(index-offset,frames.size()));image=images.get(previous);if(image!=null){resolved=previous;break;}}}}
        if(frames!=null){int index=frames.indexOf(name);for(int i=1;i<=2;i++)request(frames.get((index+i)%frames.size()));}
        // A known transparent or pending sprite suppresses procedural/default substitutes.
        if(image==null)return true;float[] size=sizes.get(resolved);float w=size[0]*scale,h=size[1]*scale;
        paint.setFilterBitmap(true);paint.setStyle(Paint.Style.FILL);paint.setColor(Color.WHITE);paint.setAlpha(Math.round(Math.max(0,Math.min(1,alpha))*(tint==null?255:Color.alpha(tint))));paint.setColorFilter(tint==null?null:new PorterDuffColorFilter(tint|0xff000000,PorterDuff.Mode.MULTIPLY));
        rect.set(x-w/2,y-h/2,x+w/2,y+h/2);canvas.drawBitmap(image,null,rect,paint);paint.setAlpha(255);paint.setColorFilter(null);return true;
    }
    /** Star fountains share one texture: resolve it once, without regex/LRU work per particle. */
    void drawKiaiStars(Canvas canvas,Paint paint,RectF rect,OsuKiaiFountain.Particle[] particles,int count,float width,float height,float opacity){
        Bitmap image=images.get("star2");if(image==null){request("star2");return;}
        float[] size=sizes.get("star2");float unit=height/768f;
        paint.setFilterBitmap(true);paint.setStyle(Paint.Style.FILL);paint.setColor(Color.WHITE);paint.setColorFilter(null);
        for(int i=0;i<count;i++){
            OsuKiaiFountain.Particle p=particles[i];float scale=p.scale*unit,w=size[0]*scale,h=size[1]*scale;
            float x=(p.side==0?0:width)+p.x*unit,y=height+p.y*unit,bound=(float)Math.hypot(w,h)*.5f;
            if(x+bound<0||x-bound>width||y+bound<0||y-bound>height||p.alpha*opacity<=0)continue;
            paint.setAlpha(Math.round(Math.min(1,p.alpha*opacity)*255));rect.set(x-w/2,y-h/2,x+w/2,y+h/2);
            canvas.save();canvas.rotate((float)(-p.angle*180/Math.PI),x,y);canvas.drawBitmap(image,null,rect,paint);canvas.restore();
        }
        paint.setAlpha(255);
    }
    boolean draw(Canvas canvas,Paint paint,RectF rect,String name,float x,float y,float width,float alpha,Integer tint){String resolved=frame(name,0,true);float w=width(resolved);return w>0&&naturalFrame(canvas,paint,rect,resolved,x,y,width/w,alpha,tint);}
    boolean judgement(Canvas canvas,Paint paint,RectF rect,int value,float x,float y,float age,float radius,float alpha){return naturalFrame(canvas,paint,rect,frame("hit"+value,age,false),x,y,radius/64,alpha,null);}
    private String prefix(String role){return role.equals("default")?hitPrefix:role.equals("combo")?comboPrefix:scorePrefix;}
    private float overlapFor(String role){return role.equals("default")?hitOverlap:role.equals("combo")?comboOverlap:scoreOverlap;}
    private String glyph(String role,char ch){if(argonPro&&!role.equals("default")&&ch=='%')return "argon-counter-percentage";return OsuSkinAssets.glyph(prefix(role),ch,textures.keySet());}
    float textHeight(String role){for(char ch='0';ch<='9';ch++){float h=height(glyph(role,ch));if(h>0)return h;}return 0;}
    float textWidth(String role,String text,float scale){float width=0;int count=0;for(char ch:text.toCharArray()){String name=glyph(role,ch);if(name==null)continue;width+=width(name)-overlapFor(role);count++;}return (width+(count==0?0:overlapFor(role)))*scale;}
    boolean text(Canvas canvas,Paint paint,RectF rect,String role,String text,float x,float top,float scale,float alpha,int align){
        if(textHeight(role)<=0)return false;
        float left=x-textWidth(role,text,scale)*(align==0?.5f:align>0?1:0);
        for(char ch:text.toCharArray()){String name=glyph(role,ch);if(name==null)continue;float w=width(name)*scale,h=height(name)*scale;naturalFrame(canvas,paint,rect,name,left+w/2,top+h/2,scale,alpha,null);left+=w-overlapFor(role)*scale;}return true;
    }
    boolean number(Canvas c,Paint p,RectF r,int value,float x,float y,float height,float alpha){float h=textHeight("default");return h>0&&text(c,p,r,"default",Integer.toString(value),x,y-height/2,height/h,alpha,0);}
    boolean scoreNumber(Canvas c,Paint p,RectF r,int value,float x,float y,float height,float alpha){float h=textHeight("score");return h>0&&text(c,p,r,"score",Integer.toString(value),x,y-height/2,height/h,alpha,0);}
    /** ArgonCounterSpriteText: native glyph size is PNG / 8, with -2 logical spacing. */
    float argonWidth(String text,float scale){
        float width=0;for(int i=0;i<text.length();i++){String name=text.charAt(i)=='.'?"argon-counter-dot":"argon-counter-5";width+=width(name)/8-2;}
        return (width+(text.isEmpty()?0:2))*scale;
    }
    void argonCounter(Canvas c,Paint p,RectF r,String text,String template,float x,float top,float scale,boolean right){
        float templateWidth=argonWidth(template,scale),actualWidth=argonWidth(text,scale);
        float edge=right?x:x+Math.max(templateWidth,actualWidth);
        float left=edge-templateWidth;
        for(int i=0;i<template.length();i++){
            String name=template.charAt(i)=='.'?"argon-counter-dot":"argon-counter-wireframes";
            float w=width(name)*scale/8,h=height(name)*scale/8;
            naturalFrame(c,p,r,name,left+w/2,top+h/2,scale/8,.25f,null);left+=w-2*scale;
        }
        left=edge-actualWidth;
        for(int i=0;i<text.length();i++){
            String name=glyph("score",text.charAt(i));float w=width(name)*scale/8,h=height(name)*scale/8;
            naturalFrame(c,p,r,name,left+w/2,top+h/2,scale/8,1,null);left+=w-2*scale;
        }
    }
    void dispose(){disposed=true;invalidation=null;images.clear();}
}
