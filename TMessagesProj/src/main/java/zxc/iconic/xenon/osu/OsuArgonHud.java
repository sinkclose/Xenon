package zxc.iconic.xenon.osu;

import android.graphics.*;
import java.util.*;

/** ArgonSkin's default layout and native counter glyph metrics, in mobile lazer HUD units. */
final class OsuArgonHud {
    private final OsuReplaySession session;
    private final OsuArgonTimeline animations;
    private final OsuArgonRenderer renderer=new OsuArgonRenderer();
    private final OsuBitmapFont bold=OsuBitmapFont.get("Torus-Bold");
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
    private final RectF rect=new RectF();
    private final PorterDuffXfermode graphAdditive=new PorterDuffXfermode(PorterDuff.Mode.ADD);
    private final int[] density;
    private final int densityMax;
    private boolean progressExpanded;
    private float progressFrom=10;
    private long progressChangedAt=System.nanoTime()/1000000-1000;
    private float progressHeight(){return OsuArgonAnimation.progressHeight(System.nanoTime()/1000000-progressChangedAt,progressFrom,progressExpanded);}
    void setProgressExpanded(boolean value){if(value==progressExpanded)return;progressFrom=progressHeight();progressChangedAt=System.nanoTime()/1000000;progressExpanded=value;}
    boolean animating(){return System.nanoTime()/1000000-progressChangedAt<(progressExpanded?200:800);}
    boolean hitProgress(float x,float y,float width,float height){float scale=OsuArgonGeometry.hudScale(width,height),inset=OsuArgonGeometry.safeInset(width),left=inset+(width-2*inset)*.05f,right=width-left;return x>=left&&x<=right&&y>=height-55*scale&&y<=height;}
    float progressAt(float x,float width,float height){float inset=OsuArgonGeometry.safeInset(width),left=inset+(width-2*inset)*.05f;return Math.max(0,Math.min(1,(x-left)/Math.max(1,width-2*left)));}
    OsuArgonHud(OsuReplaySession session){this.session=session;animations=session.argonTimeline!=null?session.argonTimeline:new OsuArgonTimeline(session);density=OsuArgonGeometry.density(session.map);int max=1;for(int value:density)max=Math.max(max,value);densityMax=max;}
    private void label(Canvas c,String text,float x,float top,float size,int colour){float width=bold.width(text,size,0);bold.draw(c,text,x+width/2,top+size,size,colour,width,0);}
    void draw(Canvas c,OsuReplaySkin skin,double time,float screenWidth,float screenHeight){
        float scale=OsuArgonGeometry.hudScale(screenWidth,screenHeight),inset=OsuArgonGeometry.safeInset(screenWidth);
        float width=(screenWidth-inset*2)/scale,height=screenHeight/scale;
        c.save();c.translate(inset,0);c.scale(scale,scale);
        // Extend decorative backing to the physical screen edge, keeping counters
        // and health in their existing safe-area positions.
        float screenLeft=-inset/scale,backingLeft=Math.min(-50,screenLeft-20);
        renderer.wedge(c,backingLeft,15,330-backingLeft,72);renderer.wedge(c,backingLeft+4,20,330-backingLeft,72);
        paint.setStyle(Paint.Style.STROKE);paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeWidth(3);paint.setColor(Color.WHITE);c.drawLine(screenLeft,30,45,30,paint);paint.setStrokeCap(Paint.Cap.BUTT);paint.setStyle(Paint.Style.FILL);
        renderer.health(c,50,20,300,50,(float)session.health.at(time));

        String score=Long.toString(Math.round(animations.score.at(time)));
        skin.argonCounter(c,paint,rect,score,"#".repeat(Math.max(6,score.length())),250,50,1,true);
        String accuracy=String.format(Locale.US,"%.2f",(double)animations.accuracy.at(time));String[] parts=accuracy.split("\\.");
        float fullWidth=skin.argonWidth("###",1)+skin.argonWidth(".##",.5f)+skin.argonWidth("%",1),left=width-20-fullWidth;
        label(c,"ACCURACY",left+2.5f,20,12,0xff99ddff);
        skin.argonCounter(c,paint,rect,parts[0],"###",left,32,1,false);
        left+=skin.argonWidth("###",1);skin.argonCounter(c,paint,rect,"."+parts[1],".##",left,34,.5f,false);
        left+=skin.argonWidth(".##",.5f);skin.argonCounter(c,paint,rect,"%","#",left,32,1,false);
        // A stored PP total is valid at completion. Do not invent a live PP approximation.
        if(session.performance!=null&&Double.isFinite(session.performance.at(time))){
            String pp=Long.toString(Math.round(animations.pp.at(time)));float ppScale=.8f,ppWidth=skin.argonWidth("#".repeat(Math.max(3,pp.length())),ppScale);
            label(c,"PP",width-20-ppWidth+2,72,12*ppScale,0xff99ddff);
            skin.argonCounter(c,paint,rect,pp,"#".repeat(Math.max(3,pp.length())),width-20,72+12*ppScale,ppScale,true);
        }
        int combo=session.hud.combo(time);float comboScale=1.3f,pulse=animations.combo.at(time),numberHeight=skin.height("argon-counter-5")/8*comboScale;
        float top=height-66-42*comboScale-(pulse-1)*numberHeight;
        label(c,"COMBO",36+2.5f*comboScale,top,12*comboScale,0xff99ddff);
        c.save();c.translate(36,top+12*comboScale);c.scale(pulse,pulse);
        String comboText=combo+"x";skin.argonCounter(c,paint,rect,comboText,"#".repeat(comboText.length()),0,0,comboScale,false);c.restore();
        keys(c,skin,time,width,height);meter(c,skin,time,0,height/2,false);meter(c,skin,time,width,height/2,true);
        c.restore();
    }
    void drawProgress(Canvas c,double time,float screenWidth,float screenHeight){
        float scale=OsuArgonGeometry.hudScale(screenWidth,screenHeight),inset=OsuArgonGeometry.safeInset(screenWidth);
        c.save();c.translate(inset,0);c.scale(scale,scale);
        progress(c,Math.min(time,session.map.duration()),(screenWidth-2*inset)/scale,screenHeight/scale);c.restore();
    }
    private void keys(Canvas c,OsuReplaySkin skin,double time,float width,float height){
        OsuReplayFrames.Sample sample=new OsuReplayFrames.Sample();session.frames.at(time,sample);int raw=sample.keys;
        boolean lazer=session.replay.lazer;
        int buttons=lazer?((raw&5)!=0?1:0)|((raw&10)!=0?2:0)|((raw&16)!=0?4:0):((raw&4)!=0?1:0)|((raw&8)!=0?2:0)|((raw&1)!=0&&(raw&4)==0?4:0)|((raw&2)!=0&&(raw&8)==0?8:0);
        String[] labels=lazer?new String[]{"B1","B2","B3"}:new String[]{"K1","K2","M1","M2"};
        for(int i=0;i<labels.length;i++){
            float x=width-36-(labels.length-i)*54.5f,top=height-66-45;boolean pressed=(buttons&(1<<i))!=0;
            float offset=animations.keyY[i].at(time);paint.setColor((Math.round(animations.keyAlpha[i].at(time)*255)<<24)|0xffffff);rect.set(x,top+offset,x+52.5f,top+4.5f+offset);c.drawRoundRect(rect,2.25f,2.25f,paint);
            label(c,labels[i],x,top+8.5f,15,mix(0xff99ddff,Color.WHITE,animations.keyWhite[i].at(time)));
            label(c,Integer.toString(session.hud.keyCount(time,i)),x,top+24,21,Color.WHITE);
        }
    }
    private void progress(Canvas c,double time,float width,float height){
        float left=width*.05f,right=width*.95f,barHeight=progressHeight(),top=height-10-barHeight;
        label(c,timeText(time),left+2,top-22,10,Color.WHITE);String remaining=timeText(Math.max(0,session.map.duration()-time));label(c,remaining,right-bold.width(remaining,10,0)-2,top-22,10,0xffaaaaaa);
        // ArgonSongProgressBar: Gray(0.2), Alpha=0.3; the filled bar is opaque Gray(0.9).
        paint.setColor(0x4d333333);rect.set(left,top,right,top+barHeight);c.drawRoundRect(rect,5,5,paint);
        int save=c.save();Path clip=new Path();clip.addRoundRect(rect,5,5,Path.Direction.CW);c.clipPath(clip);
        float progress=(float)Math.max(0,Math.min(1,time/Math.max(1,session.map.duration())));
        paint.setColor(0xffe6e6e6);rect.set(left,top,left+(right-left)*progress,top+barHeight);c.drawRoundRect(rect,5,5,paint);
        float step=(right-left)/density.length;
        // Same 200 bins and five density tiers as ArgonSongProgressGraph.
        paint.setXfermode(graphAdditive);
        for(int i=0;i<density.length;i++){
            int tier=Math.min(5,(int)Math.floor(density[i]*5f/densityMax)+1);
            paint.setColor((Math.round(tier*.02f*255)<<24)|0xffffff);c.drawRect(left+i*step,top,left+(i+1)*step,top+barHeight,paint);
        }
        paint.setXfermode(null);
        c.restoreToCount(save);
    }
    private static int upper(List<Double> a,double t){int l=0,h=a.size();while(l<h){int m=(l+h)>>>1;if(a.get(m)<=t)l=m+1;else h=m;}return l;}
    private static int mix(int a,int b,float t){int r=Math.round(((a>>16)&255)*(1-t)+((b>>16)&255)*t),g=Math.round(((a>>8)&255)*(1-t)+((b>>8)&255)*t),bl=Math.round((a&255)*(1-t)+(b&255)*t);return 0xff000000|(r<<16)|(g<<8)|bl;}
    private static String timeText(double time){int seconds=(int)Math.max(0,time/1000);return seconds/60+":"+String.format(Locale.US,"%02d",seconds%60);}
    private void meter(Canvas c,OsuReplaySkin skin,double time,float edge,float centre,boolean right){
        c.save();c.translate(edge,centre);if(right)c.scale(-1,1);
        float x=17,meh=(float)Math.max(1,200-10*session.map.overallDifficulty(session.replay.mods));
        paint.setShader(new LinearGradient(0,-100,0,100,new int[]{0x00ffcc22,0xffffcc22,0xff88b300,0xff66ccff,0xff66ccff,0xff88b300,0xffffcc22,0x00ffcc22},OsuArgonGeometry.errorStops(session.map.overallDifficulty(session.replay.mods)),Shader.TileMode.CLAMP));
        paint.setColor(Color.WHITE);c.drawRect(x-1,-100,x+1,100,paint);paint.setShader(null);
        int lo=0,hi=animations.meterTimes.size();while(lo<hi){int mid=(lo+hi)>>>1;if(animations.meterTimes.get(mid)<time-5100)lo=mid+1;else hi=mid;}
        // Lazer's judgement lines use additive blending; source-over made these
        // marks too dull, especially where several hits overlap.
        paint.setXfermode(graphAdditive);
        for(int i=Math.max(lo,upper(animations.meterTimes,time)-50);i<animations.meterTimes.size();i++){
            double event=animations.meterTimes.get(i);if(event>time)break;
            float age=(float)(time-event),y=animations.meterPositions.get(i),appear=OsuArgonAnimation.out(age,100),fade=1-OsuArgonAnimation.progress(age-100,5000);
            float halfWidth=7*appear*(1-(float)Math.pow(1-fade,5));int colour=OsuArgonGeometry.hitErrorColour(animations.meterValues.get(i));
            paint.setColor((Math.round(.6f*appear*fade*255)<<24)|(colour&0xffffff));rect.set(x-halfWidth,y-2,x+halfWidth,y+2);c.drawRoundRect(rect,2,2,paint);
        }
        paint.setXfermode(null);
        float y=animations.arrow.at(time);
        paint.setColor(0xff66ccff);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(2);c.drawCircle(x,0,3,paint);
        Path arrow=new Path();arrow.moveTo(3,y-4);arrow.lineTo(7,y);arrow.lineTo(3,y+4);paint.setColor(Color.WHITE);c.drawPath(arrow,paint);paint.setStyle(Paint.Style.FILL);
        // Undo mirroring for the two original osu! icon textures.
        if(right){c.scale(-1,1);skin.draw(c,paint,rect,"argon-hare",-x,-110,10,1,null);skin.draw(c,paint,rect,"argon-tortoise",-x,110,10,1,null);}
        else{skin.draw(c,paint,rect,"argon-hare",x,-110,10,1,null);skin.draw(c,paint,rect,"argon-tortoise",x,110,10,1,null);}
        c.restore();
    }
}
