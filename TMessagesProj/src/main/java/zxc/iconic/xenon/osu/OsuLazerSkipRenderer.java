package zxc.iconic.xenon.osu;
import android.graphics.*;
/** Canvas rendering of ppy/osu SkipOverlay.Button, shared by the viewer and visual regression fixture. */
final class OsuLazerSkipRenderer {
    private final zxc.iconic.xenon.triangles.TriangleWallpaperRenderer triangles=new zxc.iconic.xenon.triangles.TriangleWallpaperRenderer(zxc.iconic.xenon.triangles.TriangleSettings.skipPreset(),1);
    void suspend(){triangles.suspend();}
    private android.view.View host;
    void draw(Canvas c,float width,float height,float opacity,float pressedScale,float remaining,long now,boolean pressed,android.view.View host){this.host=host;draw(c,width,height,opacity,pressedScale,remaining,now,pressed);}

    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect=new RectF();private final Path path=new Path();
    private final OsuBitmapFont font=OsuBitmapFont.get("Torus-Bold");
    private final OsuLazerChevron chevron=new OsuLazerChevron();
    private boolean hovered;
    private float hoverFrom,hoverTo;
    private long hoverChanged,clickedAt=-10000;
    private float hover(long now){return hoverFrom+(hoverTo-hoverFrom)*OsuScoreAnimation.outQuint(Math.min(1,Math.max(0,(now-hoverChanged)/500f)));}
    void setHovered(boolean value,long now){if(value==hovered)return;hoverFrom=hover(now);hoverTo=value?1:0;hoverChanged=now;hovered=value;}
    void click(long now){clickedAt=now;}
    void draw(Canvas c,float width,float height,float opacity,float pressedScale,float remaining,long now,boolean pressed){
            float scale=height/100f,w=width/scale;int layer=c.saveLayerAlpha(0,0,width,height,Math.round(opacity*255));c.scale(scale,scale);
            float hover=hover(now),flash=1-OsuScoreAnimation.outQuint(Math.min(1,Math.max(0,(now-clickedAt)/500f)));
            paint.setColor((Math.round((.2f+.2f*hover)*255)<<24));paint.setStyle(Paint.Style.FILL);c.drawRect(0,0,w,100,paint);
            paint.setColor(0xffcca633);rect.set((w-w*remaining)/2,95,(w+w*remaining)/2,100);c.drawRoundRect(rect,2.5f,2.5f,paint);
            c.save();float buttonScale=pressedScale;if(now-clickedAt<2000)buttonScale=1+.2f*OsuScoreAnimation.outQuint(Math.max(0,(now-clickedAt)/2000f));c.scale(buttonScale,buttonScale,w/2,50);c.translate(w/2-30,20);
            rect.set(0,0,60,60);path.rewind();path.addRoundRect(rect,15,15,Path.Direction.CW);c.clipPath(path);
            int r=Math.round((204+10*hover)*(1-flash)+255*flash),g=Math.round((166+18*hover)*(1-flash)+255*flash),b=Math.round((51+41*hover)*(1-flash)+255*flash);
            paint.setColor(0xff000000|(r<<16)|(g<<8)|b);c.drawRect(0,0,60,60,paint);paint.setShader(null);
            if(host!=null){triangles.setGradientColors(0xffd6b85c,0xffcca633);triangles.draw(c,host,null,0xffcca633,60,60,opacity>0&&host.isShown());}
            for(int i=0;i<3;i++){float x=30+(i-1)*(15+5*hover);paint.setColor(0x66000000);chevron.draw(c,paint,x,25,15,false);paint.setColor(Color.WHITE);chevron.draw(c,paint,x,24,15,false);}
            font.centered(c,"SKIP",30,42,12,Color.WHITE,55,0);c.restore();c.restoreToCount(layer);
    }
}
