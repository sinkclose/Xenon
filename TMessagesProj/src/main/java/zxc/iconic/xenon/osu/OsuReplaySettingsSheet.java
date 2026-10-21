package zxc.iconic.xenon.osu;

import android.content.Context;
import android.widget.*;
import org.telegram.messenger.*;
import org.telegram.ui.ActionBar.*;
import zxc.iconic.xenon.NekoConfig;
import java.io.File;
import java.util.*;
import java.util.function.IntConsumer;
import zxc.iconic.xenon.settings.AltSeekbar;

/** Shares persisted playback preferences with Other. */
final class OsuReplaySettingsSheet extends BottomSheet {
    OsuReplaySettingsSheet(Context context,Runnable importSkin){
        super(context,false);
        setCanDismissWithSwipe(false);
        LinearLayout content=new LinearLayout(context);content.setOrientation(LinearLayout.VERTICAL);
        int padding=AndroidUtilities.dp(20);content.setPadding(padding,padding,padding,padding);
        TextView title=label(context,LocaleController.getString(R.string.Settings));title.setTextSize(22);content.addView(title);
        org.telegram.ui.Cells.TextCheckCell videos=new org.telegram.ui.Cells.TextCheckCell(context);
        videos.setTextAndCheck(LocaleController.getString(R.string.OsuReplayVideoBackground),NekoConfig.osuReplayVideoBackground,false);
        videos.setOnClickListener(v->{NekoConfig.setOsuReplayVideoBackground(!NekoConfig.osuReplayVideoBackground);videos.setChecked(NekoConfig.osuReplayVideoBackground);});content.addView(videos,new LinearLayout.LayoutParams(-1,-2));
        slider(context,content,R.string.OsuReplayBackgroundDim,NekoConfig.osuReplayBackgroundDim,NekoConfig::setOsuReplayBackgroundDim);
        slider(context,content,R.string.OsuReplayBackgroundBlur,NekoConfig.osuReplayBackgroundBlur,NekoConfig::setOsuReplayBackgroundBlur);
        slider(context,content,R.string.OsuReplayBackgroundParallax,NekoConfig.osuReplayBackgroundParallax,NekoConfig::setOsuReplayBackgroundParallax,-200,200);
        slider(context,content,R.string.OsuReplayHudOpacity,NekoConfig.osuReplayHudOpacity,NekoConfig::setOsuReplayHudOpacity);
        slider(context,content,R.string.OsuReplayObjectOpacity,NekoConfig.osuReplayObjectOpacity,NekoConfig::setOsuReplayObjectOpacity);
        slider(context,content,R.string.OsuReplayKiaiIntensity,NekoConfig.osuReplayKiaiIntensity,NekoConfig::setOsuReplayKiaiIntensity,0,200);
        slider(context,content,R.string.OsuLogoMapMusicVolume,NekoConfig.osuMapMusicVolume,NekoConfig::setOsuMapMusicVolume);
        slider(context,content,R.string.OsuLogoHitsoundVolume,NekoConfig.osuHitsoundVolume,NekoConfig::setOsuHitsoundVolume);
        File selected=OsuSkinStore.directory(NekoConfig.osuReplaySkin);
        TextView skin=label(context,LocaleController.getString(R.string.OsuReplaySkin)+": "+(selected==null?LocaleController.getString(R.string.OsuSkinDefault):OsuSkinStore.name(selected)));
        skin.setPadding(0,padding,0,padding);content.addView(skin);
        skin.setOnClickListener(view->{
            List<File> skins=OsuSkinStore.list();CharSequence[] names=new CharSequence[skins.size()+2];
            names[0]=LocaleController.getString(R.string.OsuSkinDefault);names[1]=LocaleController.getString(R.string.OsuSkinImport);
            for(int i=0;i<skins.size();i++)names[i+2]=OsuSkinStore.name(skins.get(i));
            new AlertDialog.Builder(context).setTitle(LocaleController.getString(R.string.OsuReplaySkin)).setItems(names,(dialog,index)->{
                if(index==1){importSkin.run();dismiss();return;}
                NekoConfig.setOsuReplaySkin(index==0?"":skins.get(index-2).getName());
                skin.setText(LocaleController.getString(R.string.OsuReplaySkin)+": "+names[index]);
            }).show();
        });
        ScrollView scroll=new ScrollView(context);scroll.setFillViewport(true);scroll.setNestedScrollingEnabled(false);scroll.addView(content);setCustomView(scroll);
    }
    @Override protected void onStart(){super.onStart();if(containerView!=null)setAllowNestedScroll(false);}
    private static TextView label(Context context,String text){TextView view=new TextView(context);view.setText(text);view.setTextSize(16);view.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));return view;}
    private static void slider(Context context,LinearLayout content,int title,int initial,IntConsumer save){
        slider(context,content,title,initial,save,0,100);
    }
    private static void slider(Context context,LinearLayout content,int title,int initial,IntConsumer save,int min,int max){
        AltSeekbar slider=new AltSeekbar(context,value->save.accept(Math.round(value)),min,max,LocaleController.getString(title),min+"%",max+"%",null);
        slider.setValueFormatter(value->value+"%");slider.setValue(initial);
        content.addView(slider,new LinearLayout.LayoutParams(-1,-2));
    }
}
