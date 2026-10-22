package zxc.iconic.xenon.osu;

/** ppy/osu ParallaxContainer, with three times its base movement. */
public final class OsuReplayParallax {
    public float x,y,scale=1;
    private boolean moving;
    public void update(float width,float height,float cursorX,float cursorY,int setting,double elapsed){
        update(width,height,cursorX,cursorY,setting,elapsed,false);
    }
    public void update(float width,float height,float cursorX,float cursorY,int setting,double elapsed,boolean results){
        update(width,height,cursorX,cursorY,setting,elapsed,results,.06f);
    }
    public void update(float width,float height,float cursorX,float cursorY,int setting,double elapsed,boolean results,float baseAmount){
        float amount=baseAmount*Math.max(-200,Math.min(200,setting))/100f;
        float unit=Math.max(1,height)/768f;
        float targetX=offset(cursorX-width/2,unit)*width/2*amount;
        float targetY=offset(cursorY-height/2,unit)*height/2*amount;
        // Player.BackgroundParallaxAmount changes from 0.1 to 1 on ResultsScreen.
        // Retain the user's stronger gameplay overscan and add that 0.9 zoom delta.
        float targetScale=1+Math.abs(amount)*(results?1.9f:1);
        float positionProgress=OsuScoreAnimation.outQuint((float)Math.max(0,Math.min(100,elapsed))/100);
        float scaleProgress=OsuScoreAnimation.outQuint((float)Math.max(0,Math.min(100,elapsed))/1000);
        x+=(targetX-x)*positionProgress;y+=(targetY-y)*positionProgress;scale+=(targetScale-scale)*scaleProgress;
        if(Math.abs(targetX-x)<.01f)x=targetX;if(Math.abs(targetY-y)<.01f)y=targetY;
        if(Math.abs(targetScale-scale)<.00001f)scale=targetScale;
        moving=x!=targetX||y!=targetY||scale!=targetScale;
    }
    private static float offset(float distance,float unit){return Math.signum(distance)*(1-(float)Math.pow(.999,Math.abs(distance)/unit));}
    public boolean animating(){return moving;}
}
