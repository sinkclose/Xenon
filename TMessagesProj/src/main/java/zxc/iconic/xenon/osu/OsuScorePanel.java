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

/** Shared animated result card for the replay sheet and in-player results. */
final class OsuScorePanel extends View {
        private static final boolean SCORE_COUNTER_HAPTICS_ENABLED=false;
        private final Theme.ResourcesProvider scoreResources;
        private OsuReplay replay;
        boolean error;
        private boolean closed,playback,paused;
        private long pausedAt;
        private final Runnable audioTick=new Runnable(){public void run(){if(closed||paused||!isAttachedToWindow()||getVisibility()!=VISIBLE)return;long elapsed=start<0?-1:SystemClock.uptimeMillis()-start;if(sounds!=null&&hasWindowFocus())sounds.update(elapsed,replay);if(elapsed<5000)postDelayed(this,16);}};
        void setPaused(boolean value){
            if(paused==value)return;long now=SystemClock.uptimeMillis();
            if(value){
                long elapsed=start<0?0:Math.max(0,now-start);if(sounds!=null)elapsed=sounds.elapsed(elapsed);
                pausedAt=start<0?now:start+elapsed;paused=true;removeCallbacks(audioTick);if(sounds!=null)sounds.stop();
            }else{
                paused=false;if(start>=0)start+=now-pausedAt;removeCallbacks(audioTick);post(audioTick);invalidate();
            }
        }

        private Runnable dismissAction;
        private OsuScorePanelSounds sounds;
        void setAvatar(File image){avatar.setImage(image.getAbsolutePath(),"80_80",null,null,0);invalidate();}
        void setDismissAction(Runnable action){dismissAction=action;}
        void setPlayback(boolean value){playback=value;}
        void setSounds(OsuScorePanelSounds value){sounds=value;}
        void restart(long delay){closed=false;paused=false;if(replay!=null&&rankFont!=null&&rankGlyph==null)bind(replay);start=SystemClock.uptimeMillis()+delay;metadataStart=-1;haptics=new OsuScoreHaptics();if(sounds!=null)sounds.reset();removeCallbacks(audioTick);post(audioTick);invalidate();}
        void stop(){closed=true;removeCallbacks(audioTick);if(sounds!=null)sounds.stop();}
        void dispose(){stop();if(sounds!=null){sounds.release();sounds=null;}}
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final ImageReceiver avatar = new ImageReceiver(this);
        private static final DispatchQueue FONT_QUEUE=new DispatchQueue("osu-result-fonts");
        private OsuBitmapFont regular,light,semibold,rankFont;
        private final Bitmap[] modeIcons = new Bitmap[4];
        private Bitmap rankGlyph, rankGlow;
        private long metadataStart = -1;
        private final String filename;
        private final NumberFormat scoreFormat = NumberFormat.getIntegerInstance(Locale.US);
        private final DateFormat dateFormat = DateFormat.getDateTimeInstance(DateFormat.MEDIUM,DateFormat.SHORT);
        private long start = -1;
        private OsuScoreHaptics haptics = new OsuScoreHaptics();
        private final int[] colors = {0xffff5c66, 0xffff9c64, 0xffffd35b, 0xffc5ff68, 0xff7bf5ee, 0xffff9ad7};
        OsuScorePanel(Context context, String filename, Theme.ResourcesProvider resources) {
            super(context); this.filename = filename;scoreResources=resources;
            setFocusable(true); setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
            setContentDescription(LocaleController.getString(R.string.Loading));
            FONT_QUEUE.postRunnable(()->{
                OsuBitmapFont r=OsuBitmapFont.get("Torus-Regular"),l=OsuBitmapFont.get("Torus-Light"),s=OsuBitmapFont.get("Torus-SemiBold"),rank=OsuBitmapFont.get("Venera-Bold");
                AndroidUtilities.runOnUIThread(()->{
                    regular=r;light=l;semibold=s;rankFont=rank;
                    if(!closed&&replay!=null)bind(replay);
                    invalidate();
                });
            });
        }
        void bind(OsuReplay r) {
            replay=r;
            if (r.userId > 0) {
                File cachedAvatar = OsuLocalCache.file("avatars", r.userId + ".img");
                if (cachedAvatar.isFile()) avatar.setImage(cachedAvatar.getAbsolutePath(), "80_80", null, null, 0);

            }
            if(rankFont==null){invalidate();return;}
            for (int i=0;i<4;i++) if(modeIcons[i]==null)try(java.io.InputStream in=getContext().getAssets().open("osu/icons/"+new String[]{"Osu","Taiko","Catch","Mania"}[i]+".png")) { modeIcons[i]=BitmapFactory.decodeStream(in); } catch(java.io.IOException e) { FileLog.e(e); }
            rankGlyph=Bitmap.createBitmap(260,180,Bitmap.Config.ARGB_8888);
            rankFont.draw(new Canvas(rankGlyph),r.displayRank(),130,128,r.displayRank().equals("SS")?76:108,Color.WHITE,240,-15);
            Paint blur=new Paint(Paint.ANTI_ALIAS_FLAG);blur.setMaskFilter(new BlurMaskFilter(9,BlurMaskFilter.Blur.NORMAL));
            rankGlow=rankGlyph.extractAlpha(blur,null);
            setContentDescription(description()); if(!playback&&isAttachedToWindow())start();invalidate();
        }
        String description() {
            if (replay == null) return filename;
            return replay.player + ", " + replay.title + ", " + replay.displayRank() + ", " + replay.score + ", "
                    + String.format(Locale.US, "%.2f%%", replay.accuracy() * 100) + ", " + replay.combo + ", " + replay.modsLabel;
        }
        void start() { if(replay==null||regular==null)return;if (start < 0) { start = SystemClock.uptimeMillis(); invalidate(); }removeCallbacks(audioTick);post(audioTick); }
        @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); avatar.onAttachedToWindow(); start(); }
        @Override protected void onDetachedFromWindow() { removeCallbacks(audioTick);if(sounds!=null)sounds.stop();avatar.onDetachedFromWindow(); super.onDetachedFromWindow(); }
        @Override public void onWindowFocusChanged(boolean focused){super.onWindowFocusChanged(focused);if(!focused){if(sounds!=null)sounds.stop();}else if(!closed&&!paused){removeCallbacks(audioTick);post(audioTick);}}
        @Override protected void onMeasure(int width, int height) {
            int w = MeasureSpec.getSize(width); setMeasuredDimension(w, Math.round(w * 1.91f));
        }
        private void rounded(Canvas c, float x, float y, float w, float h, float radius, int color) {
            paint.setShader(null); paint.setStyle(Paint.Style.FILL); paint.setColor(color); rect.set(x,y,x+w,y+h); c.drawRoundRect(rect,radius,radius,paint);
        }
        private void label(Canvas c, String value, float x, float y, float size, int color, boolean bold, float maxWidth) {
            OsuBitmapFont font=bold?semibold:size>=40?light:regular;
            if(font!=null)font.draw(c,value,x,y,size,color,maxWidth,0);
            else {paint.setColor(color);paint.setTextSize(size);paint.setTextAlign(Paint.Align.CENTER);c.drawText(value,x,y,paint);}
        }
        private void arc(Canvas c, float radius, float width, float from, float sweep, int color) {
            paint.setShader(null); paint.setColor(color); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(width); paint.setStrokeCap(Paint.Cap.BUTT);
            rect.set(250-radius, 402-radius, 250+radius, 402+radius); c.drawArc(rect,from,sweep,false,paint); paint.setStyle(Paint.Style.FILL);
        }
        private int surface() { if(playback)return 0xff202020;return Theme.getColor(Theme.key_dialogBackground, scoreResources); }
        private int primary() { if(playback)return Color.WHITE;return OsuUiColors.readable(Theme.getColor(Theme.key_dialogTextBlack, scoreResources), surface(), 0xff202020, 4.5); }
        private int secondary() { if(playback)return 0xffcccccc;return OsuUiColors.readable(Theme.getColor(Theme.key_dialogTextGray, scoreResources), surface(), primary(), 4.5); }
        private int accent(int color) { return OsuUiColors.readable(color, surface(), primary(), 4.5); }
        @Override protected void onDraw(Canvas c) {
            super.onDraw(c);
            if(closed)return;
            float scale = getWidth() / 500f;
            c.save(); c.scale(scale, scale);
            if(playback){
                paint.setShader(new LinearGradient(0,85,0,955,0xff444444,0xff333333,Shader.TileMode.CLAMP));rect.set(0,85,500,955);c.drawRoundRect(rect,28,28,paint);paint.setShader(null);
                paint.setShader(new LinearGradient(0,155,0,955,0xff555555,0xff333333,Shader.TileMode.CLAMP));rect.set(0,155,500,955);c.drawRoundRect(rect,28,28,paint);paint.setShader(null);
            }
            int foreground = primary(), muted = secondary();
            if (replay == null || regular==null) {
                label(c, error ? LocaleController.getString(R.string.OsuReplayInvalid) : LocaleController.getString(R.string.Loading), 250, 340, 19, foreground, true, 420);
                label(c, filename, 250, 380, 14, muted, false, 420); c.restore(); return;
            }
            long elapsed = start < 0 ? 0 : (paused?pausedAt:SystemClock.uptimeMillis()) - start;
            elapsed=Math.max(0,elapsed);
            if(!paused&&sounds!=null)elapsed=sounds.elapsed(elapsed);
            rounded(c, 208, 25, 84, 84, 18, 0xff333333);
            label(c, replay.player.isEmpty() ? "?" : replay.player.substring(0, 1).toUpperCase(Locale.ROOT), 250, 83, 43, OsuUiColors.readable(0xffff9ad7, 0xff333333, foreground, 4.5), true, 74);
            // ImageReceiver uses physical coordinates, independently of the score canvas scale.
            c.save(); c.scale(1 / scale, 1 / scale);
            avatar.setRoundRadius(Math.round(18*scale));avatar.setImageCoords(208*scale,25*scale,84*scale,84*scale); avatar.draw(c); c.restore();
            label(c, replay.player, 250, 132, 18, foreground, true, 420);
            label(c, replay.title, 250, 179, 21, foreground, true, 420);
            label(c, replay.artist, 250, 202, 14, foreground, false, 420);

            c.save(); c.scale(Math.max(.001f, OsuScoreAnimation.scale(elapsed)), Math.max(.001f, OsuScoreAnimation.scale(elapsed)), 250,402);
            arc(c, 139, 30, -90, 360, 0xff383d3c);
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(30);
            paint.setShader(new LinearGradient(0,250,0,550,OsuUiColors.readable(0xff7cf6ff,surface(),foreground,3),OsuUiColors.readable(0xffbaffa9,surface(),foreground,3),Shader.TileMode.CLAMP));
            rect.set(111,263,389,541);
            String rank=replay.displayRank();
            float gauge=OsuScoreAnimation.gauge(replay.accuracy(),rank,elapsed);
            long animatedScore = OsuScoreAnimation.counter(replay.score,elapsed,0);
            int pulse = haptics.update(gauge, rank, animatedScore, elapsed);
            if (haptics.finalRank(elapsed)) pulse = 2;
            if (!closed && isShown() && hasWindowFocus() && (pulse == 2 || SCORE_COUNTER_HAPTICS_ENABLED && pulse == 1)) {
                int feedback = pulse == 2 ? HapticFeedbackConstants.LONG_PRESS
                        : android.os.Build.VERSION.SDK_INT >= 34 ? HapticFeedbackConstants.SEGMENT_FREQUENT_TICK : HapticFeedbackConstants.CLOCK_TICK;
                performHapticFeedback(feedback);
            }
            c.drawArc(rect, -90, 360 * gauge, false, paint); paint.setShader(null); paint.setStyle(Paint.Style.FILL);
            float[] cuts = {0,.7f,.8f,.9f,.95f,.99f,1};
            float grades = OsuScoreAnimation.grades(elapsed);
            for (int i = 0; i < 6; i++) {
                float a = cuts[i] + 1f/360, b = Math.min(cuts[i+1]-1f/360, grades);
                if (b > a) arc(c, 119, 5, -90 + a*360, (b-a)*360, OsuUiColors.readable(colors[i],surface(),foreground,3));
            }
            String[] ranks = {"D","C","B","A","S","SS"};
            float[] positions = {.35f,.75f,.85f,.9125f,.96f,1};
            for (int i=0;i<6;i++) {
                long badgeStart=OsuScoreAnimation.badgeTime(replay.accuracy(),rank,i);
                if (i>OsuScoreAnimation.rankIndex(rank) || elapsed<badgeStart) continue;
                double angle = Math.toRadians(positions[i] * 360 - 90);
                float x = 250+(float)Math.cos(angle)*178, y = 402+(float)Math.sin(angle)*178;
                float badgeAlpha=OsuScoreAnimation.progress(elapsed-badgeStart,0,50);
                int badgeLayer=c.saveLayerAlpha(x-27,y-19,x+27,y+19,Math.round(badgeAlpha*255));
                rounded(c,x-17,y-9,34,18,9,colors[i]); label(c,ranks[i],x,y+5,15,0xff35413b,true,32);
                float flash=1-OsuScoreAnimation.progress(elapsed-badgeStart,0,500)*OsuScoreAnimation.progress(elapsed-badgeStart,0,500);
                paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.ADD));rounded(c,x-17,y-9,34,18,9,(Math.round(flash*51)<<24)|(colors[i]&0xffffff));paint.setXfermode(null);c.restoreToCount(badgeLayer);
            }
            if(elapsed>=1950 && rankGlyph!=null) {
                long age=elapsed-1950;int index=OsuScoreAnimation.rankIndex(rank);
                int color=colors[Math.max(0,index)];
                float alpha=index>=3?1:OsuScoreAnimation.outQuint(OsuScoreAnimation.progress(age,0,200));
                float rankScale=index>=4?1+.05f*(1-OsuScoreAnimation.outQuint(OsuScoreAnimation.progress(age,0,3000))):1;
                c.save();c.translate(0,OsuScoreAnimation.rankY(age,rank));c.rotate(OsuScoreAnimation.rankRotation(age,rank),250,402);c.scale(rankScale,rankScale,250,402);
                paint.setColor(color);paint.setAlpha(Math.round(110*alpha));
                rect.set(120,312,380,492);c.drawBitmap(rankGlow,null,rect,paint);
                if(index>=3) {float flash=OsuScoreAnimation.rankFlash(age,rank);
                    paint.setAlpha(Math.round(220*flash));rect.set(110,302,390,502);c.drawBitmap(rankGlow,null,rect,paint);}
                paint.setColorFilter(new PorterDuffColorFilter(OsuUiColors.icon(Color.WHITE,surface()),PorterDuff.Mode.SRC_IN));paint.setAlpha(Math.round(255*alpha));rect.set(120,312,380,492);c.drawBitmap(rankGlyph,null,rect,paint);paint.setColorFilter(null);paint.setAlpha(255);c.restore();
            }
            c.restore();

            if(elapsed>=450)label(c, scoreFormat.format(animatedScore), 250, 634, 62, foreground, false, 420);
            String stars = Double.isFinite(replay.stars) ? String.format(Locale.US,"★ %.2f",replay.stars) : "★ —";
            int difficultyColor=OsuUiColors.difficulty(replay.stars);
            rounded(c,194,651,80,27,14,difficultyColor); label(c,stars,234,671,15,OsuUiColors.icon(0xff29202a,difficultyColor),true,74);
            rounded(c,283,653,24,24,12,difficultyColor);
            if(modeIcons[replay.mode]!=null) {paint.setColorFilter(new PorterDuffColorFilter(OsuUiColors.icon(Color.WHITE,difficultyColor),PorterDuff.Mode.SRC_IN));rect.set(284,654,306,676);c.drawBitmap(modeIcons[replay.mode],null,rect,paint);paint.setColorFilter(null);}
            label(c,replay.difficulty.isEmpty() ? "—" : replay.difficulty,250,701,16,foreground,true,420);
            label(c,replay.mapper.isEmpty() ? "" : LocaleController.getString(R.string.OsuReplayMappedBy)+" "+replay.mapper,250,720,13,muted,false,420);
            label(c,replay.modsLabel,250,741,12,accent(0xffff9ad7),true,420);
            if(metadataStart<0&&Double.isFinite(replay.pp))metadataStart=Math.max(850,elapsed);
            long metaElapsed=metadataStart<0?0:elapsed-metadataStart;
            stat(c,30,752,146,LocaleController.getString(R.string.OsuReplayAccuracy),String.format(Locale.US,"%.2f%%",OsuScoreAnimation.accuracyPercent(replay.accuracy(),elapsed)),Color.WHITE,elapsed,0);
            stat(c,178,752,146,LocaleController.getString(R.string.OsuReplayMaxCombo),OsuScoreAnimation.counter(replay.combo,elapsed,1)+(replay.maxCombo>0 ? " / "+replay.maxCombo : ""),Color.WHITE,elapsed,1);
            stat(c,326,752,144,"PP",Double.isFinite(replay.pp) ? Long.toString(OsuScoreAnimation.lateCounter(Math.round(replay.pp),metaElapsed)) : "—",Color.WHITE,elapsed,2);
            String[] judgementNames = replay.mode == 3 ? new String[]{"PERFECT","GREAT","GOOD","OK","MEH","MISS"} : new String[]{"GREAT","OK","MEH","MISS"};
            int[] values = replay.mode == 3 ? new int[]{replay.geki,replay.great,replay.katu,replay.ok,replay.meh,replay.miss} : new int[]{replay.great,replay.ok,replay.meh,replay.miss};
            if (replay.mode == 1) { judgementNames = new String[]{"GREAT","OK","MISS"}; values = new int[]{replay.great,replay.ok,replay.miss}; }
            if (replay.mode == 2) { judgementNames = new String[]{"FRUIT","DROPLET","TINY","TINY MISS","MISS"}; values = new int[]{replay.great,replay.ok,replay.meh,replay.katu,replay.miss}; }
            for(int i=0;i<values.length;i++) stat(c,30+i*(440f/values.length),800,440f/values.length-2,judgementNames[i],Long.toString(OsuScoreAnimation.counter(values[i],elapsed,3+i)),i==values.length-1 ? colors[0] : i==0 ? 0xff7cf6ff : colors[2],elapsed,3+i);
            if (replay.lazer && replay.mode==0) {
                boolean spinner=replay.max("large_bonus")>0||replay.max("small_bonus")>0;
                float width=spinner?109:219;
                stat(c,30,846,width,"SLIDER TICK",ratio("large_tick_hit","large_tick_miss",elapsed,7),0xff7cf6ff,elapsed,7);
                stat(c,30+width+2,846,width,"SLIDER END",ratio("slider_tail_hit","slider_tail_miss",elapsed,8),0xff7cf6ff,elapsed,8);
                if(spinner) {
                    stat(c,252,846,108,"SPINNER BONUS",ratio("large_bonus","",elapsed,9),0xff7cf6ff,elapsed,9);
                    stat(c,362,846,108,"SPINNER SPIN",ratio("small_bonus","",elapsed,10),0xff7cf6ff,elapsed,10);
                }
            }
            label(c,LocaleController.getString(R.string.OsuReplayPlayedOn)+" "+dateFormat.format(new Date(replay.playedAt)),250,925,11,muted,false,430);
            c.restore();
            if (start>=0 && (elapsed<6000 || metadataStart>=0&&metaElapsed<3000) && !closed&&!paused) postInvalidateOnAnimation();
        }
        String ratio(String hit,String miss,long elapsed,int index) { int n=replay.count(hit), max=Math.max(replay.max(hit),n+replay.count(miss)); return OsuScoreAnimation.counter(n,elapsed,index)+" / "+max; }
        void stat(Canvas c,float x,float y,float width,String title,String value,int color,long elapsed,int index) {
            rounded(c,x,y,width,14,7,0xff252827); label(c,title.toUpperCase(Locale.ROOT),x+width/2,y+11,11,color,true,width-4);
            int content=c.saveLayerAlpha(x,y+14,x+width,y+50,Math.round(OsuScoreAnimation.statisticAlpha(elapsed,index)*255));
            boolean perfect=index==1&&replay.maxCombo>0&&replay.combo==replay.maxCombo;
            float valueWidth=perfect?width-42:width;
            label(c,value,x+valueWidth/2,y+37,23,primary(),false,valueWidth-4);
            if(perfect)label(c,"PERFECT",x+width-21,y+34,9,(Math.round(OsuScoreAnimation.perfectCombo(elapsed)*255)<<24)|0x66ffcc,true,40);
            c.restoreToCount(content);
        }
        @Override public boolean performClick() { super.performClick(); return true; }
        @Override public void onInitializeAccessibilityNodeInfo(android.view.accessibility.AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(info);
            info.addAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_DISMISS);
        }
        @Override public boolean performAccessibilityAction(int action, android.os.Bundle arguments) {
            if (action == android.view.accessibility.AccessibilityNodeInfo.ACTION_DISMISS) { if(dismissAction!=null)dismissAction.run();return true; }
            return super.performAccessibilityAction(action,arguments);
        }
    }
