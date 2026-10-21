package zxc.iconic.xenon.osu;
import java.util.*;

/** Cursor-driven spinner HUD, with lazer's signed full rotations and two-spin bonus gap. */
public final class OsuReplaySpinners {
    public final HashMap<Integer,Spin> objects=new HashMap<>();
    public static final class Spin {
        public final int required,maxBonus,bonusValue;
        private final double[] times,angles,completions;
        Spin(OsuPlaybackMap.Hit hit,OsuReplayFrames frames,double od,boolean lazer,long mods,int budget){
            bonusValue=lazer?50:1000;
            double seconds=(hit.end-hit.time)/1000;
            required=Math.max(0,(int)(seconds*rate(od,1.5,2.5,3.75)));
            maxBonus=lazer?Math.max(0,(int)(seconds*rate(od,250d/60,380d/60,430d/60))-required-2):Integer.MAX_VALUE;
            int start=0,hi=frames.size;while(start<hi){int mid=(start+hi)>>>1;if(frames.time(mid)<hit.time)start=mid+1;else hi=mid;}
            int end=start;while(end<frames.size&&end-start<budget&&frames.time(end)<=hit.end)end++;
            times=new double[end-start];angles=new double[end-start];ArrayList<Double> spins=new ArrayList<>();
            double last=Double.NaN,total=0,completionAngle=0;
            for(int f=start;f<end;f++){
                double angle=Math.atan2(frames.y(f)-192,frames.x(f)-256);
                if(((mods&(128|4096|8192))==0&&frames.buttons(f)==0)||Math.hypot(frames.x(f)-256,frames.y(f)-192)<20)last=Double.NaN;
                else{if(Double.isFinite(last))total+=Math.toDegrees(Math.atan2(Math.sin(angle-last),Math.cos(angle-last)));last=angle;}
                if((mods&4096)!=0)total=360*required*1.01*Math.max(0,frames.time(f)-hit.time)/Math.max(1,hit.end-hit.time);
                else if((mods&8192)!=0)total=Math.toDegrees(.03*Math.max(0,frames.time(f)-hit.time));
                while(Math.abs(total-completionAngle)>=360){completionAngle+=Math.signum(total-completionAngle)*360;spins.add((double)frames.time(f));}
                times[f-start]=frames.time(f);angles[f-start]=total;
            }
            completions=new double[spins.size()];for(int i=0;i<completions.length;i++)completions[i]=spins.get(i);
        }
        public int completed(){return completions.length;}
        public double turnTime(int index){return completions[index];}
        public boolean bonusTurn(int index){return index>=required+2&&index<(long)required+2+maxBonus;}
        private int count(double time){int lo=0,hi=completions.length;while(lo<hi){int mid=(lo+hi)>>>1;if(completions[mid]<=time)lo=mid+1;else hi=mid;}return lo;}
        private double angle(double time){
            if(times.length==0)return 0;
            int i=Arrays.binarySearch(times,time);if(i<0)i=-i-2;
            if(i<0)return 0;if(i>=times.length-1)return angles[times.length-1];
            double span=times[i+1]-times[i],mix=span<=0?0:(time-times[i])/span;
            return angles[i]+(angles[i+1]-angles[i])*mix;
        }
        public double rotation(double time){return angle(time);}
        public int turns(double time){return count(time);}
        public int rpm(double time){return (int)(Math.abs(angle(time)-angle(time-500))/360*120);}
        public int bonus(double time){return Math.min(maxBonus,Math.max(0,count(time)-required-2))*bonusValue;}
        public double lastBonus(double time){int count=count(time);return count>required+2&&count<=(long)required+2+maxBonus?completions[count-1]:Double.NEGATIVE_INFINITY;}
    }
    private static double rate(double od,double low,double mid,double high){return od<5?low+(mid-low)*od/5:mid+(high-mid)*(od-5)/5;}
    public OsuReplaySpinners(OsuPlaybackMap map,OsuReplayFrames frames,OsuReplay replay){
        double od=map.overallDifficulty(replay.mods);int budget=2000000;
        for(int i=0;i<map.objects.size();i++)if(map.objects.get(i).type==8){Spin spin=new Spin(map.objects.get(i),frames,od,replay.lazer,replay.mods,budget);budget-=spin.times.length;objects.put(i,spin);}
    }
}
