package zxc.iconic.xenon.osu;

/** danser-go circle.go/slider.go and common/flashlight.go, adapted under GPL-3.0.
 * Reference 7cd903284017cb216fde596993dfa81dafca7af6. See danser-autoplay-GPL-3.0.txt. */
public final class OsuReplayModEffects {
    private OsuReplayModEffects(){}
    public static double rate(OsuReplay replay){return replay.clockRate>0?replay.clockRate:(replay.mods&(64|512))!=0?1.5:(replay.mods&256)!=0?.75:1;}
    public static OsuReplayFrames autopilot(OsuPlaybackMap map,OsuReplayFrames original,OsuReplay replay)throws java.io.IOException{
        if((replay.mods&8192)==0)return original;OsuReplayFrames generated=OsuAutoplay.generate(map);int n=original.size;
        int[] times=new int[n],keys=new int[n];float[] xs=new float[n],ys=new float[n];OsuReplayFrames.Sample sample=new OsuReplayFrames.Sample();
        for(int i=0;i<n;i++){times[i]=original.time(i);keys[i]=original.rawKeys(i);generated.at(times[i],sample);xs[i]=sample.x;ys[i]=(replay.mods&16)!=0?384-sample.y:sample.y;}
        return OsuReplayFrames.generated(times,xs,ys,keys);
    }
    public static boolean hidden(OsuReplay replay){return (replay.mods&8)!=0&&!replay.hiddenOnlyApproach;}
    public static float circleAlpha(double time,double start,double preempt){double age=time-start+preempt;return (float)Math.max(0,Math.min(age/(preempt*.4),(preempt*.7-age)/(preempt*.3)));}
    public static float sliderAlpha(double time,double start,double end,double preempt){double fadeEnd=start-preempt*.6;if(time<fadeEnd)return (float)Math.max(0,Math.min(1,(time-start+preempt)/(preempt*.4)));float p=(float)Math.max(0,Math.min(1,(time-fadeEnd)/Math.max(1,end-fadeEnd)));return 1-OsuScoreAnimation.outQuad(p);}
    public static float flashlightRadius(int combo,boolean comboBased){return !comboBased||combo<=100?168:combo<=200?136.5f:105;}
    public static final class Flashlight {
        private final OsuReplayFrames frames;
        private final float[] xs,ys,radii,dims;
        public Flashlight(OsuPlaybackMap map,OsuReplayFrames frames,OsuReplay replay,OsuReplayHud hud){
            this.frames=frames;int n=frames.size;xs=new float[n];ys=new float[n];radii=new float[n];dims=new float[n];
            double rate=rate(replay),first=map.objects.get(0).time/rate,last=(map.duration()+205-10*map.overallDifficulty(replay.mods))/rate;
            float x=256,y=192,radius=1344,from=radius,target=radius,dim=0,dimFrom=0,dimTarget=0;double began=-800,dimBegan=0;int object=0;double[] sliderPosition=new double[2];
            for(int i=0;i<n;i++){
                double song=frames.time(i),time=song/rate,dt=i==0?0:(song-frames.time(i-1))/rate;
                float follow=replay.flashlightDelay<=0?1:OsuScoreAnimation.outQuad((float)Math.min(1,dt/replay.flashlightDelay));x+=(frames.x(i)-x)*follow;y+=(frames.y(i)-y)*follow;
                float wanted=flashlightRadius(hud.combo(song),replay.flashlightCombo);
                if(time<first-800)wanted=1344;else if(time<first)wanted=168;
                if(time>=last)wanted=1344;
                for(double[] gap:map.breaks)if((gap[1]-gap[0])/rate>1600&&song>=gap[0]&&song<gap[1]-800*rate){wanted=420;break;}
                radius=from+(target-from)*(float)Math.max(0,Math.min(1,(time-began)/800));
                if(wanted!=target){from=radius;target=wanted;began=time;}
                while(object+1<map.objects.size()&&map.objects.get(object+1).time<=song)object++;
                OsuPlaybackMap.Hit hit=map.objects.get(object);boolean sliding=hit.type==2&&song>=hit.time&&song<=hit.end&&((frames.rawKeys(i)&15)!=0||(replay.mods&128)!=0);
                if(sliding){double progress=(song-hit.time)/Math.max(1,hit.end-hit.time)*hit.repeats;int span=Math.min(hit.repeats-1,(int)progress);hit.position(span%2==0?progress-span:1-(progress-span),sliderPosition);double sliderY=(replay.mods&16)!=0?384-sliderPosition[1]:sliderPosition[1];sliding=Math.hypot(frames.x(i)-sliderPosition[0],frames.y(i)-sliderY)<=map.radius(replay.mods)*2.4;}
                dim=dimFrom+(dimTarget-dimFrom)*(float)Math.max(0,Math.min(1,(time-dimBegan)/50));
                float wantedDim=sliding?.8f:0;if(wantedDim!=dimTarget){dimFrom=dim;dimTarget=wantedDim;dimBegan=time;}
                xs[i]=x;ys[i]=y;radii[i]=radius*(float)replay.flashlightSize;dims[i]=dim;
            }
        }
        public void at(double time,float[] out){int lo=0,hi=frames.size;while(lo<hi){int mid=(lo+hi)>>>1;if(frames.time(mid)<=time)lo=mid+1;else hi=mid;}int i=Math.max(0,lo-1),j=Math.min(frames.size-1,i+1);float p=(float)Math.max(0,Math.min(1,(time-frames.time(i))/Math.max(1,frames.time(j)-frames.time(i))));out[0]=xs[i]+(xs[j]-xs[i])*p;out[1]=ys[i]+(ys[j]-ys[i])*p;out[2]=radii[i]+(radii[j]-radii[i])*p;out[3]=dims[i]+(dims[j]-dims[i])*p;}
    }
}
