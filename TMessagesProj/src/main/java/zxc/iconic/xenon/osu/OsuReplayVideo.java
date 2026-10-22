package zxc.iconic.xenon.osu;
import android.content.Context;
import android.graphics.*;
import android.os.Build;
import android.net.Uri;
import android.view.TextureView;
import androidx.media3.common.*;
import androidx.media3.exoplayer.*;
import java.io.File;
import org.telegram.messenger.FileLog;

/** Muted video follows the audio clock and osu Event offset; danser's 1s entry/exit fades. */
final class OsuReplayVideo extends TextureView {
    private final ExoPlayer player;
    private final double offset;
    private final Runnable invalidate;
    private boolean rendered,broken,released;
    private double previous=Double.NaN,lastCorrection=-1;
    private int videoWidth=1,videoHeight=1;
    private float lastSigma=-1;
    OsuReplayVideo(Context context,File file,double offset,Runnable invalidate){
        super(context);this.offset=offset;this.invalidate=invalidate;setOpaque(false);setAlpha(0);
        // Background video cadence must not vote the gameplay display down to 24/30/60Hz.
        player=new ExoPlayer.Builder(context).setVideoChangeFrameRateStrategy(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF).build();player.setVolume(0);
        player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_AUDIO,true).build());
        player.setSeekParameters(SeekParameters.EXACT);player.setVideoTextureView(this);
        player.addListener(new Player.Listener(){
            @Override public void onRenderedFirstFrame(){rendered=true;invalidate.run();}
            @Override public void onVideoSizeChanged(VideoSize size){videoWidth=Math.max(1,Math.round(size.width*size.pixelWidthHeightRatio));videoHeight=Math.max(1,size.height);invalidate.run();}
            @Override public void onPlayerError(PlaybackException error){FileLog.e(error);broken=true;setAlpha(0);invalidate.run();}
        });
        player.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)));player.prepare();
    }
    float sync(double song,boolean playing,double rate,boolean enabled,float visibility,float zoom,float x,float y,float sigma){
        if(released||broken)return 0;
        long duration=player.getDuration();double target=OsuBackgroundEvent.videoPosition(song,offset);
        boolean active=enabled&&visibility>0&&duration>0&&target>=0&&target<duration;
        if(!active){player.pause();setAlpha(0);previous=Double.NaN;return 0;}
        player.setPlaybackParameters(new PlaybackParameters((float)rate));
        boolean jump=!Double.isFinite(previous)||song<previous||Math.abs(song-previous)>500;
        if(jump||(Math.abs(player.getCurrentPosition()-target)>100&&(android.os.SystemClock.uptimeMillis()-lastCorrection>250||!playing))){player.seekTo(Math.max(0,(long)target));lastCorrection=android.os.SystemClock.uptimeMillis();}
        player.setPlayWhenReady(playing);previous=song;
        if(getWidth()>0&&getHeight()>0){
            float scale=Math.max(getWidth()/(float)videoWidth,getHeight()/(float)videoHeight)*zoom;
            float w=videoWidth*scale,h=videoHeight*scale;
            x=Math.max(-(w-getWidth())/2,Math.min((w-getWidth())/2,x));y=Math.max(-(h-getHeight())/2,Math.min((h-getHeight())/2,y));
            Matrix matrix=new Matrix();matrix.setScale(w/getWidth(),h/getHeight());matrix.postTranslate((getWidth()-w)/2+x,(getHeight()-h)/2+y);setTransform(matrix);
            if(Build.VERSION.SDK_INT>=31&&Math.abs(lastSigma-sigma)>.01f){setRenderEffect(sigma<=0?null:RenderEffect.createBlurEffect(sigma,sigma,Shader.TileMode.CLAMP));lastSigma=sigma;}
        }
        // Static background is composited above this texture with inverse video opacity.
        setAlpha(rendered?1:0);return rendered?OsuBackgroundEvent.videoAlpha(song,offset,duration)*Math.min(1,visibility):0;
    }
    void pause(){if(!released)player.pause();}
    void discontinuity(){previous=Double.NaN;}
    void release(){if(released)return;released=true;player.clearVideoTextureView(this);player.release();}
}
