package zxc.iconic.xenon.osu;

import java.util.*;

/** Results/AccuracyCircle and TotalScoreCounter timings from ppy/osu. Clock is panel-local. */
public final class OsuResultsAudioTimeline {
    public static final class Event {
        public final String name;public final float volume,rate;
        Event(String name,float volume,float rate){this.name=name;this.volume=volume;this.rate=rate;}
    }
    private long previous=-1,lastCircle=-1000;
    private int badges;
    private final boolean applauseEnabled;
    private final long contentDelay;
    public OsuResultsAudioTimeline(){this(true);}
    public OsuResultsAudioTimeline(boolean applauseEnabled){this(applauseEnabled,false);}
    public OsuResultsAudioTimeline(boolean applauseEnabled,boolean postReplay){this.applauseEnabled=applauseEnabled;contentDelay=postReplay?300:0;}
    public void reset(){previous=-1;lastCircle=-1000;badges=0;}
    private boolean crossed(long elapsed,long threshold){return previous<threshold&&elapsed>=threshold;}
    public List<Event> update(long elapsed,OsuReplay replay){
        ArrayList<Event> events=new ArrayList<>();if(elapsed<0)return events;
        if(elapsed<previous)reset();String rank=replay.displayRank();int index=OsuScoreAnimation.rankIndex(rank);
        if(crossed(elapsed,0))events.add(new Event("score-panel-focus",1,1));
        if(crossed(elapsed,contentDelay))events.add(new Event("score-panel-top-appear",1,1));
        long animationTime=elapsed-contentDelay;
        if(crossed(elapsed,contentDelay+443))events.add(new Event("swoosh-up",.4f,1));
        float sine=(float)Math.sin(OsuScoreAnimation.progress(animationTime,450,3000)*Math.PI/2);
        if(animationTime>=450&&animationTime<1950&&elapsed-lastCircle>=18+282*sine){
            events.add(new Event("score-tick",.6f+.4f*sine,1+OsuScoreAnimation.gaugeTarget(replay.accuracy(),rank)*sine));lastCircle=elapsed;
        }
        // Lazer disables TotalScoreCounter lesser ticks while AccuracyCircle plays with flair.
        float gauge=OsuScoreAnimation.gauge(replay.accuracy(),rank,animationTime);float[] markers={0,.7f,.8f,.9f,.95f,.99f};
        for(int i=0;i<=index;i++)if((badges&(1<<i))==0&&gauge>0&&gauge>=markers[i]){badges|=1<<i;events.add(new Event(i==5?"badge-dink-max":"badge-dink",1,1+i*.05f));}
        if(crossed(elapsed,contentDelay+1950))events.add(new Event(index>=5?"rank-impact-pass-ss":index>=3?"rank-impact-pass":index>=1?"rank-impact-fail":"rank-impact-fail-d",1,1));
        if(applauseEnabled&&crossed(elapsed,contentDelay+2495))events.add(new Event("applause-"+(index>=4?"s":index==3?"a":index==2?"b":index==1?"c":"d"),.8f,1));
        previous=elapsed;return events;
    }
}
