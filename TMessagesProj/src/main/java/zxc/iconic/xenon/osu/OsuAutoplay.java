package zxc.iconic.xenon.osu;

import java.io.IOException;
import java.util.*;

/** Single-cursor port of danser-go's BezierMover, CircleMover and natural key timing.
 * Copyright (c) 2018-2026 Sebastian Krajewski. Adapted under GPL-3.0.
 * Source revision: 7cd903284017cb216fde596993dfa81dafca7af6.
 * See assets/licenses/danser-autoplay-GPL-3.0.txt.
 */
public final class OsuAutoplay {
    private static final double AGGRESSION=60,SLIDER_AGGRESSION=3,SPINS_PER_MS=.00795;
    private static final class Curve {
        double start,end;double[][] points;
        double[] at(double time){
            double p=Math.max(0,Math.min(1,(time-start)/Math.max(1,end-start)));
            double[][] work=new double[points.length][2];for(int i=0;i<points.length;i++)work[i]=points[i].clone();
            for(int n=work.length-1;n>0;n--)for(int i=0;i<n;i++)for(int a=0;a<2;a++)work[i][a]+=(work[i+1][a]-work[i][a])*p;
            return work[0];
        }
    }
    private static double[] position(OsuPlaybackMap.Hit hit,double time){
        if(hit==null)return new double[]{256,192};
        if(hit.type==8){double angle=(time-hit.time)*SPINS_PER_MS*2*Math.PI;return new double[]{256+100*Math.cos(angle),192+100*Math.sin(angle)};}
        double[] out={hit.x,hit.y};if(hit.type==2){double span=Math.max(1,(hit.end-hit.time)/hit.repeats),p=Math.max(0,Math.min(hit.repeats,(time-hit.time)/span));int repeat=Math.min(hit.repeats-1,(int)p);double f=p-repeat;hit.position(repeat%2==0?f:1-f,out);}return out;
    }
    private static double distance(double[] a,double[] b){return Math.hypot(a[0]-b[0],a[1]-b[1]);}
    private static double[] extend(double[] from,double[] point,double length){double d=distance(from,point);return d<.0001?point.clone():new double[]{point[0]+(point[0]-from[0])*length/d,point[1]+(point[1]-from[1])*length/d};}
    public static OsuReplayFrames generate(OsuPlaybackMap map)throws IOException{
        if(map.mode!=0||map.objects.isEmpty())throw new IOException("Autoplay requires an osu!standard map");
        double duration=map.duration();if(duration>30*60*1000)throw new IOException("Autoplay map exceeds 30 minutes");
        int count=map.objects.size(),end=(int)Math.ceil(duration)+1000;Curve[] curves=new Curve[count];
        double[] lastControl={256,192};double previousSpeed=-1,previousEnd=-1000000,leftUntil=-1,rightUntil=-1;
        int[] buttons=new int[count],releases=new int[count];boolean wasLeft=false;
        TreeSet<Integer> times=new TreeSet<>();for(int t=0;t<=end;t+=8)times.add(t);times.add(end);
        for(int i=0;i<count;i++){
            OsuPlaybackMap.Hit hit=map.objects.get(i),previous=i==0?null:map.objects.get(i-1);
            double start=previous==null?Math.min(0,hit.time-500):previous.end,finish=hit.time;
            double[] a=position(previous,start),b=position(hit,finish);double d=distance(a,b);
            if(previousSpeed<0)previousSpeed=d/Math.max(1,finish-start);
            boolean longStart=previous!=null&&previous.type!=1,longEnd=hit.type!=1;
            Curve curve=new Curve();curve.start=start;curve.end=finish;
            if(d<.0001)curve.points=new double[][]{a,b};
            else if(longStart&&longEnd){double[] c=extend(position(previous,start-10),a,distance(position(previous,start-10),a)*AGGRESSION*SLIDER_AGGRESSION/10);lastControl=c;double[] e=extend(position(hit,finish+10),b,distance(position(hit,finish+10),b)*AGGRESSION*SLIDER_AGGRESSION/10);curve.points=new double[][]{a,c,e,b};}
            else if(longStart){double[] c=extend(position(previous,start-10),a,distance(position(previous,start-10),a)*AGGRESSION*SLIDER_AGGRESSION/10);lastControl=extend(lastControl,b,previousSpeed*AGGRESSION);curve.points=new double[][]{a,c,lastControl,b};}
            else if(longEnd){lastControl=extend(lastControl,a,previousSpeed*AGGRESSION);double[] c=extend(position(hit,finish+10),b,distance(position(hit,finish+10),b)*AGGRESSION*SLIDER_AGGRESSION/10);curve.points=new double[][]{a,lastControl,c,b};}
            else{lastControl=extend(lastControl,a,previousSpeed*AGGRESSION);curve.points=new double[][]{a,lastControl,b};}
            curves[i]=curve;previousSpeed=(d+1)/Math.max(1,finish-start);
            double release=hit.end+50;if(i+2<count)release=Math.max(hit.end+1,Math.min(release,map.objects.get(i+2).time-1));
            boolean left=!wasLeft&&(hit.time-previousEnd<140||rightUntil>=hit.time);
            buttons[i]=left?5:10;releases[i]=(int)Math.ceil(release);if(left)leftUntil=release;else rightUntil=release;wasLeft=left;previousEnd=hit.end;
            for(int t:new int[]{(int)Math.floor(hit.time)-1,(int)Math.ceil(hit.time),(int)Math.floor(hit.end),(int)Math.ceil(hit.end),releases[i]})if(t>=0&&t<=end)times.add(t);
        }
        if(times.size()>1_000_000)throw new IOException("Autoplay timeline too large");
        int[] ts=new int[times.size()],keys=new int[times.size()];float[] xs=new float[times.size()],ys=new float[times.size()];
        int next=0,index=0;leftUntil=rightUntil=-1;
        for(int time:times){
            while(next<count&&map.objects.get(next).time<=time){if(buttons[next]==5)leftUntil=releases[next];else rightUntil=releases[next];next++;}
            OsuPlaybackMap.Hit active=next==0?null:map.objects.get(next-1);double[] point;
            if(active!=null&&time<=active.end)point=position(active,time);
            else if(next<count)point=curves[next].at(time);else point=position(active,active.end);
            ts[index]=time;xs[index]=(float)point[0];ys[index]=(float)point[1];keys[index]=(time<leftUntil?5:0)|(time<rightUntil?10:0);index++;
        }
        return OsuReplayFrames.generated(ts,xs,ys,keys);
    }
}
