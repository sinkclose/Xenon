package zxc.iconic.xenon.osu;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.media.*;
import org.telegram.messenger.*;
import zxc.iconic.xenon.NekoConfig;
import java.io.*;
import java.util.*;

/** Result ticks stream through one capture-friendly PCM track, independently of UI frames. */
final class OsuScorePanelSounds {
    private static final DispatchQueue decoder=new DispatchQueue("osu-result-audio");
    private static Map<String,OsuWave> sampleCache;
    private final Object completed=new Object();
    private final OsuResultsAudioTimeline timeline;
    private final Object audioLock=new Object();
    private AudioTrack track;
    private OsuResultsPcm.Sequence sequence;
    private volatile boolean released,pcmReady;
    private boolean started;
    private int playbackGeneration;
    private long wantedElapsed=-1,wantedAt;
    private MediaPlayer applause;
    private volatile boolean applauseReady,applauseFailed;
    private boolean applausePending;
    private float applauseVolume;
    OsuScorePanelSounds(Context context,OsuReplay replay){this(context,replay,true);}
    OsuScorePanelSounds(Context context,OsuReplay replay,boolean applauseEnabled){
        this(context,replay,applauseEnabled,false);
    }
    OsuScorePanelSounds(Context context,OsuReplay replay,boolean applauseEnabled,boolean postReplay){
        if(android.os.Looper.myLooper()!=android.os.Looper.getMainLooper())throw new IllegalStateException("Result sounds must be created on the UI looper");
        timeline=new OsuResultsAudioTimeline(applauseEnabled,postReplay);
        decoder.postRunnable(()->{
            AudioTrack prepared=null;
            try{
                if(sampleCache==null){
                    HashMap<String,OsuWave> loaded=new HashMap<>();
                    for(String name:new String[]{"score-panel-focus","score-panel-top-appear","swoosh-up","score-tick","badge-dink","badge-dink-max","rank-impact-fail-d","rank-impact-fail","rank-impact-pass","rank-impact-pass-ss"})
                        try(InputStream in=context.getAssets().open("osu/sounds/results/"+name+".wav")){loaded.put(name,OsuWave.read(in));}
                    sampleCache=Collections.unmodifiableMap(loaded);
                }
                if(released)return;
                OsuResultsPcm.Sequence preparedSequence=new OsuResultsPcm.Sequence(replay,sampleCache,.99f+(float)Math.random()*.2f,postReplay);
                AudioAttributes.Builder attributes=new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC);
                if(android.os.Build.VERSION.SDK_INT>=29)attributes.setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_ALL);
                AudioTrack.Builder builder=new AudioTrack.Builder().setAudioAttributes(attributes.build())
                    .setAudioFormat(new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(OsuResultsPcm.RATE).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
                    .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(Math.max(
                        AudioTrack.getMinBufferSize(OsuResultsPcm.RATE,AudioFormat.CHANNEL_OUT_STEREO,AudioFormat.ENCODING_PCM_16BIT),OsuResultsPcm.RATE/50*4));
                if(android.os.Build.VERSION.SDK_INT>=26)builder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY);
                prepared=builder.build();
                AudioTrack ready=prepared;prepared=null;
                AndroidUtilities.runOnUIThread(()->{
                    if(released){ready.release();return;}
                    track=ready;sequence=preparedSequence;pcmReady=true;synchronized(completed){completed.notifyAll();}playPendingPcm();
                });
            }catch(Exception error){FileLog.e(error);pcmReady=true;synchronized(completed){completed.notifyAll();}}
            finally{if(prepared!=null)prepared.release();}
        });
        if(!applauseEnabled)return;
        // Applause exceeds SoundPool's decoded sample limit; stream the full original sample.
        int rank=OsuScoreAnimation.rankIndex(replay.displayRank());String name=rank>=4?"s":rank==3?"a":rank==2?"b":rank==1?"c":"d";
        try{
            applause=new MediaPlayer();applause.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
            boolean legacy=false;File directory=OsuSkinStore.directory(NekoConfig.osuReplaySkin);
            if(rank>=2&&directory!=null)for(String extension:new String[]{"wav","ogg","mp3"}){File file=new File(directory,"applause."+extension);if(file.isFile()){applause.setDataSource(file.getAbsolutePath());legacy=true;break;}}
            if(!legacy)try(AssetFileDescriptor asset=context.getAssets().openFd("osu/sounds/results/applause-"+name+".wav")){applause.setDataSource(asset.getFileDescriptor(),asset.getStartOffset(),asset.getLength());}
            applause.setOnPreparedListener(player->{synchronized(completed){applauseReady=true;completed.notifyAll();}org.telegram.messenger.AndroidUtilities.runOnUIThread(this::playPendingApplause);});
            applause.setOnErrorListener((player,what,extra)->{synchronized(completed){applauseFailed=true;completed.notifyAll();}return true;});applause.prepareAsync();
        }catch(Exception error){applauseFailed=true;FileLog.e(error);}
    }

    // Visuals use the monotonic panel clock, never the coarse playback-head counter.
    long elapsed(long fallback){return fallback;}
    void awaitReady()throws InterruptedException{long deadline=android.os.SystemClock.elapsedRealtime()+5000;synchronized(completed){while(!pcmReady||applause!=null&&!applauseReady&&!applauseFailed){long left=deadline-android.os.SystemClock.elapsedRealtime();if(left<=0)break;completed.wait(left);}}}
    void update(long elapsed,OsuReplay replay){
        if(released||elapsed<0)return;
        if(wantedElapsed>=0&&elapsed<wantedElapsed){stop();timeline.reset();}
        wantedElapsed=elapsed;wantedAt=android.os.SystemClock.uptimeMillis();playPendingPcm();
        for(OsuResultsAudioTimeline.Event event:timeline.update(elapsed(elapsed),replay))if(event.name.startsWith("applause-")){applausePending=true;applauseVolume=event.volume;playPendingApplause();}
    }
    private void playPendingPcm(){
        if(released||track==null||wantedElapsed<0)return;
        synchronized(audioLock){
            try{track.setVolume(NekoConfig.osuHitsoundVolume/100f);}catch(IllegalStateException error){FileLog.e(error);return;}
            if(started)return;
            started=true;
            int token=++playbackGeneration;
            long began=android.os.SystemClock.uptimeMillis();
            long elapsed=wantedElapsed+Math.max(0,began-wantedAt);
            Thread writer=new Thread(()->streamPcm(token,began,elapsed),"osu-result-stream");
            writer.start();
        }
    }
    private void streamPcm(int token,long began,long elapsed){
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO);
        short[] block=new short[512*OsuResultsPcm.CHANNELS];
        int frame=(int)Math.min(sequence.frames,(elapsed+Math.max(0,android.os.SystemClock.uptimeMillis()-began))*OsuResultsPcm.RATE/1000);
        boolean playing=false;
        try{
            while(frame<sequence.frames){
                synchronized(audioLock){if(released||token!=playbackGeneration)return;}
                int count=sequence.render(frame,block),offset=0;
                while(offset<count){
                    int written;
                    synchronized(audioLock){
                        if(released||token!=playbackGeneration)return;
                        written=track.write(block,offset,count-offset,AudioTrack.WRITE_NON_BLOCKING);
                        if(written<0)throw new IOException("Result PCM write failed: "+written);
                        if(!playing&&written>0){track.play();playing=true;}
                    }
                    offset+=written;
                    if(written==0)Thread.sleep(2);
                }
                frame+=count/OsuResultsPcm.CHANNELS;
            }
        }catch(Exception error){FileLog.e(error);}
    }
    private void playPendingApplause(){if(released||!applausePending||!applauseReady||applauseFailed)return;try{float volume=applauseVolume*NekoConfig.osuHitsoundVolume/100f;applause.setVolume(volume,volume);applause.start();applausePending=false;}catch(IllegalStateException error){FileLog.e(error);}}
    void reset(){stop();timeline.reset();}
    void stop(){
        wantedElapsed=-1;applausePending=false;
        synchronized(audioLock){
            playbackGeneration++;started=false;if(released)return;
            if(track!=null)try{track.pause();track.flush();}catch(IllegalStateException error){FileLog.e(error);}
        }
        if(applauseReady&&!applauseFailed)try{if(applause.isPlaying())applause.pause();applause.seekTo(0);}catch(IllegalStateException error){FileLog.e(error);}
    }
    void release(){if(released)return;stop();synchronized(audioLock){released=true;if(track!=null){track.release();track=null;}}if(applause!=null){applause.setOnPreparedListener(null);applause.setOnErrorListener(null);applause.release();applause=null;}}
}
