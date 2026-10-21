package zxc.iconic.xenon.osu;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.*;
import android.os.SystemClock;
import android.view.*;
import android.widget.*;
import org.telegram.messenger.*;
import org.telegram.ui.ActionBar.*;
import org.telegram.ui.Components.*;
import org.telegram.ui.LaunchActivity;
import java.io.File;
import java.text.*;
import java.util.*;

/** Native score panel in the client's normal bottom sheet; no beatmap background. */
public final class OsuReplaySheet extends BottomSheetWithRecyclerListView {
    private static final DispatchQueue QUEUE = new DispatchQueue("osu-replays");
    private static final DispatchQueue HEADERS = new DispatchQueue("osu-replay-headers");
    private static final DispatchQueue AVATARS = new DispatchQueue("osu-avatars");
    private final OsuScorePanel scoreView;
    private LinearLayout scoreContent;
    private final Theme.ResourcesProvider scoreResources;
    private boolean closed;
    private OsuReplay replay;
    private boolean metadataLoading, avatarLoading, metadataFailed, avatarFailed;
    private Bulletin downloadError;
    private boolean watchLoading;

    private void showDownloadError(boolean avatar) {
        if (closed) return;
        if (avatar) avatarFailed = true; else metadataFailed = true;
        if (downloadError != null) downloadError.hide();
        downloadError = BulletinFactory.of(container, scoreResources).createSimpleBulletin(0,
                LocaleController.getString(R.string.OsuReplayDownloadFailed), LocaleController.getString(R.string.Retry),
                Bulletin.DURATION_PROLONG, () -> {
                    if (closed) return;
                    if (metadataFailed) loadMetadata();
                    if (avatarFailed) loadAvatar();
                });
        downloadError.show();
    }
    private void loadMetadata() {
        if (closed || metadataLoading || replay == null) return;
        metadataLoading = true; metadataFailed = false;
        QUEUE.postRunnable(() -> {
            try {
                OsuReplayLoader.Metadata metadata = OsuReplayLoader.resolve(replay);
                AndroidUtilities.runOnUIThread(() -> {
                    metadataLoading = false;
                    if (closed) return;
                    replay.title = metadata.title; replay.artist = metadata.artist; replay.difficulty = metadata.difficulty; replay.mapper = metadata.mapper;
                    replay.stars = metadata.stars; replay.pp = metadata.pp; replay.maxCombo = metadata.maxCombo;
                    scoreView.setContentDescription(scoreView.description()); scoreView.invalidate();
                });
            } catch (Exception error) {
                FileLog.e(error);
                AndroidUtilities.runOnUIThread(() -> { metadataLoading = false; showDownloadError(false); });
            }
        });
    }
    private void loadAvatar() {
        if (closed || avatarLoading || replay == null || replay.userId <= 0) return;
        avatarLoading = true; avatarFailed = false;
        long userId = replay.userId;
        AVATARS.postRunnable(() -> {
            try {
                File image = OsuLocalCache.fetch("avatars", userId + ".img", "https://a.ppy.sh/" + userId, 4 * 1024 * 1024, null);
                AndroidUtilities.runOnUIThread(() -> {
                    avatarLoading = false;
                    if (!closed && image.isFile()) { scoreView.setAvatar(image); }
                });
            } catch (java.io.IOException error) {
                FileLog.e(error);
                AndroidUtilities.runOnUIThread(() -> { avatarLoading = false; showDownloadError(true); });
            }
        });
    }

    private OsuReplaySheet(Context context, Theme.ResourcesProvider resources, String filename, File source) {
        super(context, null, false, false, true, resources);
        topPadding=0;
        if(getWindow()!=null)getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        scoreResources = resources;
        setBackgroundColor(Theme.getColor(Theme.key_dialogBackground, resources));
        fixNavigationBar(Theme.getColor(Theme.key_dialogBackground, resources));
        setShowHandle(true);setShowShadow(false);
        if(actionBar!=null)actionBar.setVisibility(View.GONE);
        recyclerListView.setPadding(backgroundPaddingLeft,0,backgroundPaddingLeft,0);
        scoreView = new OsuScorePanel(context, filename,resources);
        scoreView.setDismissAction(this::dismiss);
        LinearLayout content = scoreContent = new LinearLayout(context); content.setOrientation(LinearLayout.VERTICAL);
        content.addView(scoreView, new LinearLayout.LayoutParams(-1, -2));
        OsuWatchButton watch = new OsuWatchButton(context, resources);
        LinearLayout.LayoutParams watchParams = new LinearLayout.LayoutParams(-1, AndroidUtilities.dp(48));
        watchParams.setMargins(AndroidUtilities.dp(24), 0, AndroidUtilities.dp(24), AndroidUtilities.dp(24));
        content.addView(watch, watchParams);
        watch.setOnClickListener(v -> {
            if (replay == null) return;
            if (replay.mode != 0) { BulletinFactory.of(container, scoreResources).createErrorBulletin(LocaleController.getString(R.string.OsuReplayWatchStandardOnly)).show(); return; }
            if (watchLoading) return;
            watchLoading = true; watch.update(true,false,0,0,false);
            QUEUE.postRunnable(() -> {
                try {
                    OsuReplayPreparation session=OsuReplayPreparation.prepare(context.getApplicationContext(),source,(downloading,bytes,total)->AndroidUtilities.runOnUIThread(()->{if(!closed)watch.update(true,downloading,bytes,total,false);}));
                    AndroidUtilities.runOnUIThread(()->{
                        watchLoading=false;watch.update(false,false,0,0,false);
                        if(closed){session.dispose();return;}
                        String preparedToken=OsuReplayPreparation.offer(session);
                        try{context.startActivity(new Intent(context,OsuReplayActivity.class)
                                .putExtra(OsuReplayActivity.EXTRA_REPLAY,source.getAbsolutePath())
                                .putExtra(OsuReplayActivity.EXTRA_PREPARED,preparedToken));}catch(Exception error){OsuReplayPreparation.discard(preparedToken);FileLog.e(error);BulletinFactory.of(container,scoreResources).createErrorBulletin(LocaleController.getString(R.string.OsuReplayWatchFailed)).show();}
                    });
                }catch(Exception error){FileLog.e(error);AndroidUtilities.runOnUIThread(()->{
                    watchLoading=false;watch.update(false,false,0,0,true);
                    if(!closed)BulletinFactory.of(container,scoreResources).createSimpleBulletin(0,LocaleController.getString(R.string.OsuReplayWatchFailed),LocaleController.getString(R.string.Retry),Bulletin.DURATION_PROLONG,watch::performClick).show();
                });}
            });
        });
        notifyDataSetChanged();
    }
    @Override protected CharSequence getTitle(){return "";}
    @Override protected boolean showActionBarWhenExpanded(){return false;}
    @Override protected RecyclerListView.SelectionAdapter createAdapter(RecyclerListView list){
        return new RecyclerListView.SelectionAdapter(){
            @Override public boolean isEnabled(androidx.recyclerview.widget.RecyclerView.ViewHolder holder){return false;}
            @Override public int getItemCount(){return 1;}
            @Override public androidx.recyclerview.widget.RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent,int type){
                FrameLayout frame=new FrameLayout(parent.getContext());frame.setLayoutParams(new androidx.recyclerview.widget.RecyclerView.LayoutParams(-1,-2));return new RecyclerListView.Holder(frame);
            }
            @Override public void onBindViewHolder(androidx.recyclerview.widget.RecyclerView.ViewHolder holder,int position){
                FrameLayout frame=(FrameLayout)holder.itemView;
                if(scoreContent!=null&&scoreContent.getParent()!=frame){if(scoreContent.getParent() instanceof ViewGroup)((ViewGroup)scoreContent.getParent()).removeView(scoreContent);frame.removeAllViews();frame.addView(scoreContent,new FrameLayout.LayoutParams(-1,-2));}
            }
        };
    }
    public static void open(File file, String filename, Activity activity, Theme.ResourcesProvider resources) {
        OsuReplaySheet sheet = new OsuReplaySheet(activity, resources, filename, file);
        BaseFragment fragment = LaunchActivity.getSafeLastFragment();
        if (fragment != null && fragment.getParentActivity() == activity) fragment.showDialog(sheet); else sheet.show();
        HEADERS.postRunnable(() -> {
            try {
                OsuReplay replay = OsuReplayLoader.read(file);
                String fallback = filename.replaceFirst("(?i)\\.osr$", "");
                int playing = fallback.indexOf("_playing_");
                replay.title = (playing >= 0 ? fallback.substring(playing + 9) : fallback).replace('_', ' ');
                AndroidUtilities.runOnUIThread(() -> {
                    if (sheet.closed || activity.isFinishing())return;
                    OsuScorePanelSounds sounds=new OsuScorePanelSounds(activity.getApplicationContext(),replay,false);
                    sheet.scoreView.setSounds(sounds);
                    sheet.replay = replay; sheet.scoreView.bind(replay);
                    sheet.loadMetadata(); sheet.loadAvatar();
                });
            } catch (Exception error) {
                FileLog.e(error);
                AndroidUtilities.runOnUIThread(() -> {
                    if (sheet.closed || activity.isFinishing()) return;
                    sheet.scoreView.error = true; sheet.scoreView.invalidate();
                    sheet.scoreView.setContentDescription(LocaleController.getString(R.string.OsuReplayInvalid));
                });
            }
        });
    }
    private void hideStatusBar(){if(getWindow()!=null){getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);getWindow().getDecorView().setSystemUiVisibility(getWindow().getDecorView().getSystemUiVisibility()|View.SYSTEM_UI_FLAG_FULLSCREEN);if(android.os.Build.VERSION.SDK_INT>=30&&getWindow().getInsetsController()!=null)getWindow().getInsetsController().hide(WindowInsets.Type.statusBars());}}
    @Override public void onWindowFocusChanged(boolean focus){super.onWindowFocusChanged(focus);scoreView.setPaused(!focus);}
    @Override protected void onStart(){super.onStart();hideStatusBar();}
    @Override public void onOpenAnimationEnd(){super.onOpenAnimationEnd();hideStatusBar();}
    @Override public void dismiss() { closed = true;scoreView.dispose(); if (downloadError != null) downloadError.hide(); super.dismiss(); }

}
