package zxc.iconic.xenon.osu;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.media.*;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.R;
import zxc.iconic.xenon.NekoConfig;
import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

final class OsuReplaySounds {
    private final SoundPool pool=new SoundPool.Builder().setMaxStreams(24).setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build();
    private final Map<String,Integer> samples=new HashMap<>();
    private final Set<Integer> loaded=ConcurrentHashMap.newKeySet(),completed=ConcurrentHashMap.newKeySet();
    private final OsuReplaySoundEvents timeline;
    private final boolean embedded;
    private final boolean argonPro;
    private int kiaiStart,kiaiLoop,kiaiStream;private double lastFountain=Double.NaN,kiaiPrevious=Double.NaN;
    private final OsuBeatmap.State kiaiState=new OsuBeatmap.State();
    private int next;private double previous=Double.NaN;
    private final ArrayList<Integer> streams=new ArrayList<>();
    OsuReplaySounds(Context context,String key,OsuReplaySoundEvents timeline,boolean embedded){
        this.embedded=embedded;this.argonPro=OsuSkinStore.isArgonPro(key);
        this.timeline=timeline;pool.setOnLoadCompleteListener((soundPool,id,status)->{if(status==0)loaded.add(id);synchronized(completed){completed.add(id);completed.notifyAll();}});File directory=OsuSkinStore.directory(key);
        try(AssetFileDescriptor asset=context.getAssets().openFd("osu/argon-pro/sounds/skip.wav")){samples.put("skip",pool.load(asset,1));}catch(Exception error){FileLog.e(error);}
        if(embedded){load(context,directory,"failsound",true);return;}
        for(String bank:new String[]{"normal","soft","drum"})for(String sample:new String[]{"hitnormal","hitwhistle","hitfinish","hitclap","slidertick"})load(context,directory,bank+"-"+sample,true);
        kiaiStart=pool.load(context,R.raw.osu_fountain_start,1);kiaiLoop=pool.load(context,R.raw.osu_fountain_loop,1);
        load(context,directory,"combobreak",true);load(context,directory,"failsound",true);load(context,directory,"spinnerbonus",true);load(context,directory,"spinnerbonus-max",true,"spinnerbonus");
    }
    void awaitReady()throws InterruptedException{long deadline=android.os.SystemClock.elapsedRealtime()+5000;synchronized(completed){while(completed.size()<samples.size()+(embedded?0:2)){long left=deadline-android.os.SystemClock.elapsedRealtime();if(left<=0)break;completed.wait(left);}}}
    private void load(Context context,File directory,String name,boolean fallback){load(context,directory,name,fallback,null);}
    private void load(Context context,File directory,String name,boolean fallback,String alias){
        try {
            if(directory!=null)for(String selected:alias==null?new String[]{name}:new String[]{name,alias})for(String extension:new String[]{"wav","ogg","mp3"}){File file=new File(directory,selected+"."+extension);if(file.isFile()){samples.put(name,pool.load(file.getAbsolutePath(),1));return;}}
            if(fallback)try(AssetFileDescriptor asset=context.getAssets().openFd((argonPro?"osu/argon-pro/sounds/":"osu/sounds/")+name+".wav")){samples.put(name,pool.load(asset,1));}
        }catch(Exception error){FileLog.e(error);}
    }
    void skip(){Integer id=samples.get("skip");if(id!=null&&loaded.contains(id)){float volume=NekoConfig.osuHitsoundVolume/100f;pool.play(id,volume,volume,2,0,1);}}
    void fail(){stop();Integer id=samples.get("failsound");if(id!=null&&loaded.contains(id)){float volume=NekoConfig.osuHitsoundVolume/100f;int stream=pool.play(id,volume,volume,2,0,1);if(stream!=0)streams.add(stream);}}
    void seek(double time){stop();next=timeline.after(time);previous=time;}
    void update(double time,double rate){
        if(embedded)return;
        if(!Double.isFinite(previous)){next=timeline.after(time-75);}
        else if(time<previous||time-previous>300){seek(time);return;}
        while(next<timeline.events.size()&&timeline.events.get(next).time<=time){
            OsuReplaySoundEvents.Event event=timeline.events.get(next++);if(time-event.time>100)continue;
            Integer sample=samples.get(event.name);if(sample==null||!loaded.contains(sample))continue;
            float volume=event.volume*NekoConfig.osuHitsoundVolume/100f;int stream=pool.play(sample,volume,volume,1,0,(float)Math.max(.5,Math.min(2,rate)));if(stream!=0){streams.add(stream);if(streams.size()>64)streams.remove(0);}
        }previous=time;
    }
    void updateKiai(double time,double rate,OsuBeatmap map){
        if(embedded)return;
        if(map==null){if(kiaiStream!=0){pool.stop(kiaiStream);kiaiStream=0;}return;}
        map.at(time,kiaiState);float volume=NekoConfig.osuHitsoundVolume/100f;float pitch=(float)Math.max(.5,Math.min(2,rate));
        boolean continuous=Double.isFinite(kiaiPrevious)&&time>=kiaiPrevious&&time-kiaiPrevious<300;
        if((continuous||(!Double.isFinite(kiaiPrevious)&&time<100&&kiaiState.fountainStart>=0))&&Double.isFinite(kiaiState.fountainStart)&&kiaiState.fountainStart!=lastFountain&&time-kiaiState.fountainStart<100&&loaded.contains(kiaiStart)){
            int stream=pool.play(kiaiStart,volume,volume,1,0,pitch);if(stream!=0)streams.add(stream);
        }
        lastFountain=kiaiState.fountainStart;kiaiPrevious=time;
        float fade=Double.isFinite(kiaiState.fountainLoopStart)?(float)Math.max(0,Math.min(1,(kiaiState.fountainLastTrigger+1000-time)/500)):0;
        if(fade<=0){if(kiaiStream!=0)pool.stop(kiaiStream);kiaiStream=0;}
        else if(loaded.contains(kiaiLoop)){if(kiaiStream==0)kiaiStream=pool.play(kiaiLoop,volume*fade,volume*fade,1,-1,pitch);else{pool.setVolume(kiaiStream,volume*fade,volume*fade);pool.setRate(kiaiStream,pitch);}}
    }
    void stop(){if(kiaiStream!=0){pool.stop(kiaiStream);kiaiStream=0;}kiaiPrevious=Double.NaN;for(int stream:streams)pool.stop(stream);streams.clear();previous=Double.NaN;}
    void release(){stop();pool.setOnLoadCompleteListener(null);pool.release();loaded.clear();}
}
