"""Count fade captures using extracted production methods and Android stubs.

Run with Python 3 and a JDK containing jdk.compiler. This is a scheduling
reproduction, not an Android renderer or a frame-time benchmark.
"""
import sys, pathlib, subprocess, tempfile
ROOT = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT/'tests'))
from test_glass_blur import method
src=(ROOT/'TMessagesProj/src/main/java/org/telegram/ui/ChatActivity.java').read_text()
if len(sys.argv)>1:
    src=subprocess.check_output(['git','show',sys.argv[1]+':TMessagesProj/src/main/java/org/telegram/ui/ChatActivity.java'],cwd=ROOT,text=True)
methods='\n'.join(method(src,s) for s in (
'private void invalidateMergedVisibleBlurredPositionsAndSources(int flags)',
'private void invalidateFadeBlur()',
'private void invalidateFadeBlurImpl()',
'private void captureFadeBlur()'))
harness=r'''
public class CaptureAudit {
 static class Build { static class VERSION { static int SDK_INT=33; } static class VERSION_CODES { static final int S=31,TIRAMISU=33; } }
 static class SystemClock { static long now=1000; static long uptimeMillis(){return now;} }
 static class NekoConfig {
  static boolean fade=true, progressive=false, blurredFadeDimming=true;
  static int progressiveFadeBlurRefreshRate=120, blurredFadePixelation=1, progressiveFadeBlurMaxRadius=20, progressiveFadeBlurSamples=11, blurredFadeBlurStrength=20, blurredFadeDimStrength=50;
  static boolean blurredFadeViewEnabled(){return fade;} static boolean progressiveFadeBlurEnabled(){return progressive;}
 }
 static class AndroidUtilities {static float dpf2(float v){return v;}}
 static class Theme {static int key_chat_wallpaper;}
 static class Color {static int alpha(int c){return 255;}}
 static class ColorUtils {static int setAlphaComponent(int c,int a){return c;}}
 static class Canvas {void drawColor(int c){} void save(){} void translate(float x,float y){} void restore(){}}
 static class BitwiseUtils { static boolean hasFlag(int flags,int mask){return (flags & mask)!=0;} }
 static class RectF {void set(int a,int b,int c,int d){}}
 static class Background {int draws; int getWidth(){return 1080;} float getX(){return 0;} float getY(){return 0;} void draw(Canvas c){draws++;}}
 static class Content {int captures; Background backgroundView=new Background(); int getWidth(){return 1080;} int getHeight(){return 2400;} void drawList(Canvas c,RectF p){captures++;}}
 interface BlurredBackgroundSource {void draw(Canvas c,int a,int b,int d,int e);}
 static class Source implements BlurredBackgroundSource {
  BlurredBackgroundSource underSource; int recordings;
  boolean inRecording(){return false;} void setPixelation(int v){} void setProgressiveBlur(float a,int b,int c,float d,float e,int f){} void setBlur(float a){}
  Canvas beginRecording(int w,int h){recordings++;return new Canvas();} void endRecording(){} void setUnderSource(BlurredBackgroundSource s){underSource=s;} void invalidateDisplayListForDrawables(){}
  public void draw(Canvas c,int a,int b,int d,int e){}
 }
 static class Fade {int getFadeZoneTop(){return 100;} int getFadeZoneBottom(){return 100;} void setDimColor(int c){} void setDim(int c){} void invalidate(){}}
 static class Observer {int flags; void invalidate(int f){flags|=f;}}
 static class Chat {
  static final int BLUR_INVALIDATE_FLAG_SCROLL=1, BLUR_INVALIDATE_FLAG_POSITIONS=2, BLUR_INVALIDATE_FLAG_CLIP=4, BLUR_INVALIDATE_FLAG_WALLPAPER=8;
  boolean fadeWallpaperDirty=true, openAnimationEnded=true, inPreviewMode, isInsideContainer, inBubbleMode; int fadeWallpaperWidth,fadeWallpaperHeight;
  int glassSourceCaptureDepth,pendingGlassSourceFlags; boolean fadeBlurCaptureScheduled,fadeBlurCapturePending; long lastFadeBlurUpdateTime;
  Runnable feedContentChangedCallback; Chat parentChatActivity;
  Content contentView=new Content(); Object scrollableViewNoiseSuppressor=new Object();
  Observer fadeBlurCaptureView=new Observer(), invalidateBlurredSourcesView=new Observer();
  Source fadeBlurSource=new Source(),fadeWallpaperSource=new Source(),navbarContentSourceWallpaper=new Source(),navbarContentSourceWallpaperSharp=new Source();
  Fade chatActivityFadeView=new Fade(); RectF fadeBlurCaptureRect=new RectF();
  void syncFadeBlurEnabledState(){} int getThemedColor(int key){return 0;} int dp(int v){return v;}
  METHODS
  void flush(){if(fadeBlurCaptureView.flags!=0){fadeBlurCaptureView.flags=0;invalidateFadeBlurImpl();}}
 }
 public static void main(String[] args){
  if(!BASELINE){
   NekoConfig.fade=true;NekoConfig.progressive=true;
   Chat opening=new Chat();opening.openAnimationEnded=false;
   SystemClock.now+=1000;
   for(int change=0;change<1000;change++)opening.invalidateFadeBlur();
   opening.flush();
   if(opening.contentView.captures!=1 || opening.fadeBlurCaptureScheduled)throw new AssertionError("Opening must capture before the transition ends and coalesce requests");
   opening.invalidateFadeBlur();opening.flush();
   if(opening.contentView.captures!=1 || !opening.fadeBlurCapturePending)throw new AssertionError("Opening must retain the refresh limit");
   SystemClock.now+=1000;opening.invalidateFadeBlur();opening.flush();
   if(opening.contentView.captures!=2)throw new AssertionError("Opening content must keep updating");
  }
  for(int flags : new int[]{2,1,8}) for(int mode=0;mode<3;mode++){
   NekoConfig.fade=mode!=0;NekoConfig.progressive=mode==2;Chat c=new Chat();
   for(int frame=0;frame<120;frame++){SystemClock.now+=9;c.invalidateMergedVisibleBlurredPositionsAndSources(flags);c.flush();}
   System.out.println("mode="+mode+" flags="+flags+" updates=120 message_captures="+c.contentView.captures+" wallpaper_draws="+c.contentView.backgroundView.draws);
   if(!BASELINE){
    int captures=mode==0 || flags==2 ? 0 : 120;
    int wallpapers=captures==0 ? 0 : flags==8 ? 120 : 1;
    if(c.contentView.captures!=captures || c.contentView.backgroundView.draws!=wallpapers)throw new AssertionError();
   }
  }
 }
}
'''.replace('METHODS',methods).replace('BASELINE',str(len(sys.argv)>1).lower())
with tempfile.TemporaryDirectory(prefix='chat-fade-audit-') as directory:
    p=pathlib.Path(directory)/'CaptureAudit.java'
    p.write_text(harness)
    subprocess.run(['java','-m','jdk.compiler/com.sun.tools.javac.Main',str(p)],check=True)
    subprocess.run(['java','-cp',directory,'CaptureAudit'],check=True)
