package zxc.iconic.xenon.osu;

import android.graphics.*;

/** Native Canvas counterparts of ppy/osu's procedural Argon components (MIT).
 * No legacy skin sprites are used by these components. All transforms use replay time.
 */
final class OsuArgonRenderer {
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect=new RectF();
    private final Path shape=new Path();
    private final OsuLazerChevron followChevron=new OsuLazerChevron();
    private final OsuBitmapFont bold=OsuBitmapFont.get("Torus-Bold");
    private final OsuBitmapFont semiBold=OsuBitmapFont.get("Torus-SemiBold");
    private final PorterDuffXfermode additive=new PorterDuffXfermode(PorterDuff.Mode.ADD);
    static int dark(int colour,float amount){float factor=1/(1+amount);return Color.rgb(Math.round(Color.red(colour)*factor),Math.round(Color.green(colour)*factor),Math.round(Color.blue(colour)*factor));}
    private void style(int colour,float alpha,boolean stroke,float thickness){paint.setShader(null);paint.setColor(colour);paint.setAlpha(Math.round(255*Math.max(0,Math.min(1,alpha))));paint.setStyle(stroke?Paint.Style.STROKE:Paint.Style.FILL);paint.setStrokeWidth(thickness);}
    private void circle(Canvas c,float x,float y,float radius,int colour,float alpha){style(colour,alpha,false,0);c.drawCircle(x,y,Math.max(0,radius),paint);}
    private void ring(Canvas c,float x,float y,float radius,int colour,float alpha,float thickness){style(colour,alpha,true,thickness);c.drawCircle(x,y,Math.max(0,radius-thickness/2),paint);}
    private void gradient(Canvas c,float x,float y,float radius,int top,int bottom,float alpha){style(Color.WHITE,alpha,false,0);paint.setShader(new LinearGradient(x,y-radius,x,y+radius,top,bottom,Shader.TileMode.CLAMP));c.drawCircle(x,y,radius,paint);paint.setShader(null);}
    private final Path healthPath=new Path(),healthSegment=new Path();
    private final PathMeasure healthMeasure=new PathMeasure();
    private float healthWidth=-1,healthHeight=-1;
    void health(Canvas c,float x,float y,float width,float height,float hp){
        if(width!=healthWidth||height!=healthHeight){
            healthWidth=width;healthHeight=height;healthPath.rewind();float[] points=OsuArgonGeometry.healthPath(width,height);healthPath.moveTo(points[0],points[1]);for(int i=2;i<points.length;i+=2)healthPath.lineTo(points[i],points[i+1]);healthMeasure.setPath(healthPath,false);
        }
        c.save();c.translate(x,y);paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeJoin(Paint.Join.ROUND);
        // Original shader background: white one-unit rim, grey interior and dark centre.
        style(Color.WHITE,.8f,true,20);c.drawPath(healthPath,paint);
        style(0xff8a8a8a,.85f,true,18);c.drawPath(healthPath,paint);
        style(0xff12191c,.85f,true,14);c.drawPath(healthPath,paint);
        healthSegment.rewind();healthMeasure.getSegment(0,Math.max(.01f,Math.min(1,hp)*healthMeasure.getLength()),healthSegment,true);
        int colour=hp<.2f?0xffff5d5d:0xff7ed7fd;
        paint.setXfermode(additive);float previous=0;
        for(int band=40;band>=4;band--){float target=.5f*(float)Math.pow((40-band)/36f,8),opacity=(target-previous)/Math.max(.001f,1-previous);style(colour,opacity,true,band*2);c.drawPath(healthSegment,paint);previous=target;}
        style(colour,.8f,true,9);c.drawPath(healthSegment,paint);style(Color.WHITE,1,true,6);c.drawPath(healthSegment,paint);
        paint.setXfermode(null);paint.setStrokeCap(Paint.Cap.BUTT);c.restore();
    }
    void wedge(Canvas c,float x,float y,float width,float height){
        c.save();c.translate(x,y);c.skew(-.8f,0);style(Color.WHITE,1,false,0);paint.setShader(new LinearGradient(0,0,0,height,0x0066ccff,0x4066ccff,Shader.TileMode.CLAMP));rect.set(0,0,width,height);c.drawRoundRect(rect,10,10,paint);paint.setShader(null);c.restore();
    }
    void head(Canvas c,float x,float y,float radius,int colour,float alpha,int number,boolean slider,double hitAge,float kiai){
        float border=radius*4/58,outer=radius-border*2,thickness=border*2.5f;
        float fillAlpha=1-OsuArgonAnimation.out(hitAge,150),ringScale=OsuArgonAnimation.ringScale(hitAge);
        int dark=dark(colour,4);
        if(!slider)circle(c,x,y,radius-.5f,dark,alpha*fillAlpha);
        float gradientAlpha=hitAge<=12.5?1:1-OsuArgonAnimation.progress(hitAge-92.5,150);
        int flashColour=blend(colour,Color.WHITE,OsuArgonAnimation.progress(hitAge-12.5,80));
        gradient(c,x,y,outer*OsuArgonAnimation.ringScale(hitAge-12.5),flashColour,dark(flashColour,.1f),alpha*gradientAlpha);
        gradient(c,x,y,Math.max(1,outer-thickness),dark(colour,.5f),dark(colour,.6f),alpha*fillAlpha);
        circle(c,x,y,Math.max(0,outer-2*thickness),dark,alpha*fillAlpha);
        if(kiai>0){paint.setXfermode(additive);circle(c,x,y,radius*ringScale,colour,alpha*kiai*fillAlpha);paint.setXfermode(null);}
        if(hitAge<=75)bold.centered(c,Integer.toString(number),x,y-radius*.03125f,radius*.8125f,(Math.round(alpha*OsuArgonAnimation.numberAlpha(hitAge)*255)<<24)|0xffffff,radius*1.7f,0);
        // The hit border fades to a translucent vertical gradient, not an opaque combo-colour ring.
        float borderFade=OsuArgonAnimation.progress(hitAge,800);
        int borderColour=blend(Color.WHITE,colour,borderFade);
        float borderRadius=radius*ringScale+border*.5f*(1-ringScale)/.2f;
        style(Color.WHITE,alpha,true,border);
        paint.setShader(new LinearGradient(x,y-borderRadius,x,y+borderRadius,
                (Math.round(255*(1-.5f*borderFade))<<24)|(borderColour&0xffffff),
                (Math.round(255*(1-borderFade))<<24)|(borderColour&0xffffff),Shader.TileMode.CLAMP));
        c.drawCircle(x,y,Math.max(0,borderRadius-border/2),paint);paint.setShader(null);
        if(hitAge>0&&hitAge<800){
            float flash=OsuArgonAnimation.out(hitAge,150);
            paint.setXfermode(additive);style(colour,alpha*flash,false,0);
            paint.setShader(new RadialGradient(x,y,radius*1.7f,new int[]{colour,colour,colour&0xffffff},new float[]{0,.29f,1},Shader.TileMode.CLAMP));
            c.drawCircle(x,y,radius*1.7f,paint);paint.setShader(null);paint.setXfermode(null);
        }
    }
    private static int blend(int a,int b,float t){return Color.rgb(Math.round(Color.red(a)+(Color.red(b)-Color.red(a))*t),Math.round(Color.green(a)+(Color.green(b)-Color.green(a))*t),Math.round(Color.blue(a)+(Color.blue(b)-Color.blue(a))*t));}
    void approach(Canvas c,float x,float y,float radius,int colour,float alpha){
        // Gameplay/osu/approachcircle is a soft ring, not a one-pixel outline.
        for(int i=0;i<5;i++)ring(c,x,y,radius-i*.45f,colour,alpha*(i==2?.55f:.12f),2.2f);
    }
    void sliderBody(Canvas c,Path path,float radius,int colour,float alpha){
        float border=radius*4/58,outer=radius-border*2,thickness=border*2.5f;
        int layer=c.saveLayer(null,null);paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC));paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeJoin(Paint.Join.ROUND);
        style(colour,alpha*.92f,true,outer*2);c.drawPath(path,paint);
        style(dark(colour,4),alpha*.92f,true,Math.max(1,(outer-thickness)*2));c.drawPath(path,paint);
        paint.setXfermode(null);paint.setStrokeCap(Paint.Cap.BUTT);c.restoreToCount(layer);
    }
    void ball(Canvas c,float x,float y,float radius,int colour,float alpha,float rotation){
        float border=radius*4/58,outer=radius-border*2;
        gradient(c,x,y,outer,colour,dark(colour,.5f),alpha);ring(c,x,y,outer,Color.WHITE,alpha,border*2.5f);
        c.save();c.rotate(rotation,x,y);chevron(c,x,y,radius*.23f,Color.WHITE,alpha,1);c.restore();
    }
    void follow(Canvas c,float x,float y,float radius,int colour,float alpha,float scale){
        paint.setXfermode(additive);gradient(c,x,y,radius*scale,colour,dark(colour,.5f),alpha*.3f);ring(c,x,y,radius*scale,colour,alpha,4*radius/64);paint.setXfermode(null);
    }
    void reverse(Canvas c,float x,float y,float radius,int colour,float alpha,float rotation,double age){
        c.save();c.rotate(rotation,x,y);float scale=OsuArgonAnimation.reversePulse(age);c.scale(scale,scale,x,y);
        style(Color.WHITE,alpha,false,0);rect.set(x-radius*.3125f,y-radius*.15625f,x+radius*.3125f,y+radius*.15625f);c.drawRoundRect(rect,radius*.15625f,radius*.15625f,paint);
        chevron(c,x-radius*.06f,y,radius*.1f,dark(colour,4),alpha,2);c.restore();
    }
    private void chevron(Canvas c,float x,float y,float size,int colour,float alpha,int count){
        style(colour,alpha,true,Math.max(1,size*.35f));paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeJoin(Paint.Join.ROUND);
        for(int i=0;i<count;i++){float offset=i*size;shape.rewind();shape.moveTo(x-size*.5f+offset,y-size);shape.lineTo(x+size*.5f+offset,y);shape.lineTo(x-size*.5f+offset,y+size);c.drawPath(shape,paint);}paint.setStrokeCap(Paint.Cap.BUTT);
    }
    void tick(Canvas c,float x,float y,float radius,int colour,float alpha){ring(c,x,y,radius*6/64,colour,alpha,radius*3/64);}
    void followPoint(Canvas c,float x,float y,float angle,float alpha){followPoint(c,x,y,angle,alpha,1);}
    void followPoint(Canvas c,float x,float y,float angle,float alpha,float scale){
        c.save();c.translate(x,y);c.rotate(angle,0,0);c.scale(scale,scale);paint.setXfermode(additive);
        style(Color.WHITE,alpha*.2f,false,0);paint.setShader(new LinearGradient(0,-4,0,4,0xfffc618f,0xffbb1a41,Shader.TileMode.CLAMP));followChevron.draw(c,paint,-2,0,8,false);
        paint.setAlpha(Math.round(alpha*255));followChevron.draw(c,paint,2,0,8,false);paint.setShader(null);paint.setXfermode(null);c.restore();
    }
    void cursor(Canvas c,float x,float y,boolean pressed,float alpha){
        cursor(c,x,y,pressed?1.2f:1,alpha);
    }
    void cursor(Canvas c,float x,float y,float expansion,float alpha){
        float radius=14*expansion;
        circle(c,x,y,radius,dark(0xfffc618f,.6f),alpha*.4f);
        style(Color.WHITE,alpha,true,6*expansion);paint.setShader(new LinearGradient(x,y-radius,x,y+radius,0xfffc618f,0xffbb1a41,Shader.TileMode.CLAMP));c.drawCircle(x,y,radius-3*expansion,paint);paint.setShader(null);
        ring(c,x,y,radius,Color.WHITE,alpha*.8f,1);
        paint.setXfermode(additive);style(Color.WHITE,alpha,false,0);paint.setShader(new RadialGradient(x,y,8,new int[]{0xffffffff,0x64abffff,0x00abffff},new float[]{0,.2f,1},Shader.TileMode.CLAMP));c.drawCircle(x,y,8,paint);paint.setShader(null);circle(c,x,y,1.8f,Color.WHITE,alpha);paint.setXfermode(null);
    }
    void trail(Canvas c,float x,float y,float alpha){circle(c,x,y,3.2f,0xfffc618f,alpha);}
    void judgement(Canvas c,int value,float x,float y,double age,int seed,float radius){
        c.save();c.translate(x,y);float scale=OsuArgonGeometry.judgementScale(radius);c.scale(scale,scale);
        judgement(c,value,0,0,age,seed);c.restore();
    }
    void judgement(Canvas c,int value,float x,float y,double age,int seed){
        if(value==300||age>=1000)return; // Pro deliberately hides Great/Perfect.
        int colour=value==100?0xff88b300:value==50?0xffffcc22:0xffed1121;
        float alpha=1-OsuArgonAnimation.progress(age,800);c.save();
        if(value==0){float t=(float)Math.pow(OsuArgonAnimation.progress(age,800),5);c.translate(0,100*t);c.rotate(40*t,x,y);float scale=1.6f-.6f*(float)Math.pow(OsuArgonAnimation.progress(age,100),2);c.scale(scale,scale,x,y);}
        else{float scale=1+.2f*OsuArgonAnimation.out(age,1800);c.scale(scale,scale,x,y);alpha*=OsuArgonAnimation.out(age,300);}
        paint.setXfermode(additive);
        bold.centeredAdditive(c,value==0?"MISS":value==100?"OK":"MEH",x,y,20,(Math.round(alpha*255)<<24)|(colour&0xffffff),160,5);paint.setXfermode(null);c.restore();
        if(value>0){paint.setXfermode(additive);int count=value==50?3:4;float travel=52*(value==50?.3f:.6f);
            for(int i=0;i<count;i++){double direction=(seed*137.5+i*360.0/count)*Math.PI/180;float distance=travel*(.5f+((seed+i*17)&31)/62f)*(.3f+.7f*OsuArgonAnimation.out(age,600));ring(c,x+(float)Math.cos(direction)*distance,y+(float)Math.sin(direction)*distance,4.5f,colour,(1-OsuArgonAnimation.progress(age,800))*(1-OsuArgonAnimation.out(age,1000)),4);}
            paint.setXfermode(null);
        }
    }
    private void arc(Canvas c,float x,float y,float radius,float start,float sweep,float thickness,int colour,float alpha){
        if(sweep<=0||alpha<=0)return;
        style(colour,alpha,true,thickness);paint.setStrokeCap(Paint.Cap.ROUND);
        rect.set(x-radius+thickness/2,y-radius+thickness/2,x+radius-thickness/2,y+radius-thickness/2);
        c.drawArc(rect,start,sweep,false,paint);paint.setStrokeCap(Paint.Cap.BUTT);
    }
    void spinner(Canvas c,OsuReplaySpinners.Spin spin,double time,double start,double end,double preempt,float alpha){
        float x=256,y=192,radius=192;
        float progress=spin==null?0:spin.smoothProgress(time,100),tracking=spin==null?0:spin.tracking(time);
        float completion=OsuArgonAnimation.spinnerCompletion(time,spin==null?Double.POSITIVE_INFINITY:spin.completedAt());
        double after=Math.max(0,time-end);
        float discScale=OsuArgonAnimation.spinnerDiscScale(time-start,preempt);
        if(after>0)discScale*=1+(spin!=null&&spin.progress(end)>.75f?.2f*OsuArgonAnimation.out(after,320):-.2f*(float)Math.pow(OsuArgonAnimation.progress(after,320),5));
        c.save();c.scale(discScale,discScale,x,y);
        // ArgonSpinnerDisc: growing pink shadow fill inside the eight-unit padding.
        float fillRadius=(radius-8)*(.1f+.88f*progress),fillAlpha=alpha*(.2f+.2f*tracking);
        if(completion>0&&spin!=null&&spin.turns(time)>0){double age=time-spin.turnTime(spin.turns(time)-1);fillAlpha+=alpha*.2f*(1-OsuArgonAnimation.out(Math.max(0,age-60),250))*OsuArgonAnimation.out(age,60);}
        style(Color.WHITE,fillAlpha,false,0);
        paint.setShader(new RadialGradient(x,y,fillRadius+40,new int[]{0xfffc618f,0xfffc618f,0x00fc618f},new float[]{0,Math.max(.01f,(fillRadius-2)/(fillRadius+40)),1},Shader.TileMode.CLAMP));
        c.drawCircle(x,y,fillRadius+40,paint);paint.setShader(null);
        c.save();float ambient=(float)(25*(end-start)/2000)*OsuArgonAnimation.progress(time-start+preempt/2,end-start+preempt);
        c.rotate((spin==null?0:(float)spin.rotation(Math.min(time,end)))+ambient+(float)(180*OsuArgonAnimation.progress(after,320)),x,y);
        // Upstream ticks are 30x5 with a two-unit border (not half-sized).
        for(int i=0;i<25;i++){
            double angle=i*Math.PI*2/25;float tx=x+(float)Math.sin(angle)*(radius-8)*.75f,ty=y+(float)Math.cos(angle)*(radius-8)*.75f;
            c.save();c.rotate(-i*360f/25-120,tx,ty);
            float previous=0;
            for(int band=12;band>=1;band--){float target=.2f*(float)Math.pow(1-band/13f,3);style(Color.WHITE,alpha*(target-previous),false,0);rect.set(tx-15-band,ty-2.5f-band,tx+15+band,ty+2.5f+band);c.drawRoundRect(rect,2.5f+band,2.5f+band,paint);previous=target;}
            style(Color.WHITE,alpha,true,2);rect.set(tx-14,ty-1.5f,tx+14,ty+1.5f);c.drawRoundRect(rect,1.5f,1.5f,paint);c.restore();
        }c.restore();
        float sweep=360*(.31f+.19f*completion),thickness=(radius-8)*(.02f+.024f*completion);
        arc(c,x,y,radius-8,-90-sweep/2,sweep,thickness,Color.WHITE,alpha);
        arc(c,x,y,radius-8,90-sweep/2,sweep,thickness,Color.WHITE,alpha);
        float sideProgress=spin==null?0:spin.smoothProgress(time,40),sideSweep=54*sideProgress*(1-completion),sideWidth=radius*.12f;
        float sideAlpha=alpha*(1-completion);
        for(int side=0;side<2;side++){
            float centre=side==0?0:180;
            arc(c,x,y,radius,centre-27,54,sideWidth,Color.WHITE,alpha*.25f*(1-completion));
            // Layered halo approximates the upstream SpinnerGlow shader on Canvas.
            float previous=0;
            for(int band=20;band>=1;band--){float target=.5f*(float)Math.pow(1-band/21f,3);arc(c,x,y,radius+band,centre-sideSweep/2,sideSweep,sideWidth+band*2,0xffabffff,sideAlpha*(target-previous));previous=target;}
            arc(c,x,y,radius,centre-sideSweep/2,sideSweep,sideWidth,Color.WHITE,sideAlpha);
        }
        c.restore();
        float centreScale=.3f*OsuArgonAnimation.out(time-start+preempt/2,preempt/4);
        if(time>=start)centreScale=.3f+.5f*OsuArgonAnimation.out(time-start,preempt/2);
        float centreRadius=(40-20*tracking)*centreScale;
        ring(c,x,y,centreRadius*.8f,Color.WHITE,alpha,10*centreScale);
        ring(c,x,y,centreRadius,Color.WHITE,alpha,3*centreScale);
        float spmAlpha=alpha*OsuArgonAnimation.progress(time-(spin==null?Double.POSITIVE_INFINITY:spin.startedAt()),OsuApproachCircle.fadeInDuration(preempt));
        semiBold.centered(c,Integer.toString(spin==null?0:spin.rpm(Math.min(time,end))),x,y+74,28,(Math.round(spmAlpha*255)<<24)|0xffffff,200,0);
        semiBold.centered(c,"SPINS PER MINUTE",x,y+98,16,(Math.round(spmAlpha*255)<<24)|0xffffff,220,0);
        int bonus=spin==null?0:spin.bonus(time);double bonusAge=spin==null?0:time-spin.lastBonus(time);
        if(bonus>0){boolean max=bonus==spin.maxBonus*spin.bonusValue;float fade=1-OsuArgonAnimation.progress(bonusAge,max?500:1500),scale=max?1.5f+1.3f*OsuArgonAnimation.out(bonusAge,1000):1.5f-.5f*OsuArgonAnimation.out(bonusAge,1000);bold.centered(c,max?"MAX":Integer.toString(bonus),x,y-100,28*scale,(Math.round(alpha*fade*255)<<24)|0xffffff,300,0);}
    }
}
