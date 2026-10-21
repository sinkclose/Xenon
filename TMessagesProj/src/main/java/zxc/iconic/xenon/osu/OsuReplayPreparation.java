package zxc.iconic.xenon.osu;

import android.content.Context;
import android.graphics.*;
import org.telegram.messenger.*;
import zxc.iconic.xenon.NekoConfig;
import java.io.*;
import java.util.UUID;
import java.util.concurrent.*;

/** Owns every expensive viewer resource until Watch transfers it to the Activity. */
final class OsuReplayPreparation {
    final OsuReplaySession session;
    Bitmap background,resultsBackground,rawBackground;
    OsuReplaySkin skin;
    OsuReplaySounds sounds;
    OsuScorePanelSounds resultsSounds;
    OsuReplayMusic music;
    private static OsuReplayPreparation ready;
    private static String readyToken;
    private OsuReplayPreparation(OsuReplaySession session){this.session=session;}
    private static <T>T onMain(Callable<T> action)throws Exception{if(android.os.Looper.myLooper()==android.os.Looper.getMainLooper())return action.call();FutureTask<T> task=new FutureTask<>(action);AndroidUtilities.runOnUIThread(task);return task.get();}
    static OsuReplayPreparation prepare(Context context,File file,OsuReplaySession.Progress progress)throws Exception{
        return prepare(context,OsuReplaySession.prepare(file,progress,NekoConfig.osuReplayVideoBackground));
    }
    static OsuReplayPreparation prepareAutoplay(Context context,File map,File audio)throws Exception{
        return prepare(context,OsuReplaySession.autoplay(map,audio));
    }
    private static OsuReplayPreparation prepare(Context context,OsuReplaySession session)throws Exception{
        OsuReplayPreparation data=new OsuReplayPreparation(session);
        try{
            if(data.session.assets.background!=null){data.background=decodeBackground(data.session.assets.background);data.resultsBackground=decodeBackground(data.session.assets.background,10);if(android.os.Build.VERSION.SDK_INT>=31)data.rawBackground=decodeBackground(data.session.assets.background,0);}
            data.skin=new OsuReplaySkin(NekoConfig.osuReplaySkin);
            data.sounds=onMain(()->new OsuReplaySounds(context,NekoConfig.osuReplaySkin,data.session.sounds,true));
            data.resultsSounds=onMain(()->new OsuScorePanelSounds(context,data.session.replay));
            OsuReplayPcmMixer mixer=new OsuReplayPcmMixer(session.sounds,session.effects,Math.min(session.map.duration()+1000,session.health.failureTime),OsuReplaySamples.load(context,NekoConfig.osuReplaySkin));
            mixer.musicVolume=NekoConfig.osuMapMusicVolume/100f;
            data.music=onMain(()->new OsuReplayMusic(context,data.session.assets.audio,mixer));data.music.awaitReady();
            data.sounds.awaitReady();data.resultsSounds.awaitReady();
            // Fonts are shared caches: warm them on the Watch worker too.
            for(String font:new String[]{"Torus-Regular","Torus-Light","Torus-SemiBold","Venera-Bold"})OsuBitmapFont.get(font);
            return data;
        }catch(Exception error){data.dispose();throw error;}
    }
    static Bitmap decodeBackground(File file){return decodeBackground(file,NekoConfig.osuReplayBackgroundBlur*.25f);}
    static Bitmap decodeBackground(File file,float radius){
        BitmapFactory.Options options=new BitmapFactory.Options();options.inJustDecodeBounds=true;BitmapFactory.decodeFile(file.getAbsolutePath(),options);
        if(options.outWidth<=0||options.outHeight<=0)return null;
        options.inSampleSize=1;while(options.outWidth/options.inSampleSize>1920||options.outHeight/options.inSampleSize>1080)options.inSampleSize*=2;
        options.inJustDecodeBounds=false;options.inMutable=true;options.inPreferredConfig=Bitmap.Config.ARGB_8888;
        Bitmap image=BitmapFactory.decodeFile(file.getAbsolutePath(),options);
        if(image!=null&&radius>0)Utilities.stackBlurBitmap(image,Math.max(1,Math.round(radius*image.getHeight()/768f)));
        return image;
    }
    static synchronized String offer(OsuReplayPreparation data){
        if(ready!=null)ready.dispose();ready=data;String token=readyToken=UUID.randomUUID().toString();
        AndroidUtilities.runOnUIThread(()->discard(token),30000);return token;
    }
    static synchronized OsuReplayPreparation take(String token){if(token==null||!token.equals(readyToken))return null;OsuReplayPreparation data=ready;ready=null;readyToken=null;return data;}
    static synchronized void discard(String token){OsuReplayPreparation data=take(token);if(data!=null)data.dispose();}
    void dispose(){if(rawBackground!=null){rawBackground.recycle();rawBackground=null;}if(resultsBackground!=null){resultsBackground.recycle();resultsBackground=null;}if(background!=null){background.recycle();background=null;}if(skin!=null){skin.dispose();skin=null;}if(sounds!=null){sounds.release();sounds=null;}if(resultsSounds!=null){resultsSounds.release();resultsSounds=null;}if(music!=null){OsuReplayMusic abandoned=music;music=null;AndroidUtilities.runOnUIThread(abandoned::release);}}
}
