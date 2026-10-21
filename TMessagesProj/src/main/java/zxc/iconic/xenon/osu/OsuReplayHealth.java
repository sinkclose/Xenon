package zxc.iconic.xenon.osu;

import java.util.*;

/** Recorded HP takes precedence. Missing graphs use an explicitly approximate HP-dependent preview. */
public final class OsuReplayHealth {
    private final double[] times, values;
    public final boolean recorded;
    public final double failureTime;
    public OsuReplayHealth(OsuPlaybackMap map,OsuReplayFrames frames,OsuReplay replay,OsuReplayJudgements judgements){
        ArrayList<Double> t=new ArrayList<>(),v=new ArrayList<>();
        String graph=replay.lifeGraph==null?"":replay.lifeGraph;
        for(String point:graph.split(",",100001)){
            if(t.size()>=100000)break;
            String[] pair=point.split("\\|",3);if(pair.length<2)continue;
            try{double time=Double.parseDouble(pair[0]),value=Double.parseDouble(pair[1]);
                if(Double.isFinite(time)&&Double.isFinite(value)&&time>=0&&value>=0&&value<=1&&(t.isEmpty()||time>t.get(t.size()-1))){t.add(time);v.add(value);}
            }catch(NumberFormatException ignored){}
        }
        recorded=!t.isEmpty();
        if(!recorded){
            t.add(0d);v.add(1d);double health=1,previous=map.objects.isEmpty()?0:map.objects.get(0).time;
            double hp=map.drain(replay.mods);
            ArrayList<double[]> gaps=new ArrayList<>();
            ArrayList<double[]> sorted=new ArrayList<>(map.breaks);sorted.sort(Comparator.comparingDouble(gap->gap[0]));
            for(double[] gap:sorted){if(!gaps.isEmpty()&&gap[0]<=gaps.get(gaps.size()-1)[1])gaps.get(gaps.size()-1)[1]=Math.max(gap[1],gaps.get(gaps.size()-1)[1]);else gaps.add(gap.clone());}
            int gapIndex=0;
            for(OsuReplayJudgements.Result result:judgements.results){
                double elapsed=Math.max(0,result.time-previous);
                while(gapIndex<gaps.size()&&gaps.get(gapIndex)[1]<=previous)gapIndex++;
                for(int g=gapIndex;g<gaps.size()&&gaps.get(g)[0]<result.time;g++){double[] gap=gaps.get(g);elapsed-=Math.max(0,Math.min(result.time,gap[1])-Math.max(previous,gap[0]));}
                health=Math.max(0,health-Math.max(0,elapsed)*(.000006+.000002*hp));
                health=Math.max(0,Math.min(1,health+(result.value==300?.03:result.value==100?.011:result.value==50?.002:-(.04+.008*hp))));
                t.add(result.time);v.add(health);previous=result.time;
            }
        }
        times=new double[t.size()];values=new double[v.size()];for(int i=0;i<times.length;i++){times[i]=t.get(i);values[i]=v.get(i);}
        double fail=Double.POSITIVE_INFINITY;
        boolean noFail=(replay.mods&1)!=0;
        if(!noFail&&replay.rank.equals("F")){
            fail=Math.min(frames.duration(),map.duration());
            if(recorded&&values[values.length-1]<=0){int i=values.length-1;while(i>0&&values[i-1]<=0)i--;fail=times[i];}
        }else if(!noFail&&recorded&&values[values.length-1]<=0&&frames.duration()<map.duration()-1000){
            int i=values.length-1;while(i>0&&values[i-1]<=0)i--;fail=times[i];
        }
        failureTime=fail;
    }
    public double at(double time){int i=Arrays.binarySearch(times,time);if(i>=0)return values[i];i=-i-1;if(i==0)return values[0];if(i>=times.length)return values[values.length-1];double f=(time-times[i-1])/Math.max(1,times[i]-times[i-1]);return values[i-1]+(values[i]-values[i-1])*f;}
}
