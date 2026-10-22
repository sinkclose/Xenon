package zxc.iconic.xenon.osu;

import android.app.Activity;
import android.animation.ValueAnimator;
import android.content.*;
import android.graphics.*;
import android.media.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import org.telegram.messenger.*;
import org.telegram.messenger.MediaController;
import org.telegram.ui.Components.Bulletin;
import org.telegram.ui.Components.BulletinFactory;
import zxc.iconic.xenon.NekoConfig;
import java.io.*;
import java.util.*;

/** Local standard replay playback, driven by recorded cursor input and the audio clock. */
public final class OsuReplayActivity extends Activity {
    public static final String EXTRA_REPLAY="osu_replay_path",EXTRA_PREPARED="osu_prepared_session";
    public static final String EXTRA_AUTOPLAY_MAP="osu_autoplay_map",EXTRA_AUTOPLAY_AUDIO="osu_autoplay_audio";
    private final DispatchQueue queue=new DispatchQueue("osu-watch");
    private final Handler handler=new Handler(Looper.getMainLooper());
    private OsuReplaySession session;
    private OsuReplay replay;
    private OsuPlaybackMap map;
    private OsuReplayFrames frames;
    private OsuReplayJudgements judgements;
    private OsuReplaySounds sounds;
    private OsuReplayMusic music;
    private OsuReplayVideo video;
    private AudioManager audioManager;
    private FrameLayout root;
    private TextView status;
    private ImageButton play;
    private org.telegram.ui.Components.PlayPauseDrawable playIcon;
    private TextView seekTime;
    private LinearLayout transportTimeline;
    private int seekMaximum;
    private View skipBreak,settingsButton;
    private OsuScorePanel resultsPanel;
    private boolean resultsShown,startWhenResumed,resumeAfterDrag,resumeOnSeek;
    private float gameplayAlpha=1,gameplayScale=1,resultsBackgroundMix,resultsDimMix,resultsVideoMix;
    private ValueAnimator resultsAnimation;
    private LinearLayout controls;
    private boolean controlsVisible=true;
    private final Runnable hideControls=()->{if(controls!=null&&!this.seeking){controlsVisible=false;controls.animate().alpha(0).setDuration(180).withEndAction(()->controls.setVisibility(View.GONE)).start();}};
    private void showControls(){controlsVisible=true;controls.animate().cancel();controls.setVisibility(View.VISIBLE);controls.animate().alpha(1).setDuration(120).withEndAction(null).start();handler.removeCallbacks(hideControls);handler.postDelayed(hideControls,2500);if(skipBreak instanceof SkipButton)((SkipButton)skipBreak).reveal();}

    private org.telegram.ui.Components.SeekBarView seek;
    private Playfield field;
    private View argonProgress;
    private boolean destroyed,foreground,prepared,loading,seeking,audioSeeking,failed,importingSkin,failurePaused;
    private int generation,settingsGeneration;
    private double rate=1,failTime,endTime,finalJudgementTime;
    private long failElapsed,failLast;
    private boolean resumeAfterAudioFocus;
    private float audioFocusVolume=1;
    private final AudioManager.OnAudioFocusChangeListener audioFocus=change->runOnUiThread(()->{
        if(destroyed)return;
        if(change==AudioManager.AUDIOFOCUS_GAIN){
            audioFocusVolume=1;restoreAudio();boolean restart=resumeAfterAudioFocus;resumeAfterAudioFocus=false;if(restart)resume();
        }else if(change==AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK){
            audioFocusVolume=.2f;restoreAudio();
        }else if(change==AudioManager.AUDIOFOCUS_LOSS||change==AudioManager.AUDIOFOCUS_LOSS_TRANSIENT){
            boolean wasPlaying=prepared&&music!=null&&(music.isPlaying()||resumeOnSeek);
            pause();resumeOnSeek=false;resumeAfterAudioFocus=change==AudioManager.AUDIOFOCUS_LOSS_TRANSIENT&&wasPlaying;
        }
    });
    private final Runnable soundTick=new Runnable(){public void run(){
        if(destroyed||!foreground||!prepared||music==null||!music.isPlaying()||failed)return;
        double time=playbackTime();
        if(!seeking&&!audioSeeking){
            if(time>=session.health.failureTime){startFailure();field.invalidate();return;}
            if(OsuReplayCompletion.reached(time,endTime,failed)){setResultsVisible(true);return;}
            if(sounds!=null){sounds.update(time,rate);sounds.updateKiai(time,rate,session.effects);}
        }
        handler.postDelayed(this,8);
    }};

    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if(Build.VERSION.SDK_INT>=28){WindowManager.LayoutParams attributes=getWindow().getAttributes();attributes.layoutInDisplayCutoutMode=Build.VERSION.SDK_INT>=30?WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS:WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;getWindow().setAttributes(attributes);}
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_LAYOUT_STABLE|View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN|View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        root=new FrameLayout(this);root.setBackgroundColor(Color.BLACK);setContentView(root);
        status=new TextView(this);status.setTextColor(Color.WHITE);status.setGravity(Gravity.CENTER);status.setTextSize(17);root.addView(status,new FrameLayout.LayoutParams(-1,-1));
        audioManager=(AudioManager)getSystemService(AUDIO_SERVICE);
        OsuReplayPreparation data=OsuReplayPreparation.take(getIntent().getStringExtra(EXTRA_PREPARED));if(data!=null)install(data);else load();
    }
    private void install(OsuReplayPreparation data){
        loading=false;session=data.session;replay=session.replay;map=session.map;frames=session.frames;judgements=session.judgements;endTime=map.duration();
        rate=OsuReplayModEffects.rate(replay);
        finalJudgementTime=0;for(OsuReplayJudgements.Result result:judgements.results)finalJudgementTime=Math.max(finalJudgementTime,result.time);for(OsuPlaybackMap.Hit hit:map.objects)if(hit.type!=1)finalJudgementTime=Math.max(finalJudgementTime,hit.end);
        bind(data);
    }
    private void load(){
        if(loading||destroyed)return;loading=true;int token=++generation;
        String autoplayMap=getIntent().getStringExtra(EXTRA_AUTOPLAY_MAP),autoplayAudio=getIntent().getStringExtra(EXTRA_AUTOPLAY_AUDIO);
        status.setVisibility(View.VISIBLE);status.setText(LocaleController.getString(autoplayMap==null?R.string.OsuReplayWatchLoading:R.string.OsuAutoplayLoading));
        String path=getIntent().getStringExtra(EXTRA_REPLAY);
        queue.postRunnable(()->{
            try{
                if(path==null&&(autoplayMap==null||autoplayAudio==null))throw new IOException("Playback source missing");
                OsuReplayPreparation data=autoplayMap==null?OsuReplayPreparation.prepare(getApplicationContext(),new File(path),null):OsuReplayPreparation.prepareAutoplay(getApplicationContext(),new File(autoplayMap),new File(autoplayAudio));
                runOnUiThread(()->{if(destroyed||token!=generation){data.dispose();return;}install(data);});
            }catch(Exception error){FileLog.e(error);runOnUiThread(()->{if(destroyed||token!=generation)return;loading=false;showError();});}
        });
    }
    private void showError(){
        int message=getIntent().hasExtra(EXTRA_AUTOPLAY_MAP)?R.string.OsuAutoplayFailed:R.string.OsuReplayWatchFailed;
        status.setVisibility(View.VISIBLE);status.setText(LocaleController.getString(message));status.setOnClickListener(v->load());
        BulletinFactory.of(root,null).createSimpleBulletin(0,LocaleController.getString(message),LocaleController.getString(R.string.Retry),Bulletin.DURATION_PROLONG,this::load).show();
    }
    private ImageButton button(int icon,int label){ImageButton button=new ImageButton(this);button.setImageResource(icon);button.setColorFilter(Color.WHITE);button.setScaleType(ImageView.ScaleType.CENTER);button.setContentDescription(LocaleController.getString(label));button.setBackground(org.telegram.ui.ActionBar.Theme.createSimpleSelectorCircleDrawable(AndroidUtilities.dp(44),0x66333333,0x88777777));return button;}
    private void toggleControls(){if(controlsVisible){handler.removeCallbacks(hideControls);hideControls.run();}else showControls();}
    private void updateSeekTime(double time){if(seekTime!=null){String text=formatTime(Math.min(time,seekMaximum))+" / "+formatTime(seekMaximum);if(!text.contentEquals(seekTime.getText()))seekTime.setText(text);}}
    private static String formatTime(double time){int seconds=(int)Math.max(0,time/1000);return String.format(Locale.US,"%d:%02d",seconds/60,seconds%60);}
    private double playbackTime(){
        if(!prepared||music==null)return 0;
        return Math.max(0,Math.min(music.getDuration(),music.getCurrentPosition()));
    }
    private void preferMaximumRefresh(){
        try{Display display=getWindowManager().getDefaultDisplay();Display.Mode current=display.getMode(),best=current;for(Display.Mode mode:display.getSupportedModes())if(mode.getPhysicalWidth()==current.getPhysicalWidth()&&mode.getPhysicalHeight()==current.getPhysicalHeight()&&mode.getRefreshRate()>best.getRefreshRate())best=mode;WindowManager.LayoutParams attributes=getWindow().getAttributes();attributes.preferredDisplayModeId=best.getModeId();attributes.preferredRefreshRate=best.getRefreshRate();if(Build.VERSION.SDK_INT>=30)attributes.preferMinimalPostProcessing=true;getWindow().setAttributes(attributes);if(Build.VERSION.SDK_INT>=35){if(root!=null)root.setRequestedFrameRate(best.getRefreshRate());if(field!=null)field.setRequestedFrameRate(best.getRefreshRate());if(video!=null)video.setRequestedFrameRate(best.getRefreshRate());}}
        catch(Exception error){FileLog.e(error);}
    }
    private void bind(OsuReplayPreparation data){
        Bitmap background=data.background;OsuReplaySkin skin=data.skin;
        if(resultsAnimation!=null)resultsAnimation.cancel();if(resultsPanel!=null)resultsPanel.dispose();resultsShown=false;gameplayAlpha=1;gameplayScale=1;resultsBackgroundMix=0;resultsDimMix=0;resultsVideoMix=0;
        releasePlayer();failed=false;if(field!=null)field.dispose();root.removeAllViews();
        configureVideo(session.assets.video);
        field=new Playfield(background,data.resultsBackground,data.rawBackground,skin);root.addView(field,new FrameLayout.LayoutParams(-1,-1));field.setOnTouchListener((v,event)->field.touch(event));
        preferMaximumRefresh();
        field.setOnHoverListener((v,event)->{if(field.skin.argonPro){field.argonHud.setProgressExpanded(event.getActionMasked()!=MotionEvent.ACTION_HOVER_EXIT&&field.argonHud.hitProgress(event.getX(),event.getY(),field.getWidth(),field.getHeight()));field.invalidate();}return false;});
        controls=new LinearLayout(this);controls.setGravity(Gravity.CENTER);controls.setPadding(12,0,12,0);controls.setBackgroundColor(0x66000000);
        root.addView(controls,new FrameLayout.LayoutParams(-1,AndroidUtilities.dp(76),Gravity.BOTTOM));
        ImageButton back=button(R.drawable.ic_ab_back,R.string.Back);back.setContentDescription(LocaleController.getString(R.string.Back));controls.addView(back,new LinearLayout.LayoutParams(AndroidUtilities.dp(44),AndroidUtilities.dp(44)));back.setOnClickListener(v->finish());
        play=button(R.drawable.ic_action_pause,R.string.AccActionPause);playIcon=new org.telegram.ui.Components.PlayPauseDrawable(24);playIcon.setPause(true,false);play.setImageDrawable(playIcon);play.setContentDescription(LocaleController.getString(R.string.AccActionPause));play.setEnabled(false);controls.addView(play,new LinearLayout.LayoutParams(AndroidUtilities.dp(44),AndroidUtilities.dp(44)));
        play.setOnClickListener(v->{if(prepared){if(failed||(resultsShown&&music.getCurrentPosition()>=music.getDuration()-100)){seekTo(0,true);}else if(music.isPlaying())pause();else resume();}});
        skipBreak=new SkipButton();skipBreak.setVisibility(View.GONE);root.addView(skipBreak,new FrameLayout.LayoutParams(-1,-2,Gravity.TOP));
        skipBreak.setOnClickListener(v->{if(prepared){double target=map.skipSection(music.getCurrentPosition(),replay.mods);if(target>=0){seekTo((int)target);if(sounds!=null)sounds.skip();}}});
        LinearLayout timeline=transportTimeline=new LinearLayout(this);timeline.setVisibility(skin.argonPro?View.GONE:View.VISIBLE);timeline.setOrientation(LinearLayout.VERTICAL);timeline.setGravity(Gravity.CENTER_VERTICAL);controls.addView(timeline,new LinearLayout.LayoutParams(0,-1,1));
        seekMaximum=OsuReplayCompletion.seekEnd(endTime,session.health.failureTime);seek=new org.telegram.ui.Components.SeekBarView(this);seek.setColors(0x66ffffff,Color.WHITE);seek.setReportChanges(true);timeline.addView(seek,new LinearLayout.LayoutParams(-1,AndroidUtilities.dp(44)));
        seekTime=new TextView(this);seekTime.setTextColor(Color.WHITE);seekTime.setTextSize(12);seekTime.setGravity(Gravity.CENTER);timeline.addView(seekTime,new LinearLayout.LayoutParams(-1,AndroidUtilities.dp(20)));updateSeekTime(0);
        seek.setDelegate(new org.telegram.ui.Components.SeekBarView.SeekBarViewDelegate(){
            public void onSeekBarPressed(boolean pressed){if(pressed){resumeAfterDrag=prepared&&(music.isPlaying()||(resultsShown&&music.getCurrentPosition()>=music.getDuration()-100));seeking=true;handler.removeCallbacks(hideControls);}else{seeking=false;showControls();}}
            public void onSeekBarDrag(boolean stop,float progress){int value=Math.round(progress*seekMaximum);if(resultsShown&&value<endTime+1000)setResultsVisible(false);updateSeekTime(value);field.invalidate();if(stop){boolean continuePlaying=seeking?resumeAfterDrag:prepared&&(music.isPlaying()||resumeOnSeek);seeking=false;if(prepared)seekTo(Math.min(value,music.getDuration()),continuePlaying);showControls();}}
        });
        ImageButton restart=button(R.drawable.msg_retry,R.string.OsuReplayRestart);controls.addView(restart,new LinearLayout.LayoutParams(AndroidUtilities.dp(44),AndroidUtilities.dp(44)));restart.setOnClickListener(v->{if(prepared)seekTo(0,true);});
        ImageButton settings=button(R.drawable.msg_settings,R.string.Settings);controls.addView(settings,new LinearLayout.LayoutParams(AndroidUtilities.dp(44),AndroidUtilities.dp(44)));settingsButton=settings;settings.setOnClickListener(v->showSettings());
        controlsVisible=true;handler.postDelayed(hideControls,2500);
        status=new TextView(this);status.setTextColor(Color.WHITE);status.setGravity(Gravity.CENTER);status.setText(LocaleController.getString(R.string.Loading));root.addView(status,new FrameLayout.LayoutParams(-1,-1));
        sounds=data.sounds;music=data.music;
        resultsPanel=new OsuScorePanel(this,replay.title,null);resultsPanel.setPlayback(true);resultsPanel.setSounds(data.resultsSounds);resultsPanel.bind(replay);resultsPanel.setVisibility(View.GONE);
        root.addView(resultsPanel,new FrameLayout.LayoutParams(1,1,Gravity.CENTER));
        // A separate screen-space layer survives the gameplay/results fade and
        // remains above the results card, including its transparent view bounds.
        argonProgress=new View(this){
            @Override protected void onDraw(Canvas canvas){
                super.onDraw(canvas);if(field==null||!field.skin.argonPro)return;
                double time=seeking?seek.getProgress()*seekMaximum:playbackTime();
                float opacity=OsuReplayCompletion.progressOpacity(NekoConfig.osuReplayHudOpacity,gameplayAlpha);
                int layer=canvas.saveLayerAlpha(0,0,getWidth(),getHeight(),Math.round(opacity*255));
                field.argonHud.drawProgress(canvas,time,getWidth(),getHeight());canvas.restoreToCount(layer);
            }
        };
        argonProgress.setOnTouchListener((v,event)->field.skin.argonPro&&(field.hudSeeking||field.argonHud.hitProgress(event.getX(),event.getY(),v.getWidth(),v.getHeight()))&&field.touch(event));
        argonProgress.setOnHoverListener((v,event)->{if(!field.skin.argonPro)return false;boolean hit=event.getActionMasked()!=MotionEvent.ACTION_HOVER_EXIT&&field.argonHud.hitProgress(event.getX(),event.getY(),v.getWidth(),v.getHeight());field.argonHud.setProgressExpanded(hit);field.invalidate();return hit;});
        root.addView(argonProgress,new FrameLayout.LayoutParams(-1,-1));
        root.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->layoutResults());layoutResults();
        // Keep transport above the non-modal results card, so it can always rewind.
        controls.bringToFront();
        music.setOnSeekCompleteListener(player->{audioSeeking=false;field.hudSmoothing.reset();if(sounds!=null)sounds.seek(player.getCurrentPosition());field.invalidate();boolean resume=resumeOnSeek;resumeOnSeek=false;if(resume)resume();});
        music.setOnCompletionListener(player->{playIcon.setPause(false);play.setContentDescription(LocaleController.getString(R.string.AccActionPlay));if(!seeking&&!audioSeeking&&!failed&&player.getCurrentPosition()<session.health.failureTime&&player.getCurrentPosition()>=endTime)setResultsVisible(true);field.invalidate();});
        music.setOnErrorListener((player,what,extra)->{prepared=false;play.setEnabled(false);showError();return true;});
        prepared=true;restoreAudio();music.pause();status.setVisibility(View.GONE);play.setEnabled(true);updateSeekTime(0);
        preferMaximumRefresh();
        startWhenResumed=!foreground;if(foreground)resume();
    }
    private void configureVideo(File file){
        if(video!=null){video.release();root.removeView(video);video=null;}
        if(NekoConfig.osuReplayVideoBackground&&file!=null&&file.isFile())try{video=new OsuReplayVideo(this,file,map.videoOffset,()->{if(field!=null)field.invalidate();});root.addView(video,0,new FrameLayout.LayoutParams(-1,-1));}catch(Exception error){FileLog.e(error);}
    }
    private void layoutResults(){if(resultsPanel==null)return;int height=Math.max(1,root.getHeight()-AndroidUtilities.dp(12)),width=Math.min(root.getWidth()-AndroidUtilities.dp(24),Math.round(height/1.91f));FrameLayout.LayoutParams params=(FrameLayout.LayoutParams)resultsPanel.getLayoutParams();if(params.width!=width||params.height!=height){params.width=Math.max(1,width);params.height=height;resultsPanel.setLayoutParams(params);}}
    private void setResultsVisible(boolean visible){
        if(resultsPanel==null||resultsShown==visible)return;
        resultsShown=visible;controls.animate().cancel();if(resultsAnimation!=null)resultsAnimation.cancel();handler.removeCallbacks(hideControls);
        if(visible){handler.removeCallbacks(soundTick);if(sounds!=null)sounds.stop();resultsPanel.setVisibility(View.VISIBLE);resultsPanel.restart(0);resultsPanel.setAlpha(0);resultsPanel.setScaleX(1);resultsPanel.setScaleY(1);skipBreak.setVisibility(View.GONE);}
        else resultsPanel.stop();
        float from=gameplayAlpha,scaleFrom=gameplayScale,backgroundFrom=resultsBackgroundMix,dimFrom=resultsDimMix,videoFrom=resultsVideoMix,panelAlpha=resultsPanel.getAlpha();long began=SystemClock.uptimeMillis();
        resultsAnimation=ValueAnimator.ofFloat(0,1);resultsAnimation.setDuration(800);resultsAnimation.setInterpolator(input->input);
        resultsAnimation.addUpdateListener(animation->{long elapsed=SystemClock.uptimeMillis()-began;gameplayScale=OsuReplayCompletion.gameplayScale(elapsed,scaleFrom,visible);resultsBackgroundMix=OsuReplayCompletion.backgroundMix(elapsed,backgroundFrom,visible);resultsDimMix=OsuReplayCompletion.dimMix(elapsed,dimFrom,visible);gameplayAlpha=visible?from*OsuReplayCompletion.hudAlpha(elapsed):from+(1-from)*(1-OsuReplayCompletion.hudAlpha(elapsed));
            resultsVideoMix=OsuReplayCompletion.videoMix(elapsed,videoFrom,visible);
            if(visible){resultsPanel.setAlpha(OsuReplayCompletion.panelAlpha(elapsed));}
            else {resultsPanel.setAlpha(panelAlpha*OsuReplayCompletion.panelExitAlpha(elapsed));if(resultsPanel.getAlpha()==0)resultsPanel.setVisibility(View.GONE);}
            if(settingsButton!=null){settingsButton.setAlpha(1);settingsButton.setEnabled(!visible);}if(visible){controls.setAlpha(gameplayAlpha);if(gameplayAlpha==0){controls.setVisibility(View.GONE);controlsVisible=false;}}field.invalidate();
        });resultsAnimation.start();
        if(!visible)showControls();
    }
    private void restoreAudio(){if(music!=null&&prepared)try{music.setPlaybackParams(new PlaybackParams().setSpeed((float)rate).setPitch((replay.adjustPitch||(replay.mods&512)!=0)?(float)rate:1));float volume=NekoConfig.osuMapMusicVolume/100f*audioFocusVolume;music.setVolume(volume,volume);}catch(Exception error){FileLog.e(error);}}
    private void seekTo(int position){seekTo(position,prepared&&music.isPlaying());}
    private void seekTo(int position,boolean resume){
        if(!prepared)return;if(resultsShown&&position<endTime+1000)setResultsVisible(false);
        failed=false;failElapsed=0;restoreAudio();music.pause();resumeOnSeek=resume;
        audioSeeking=true;if(video!=null)video.discontinuity();if(sounds!=null)sounds.seek(position);music.seekTo(position);field.invalidate();
    }
    private void resume(){
        if(!prepared||destroyed||!foreground)return;if(failed){failurePaused=false;failLast=SystemClock.uptimeMillis();field.invalidate();return;}
        MessageObject playing=MediaController.getInstance().getPlayingMessageObject();if(playing!=null)MediaController.getInstance().pauseMessage(playing);
        if(audioManager.requestAudioFocus(audioFocus,AudioManager.STREAM_MUSIC,AudioManager.AUDIOFOCUS_GAIN)!=AudioManager.AUDIOFOCUS_REQUEST_GRANTED)return;
        if(audioSeeking){resumeOnSeek=true;return;}if(music.getCurrentPosition()>=music.getDuration()-100){seekTo(0,true);return;}music.start();if(sounds!=null)sounds.seek(music.getCurrentPosition());handler.removeCallbacks(soundTick);handler.post(soundTick);playIcon.setPause(true);play.setContentDescription(LocaleController.getString(R.string.AccActionPause));field.invalidate();
    }
    private void pause(){resumeAfterAudioFocus=false;if(video!=null)video.pause();if(failed)failurePaused=true;handler.removeCallbacks(soundTick);if(sounds!=null)sounds.stop();if(prepared&&music!=null){try{music.pause();}catch(IllegalStateException ignored){}if(play!=null){playIcon.setPause(false);play.setContentDescription(LocaleController.getString(R.string.AccActionPlay));}if(field!=null)field.invalidate();}}
    private void releasePlayer(){if(video!=null){video.release();video=null;}handler.removeCallbacks(soundTick);if(sounds!=null){sounds.release();sounds=null;}prepared=false;audioSeeking=false;if(music!=null){music.release();music=null;}if(audioManager!=null)audioManager.abandonAudioFocus(audioFocus);}
    private void startFailure(){failed=true;failurePaused=false;failTime=session.health.failureTime;failElapsed=0;failLast=SystemClock.uptimeMillis();handler.removeCallbacks(soundTick);if(sounds!=null)sounds.fail();}
    private OsuFailAnimation updateFailure(){
        if(foreground&&!failurePaused){long now=SystemClock.uptimeMillis();failElapsed=Math.min(OsuFailAnimation.DURATION,failElapsed+Math.max(0,now-failLast));failLast=now;}
        OsuFailAnimation animation=new OsuFailAnimation(failElapsed);
        if(prepared&&music!=null)try{
            if(animation.frequency<=.05f)music.pause();
            else if(foreground&&!failurePaused){music.setPlaybackParams(new PlaybackParams().setSpeed((float)Math.max(.1,rate*animation.frequency)).setPitch(Math.max(.1f,animation.frequency*((replay.adjustPitch||(replay.mods&512)!=0)?(float)rate:1))));}
            float volume=NekoConfig.osuMapMusicVolume/200f;music.setVolume(volume,volume);
        }catch(Exception error){FileLog.e(error);}
        return animation;
    }
    private void showSettings(){
        String oldSkin=NekoConfig.osuReplaySkin;int oldBlur=NekoConfig.osuReplayBackgroundBlur;boolean oldVideo=NekoConfig.osuReplayVideoBackground;
        OsuReplaySettingsSheet sheet=new OsuReplaySettingsSheet(this,()->{
            importingSkin=true;Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE);
            try{startActivityForResult(intent,49423);}catch(Exception error){importingSkin=false;FileLog.e(error);}
        });
        sheet.setOnDismissListener(dialog->{if(!importingSkin){
            if(!oldSkin.equals(NekoConfig.osuReplaySkin)||oldBlur!=NekoConfig.osuReplayBackgroundBlur||oldVideo!=NekoConfig.osuReplayVideoBackground)reloadSettings();
            else {restoreAudio();seek.updateStyle();field.invalidate();}
        }});sheet.show();
    }
    private void reloadSettings(){
        if(session==null)return;int token=++settingsGeneration;
        queue.postRunnable(()->{
            File foundVideo=session.assets.video;
            if(NekoConfig.osuReplayVideoBackground&&foundVideo==null&&!map.video.isEmpty()&&replay.beatmapHash!=null)try{foundVideo=OsuReplayAssets.prepare(session.assets.map,replay.beatmapHash,null,true).video;}catch(Exception error){FileLog.e(error);}
            File videoFile=foundVideo;
            java.util.Map<String,OsuWave> hitSamples=OsuReplaySamples.load(getApplicationContext(),NekoConfig.osuReplaySkin);
            Bitmap background=null,resultsBackground=null,rawBackground=null;OsuReplaySkin skin=null;
            try{if(session.assets.background!=null){background=OsuReplayPreparation.decodeBackground(session.assets.background);resultsBackground=OsuReplayPreparation.decodeBackground(session.assets.background,10);if(Build.VERSION.SDK_INT>=31)rawBackground=OsuReplayPreparation.decodeBackground(session.assets.background,0);}skin=new OsuReplaySkin(NekoConfig.osuReplaySkin);}catch(Exception error){FileLog.e(error);}
            Bitmap image=background,resultImage=resultsBackground,rawImage=rawBackground;OsuReplaySkin replacement=skin;
            runOnUiThread(()->{
                if(destroyed||token!=settingsGeneration||replacement==null){if(image!=null)image.recycle();if(resultImage!=null)resultImage.recycle();if(rawImage!=null)rawImage.recycle();if(replacement!=null)replacement.dispose();return;}
                session.assets.video=videoFile;configureVideo(videoFile);seek.updateStyle();field.replaceResources(image,resultImage,rawImage,replacement);skipBreak.requestLayout();skipBreak.invalidate();if(sounds!=null)sounds.release();sounds=new OsuReplaySounds(this,NekoConfig.osuReplaySkin,session.sounds,true);music.setHitSamples(hitSamples);restoreAudio();field.invalidate();
            });
        });
    }
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);if(request!=49423)return;importingSkin=false;
        if(result!=RESULT_OK||data==null||data.getData()==null){reloadSettings();return;}
        android.net.Uri uri=data.getData();queue.postRunnable(()->{
            String imported=null;try(InputStream input=getContentResolver().openInputStream(uri)){imported=OsuSkinStore.importSkin(input);}catch(Exception error){FileLog.e(error);}String key=imported;
            runOnUiThread(()->{if(destroyed)return;if(key!=null){NekoConfig.setOsuReplaySkin(key);BulletinFactory.of(root,null).createSuccessBulletin(LocaleController.getString(R.string.OsuSkinImported)).show();reloadSettings();}else BulletinFactory.of(root,null).createErrorBulletin(LocaleController.getString(R.string.OsuSkinImportFailed)).show();});
        });
    }
    @Override public void onWindowFocusChanged(boolean focus){super.onWindowFocusChanged(focus);if(focus&&foreground)preferMaximumRefresh();}
    @Override protected void onResume(){super.onResume();foreground=true;preferMaximumRefresh();if(resultsPanel!=null)resultsPanel.setPaused(false);if(startWhenResumed){startWhenResumed=false;resume();}failLast=SystemClock.uptimeMillis();if(failed&&field!=null)field.invalidate();}
    @Override protected void onPause(){startWhenResumed=prepared&&(music.isPlaying()||resumeOnSeek);foreground=false;resumeOnSeek=false;if(resultsPanel!=null)resultsPanel.setPaused(true);pause();if(audioManager!=null)audioManager.abandonAudioFocus(audioFocus);super.onPause();}
    @Override protected void onDestroy(){destroyed=true;if(resultsAnimation!=null)resultsAnimation.cancel();if(resultsPanel!=null)resultsPanel.dispose();handler.removeCallbacksAndMessages(null);generation++;settingsGeneration++;releasePlayer();if(field!=null)field.dispose();super.onDestroy();}

    /** Lazer SkipOverlay: centre at 70% height, 100-high full-width strip and 60-square button. */
    private final class SkipButton extends View {
        private final OsuLazerSkipRenderer skipRenderer=new OsuLazerSkipRenderer();
        private boolean available,pressed,hovered;
        private double target=Double.NaN,shownAt,previousTime=Double.NaN,periodEnd;
        private long revealed,pressedAt,releasedAt,hiddenAt;
        private float releasedFrom=1;
        SkipButton(){super(OsuReplayActivity.this);setContentDescription(LocaleController.getString(R.string.OsuReplaySkipSection));setClickable(true);setFocusable(true);}
        @Override public boolean onHoverEvent(MotionEvent event){hovered=event.getActionMasked()!=MotionEvent.ACTION_HOVER_EXIT;skipRenderer.setHovered(hovered,SystemClock.uptimeMillis());reveal();return true;}
        void reveal(){revealed=SystemClock.uptimeMillis();invalidate();}
        void update(double time,boolean active,double nextTarget){
            if(active&&(!available||target!=nextTarget||time<previousTime-100)){
                target=nextTarget;shownAt=time;reveal();
                boolean intro=!map.objects.isEmpty()&&time<map.objects.get(0).time-Math.max(1000,map.preempt(replay.mods));periodEnd=target-(intro?2000:1000);
            }
            if(!active&&available)hiddenAt=SystemClock.uptimeMillis();
            available=active;previousTime=time;setEnabled(active);
            if(active){setVisibility(View.VISIBLE);setTranslationY(root.getHeight()*.7f-getMeasuredHeight()/2f);invalidate();}
            else if(getVisibility()==View.VISIBLE)invalidate();
        }
        @Override protected void onMeasure(int widthSpec,int heightSpec){float scale=OsuArgonGeometry.hudScale(root.getWidth(),Math.max(1,field.getMeasuredHeight()));setMeasuredDimension(MeasureSpec.getSize(widthSpec),Math.max(1,Math.round(100*scale)));}
        @Override protected void onDetachedFromWindow(){skipRenderer.suspend();super.onDetachedFromWindow();}
        private float pressScale(long now){return pressed?OsuLazerSkipAnimation.pressScale(now-pressedAt):releasedAt==0?1:OsuLazerSkipAnimation.releaseScale(releasedFrom,now-releasedAt);}
        @Override public boolean onTouchEvent(MotionEvent event){
            if(!available)return false;
            if(event.getActionMasked()==MotionEvent.ACTION_DOWN){pressed=true;pressedAt=SystemClock.uptimeMillis();reveal();return true;}
            if(event.getActionMasked()==MotionEvent.ACTION_UP||event.getActionMasked()==MotionEvent.ACTION_CANCEL){releasedFrom=pressScale(SystemClock.uptimeMillis());releasedAt=SystemClock.uptimeMillis();pressed=false;reveal();if(event.getActionMasked()==MotionEvent.ACTION_UP){skipRenderer.click(SystemClock.uptimeMillis());performClick();}return true;}
            return true;
        }
        @Override protected void onDraw(Canvas c){
            long now=SystemClock.uptimeMillis();float opacity=OsuLazerSkipAnimation.opacity(now-revealed);
            if(pressed||hovered)opacity=1;
            if(!available)opacity*=1-OsuArgonAnimation.progress(now-hiddenAt,300);
            if(opacity<=0&&!available){skipRenderer.suspend();setVisibility(View.GONE);return;}
            skipRenderer.draw(c,getWidth(),getHeight(),opacity,pressScale(now),OsuLazerSkipAnimation.remaining(playbackTime(),shownAt,periodEnd),now,pressed,this);
            if(foreground&&(available||opacity>0))postInvalidateOnAnimation();
        }
    }

    private final class Playfield extends View {
        private final OsuArgonRenderer argon=new OsuArgonRenderer();
        private final OsuArgonHud argonHud=new OsuArgonHud(session);
        private final OsuLazerBreakRenderer breakOverlay=new OsuLazerBreakRenderer(session);
        private final OsuReplayKiai kiai=new OsuReplayKiai();
        private final OsuKiaiFlash kiaiFlash=new OsuKiaiFlash(session.effects);
        private final PorterDuffXfermode kiaiAdditive=new PorterDuffXfermode(PorterDuff.Mode.ADD);
        private float kiaiGlow;
        private final float[] flashlightState=new float[4];
        private final Paint flashlightPaint=new Paint(Paint.ANTI_ALIAS_FLAG);
        private final OsuReplayModEffects.Flashlight flashlight=session.flashlight;
        private void drawFlashlight(Canvas c,double time,float[] viewport){
            if(flashlight==null)return;flashlight.at(time,flashlightState);int[] colors=new int[17];float[] stops=new float[17];
            for(int i=0;i<=16;i++){float p=i/16f;stops[i]=p;colors[i]=(Math.round((flashlightState[3]+(1-flashlightState[3])*(float)Math.pow(p,5))*255)<<24);}
            flashlightPaint.setShader(new RadialGradient(flashlightState[0],flashlightState[1],Math.max(1,flashlightState[2]),colors,stops,Shader.TileMode.CLAMP));
            c.drawRect(-viewport[1]/viewport[0],-viewport[2]/viewport[0],(getWidth()-viewport[1])/viewport[0],(getHeight()-viewport[2])/viewport[0],flashlightPaint);
        }
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
        private final RectF rect=new RectF(),trackBounds=new RectF();
        private final PorterDuffXfermode trackSource=new PorterDuffXfermode(PorterDuff.Mode.SRC);
        private final Paint hudText=new Paint(Paint.ANTI_ALIAS_FLAG);
        private final OsuHudSmoothing hudSmoothing=new OsuHudSmoothing();
        private final OsuReplayParallax parallax=new OsuReplayParallax();
        private long parallaxLast;
        private boolean hudSeeking;
        boolean touch(MotionEvent event){
            int action=event.getActionMasked();
            if(skin.argonPro&&(hudSeeking||action==MotionEvent.ACTION_DOWN&&argonHud.hitProgress(event.getX(),event.getY(),getWidth(),getHeight()))){
                if(action==MotionEvent.ACTION_DOWN){hudSeeking=true;resumeAfterDrag=prepared&&(music.isPlaying()||resumeOnSeek||resultsShown&&music.getCurrentPosition()>=music.getDuration()-100);seeking=true;handler.removeCallbacks(hideControls);argonHud.setProgressExpanded(true);}
                if(action!=MotionEvent.ACTION_CANCEL){seek.setProgress(argonHud.progressAt(event.getX(),getWidth(),getHeight()));updateSeekTime(seek.getProgress()*seekMaximum);}
                if(action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_CANCEL){hudSeeking=false;seeking=false;argonHud.setProgressExpanded(false);if(action==MotionEvent.ACTION_UP&&prepared){seekTo(Math.min(Math.round(seek.getProgress()*seekMaximum),music.getDuration()),resumeAfterDrag);performClick();}}
                invalidate();return true;
            }
            if(action==MotionEvent.ACTION_UP){toggleControls();performClick();}return true;
        }
        private Bitmap background,resultsBackground,rawBackground;
        private RenderNode backgroundNode;
        private float lastBlurSigma=Float.NaN;
        private boolean nativeBlurDisabled;
        private OsuReplaySkin skin;
        private final double[] bounds={0,0,512,384},slider=new double[2],direction=new double[2],prefixEnds;
        private final IdentityHashMap<OsuPlaybackMap.Hit,Path> paths=new IdentityHashMap<>();
        private final OsuReplayFrames.Sample cursor=new OsuReplayFrames.Sample(),trailSample=new OsuReplayFrames.Sample();
        private final OsuBitmapFont font=OsuBitmapFont.get("Torus-Regular"),numbers=OsuBitmapFont.get("Torus-Light"),ranks=OsuBitmapFont.get("Venera-Bold");
        private final int[] colors={0xffffc000,0xff00ca00,0xff127cff,0xfff21839};
        Playfield(Bitmap background,Bitmap resultsBackground,Bitmap rawBackground,OsuReplaySkin skin){
            super(OsuReplayActivity.this);hudText.setTypeface(Typeface.createFromAsset(getAssets(),"osu/fonts/Quicksand-Bold.ttf"));this.background=background;this.resultsBackground=resultsBackground;this.rawBackground=rawBackground;this.skin=skin;bindSkin();prefixEnds=new double[map.objects.size()];double end=0;
            for(int i=0;i<map.objects.size();i++){OsuPlaybackMap.Hit hit=map.objects.get(i);include(hit.x,y(hit.y));end=Math.max(end,hit.end+1000);prefixEnds[i]=end;
                if(hit.px!=null){Path path=new Path();for(int j=0;j<hit.px.length;j++){float x=(float)hit.px[j],y=(float)y(hit.py[j]);include(x,y);if(j==0)path.moveTo(x,y);else path.lineTo(x,y);}paths.put(hit,path);}
            }
        }
        private void bindSkin(){if(transportTimeline!=null)transportTimeline.setVisibility(skin.argonPro?View.GONE:View.VISIBLE);skin.setInvalidationCallback(()->{invalidate();if(skipBreak!=null)skipBreak.invalidate();});}
        private void include(double x,double y){bounds[0]=Math.min(bounds[0],x);bounds[1]=Math.min(bounds[1],y);bounds[2]=Math.max(bounds[2],x);bounds[3]=Math.max(bounds[3],y);}
        void replaceResources(Bitmap image,Bitmap resultImage,Bitmap rawImage,OsuReplaySkin replacement){if(backgroundNode!=null)backgroundNode.discardDisplayList();if(rawBackground!=null&&!rawBackground.isRecycled())rawBackground.recycle();rawBackground=rawImage;nativeBlurDisabled=false;if(resultsBackground!=null&&!resultsBackground.isRecycled())resultsBackground.recycle();resultsBackground=resultImage;if(background!=null&&!background.isRecycled())background.recycle();skin.dispose();background=image;skin=replacement;bindSkin();invalidate();}
        void dispose(){breakOverlay.dispose();if(backgroundNode!=null)backgroundNode.discardDisplayList();if(rawBackground!=null&&!rawBackground.isRecycled())rawBackground.recycle();if(resultsBackground!=null&&!resultsBackground.isRecycled())resultsBackground.recycle();if(background!=null&&!background.isRecycled())background.recycle();skin.dispose();}
        private double y(double value){return (replay.mods&16)!=0?384-value:value;}
        private void circle(Canvas c,float x,float y,float radius,int color,boolean fill,float width){paint.setColor(color);paint.setStyle(fill?Paint.Style.FILL:Paint.Style.STROKE);paint.setStrokeWidth(width);c.drawCircle(x,y,radius,paint);paint.setStyle(Paint.Style.FILL);}
        private void tint(float factor){paint.setColorFilter(new ColorMatrixColorFilter(new float[]{factor,0,0,0,0,0,factor,0,0,0,0,0,factor,0,0,0,0,0,1,0}));}
        private void drawPreparedBackground(Canvas canvas){
            if(Build.VERSION.SDK_INT>=31&&canvas.isHardwareAccelerated()&&!nativeBlurDisabled&&rawBackground!=null&&!rawBackground.isRecycled())try{
                if(backgroundNode==null)backgroundNode=new RenderNode("osu-background");
                backgroundNode.setPosition(0,0,getWidth(),getHeight());
                float sigma=OsuReplayCompletion.blurSigma(NekoConfig.osuReplayBackgroundBlur,resultsBackgroundMix)*getHeight()/768f;
                if(!Float.isFinite(lastBlurSigma)||Math.abs(sigma-lastBlurSigma)>.001f){backgroundNode.setRenderEffect(sigma<=0?null:RenderEffect.createBlurEffect(sigma,sigma,Shader.TileMode.CLAMP));lastBlurSigma=sigma;}
                Canvas recording=backgroundNode.beginRecording(getWidth(),getHeight());
                try{recording.drawBitmap(rawBackground,null,rect,paint);}finally{backgroundNode.endRecording();}
                canvas.drawRenderNode(backgroundNode);return;
            }catch(RuntimeException error){FileLog.e(error);nativeBlurDisabled=true;}
            canvas.drawBitmap(background,null,rect,paint);
            if(resultsBackground!=null&&!resultsBackground.isRecycled()&&resultsBackgroundMix>0){paint.setAlpha(Math.round(resultsBackgroundMix*255));canvas.drawBitmap(resultsBackground,null,rect,paint);paint.setAlpha(255);}
        }
        private void drawJudgements(Canvas c,double time,float radius){
            List<OsuReplayJudgements.Result> results=judgements.results;int lo=0,hi=results.size();while(lo<hi){int mid=(lo+hi)>>>1;if(results.get(mid).time<time-1100)lo=mid+1;else hi=mid;}
            for(int i=lo;i<results.size();i++){OsuReplayJudgements.Result result=results.get(i);if(result.time>time)break;
                if(skin.argonPro){argon.judgement(c,result.value,(float)result.x,(float)y(result.y),time-result.time,result.object,radius);continue;}
                float age=(float)(time-result.time),alpha=Math.min(1,age/120)*Math.max(0,Math.min(1,(1100-age)/600)),x=(float)result.x,y=(float)y(result.y)+(result.value==0?-5+45*age/1100:0);
                float pulse=age<96?.6f+.5f*age/96:age<144?1.1f-.2f*(age-96)/48:age<168?.9f+.1f*(age-144)/24:1;
                c.save();if(result.value==0)c.rotate(age/1100*12,x,y);skin.judgement(c,paint,rect,result.value,x,y,age,radius*pulse,alpha);c.restore();
            }
        }
        @Override protected void onDraw(Canvas c){
            super.onDraw(c);paint.setColor(Color.WHITE);paint.setAlpha(255);paint.setColorFilter(null);paint.setStyle(Paint.Style.FILL);double time=seeking?seek.getProgress()*seekMaximum:playbackTime();
            if(!failed&&!seeking&&!audioSeeking&&prepared&&music.isPlaying()&&time>=session.health.failureTime)startFailure();
            OsuFailAnimation failure=failed?updateFailure():null;if(failed)time=failTime;
            float width=getWidth(),height=getHeight(),unit=Math.min(width/900f,height/430f);
            float[] viewport=OsuPlayfieldLayout.fit(bounds,(float)map.radius(replay.mods),width,0,height,0);
            frames.at(time,cursor);long parallaxNow=SystemClock.uptimeMillis();
            float cursorScreenX=cursor.x*viewport[0]+viewport[1],cursorScreenY=cursor.y*viewport[0]+viewport[2];
            double parallaxElapsed=parallaxLast==0?0:parallaxNow-parallaxLast;
            parallax.update(width,height,cursorScreenX,cursorScreenY,NekoConfig.osuReplayBackgroundParallax,parallaxElapsed,resultsShown);parallaxLast=parallaxNow;
            float videoAlpha=video==null?0:video.sync(time,foreground&&prepared&&music.isAdvancing()&&!seeking&&!audioSeeking&&!failed,rate,NekoConfig.osuReplayVideoBackground,1-resultsVideoMix,parallax.scale,parallax.x,parallax.y,OsuReplayCompletion.blurSigma(NekoConfig.osuReplayBackgroundBlur,resultsBackgroundMix)*height/768);
            if(background!=null&&!background.isRecycled()){
                int backgroundLayer=c.saveLayerAlpha(0,0,width,height,Math.round((1-videoAlpha)*255));
                float scale=Math.max(width/background.getWidth(),getHeight()/(float)background.getHeight())*parallax.scale,w=background.getWidth()*scale,h=background.getHeight()*scale;
                // Limit movement to the current overscan while zoom is still easing in.
                float offsetX=Math.max(-(w-width)/2,Math.min((w-width)/2,parallax.x)),offsetY=Math.max(-(h-height)/2,Math.min((h-height)/2,parallax.y));
                rect.set((width-w)/2+offsetX,(height-h)/2+offsetY,(width+w)/2+offsetX,(height+h)/2+offsetY);paint.setColor(Color.WHITE);if(failed)tint(1-.7f*Math.min(1,failElapsed/60f));drawPreparedBackground(c);paint.setColorFilter(null);c.restoreToCount(backgroundLayer);
            }
            if((background==null||background.isRecycled())&&videoAlpha<1)c.drawColor(Math.round((1-videoAlpha)*255)<<24);
            float lightening=failed?0:Math.max(OsuBreakAnimation.lightening(map.breaks,time),OsuReplayCompletion.endingLightening(time,finalJudgementTime));
            c.drawColor(Math.round((1-OsuReplayCompletion.backgroundBrightness(NekoConfig.osuReplayBackgroundDim,resultsBackgroundMix,resultsDimMix,lightening))*255)<<24);
            if(!resultsShown&&!seeking&&!audioSeeking&&prepared&&music.isPlaying()&&OsuReplayCompletion.reached(time,endTime,failed))setResultsVisible(true);
            // Add stars directly to the dimmed background; a transparent gameplay layer
            // would turn their additive blend into source-over when it is composited.
            int fountainTransform=c.save();
            c.scale(gameplayScale,gameplayScale,width/2,height/2);
            kiai.draw(c,session.effects,time,width,height,skin,gameplayAlpha);
            c.restoreToCount(fountainTransform);
            int gameplayLayer=c.saveLayerAlpha(0,0,width,height,Math.round(gameplayAlpha*255));
            c.scale(gameplayScale,gameplayScale,width/2,height/2);
            int contentLayer=-1;
            if(failed){tint(1-failure.shade);contentLayer=c.saveLayer(0,0,width,height,paint);paint.setColorFilter(null);c.scale(failure.scale,failure.scale,width/2,height/2);c.rotate(failure.rotation,width/2,height/2);}
            kiaiGlow=kiaiFlash.alpha(time,NekoConfig.osuReplayKiaiIntensity);
            float radius=(float)map.radius(replay.mods);double preempt=Math.max(100,map.preempt(replay.mods));
            int objectsLayer=c.saveLayerAlpha(0,0,width,height,Math.round(NekoConfig.osuReplayObjectOpacity*2.55f*(failed?failure.objectAlpha:1)));
            c.save();c.translate(viewport[1],viewport[2]);c.scale(viewport[0],viewport[0]);
            int lo=0,hi=map.objects.size();while(lo<hi){int mid=(lo+hi)>>>1;if(prefixEnds[mid]<time-180)lo=mid+1;else hi=mid;}int first=lo;
            hi=map.objects.size();while(lo<hi){int mid=(lo+hi)>>>1;if(map.objects.get(mid).time<=time+preempt)lo=mid+1;else hi=mid;}
            if(skin.argonPro)drawArgonFollowPoints(c,time,preempt,radius,first,lo);
            for(int i=lo-1;i>=first&&i>=lo-500;i--){
                OsuPlaybackMap.Hit hit=map.objects.get(i);double visibleEnd=hit.type==1?(Double.isFinite(judgements.headTimes[i])?judgements.headTimes[i]:hit.time+200):hit.end;
                if(time>visibleEnd+(skin.argonPro?800:240))continue;double before=hit.time-time;
                float alpha=(float)Math.min(1,(preempt-before)/OsuApproachCircle.fadeInDuration(preempt));if(time>visibleEnd)alpha*=skin.argonPro?OsuArgonAnimation.hitAlpha(time-visibleEnd):Math.max(0,1-(float)(time-visibleEnd)/240);
                if(OsuReplayModEffects.hidden(replay)&&hit.type!=8)alpha=OsuReplayModEffects.circleAlpha(time,hit.time,preempt);alpha=Math.max(0,Math.min(1,alpha));
                int a=Math.round(alpha*255),color=skin.argonPro&&!map.comboColours.isEmpty()?map.comboColours.get(hit.combo%map.comboColours.size()):skin.combos.isEmpty()?colors[hit.combo%colors.length]:skin.combos.get((hit.combo+(skin.argonPro?1:0))%skin.combos.size());float x=(float)hit.x,y=(float)y(hit.y);
                c.save();
                if(failed){float pivotX=hit.type==8?256:x,pivotY=hit.type==8?192:y;c.translate(0,failure.drop);c.rotate(OsuFailAnimation.objectRotation(i)*failure.progress,pivotX,pivotY);c.scale(failure.objectScale,failure.objectScale,pivotX,pivotY);}
                if(hit.type==8){drawSpinner(c,i,time,alpha,viewport);c.restore();continue;}
                if(hit.type==2){
                    drawSliderTrack(c,hit,i,time,preempt,radius,OsuReplayModEffects.hidden(replay)?OsuReplayModEffects.sliderAlpha(time,hit.time,hit.end,preempt):alpha,color);
                }
                if(hit.type==2)drawSliderEndpoints(c,hit,time,preempt,radius,color);
                double headEnd=Double.isFinite(judgements.headTimes[i])?judgements.headTimes[i]+(skin.argonPro?800:240):hit.time+200;
                if(hit.type==1||time<=headEnd){
                    float headAlpha=alpha;double headTime=judgements.headTimes[i];if(hit.type==2&&Double.isFinite(headTime)&&time>headTime)headAlpha*=Math.max(0,1-(float)(time-headTime)/240);
                    if(skin.argonPro){
                        double age=Double.isFinite(judgements.headTimes[i])?Math.max(0,time-judgements.headTimes[i]):0;
                        float headFade=hit.type==2?alpha*OsuArgonAnimation.hitAlpha(age):alpha;
                        argon.head(c,x,y,radius,color,headFade,hit.number,hit.type==2,age,kiaiGlow);
                    }else{
                    String headName=skin.circleName(hit.type==2,false);
                    double hitAge=Double.isFinite(headTime)?time-headTime:0;
                    float hitRadius=radius*OsuHitCircleAnimation.scale(hitAge);
                    float headBaseAlpha=hit.type==1&&hitAge>0?headAlpha/Math.max(.00001f,OsuHitCircleAnimation.alpha(hitAge)):alpha;
                    float numberRadius=radius*OsuHitCircleAnimation.numberScale(hitAge,skin.oldNumberAnimation()),numberAlpha=headBaseAlpha*OsuHitCircleAnimation.numberAlpha(hitAge,skin.oldNumberAnimation());
                    if(!skin.natural(c,paint,rect,headName,x,y,hitRadius/64,headAlpha,color,time)){circle(c,x,y,hitRadius,((int)(a*.55)<<24)|(color&0xffffff),true,0);circle(c,x,y,hitRadius,(a<<24)|0xffffff,false,2);}
                    if(!skin.hitCircleOverlayAboveNumber)skin.natural(c,paint,rect,headName+"overlay",x,y,hitRadius/64,headAlpha,null,time);
                    if(!skin.number(c,paint,rect,hit.number,x,y,skin.textHeight("default")*.8f*numberRadius/64,numberAlpha))font.draw(c,Integer.toString(hit.number),x,y+numberRadius*.27f,numberRadius*.8f,(Math.round(numberAlpha*255)<<24)|0xffffff,numberRadius*1.6f,0);
                    if(skin.hitCircleOverlayAboveNumber)skin.natural(c,paint,rect,headName+"overlay",x,y,hitRadius/64,headAlpha,null,time);
                    drawKiaiCircle(c,headName,x,y,hitRadius,headAlpha,color,time);
                    }
                }

                if(hit.type==2&&time>=hit.time&&time<=hit.end)drawSliderBall(c,hit,time,radius,1,color);
                c.restore();
            }
            drawJudgements(c,time,radius);drawApproachCircles(c,time,preempt,radius,first,lo,failure);drawFlashlight(c,time,viewport);drawCursor(c,time);c.restore();c.restoreToCount(objectsLayer);
            drawHud(c,time,width,height,unit);
            if(!failed)breakOverlay.draw(c,time,width,height,cursorScreenX,cursorScreenY,NekoConfig.osuReplayBackgroundParallax,parallaxElapsed);
            if(failed){
                c.restoreToCount(contentLayer);paint.setColor((Math.round(failure.flash*255)<<24)|0xff0000);paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.ADD));c.drawRect(0,0,width,height,paint);paint.setXfermode(null);
                if(failElapsed>=OsuFailAnimation.DURATION){font.draw(c,LocaleController.getString(R.string.OsuReplayFailed),width/2,height/2,46*unit,Color.WHITE,width*.8f,0);playIcon.setPause(false);}
            }
            c.restoreToCount(gameplayLayer);
            if(argonProgress!=null)argonProgress.invalidate();
            ((SkipButton)skipBreak).update(time,!resultsShown&&!failed&&map.skipSection(time,replay.mods)>=0,map.skipSection(time,replay.mods));if(!seeking&&prepared)seek.setProgress((float)Math.min(1,time/Math.max(1,seekMaximum)));updateSeekTime(time);
            if(foreground&&(parallax.animating()||breakOverlay.animating()||skin.argonPro&&argonHud.animating()))postInvalidateOnAnimation();
            if(failed&&foreground&&!failurePaused&&failElapsed<OsuFailAnimation.DURATION)postInvalidateOnAnimation();
            if(!failed&&prepared&&foreground&&music.isPlaying())postInvalidateOnAnimation();
        }
        /** OsuPlayfield.approachCircles is above HitObjectContainer and judgementAboveHitObjectLayer. */
        private void drawApproachCircles(Canvas c,double time,double preempt,float radius,int first,int last,OsuFailAnimation failure){
            for(int i=last-1;i>=first&&i>=last-500;i--){
                OsuPlaybackMap.Hit hit=map.objects.get(i);if(hit.type==8||((replay.mods&8)!=0&&i!=0))continue;
                float alpha=OsuApproachCircle.alpha(time,hit.time,preempt,judgements.headTimes[i]);if(alpha<=0)continue;
                float size=radius*OsuApproachCircle.scale(time,hit.time,preempt),x=(float)hit.x,y=(float)y(hit.y);
                int colour=skin.argonPro&&!map.comboColours.isEmpty()?map.comboColours.get(hit.combo%map.comboColours.size()):skin.combos.isEmpty()?colors[hit.combo%colors.length]:skin.combos.get((hit.combo+(skin.argonPro?1:0))%skin.combos.size());
                c.save();if(failed&&failure!=null){c.translate(0,failure.drop);c.rotate(OsuFailAnimation.objectRotation(i)*failure.progress,x,y);c.scale(failure.objectScale,failure.objectScale,x,y);}
                if(skin.argonPro)skin.draw(c,paint,rect,"argon-approachcircle",x,y,size*2*128/118,alpha,colour);
                else if(!skin.natural(c,paint,rect,"approachcircle",x,y,size/64,alpha,colour,time))circle(c,x,y,size,(Math.round(alpha*255)<<24)|(colour&0xffffff),false,2);
                c.restore();
            }
        }
        private void drawKiaiCircle(Canvas c,String name,float x,float y,float radius,float alpha,int color,double time){
            float glow=alpha*kiaiGlow;if(glow<=0)return;
            int tint=OsuKiaiFlash.tintedGlowColour(color);
            paint.setXfermode(kiaiAdditive);
            try{if(!skin.natural(c,paint,rect,name,x,y,radius/64,glow,tint,time))circle(c,x,y,radius,(Math.round(glow*255)<<24)|(tint&0xffffff),true,0);}
            finally{paint.setXfermode(null);}
        }
        private void drawArgonFollowPoints(Canvas c,double time,double preempt,float radius,int first,int last){
            if(OsuReplayModEffects.hidden(replay))return;
            int lo=0,hi=map.objects.size();double ahead=time+OsuArgonAnimation.followPreempt(preempt);
            while(lo<hi){int mid=(lo+hi)>>>1;if(map.objects.get(mid).time<=ahead)lo=mid+1;else hi=mid;}last=lo;
            lo=0;hi=last;while(lo<hi){int mid=(lo+hi)>>>1;if(map.objects.get(mid).time<time-400)lo=mid+1;else hi=mid;}first=lo;
            for(int i=Math.max(Math.max(1,first),last-500);i<last;i++){
                OsuPlaybackMap.Hit previous=map.objects.get(i-1),next=map.objects.get(i);
                if(previous.type==8||next.type==8||previous.combo!=next.combo||next.time<=previous.end)continue;
                previous.position(previous.repeats%2==0?0:1,slider);
                double dx=next.x-slider[0],dy=y(next.y)-y(slider[1]),length=Math.hypot(dx,dy);
                int distance=(int)length;
                for(int d=48;d<distance-32;d+=32){
                    float fraction=d/(float)distance;
                    double at=previous.end+(next.time-previous.end)*fraction;
                    float alpha=OsuArgonAnimation.followAlpha(time,at,preempt),move=OsuArgonAnimation.followMove(time,at,preempt),position=fraction-.1f*(1-move);
                    if(alpha>0)argon.followPoint(c,(float)(slider[0]+dx*position),(float)(y(slider[1])+dy*position),(float)Math.toDegrees(Math.atan2(dy,dx)),alpha,radius/64*(1.5f-.5f*move));
                }
            }
        }
        private final Path snakePath=new Path();
        private final PathMeasure snakeMeasure=new PathMeasure();
        private void drawSliderTrack(Canvas c,OsuPlaybackMap.Hit hit,int index,double time,double preempt,float radius,float alpha,int combo){
            Path path=paths.get(hit);if(path==null)return;if(skin.argonPro){
                double span=(hit.end-hit.time)/hit.repeats,progress=Math.max(0,Math.min(hit.repeats,(time-hit.time)/Math.max(1,span)));
                boolean headHit=Double.isFinite(judgements.headTimes[index])&&time>=judgements.headTimes[index];
                float start=0,end=(float)Math.max(0,Math.min(1,(time-hit.time+preempt)/(preempt/3)));
                if(headHit&&progress>=hit.repeats-1){float local=(float)Math.min(1,progress-(hit.repeats-1));if((hit.repeats&1)==1)start=local;else end=1-local;}
                if(time>hit.end)alpha=(float)Math.max(0,1-(time-hit.end)/(headHit?40:240));
                snakeMeasure.setPath(path,false);float length=snakeMeasure.getLength();snakePath.rewind();
                if(end>start)snakeMeasure.getSegment(length*start,length*end,snakePath,true);
                argon.sliderBody(c,snakePath,radius,combo,alpha);return;
            }path.computeBounds(trackBounds,true);trackBounds.inset(-radius-1,-radius-1);
            int layer=c.saveLayerAlpha(trackBounds,Math.round(alpha*255));paint.setStyle(Paint.Style.STROKE);paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeJoin(Paint.Join.ROUND);paint.setXfermode(trackSource);
            int body=skin.sliderTrackOverride?skin.sliderTrack:combo;
            for(int band=0;band<32;band++){float inward=band/32f;paint.setStrokeWidth(2*radius*(1-inward));paint.setColor(OsuSliderTrack.color((band+.5f)/32,skin.sliderBorder,body));c.drawPath(path,paint);}
            paint.setXfermode(null);paint.setStrokeCap(Paint.Cap.BUTT);paint.setStyle(Paint.Style.FILL);c.restoreToCount(layer);
        }
        private void drawSliderEndpoints(Canvas c,OsuPlaybackMap.Hit hit,double time,double preempt,float radius,int color){
            double span=(hit.end-hit.time)/hit.repeats;
            if(skin.argonPro&&hit.tickInterval>0){
                int activeSpan=Math.max(0,Math.min(hit.repeats-1,(int)((time-hit.time)/Math.max(1,span))));
                for(int tick=1;tick<=Math.min(512,(int)(span/hit.tickInterval));tick++){
                    double tickTime=hit.time+activeSpan*span+tick*hit.tickInterval;if(tickTime>=hit.time+(activeSpan+1)*span-10||tickTime<time)continue;
                    double fraction=tick*hit.tickInterval/span;hit.position(activeSpan%2==0?fraction:1-fraction,slider);
                    argon.tick(c,(float)slider[0],(float)y(slider[1]),radius,color,OsuArgonAnimation.progress(time-(hit.time-preempt),150));
                }
            }
            int next=Math.max(1,(int)Math.floor((time-hit.time)/Math.max(1,span))+1);
            for(int index=Math.min(hit.repeats,next+1);index>=next;index--){
                float alpha=OsuSliderEndpoints.alpha(hit,index,time,preempt);if(OsuReplayModEffects.hidden(replay))alpha*=OsuReplayModEffects.circleAlpha(time,hit.time,preempt);if(alpha<=0)continue;
                hit.position(index%2==1?1:0,slider);float x=(float)slider[0],y=(float)y(slider[1]);String name=skin.circleName(true,true);
                if(!skin.argonPro){skin.natural(c,paint,rect,name,x,y,radius/64,alpha,color,time);skin.natural(c,paint,rect,name+"overlay",x,y,radius/64,alpha,null,time);}
                if(!skin.argonPro)drawKiaiCircle(c,name,x,y,radius,alpha,color,time);
                if(index<hit.repeats){hit.position(index%2==1?.99:.01,direction);float angle=(float)Math.toDegrees(Math.atan2(y(direction[1])-y,direction[0]-x));if(skin.argonPro){
                    double age=time-OsuSliderEndpoints.appear(hit,index,preempt);
                    c.save();c.rotate(angle,x,y);float offset=-12*(OsuArgonAnimation.reversePulse(age)-1)/.3f*radius/64;
                    skin.draw(c,paint,rect,"argon-repeat-edge-piece",x+offset,y,radius*(2-16f/58),alpha,null);c.restore();
                    argon.reverse(c,x,y,radius,color,alpha,angle,age);continue;
                }c.save();c.rotate(angle,x,y);skin.natural(c,paint,rect,"reversearrow",x,y,radius/64*OsuSliderEndpoints.bounce(hit,index,time,preempt),Math.min(1,(float)((time-OsuSliderEndpoints.appear(hit,index,preempt))/150)),null,time);c.restore();}
            }
        }
        private void drawSliderBall(Canvas c,OsuPlaybackMap.Hit hit,double time,float radius,float alpha,int color){
            double p=(time-hit.time)/Math.max(1,hit.end-hit.time)*hit.repeats;int span=Math.min(hit.repeats-1,(int)p);double fraction=Math.min(1,p-span),f=span%2==0?fraction:1-fraction;hit.position(f,slider);float x=(float)slider[0],y=(float)y(slider[1]);
            hit.position(Math.max(0,Math.min(1,f+.01)),direction);if(f>.99){hit.position(Math.max(0,f-.01),direction);direction[0]=slider[0]*2-direction[0];direction[1]=slider[1]*2-direction[1];}
            if(skin.argonPro){
                float rotation=(float)Math.toDegrees(Math.atan2(y(direction[1])-y,direction[0]-x))+(span%2==1?180:0);
                argon.ball(c,x,y,radius,color,alpha*OsuArgonAnimation.out(time-hit.time,200),rotation);
                frames.at(time,cursor);boolean held=(cursor.keys&15)!=0&&Math.hypot(cursor.x-x,cursor.y-y)<=radius*2.4;
                if(held)argon.follow(c,x,y,radius,color,alpha,1+1.4f*OsuArgonAnimation.out(time-hit.time,300));
                return;
            }
            c.save();c.rotate((float)Math.toDegrees(Math.atan2(y(direction[1])-y,direction[0]-x)),x,y);if(skin.sliderBallFlip&&span%2==1)c.scale(-1,1,x,y);
            skin.natural(c,paint,rect,skin.sliderBall(time-hit.time),x,y,radius/64,alpha,skin.sliderBallTint?color:null,time);c.restore();
            frames.at(time,cursor);if((cursor.keys&15)!=0&&Math.hypot(cursor.x-x,cursor.y-y)<=radius*2.4)skin.natural(c,paint,rect,"sliderfollowcircle",x,y,radius/64,alpha,null,time);
        }
        private void hudLabel(Canvas c,String text,float x,float baseline,float size,int color,float maxWidth){hudText.setColor(color);hudText.setTextSize(size);float width=hudText.measureText(text);if(width>maxWidth){hudText.setTextSize(size*maxWidth/width);width=maxWidth;}c.drawText(text,x-width/2,baseline,hudText);}
        private void drawHud(Canvas c,double time,float width,float height,float unit){
            int layer=c.saveLayerAlpha(0,0,width,height,Math.round(NekoConfig.osuReplayHudOpacity*2.55f));
            if(skin.argonPro){argonHud.draw(c,skin,time,width,height);c.restoreToCount(layer);return;}
            float scale=height/768f,sw=width/scale;c.save();c.scale(scale,scale);
            float hp=(float)session.health.at(time);
            skin.natural(c,paint,rect,"scorebar-bg",skin.width("scorebar-bg")/2,skin.height("scorebar-bg")/2,1,1,null,time);
            float hpX=skin.modernScorebar()?12:4.8f,hpY=skin.modernScorebar()?12.5f:16,hpWidth=skin.width("scorebar-colour"),hpHeight=skin.height("scorebar-colour");
            c.save();c.clipRect(hpX,hpY,hpX+hpWidth*hp,hpY+hpHeight);skin.natural(c,paint,rect,"scorebar-colour",hpX+hpWidth/2,hpY+hpHeight/2,1,1,null,time);c.restore();
            String marker=skin.modernScorebar()?"scorebar-marker":hp<.2?"scorebar-kidanger2":hp<.5?"scorebar-kidanger":"scorebar-ki";
            skin.natural(c,paint,rect,marker,hpX+hpWidth*hp,skin.modernScorebar()?17:16,1,1,null,time);
            float scoreScale=.96f,scoreHeight=skin.textHeight("score")*scoreScale,accScale=scoreScale*.6f,accTop=scoreHeight+4.8f,right=sw-9.6f;
            hudSmoothing.update(time,session.hud.score(time),session.hud.accuracy(time)*100);
            String score=String.format(Locale.US,replay.lazer?"%06d":"%08d",Math.round(hudSmoothing.score));
            skin.monoText(c,paint,rect,"score",score,right,0,scoreScale,1);
            String acc=String.format(Locale.US,"%.2f%%",hudSmoothing.accuracy);
            skin.monoText(c,paint,rect,"score",acc,right,accTop,accScale,1);
            float pieX=right-skin.monoWidth("score","99.99%",accScale)-38.4f,pieY=accTop+skin.textHeight("score")*accScale/2;
            float pieRadius=16;paint.setColor(0x99ffffff);rect.set(pieX-pieRadius,pieY-pieRadius,pieX+pieRadius,pieY+pieRadius);c.drawArc(rect,-90,(float)(360*Math.max(0,Math.min(1,time/Math.max(1,endTime)))),true,paint);
            if(!skin.natural(c,paint,rect,"circularmetre",pieX,pieY,1,1,null,time)){circle(c,pieX,pieY,16,Color.WHITE,false,1.2f);paint.setStrokeWidth(1);paint.setColor(Color.WHITE);c.drawLine(pieX,pieY,pieX,pieY-pieRadius,paint);}
            float age=(float)(time-session.hud.lastChange(time)),pulse=1+.094f*(float)Math.max(0,1-Math.abs(age-50)/50);
            String combo=session.hud.combo(time)+"x";float comboScale=1.28f*pulse,comboTop=768-12.8f-skin.textHeight("combo")*comboScale;
            if(age>=0&&age<300){float popScale=1.28f*(1+.563f*(1-age/300));skin.text(c,paint,rect,"combo",combo,3.2f,768-12.8f-skin.textHeight("combo")*popScale,popScale,.3f*(1-age/300),-1);}
            skin.text(c,paint,rect,"combo",combo,3.2f,comboTop,comboScale,1,-1);
            drawKeys(c,time,sw);drawHitErrors(c,time,sw);
            c.restore();c.restoreToCount(layer);
        }
        private void drawKeys(Canvas c,double time,float width){
            frames.at(time,cursor);int raw=cursor.keys;
            int buttons=replay.lazer?((raw&5)!=0?1:0)|((raw&10)!=0?2:0)|((raw&16)!=0?4:0):((raw&4)!=0?1:0)|((raw&8)!=0?2:0)|((raw&1)!=0&&(raw&4)==0?4:0)|((raw&2)!=0&&(raw&8)==0?8:0);
            c.save();c.translate(width,768/2-64);c.rotate(90);skin.natural(c,paint,rect,"inputoverlay-background",skin.width("inputoverlay-background")/2,skin.height("inputoverlay-background")/2,1,1,null,time);c.restore();
            String[] names=replay.lazer?new String[]{"B1","B2","B3"}:new String[]{"K1","K2","M1","M2"};
            for(int i=0;i<names.length;i++){
                float x=width-24,y=768/2-64+30.4f+i*47.2f;boolean pressed=(buttons&(1<<i))!=0;
                skin.natural(c,paint,rect,"inputoverlay-key",x,y,pressed?.9f:1,1,pressed?0xffffde00:null,time);
                int count=session.hud.keyCount(time,i);hudLabel(c,count>0?Integer.toString(count):names[i],x,y+5,14,skin.inputOverlayText,40);
            }
        }
        private void drawHitErrors(Canvas c,double time,float width){
            double od=map.overallDifficulty(replay.mods);float great=(float)(80-6*od)*.8f,ok=(float)(140-8*od)*.8f,meh=(float)(200-10*od)*.8f,x=width/2,y=758;
            paint.setColor(0x99000000);c.drawRect(x-meh,y-9,x+meh,y+9,paint);
            paint.setColor(0xffffcf46);c.drawRect(x-meh,y-2.5f,x+meh,y+2.5f,paint);paint.setColor(0xff91d94a);c.drawRect(x-ok,y-2.5f,x+ok,y+2.5f,paint);paint.setColor(0xff66d9f4);c.drawRect(x-great,y-2.5f,x+great,y+2.5f,paint);
            List<OsuReplayJudgements.Result> results=judgements.results;int lo=0,hi=results.size();while(lo<hi){int mid=(lo+hi)>>>1;if(results.get(mid).time<time-10000)lo=mid+1;else hi=mid;}
            double mean=0;int count=0;
            for(int i=lo;i<results.size();i++){OsuReplayJudgements.Result result=results.get(i);if(result.time>time)break;OsuPlaybackMap.Hit hit=map.objects.get(result.object);double tap=judgements.headTimes[result.object];if(hit.type==8||!Double.isFinite(tap)||tap>time)continue;
                float error=(float)(tap-hit.time)*.8f;float age=(float)(time-tap),alpha=Math.max(0,1-age/10000);int color=Math.abs(error)<great?0xff66d9f4:Math.abs(error)<ok?0xff91d94a:0xffffcf46;
                paint.setColor((Math.round(alpha*255)<<24)|(color&0xffffff));c.drawRect(x+error-1,y-8,x+error+1,y+8,paint);if(age<2000){mean+=error;count++;}
            }
            float pointer=x+(count==0?0:(float)(mean/count));paint.setColor(Color.WHITE);Path triangle=new Path();triangle.moveTo(pointer-4,y-14);triangle.lineTo(pointer+4,y-14);triangle.lineTo(pointer,y-9);triangle.close();c.drawPath(triangle,paint);
        }
        private void spinnerLayer(Canvas c,String name,float rotation,float alpha,double time){c.save();c.rotate(rotation,256,192);skin.natural(c,paint,rect,name,256,192,.624f,alpha,null,time);c.restore();}
        private void drawSpinner(Canvas c,int index,double time,float alpha,float[] viewport){
            OsuPlaybackMap.Hit hit=map.objects.get(index);OsuReplaySpinners.Spin spin=session.spinners.objects.get(index);
            float rotation=spin==null?0:(float)spin.rotation(time);
            if(skin.argonPro){
                float spinnerAlpha=(float)Math.min(1,(time-hit.time+map.preempt(replay.mods))/OsuApproachCircle.fadeInDuration(map.preempt(replay.mods)))*(1-OsuArgonAnimation.progress(time-hit.end,240));
                argon.spinner(c,spin,time,hit.time,hit.end,map.preempt(replay.mods),Math.max(0,spinnerAlpha));return;
            }
            float[] overlay=OsuSpinnerLayout.overlay(getWidth(),getHeight(),viewport);
            if(skin.oldSpinnerStyle()){
                c.save();c.translate(overlay[0],overlay[1]);c.scale(overlay[2],overlay[2]);
                skin.natural(c,paint,rect,"spinner-background",overlay[3]/2,OsuSpinnerLayout.BACKGROUND_Y,1,alpha,null,time);c.restore();
                spinnerLayer(c,"spinner-circle",rotation,alpha,time);
            }else{
                spinnerLayer(c,"spinner-bottom",rotation/3,alpha,time);
                spinnerLayer(c,"spinner-top",rotation/2,alpha,time);
                spinnerLayer(c,"spinner-middle2",rotation,alpha,time);
                skin.natural(c,paint,rect,"spinner-middle",256,192,.624f,alpha,null,time);
            }
            float remaining=(float)Math.max(0,Math.min(1,(hit.end-time)/Math.max(1,hit.end-hit.time)));
            if((replay.mods&8)==0)skin.natural(c,paint,rect,"spinner-approachcircle",256,192,.624f*OsuSpinnerLayout.approachScale(remaining),alpha,null,time);
            if(spin!=null){
                String label=spin.turns(time)>=spin.required?"spinner-clear":"spinner-spin";
                c.save();c.translate(overlay[0],overlay[1]);c.scale(overlay[2],overlay[2]);
                float centre=overlay[3]/2;
                if(skin.oldSpinnerStyle()&&skin.has("spinner-metre")){
                    float left=centre-512,top=OsuSpinnerLayout.METRE_TOP,w=skin.width("spinner-metre"),h=skin.height("spinner-metre");
                    float completion=(float)Math.min(1,spin.turns(time)/(double)Math.max(1,spin.required));
                    int metreLayer=c.save();c.clipRect(left,top+h*(1-completion),left+w,top+h);
                    skin.natural(c,paint,rect,"spinner-metre",left+w/2,top+h/2,1,alpha,null,time);c.restoreToCount(metreLayer);
                }
                skin.natural(c,paint,rect,label,centre,label.equals("spinner-clear")?OsuSpinnerLayout.CLEAR_Y:OsuSpinnerLayout.SPIN_Y,1,alpha,null,time);
                skin.natural(c,paint,rect,"spinner-rpm",centre-OsuSpinnerLayout.RPM_HALF_WIDTH+skin.width("spinner-rpm")/2,OsuSpinnerLayout.RPM_TOP+skin.height("spinner-rpm")/2,1,alpha,null,time);
                skin.text(c,paint,rect,"score",Integer.toString(spin.rpm(time)),centre+OsuSpinnerLayout.RPM_HALF_WIDTH,OsuSpinnerLayout.RPM_TOP,1,alpha,1);
                c.restore();
                int bonus=spin.bonus(time);if(bonus>0){float age=(float)(time-spin.lastBonus(time)),fade=Math.max(0,1-age/800),size=24*(1+.56f*(float)Math.pow(Math.max(0,1-age/800),2));skin.scoreNumber(c,paint,rect,bonus,256,272,size,fade);}
            }
        }
        private void drawCursor(Canvas c,double time){
            if(skin.argonPro){frames.at(time,cursor);for(int i=60;i>0;i--){frames.at(time-i,trailSample);float alpha=.8f*(float)Math.pow(1-i/61f,4);skin.draw(c,paint,rect,"argon-cursortrail",trailSample.x,trailSample.y,4,alpha,null);}boolean held=(cursor.keys&15)!=0;double changed=time-400;int lo=0,hi=frames.size;
                while(lo<hi){int mid=(lo+hi)>>>1;if(frames.time(mid)<=time)lo=mid+1;else hi=mid;}
                for(int i=lo-1;i>0&&frames.time(i)>=time-400;i--)if(((frames.buttons(i)&15)!=0)!=((frames.buttons(i-1)&15)!=0)){changed=frames.time(i);break;}
                argon.cursor(c,cursor.x,cursor.y,OsuArgonAnimation.cursorScale(held,time-changed),1);return;}
            frames.at(time,cursor);for(int trail=5;trail>0;trail--){frames.at(time-trail*14,trailSample);if(!skin.natural(c,paint,rect,"cursortrail",trailSample.x,trailSample.y,.5f,(6-trail)*.08f,null,time))circle(c,trailSample.x,trailSample.y,4-trail*.4f,((85-trail*12)<<24)|0xffd66b,true,0);}
            c.save();if(skin.cursorRotate)c.rotate((float)(time*.06),cursor.x,cursor.y);if(!skin.natural(c,paint,rect,"cursor",cursor.x,cursor.y,.5f*(skin.cursorExpand&&(cursor.keys&15)!=0?1.3f:1),1,null,time)){circle(c,cursor.x,cursor.y,(cursor.keys&15)!=0?11:8,0xffffdd66,true,0);circle(c,cursor.x,cursor.y,12,Color.WHITE,false,1.5f);}c.restore();
        }
    }
}
