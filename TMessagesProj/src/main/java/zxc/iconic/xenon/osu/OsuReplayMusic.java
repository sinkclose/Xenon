package zxc.iconic.xenon.osu;

import android.content.Context;
import android.media.PlaybackParams;
import android.net.Uri;
import androidx.media3.common.*;
import androidx.media3.exoplayer.*;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.extractor.DefaultExtractorsFactory;
import androidx.media3.extractor.mp3.Mp3Extractor;
import java.io.File;
import java.io.IOException;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Main-looper player. Position follows the audio sink, including Sonic's stretch latency. */
final class OsuReplayMusic {
    private final ExoPlayer player;
    private final OsuReplayPcmMixer mixer;
    private final CountDownLatch ready=new CountDownLatch(1);
    private volatile PlaybackException error;
    private boolean seeking;
    private Consumer<OsuReplayMusic> seekComplete,completion;
    interface ErrorListener { boolean onError(OsuReplayMusic player,int what,int extra); }
    private ErrorListener errorListener;

    OsuReplayMusic(Context context,File file,OsuReplayPcmMixer mixer){
        this.mixer=mixer;
        DefaultExtractorsFactory extractors=new DefaultExtractorsFactory()
                .setMp3ExtractorFlags(Mp3Extractor.FLAG_ENABLE_INDEX_SEEKING);
        player=new ExoPlayer.Builder(context)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(context,extractors))
                .setRenderersFactory(new DefaultRenderersFactory(context){
                    @Override protected androidx.media3.exoplayer.audio.AudioSink buildAudioSink(Context context,boolean floatOutput,boolean playbackParams){
                        androidx.media3.exoplayer.audio.AudioSink sink=new androidx.media3.exoplayer.audio.DefaultAudioSink.Builder(context).setEnableFloatOutput(floatOutput).setEnableAudioTrackPlaybackParams(false).build();
                        return new OsuReplayAudioSink(sink,mixer);
                    }
                }.setEnableAudioTrackPlaybackParams(false))
                .build();
        player.setAudioAttributes(new AudioAttributes.Builder().setUsage(C.USAGE_GAME).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),false);
        player.setSeekParameters(SeekParameters.EXACT);
        player.addListener(new Player.Listener(){
            @Override public void onPlayerError(PlaybackException failure){error=failure;ready.countDown();if(errorListener!=null)errorListener.onError(OsuReplayMusic.this,failure.errorCode,0);}
            @Override public void onEvents(Player ignored,Player.Events events){
                int state=player.getPlaybackState();
                if(state==Player.STATE_READY){ready.countDown();if(seeking){seeking=false;if(seekComplete!=null)seekComplete.accept(OsuReplayMusic.this);}}
                if(events.contains(Player.EVENT_PLAYBACK_STATE_CHANGED)&&state==Player.STATE_ENDED&&completion!=null)completion.accept(OsuReplayMusic.this);
            }
        });
        player.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)));player.prepare();
    }
    // Called only by the Watch worker; all player access remains on the main looper.
    void awaitReady()throws IOException,InterruptedException{
        if(!ready.await(30,TimeUnit.SECONDS))throw new IOException("Replay audio preparation timed out");
        if(error!=null)throw new IOException("Cannot prepare replay audio",error);
    }
    int getCurrentPosition(){return (int)player.getCurrentPosition();}
    int getDuration(){return (int)Math.max(0,player.getDuration());}
    boolean isPlaying(){return player.getPlayWhenReady()&&player.getPlaybackState()!=Player.STATE_ENDED&&error==null;}
    boolean isAdvancing(){return player.isPlaying();}
    void start(){player.play();}
    void pause(){player.pause();}
    void seekTo(int position){seeking=true;player.seekTo(position);}
    void setPlaybackParams(PlaybackParams params){player.setPlaybackParameters(new PlaybackParameters(params.getSpeed(),params.getPitch()));}
    void setVolume(float left,float right){mixer.musicVolume=(left+right)/2;player.setVolume(1);}
    void setHitSamples(java.util.Map<String,OsuWave> samples){mixer.setSamples(samples);}
    void setOnSeekCompleteListener(Consumer<OsuReplayMusic> listener){seekComplete=listener;}
    void setOnCompletionListener(Consumer<OsuReplayMusic> listener){completion=listener;}
    void setOnErrorListener(ErrorListener listener){errorListener=listener;}
    void release(){player.release();}
}
