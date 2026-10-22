package zxc.iconic.xenon.osu;

import android.graphics.*;
import java.util.Locale;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;

/** Canvas counterpart of ppy/osu BreakOverlay, BreakArrows and RemainingTimeCounter. */
final class OsuLazerBreakRenderer {
    private final OsuReplaySession session;
    private final OsuBitmapFont numeric=OsuBitmapFont.get("Venera-Bold"),bold=OsuBitmapFont.get("Torus-Bold"),regular=OsuBitmapFont.get("Torus-Regular");
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
    private final RectF rect=new RectF();
    private final OsuLazerChevron chevron=new OsuLazerChevron();
    private final OsuReplayParallax nearParallax=new OsuReplayParallax(),farParallax=new OsuReplayParallax();
    private Bitmap glow,blurred;
    private double snapshotStart=Double.NaN;
    private String accuracy,grade;
    OsuLazerBreakRenderer(OsuReplaySession session){this.session=session;}
    private Bitmap icon(float size,float sigma){
        int side=(int)(size+sigma*8);Bitmap bitmap=Bitmap.createBitmap(side,side,Bitmap.Config.ARGB_8888);
        Canvas c=new Canvas(bitmap);paint.setColor(Color.WHITE);paint.setMaskFilter(new BlurMaskFilter(sigma,BlurMaskFilter.Blur.NORMAL));
        chevron.draw(c,paint,side/2f,side/2f,size,false);paint.setMaskFilter(null);return bitmap;
    }
    private void arrow(Canvas c,float x,float y,float size,boolean left,boolean diffuse){
        if(glow==null){glow=icon(60,10);blurred=icon(130,20);}
        Bitmap image=diffuse?blurred:glow;float side=diffuse?290:140;
        c.save();c.translate(x,y);if(left)c.scale(-1,1);paint.setColor(diffuse?0xb3ffffff:Color.WHITE);
        rect.set(-side/2,-side/2,side/2,side/2);c.drawBitmap(image,null,rect,paint);
        if(!diffuse){paint.setColor(Color.WHITE);chevron.draw(c,paint,0,0,size,false);}c.restore();
    }
    private void snapshot(double start){
        if(snapshotStart==start)return;snapshotStart=start;
        double acc=session.hud.accuracy(start);accuracy=String.format(Locale.US,"%.2f%%",acc*100);
        OsuReplay score=new OsuReplay();score.lazer=session.replay.lazer;score.mode=session.replay.mode;
        for(OsuReplayJudgements.Result result:session.judgements.results){if(result.time>start)break;switch(result.value){case 300:score.great++;break;case 100:score.ok++;break;case 50:score.meh++;break;default:score.miss++;}}
        grade=score.lazer?(acc==1?"SS":acc>=.95&&score.miss==0?"S":acc>=.9?"A":acc>=.8?"B":acc>=.7?"C":"D"):score.displayRank();
    }
    void draw(Canvas c,double time,float screenWidth,float screenHeight){
        draw(c,time,screenWidth,screenHeight,screenWidth/2,screenHeight/2,0,0);
    }
    boolean animating(){return nearParallax.animating()||farParallax.animating();}
    void draw(Canvas c,double time,float screenWidth,float screenHeight,float cursorX,float cursorY,int parallaxSetting,double elapsed){
        nearParallax.update(screenWidth,screenHeight,cursorX,cursorY,parallaxSetting,elapsed,false,-.01f);
        farParallax.update(screenWidth,screenHeight,cursorX,cursorY,parallaxSetting,elapsed,false,-.02f);
        double[] period=null;for(double[] b:session.map.breaks)if(OsuBreakAnimation.visible(b[0],b[1],time)){period=b;break;}
        if(period==null)return;double start=period[0],end=period[1];snapshot(start);
        float scale=OsuArgonGeometry.hudScale(screenWidth,screenHeight),width=screenWidth/scale,height=screenHeight/scale;
        int layer=c.saveLayerAlpha(0,0,screenWidth,screenHeight,Math.round(OsuBreakAnimation.alpha(start,end,time)*255));
        c.scale(scale,scale);float cx=width/2,cy=height/2,enter=OsuBreakAnimation.enter(start,time),exit=OsuBreakAnimation.exit(end,time);
        paint.setColor(Color.WHITE);paint.setShader(new RadialGradient(cx,cy,260,new int[]{0xcc333333,0x00333333},null,Shader.TileMode.CLAMP));c.drawRect(cx-300,cy-270,cx+300,cy+270,paint);paint.setShader(null);
        float near=.6f+(.22f-.6f)*enter+(.6f-.22f)*exit,far=.7f+(.38f-.7f)*enter+(.7f-.38f)*exit;
        c.save();c.translate(nearParallax.x/scale,nearParallax.y/scale);c.scale(nearParallax.scale,nearParallax.scale,cx,cy);
        arrow(c,cx-near*width-30,cy,60,false,false);arrow(c,cx+near*width+30,cy,60,true,false);c.restore();
        c.save();c.translate(farParallax.x/scale,farParallax.y/scale);c.scale(farParallax.scale,farParallax.scale,cx,cy);
        arrow(c,cx-far*width-65,cy,130,false,true);arrow(c,cx+far*width+65,cy,130,true,true);c.restore();
        float barWidth=width*.3f*enter*OsuBreakAnimation.remaining(start,end,time),barHeight=Math.min(8,barWidth);
        if(time<end-OsuBreakAnimation.FADE){paint.setColor(Color.WHITE);rect.set(cx-barWidth/2,cy-barHeight/2,cx+barWidth/2,cy+barHeight/2);c.drawRoundRect(rect,barHeight/2,barHeight/2,paint);}
        // Counter bottom is 15 above centre; info starts 15 below centre.
        numeric.centered(c,Integer.toString(OsuBreakAnimation.seconds(end,time)),cx-50*(1-enter),cy-31.5f,33,Color.WHITE,width*.3f,0);
        float infoX=cx+50*(1-enter);
        bold.centered(c,LocaleController.getString(R.string.OsuReplayBreakProgress).toUpperCase(Locale.ROOT),infoX,cy+22.5f,15,Color.WHITE,width*.4f,0);
        line(c,LocaleController.getString(R.string.OsuReplayAccuracy),accuracy,infoX,cy+43.5f);
        line(c,LocaleController.getString(R.string.OsuReplayBreakGrade),grade,infoX,cy+60.5f);
        c.restoreToCount(layer);
    }
    private void line(Canvas c,String name,String value,float x,float y){
        float labelWidth=regular.width(name,17,0),valueWidth=bold.width(value,17,0);
        regular.centered(c,name,x-2-labelWidth/2,y,17,0xffffcc22,labelWidth,0);
        bold.centered(c,value,x+2+valueWidth/2,y,17,0xffffdd55,valueWidth,0);
    }
    void dispose(){if(glow!=null)glow.recycle();if(blurred!=null)blurred.recycle();glow=blurred=null;}
}
