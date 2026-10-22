package zxc.iconic.xenon.osu;

/** Lazer waits 1000ms after the final object; rewinding cancels results immediately. */
public final class OsuReplayCompletion {
    private OsuReplayCompletion(){}
    /** The seek layer remains interactive at 20% opacity on the results screen. */
    public static float progressOpacity(int hudOpacity,float gameplayAlpha){return .2f+(Math.max(0,Math.min(100,hudOpacity))/100f-.2f)*Math.max(0,Math.min(1,gameplayAlpha));}
    public static int seekEnd(double mapEnd,double failureTime){return (int)Math.max(1,Math.ceil(Math.min(mapEnd,failureTime)));}
    public static boolean reached(double time,double end,boolean failed){return !failed&&time>=end+1000;}
    public static float hudAlpha(long elapsed){return 1-OsuScoreAnimation.outQuint(OsuScoreAnimation.progress(elapsed,0,300));}
    /** User-selected 300ms OutQuint exit; 0.1 contraction. */
    public static float gameplayScale(long elapsed,float from,boolean visible){return from+((visible?.9f:1)-from)*OsuScoreAnimation.outQuint(OsuScoreAnimation.progress(elapsed,0,300));}
    public static float panelScale(long elapsed,boolean horizontal){float initial=horizontal?130f/360:385f/586;return initial+(1-initial)*OsuScoreAnimation.outQuint(OsuScoreAnimation.progress(elapsed,0,200));}
    /** ScorePanel resizes its backing for 200ms, then waits 100ms before revealing content. */
    public static float panelContentAlpha(long elapsed){return OsuScoreAnimation.progress(elapsed,300,50);}
    public static float panelTopExpansion(long elapsed){return OsuScoreAnimation.outQuint(OsuScoreAnimation.progress(elapsed,300,200));}
    public static float panelExitAlpha(long elapsed){return 1-OsuScoreAnimation.progress(elapsed,0,100);}
    public static float panelAlpha(long elapsed){return OsuScoreAnimation.progress(elapsed,0,50);}
    /** Blur follows UserDimContainer: 800ms OutQuint, including an interrupted rewind. */
    public static float backgroundMix(long elapsed,float from,boolean visible){return from+((visible?1:0)-from)*OsuScoreAnimation.outQuint(OsuScoreAnimation.progress(elapsed,0,800));}
    /** Results replace the video with the static background over 300ms, also reversible on seek. */
    public static float videoMix(long elapsed,float from,boolean visible){return from+((visible?1:0)-from)*OsuScoreAnimation.outQuint(OsuScoreAnimation.progress(elapsed,0,300));}
    /** ResultsScreen.FadeColour: 250ms with default linear easing. */
    public static float dimMix(long elapsed,float from,boolean visible){return from+((visible?1:0)-from)*OsuScoreAnimation.progress(elapsed,0,250);}
    /** UserDimContainer brightens independently of ResultsScreen's outer Gray(0.5). */
    public static float backgroundBrightness(int userDim,float innerMix,float outerMix){float dim=Math.max(0,Math.min(100,userDim))/100f;return (1-.5f*outerMix)*(1-dim+dim*innerMix);}
    /** BreakTracker treats completed gameplay as a break; UserDimContainer removes up to 0.3 dim in 800ms. */
    public static float endingLightening(double time,double finalJudgement){return OsuScoreAnimation.outQuint((float)Math.max(0,Math.min(1,(time-finalJudgement)/800)));}
    public static float backgroundBrightness(int userDim,float innerMix,float outerMix,float endingMix){
        float dim=Math.max(0,Math.min(100,userDim))/100f;
        float gameplay=1-dim+Math.min(dim,.3f)*endingMix;
        return (1-.5f*outerMix)*(gameplay+(1-gameplay)*innerMix);
    }
    public static float blurSigma(int userBlur,float mix){float initial=Math.max(0,Math.min(100,userBlur))*.25f;return initial+(10-initial)*mix;}
}
