package org.telegram.ui.Components;

import android.graphics.RectF;

/** View port of NEORUAA/Glasmio LiquidMenuMorphMotion.kt (Apache-2.0).
 * Revision 1a0652509071c36fedb35300abdfd12316cc3c54. Coordinates remain in screen pixels.
 * Every channel, action boundary, tolerance and closed-form solution follows the source.
 */
public final class GlasmioMenuMotion {
    public final RectF bounds = new RectF(), sourceLobe = new RectF();
    public float radiusX, radiusY, sourceRadius, contentProgress, lensProgress, smoothing;
    public float sourceOpacity, sourceBlur, sourceZoom, contentBlur, contentZoom, portalBlur, lensHeight, lensAmount;
    public boolean initialized, separate;
    private boolean opensAbove;
    private final Spring separateProgress = new Spring(.001f,.01f);
    private final RectF source = new RectF(), through = new RectF(), destination = new RectF();
    private float destinationRadius, portalZoomReferenceHeight, density = 1, elapsed;
    private boolean expanded, correctionApplied, smoothingReleased, lensReleased, closingKickReleased,
            sourceKickReleased, portalBlurReleased;
    private static final Spec COLLAPSE = new Spec(1, .3f * .7f), EJECT = new Spec(.75f, .5f * .7f),
            KICK = new Spec(1, .2f * .7f), BLUR_IN = new Spec(1, .3f * .7f),
            BLUR_OUT = new Spec(.8f, .45f * .7f), LENS_ON = new Spec(1, .2f * .7f),
            EJECT_LENS_OFF = new Spec(1, .6f * .7f), ABSORB_LENS_OFF = new Spec(1, .4f * .7f),
            ABSORB = new Spec(.8f, .7f * .7f), ABSORB_SMOOTHING = new Spec(1, .1f * .7f),
            SMOOTHING_RELEASE = new Spec(1, .6f * .7f);
    private final Spring x = new Spring(), y = new Spring(), width = new Spring(), height = new Spring(),
            rx = new Spring(), ry = new Spring(), sx = new Spring(), sy = new Spring(),
            sw = new Spring(), sh = new Spring(), sourceScale = new Spring(.0005f, .01f),
            incomingScale = new Spring(.0005f, .01f), kick = new Spring(), sourceKick = new Spring(),
            content = new Spring(.001f, .01f), sourceContent = new Spring(.001f, .01f),
            lens = new Spring(.001f, .01f), blur = new Spring(.001f, .01f), smooth = new Spring(.05f, 1);
    private final Spring[] channels = {x,y,width,height,rx,ry,sx,sy,sw,sh,sourceScale,incomingScale,
            kick,sourceKick,content,sourceContent,lens,blur,smooth};

    /** IosMenus.kt's explicit non-anchor branch: DropdownMorphSpring(.8, .3). */
    public void initializeSeparate(RectF target, boolean above, float density) {
        separate=true; opensAbove=above; this.density=density; destination.set(target);
        destinationRadius=34*density; separateProgress.reset(0); initialized=true; publishSeparate();
    }
    private void publishSeparate() {
        float scale=.2f+.8f*separateProgress.value;
        float w=Math.max(1,destination.width()*scale),h=Math.max(1,destination.height()*scale);
        float top=opensAbove?destination.bottom-h:destination.top;
        bounds.set(destination.right-w,top,destination.right,top+h); sourceLobe.set(bounds);
        radiusX=radiusY=sourceRadius=destinationRadius*scale;
        contentProgress=clamp(separateProgress.value); sourceOpacity=sourceBlur=contentBlur=portalBlur=lensHeight=lensProgress=smoothing=0;
        sourceZoom=contentZoom=1; lensAmount=0;
    }

    public void initialize(RectF source, RectF target, float targetRadius, float density) {
        initialize(source,target,targetRadius,density,source);
    }
    public void initialize(RectF source, RectF target, float targetRadius, float density, RectF preview) {
        this.source.set(source); through.set(preview); destination.set(target);
        this.density = density;
        destinationRadius = Math.min(targetRadius, Math.min(target.width(), target.height()) / 2);
        portalZoomReferenceHeight = target.height();
        float cx = bounded(through.centerX(), source.left, source.right, through.width() / 2);
        float cy = bounded(through.centerY(), source.top, source.bottom, through.height() / 2);
        x.reset(aligned(cx, target.width())); y.reset(aligned(cy, target.width()));
        width.reset(target.width()); height.reset(target.width());
        rx.reset(target.width()/2); ry.reset(target.width()/2);
        incomingScale.reset(diameter()/target.width());
        sx.reset(source.centerX()); sy.reset(source.centerY()); sw.reset(source.width()); sh.reset(source.height());
        sourceScale.reset(1); kick.reset(0); sourceKick.reset(0); content.reset(0); sourceContent.reset(1);
        lens.reset(1); blur.reset(0); smooth.reset(0);
        expanded = false; elapsed = 0;
        correctionApplied = smoothingReleased = lensReleased = closingKickReleased = sourceKickReleased = portalBlurReleased = false;
        initialized = true; publish();
    }

    public void updateSource(RectF rect) { updateSource(rect,rect); }
    public void updateSource(RectF rect,RectF preview) {
        source.set(rect); through.set(preview);
        if (initialized && !expanded) collapse();
    }

    public void updateTarget(RectF target, float radius) {
        if (separate) { destination.set(target); destinationRadius=radius; publishSeparate(); return; }
        float r = Math.min(radius, Math.min(target.width(),target.height())/2);
        if (!initialized || destination.equals(target) && destinationRadius == r) return;
        destination.set(target); destinationRadius = r;
        if (expanded) expansion(); else collapse();
        publish();
    }

    public void retarget(boolean expanded) {
        if(separate) { separateProgress.target(expanded?1:0,new Spec(.8f,.3f)); return; }
        this.expanded = expanded; portalZoomReferenceHeight = destination.height(); elapsed = 0;
        correctionApplied = smoothingReleased = lensReleased = closingKickReleased = portalBlurReleased = false;
        sourceKickReleased = expanded;
        blur.target(1, BLUR_IN); lens.target(1, LENS_ON);
        if (expanded) {
            expansion(); incomingScale.target(1,EJECT); sourceKick.target(0,KICK);
            sourceContent.target(0,EJECT); content.target(1,EJECT); smooth.target(20*density,COLLAPSE);
        } else collapse();
        publish();
    }

    private void expansion() {
        float inset = Math.min(source.width(), source.height())/8;
        float px = aligned(bounded(through.centerX()*.75f + destination.centerX()*.25f,
                destination.left,destination.right,inset), Math.min(source.width(),source.height()));
        float py = aligned(bounded(through.centerY()*.75f + destination.centerY()*.25f,
                destination.top,destination.bottom,inset), Math.min(source.width(),source.height()));
        sx.target(px,EJECT); sy.target(py,EJECT);
        sw.target(Math.min(source.width(),source.height()),COLLAPSE);
        sh.target(Math.min(source.width(),source.height()),COLLAPSE);
        sourceScale.target(.25f,EJECT);
        x.target(destination.centerX(),EJECT); y.target(destination.centerY(),EJECT);
        width.target(destination.width(),EJECT); height.target(destination.height(),EJECT);
        rx.target(correctionApplied ? destinationRadius : destination.width(),EJECT);
        ry.target(correctionApplied ? destinationRadius : destination.width(),EJECT);
        kick.target(correctionApplied ? 0 : kickAmount(-80),KICK);
    }

    private void collapse() {
        x.target(source.centerX(),ABSORB); y.target(source.centerY(),ABSORB);
        width.target(destination.width(),ABSORB); height.target(destination.width(),ABSORB);
        incomingScale.target(diameter()/destination.width(),ABSORB);
        rx.target(destination.width()/2,ABSORB); ry.target(destination.width()/2,ABSORB);
        sx.target(source.centerX(),ABSORB); sy.target(source.centerY(),ABSORB);
        sw.target(source.width(),ABSORB); sh.target(source.height(),ABSORB); sourceScale.target(1,ABSORB);
        kick.target(closingKickReleased ? 0 : kickAmount(-80),KICK);
        sourceKick.target(sourceKickReleased ? 0 : kickAmount(40),KICK);
        content.target(0,ABSORB); sourceContent.target(1,ABSORB);
        smooth.target(smoothingReleased ? 0 : 20*density, smoothingReleased ? SMOOTHING_RELEASE : ABSORB_SMOOTHING);
    }

    public boolean advanceBy(float seconds) {
        if (!initialized) return true;
        if(separate) { separateProgress.advance(Math.max(0,seconds)); publishSeparate(); return separateProgress.settled(); }
        float remaining = Float.isFinite(seconds) ? Math.max(0,seconds) : 0;
        while (remaining > 0) {
            actions();
            float next = Float.POSITIVE_INFINITY;
            if (expanded && !correctionApplied) next = Math.min(next,.1f*.7f);
            if (!lensReleased || !portalBlurReleased) next = Math.min(next,opticalDelay());
            if (!smoothingReleased) next = Math.min(next,(expanded ? .3f : .1f)*.7f);
            if (!expanded && !closingKickReleased) next = Math.min(next,.1f*.7f);
            if (!expanded && !sourceKickReleased) next = Math.min(next,.15f*.7f);
            float step = Math.min(remaining,Math.max(0,next-elapsed));
            if (step <= 0) { actions(); continue; }
            for (Spring channel : channels) channel.advance(step);
            elapsed += step; remaining -= step;
        }
        actions(); publish();
        if (!expanded && visuallyCollapsed()) { finish(); return true; }
        boolean settled = !expanded || smoothingReleased;
        for (Spring channel : channels) settled &= channel.settled();
        return settled;
    }

    public void finish() { for (Spring channel : channels) channel.finish(); publish(); }
    private void actions() {
        if (!portalBlurReleased && elapsed+.000001f >= opticalDelay()) { portalBlurReleased=true; blur.target(0,BLUR_OUT); }
        if (!lensReleased && elapsed+.000001f >= opticalDelay()) {
            lensReleased=true; lens.target(0,expanded ? EJECT_LENS_OFF : ABSORB_LENS_OFF);
        }
        if (!expanded) {
            if (!closingKickReleased && elapsed+.000001f >= .1f*.7f) { closingKickReleased=true; kick.target(0,KICK); }
            if (!sourceKickReleased && elapsed+.000001f >= .15f*.7f) { sourceKickReleased=true; sourceKick.target(0,KICK); }
            if (!smoothingReleased && elapsed+.000001f >= .1f*.7f) { smoothingReleased=true; smooth.target(0,SMOOTHING_RELEASE); }
            return;
        }
        if (!correctionApplied && elapsed+.000001f >= .1f*.7f) {
            correctionApplied=true; rx.target(destinationRadius,EJECT); ry.target(destinationRadius,EJECT); kick.target(0,KICK);
        }
        if (!smoothingReleased && elapsed+.000001f >= .3f*.7f) { smoothingReleased=true; smooth.target(0,SMOOTHING_RELEASE); }
    }
    private boolean visuallyCollapsed() {
        float e = density*.5f;
        return Math.abs(sourceLobe.left-source.left)<=e && Math.abs(sourceLobe.top-source.top)<=e
                && Math.abs(sourceLobe.right-source.right)<=e && Math.abs(sourceLobe.bottom-source.bottom)<=e
                && bounds.left>=source.left-e && bounds.top>=source.top-e && bounds.right<=source.right+e
                && bounds.bottom<=source.bottom+e && contentProgress<.02f && sourceOpacity>.98f
                && Math.abs(kick.value)<e && Math.abs(sourceKick.value)<e && smoothing<e && lensProgress<.02f;
    }
    private float opticalDelay() { return (expanded ? .12f : .1f)*.7f; }
    private float diameter() { return Math.max(1,destination.width()*.5f*through.height()/Math.max(destination.width(),destination.height())); }
    private float aligned(float center, float side) {
        float p = (center-side/2)/density;
        return (float)(p<0 ? Math.ceil(p-.5f) : Math.floor(p+.5f))*density+side/2;
    }
    private static float bounded(float v,float start,float end,float inset) {
        return start+inset <= end-inset ? Math.max(start+inset,Math.min(v,end-inset)) : start+(end-start)/2;
    }
    private float kickAmount(float points) {
        return points*density*Math.max(.5f,Math.min(1,(destination.height()/density-60)/140))
                *(2*clamp((through.centerY()-destination.top)/destination.height())-1);
    }
    private static float clamp(float v) { return Math.max(0,Math.min(1,v)); }
    private static void rect(RectF rect,float cx,float cy,float w,float h) { rect.set(cx-w/2,cy-h/2,cx+w/2,cy+h/2); }
    private void publish() {
        float scale = Math.max(.001f,incomingScale.value);
        rect(bounds,x.value,y.value+kick.value*scale,Math.max(1,width.value*scale),Math.max(1,height.value*scale));
        float ss = Math.max(.01f,sourceScale.value);
        rect(sourceLobe,sx.value,sy.value+sourceKick.value*ss,Math.max(1,sw.value*ss),Math.max(1,sh.value*ss));
        radiusX=Math.max(0,rx.value*scale); radiusY=Math.max(0,ry.value*scale);
        sourceRadius=Math.min(sourceLobe.width(),sourceLobe.height())/2;
        contentProgress=clamp(content.value); lensProgress=clamp(lens.value); smoothing=Math.max(0,smooth.value);
        sourceOpacity=clamp(sourceContent.value); sourceBlur=4*density*(1-sourceOpacity);
        float zoom=1.65f+(1.2f-1.65f)*clamp((portalZoomReferenceHeight/density-215)/305);
        sourceZoom=contentZoom=1+(zoom-1)*blur.value;
        contentBlur=4*density*(1-contentProgress); portalBlur=4*density*Math.max(0,blur.value);
        float dimension=expanded ? Math.min(source.width(),source.height()) : Math.min(destination.width(),destination.height());
        lensHeight=75*density*lensProgress*Math.min(1,dimension/(250*density));
        lensAmount=Math.min(-lensHeight,-.001f*density);
    }
    private static final class Spec {
        final float damping,omega,alpha,frequency;
        Spec(float damping,float response) {
            this.damping=damping; omega=(float)(2*Math.PI/response); alpha=damping*omega;
            frequency=damping>=.9999f ? 0 : omega*(float)Math.sqrt(1-damping*damping);
        }
    }
    private static final class Spring {
        float value,velocity,destination;
        final float valueTolerance,velocityTolerance;
        Spec spec=new Spec(1,.3f);
        Spring() { this(.2f,3); }
        Spring(float v,float speed) { valueTolerance=v; velocityTolerance=speed; }
        void reset(float v) { value=destination=v; velocity=0; }
        void target(float v,Spec s) { destination=v; spec=s; }
        boolean settled() { return Math.abs(value-destination)<valueTolerance && Math.abs(velocity)<velocityTolerance; }
        void finish() { value=destination; velocity=0; }
        void advance(float dt) {
            float d=value-destination, decay=(float)Math.exp(-spec.alpha*dt);
            if (spec.damping>=.9999f) {
                float c=velocity+spec.omega*d;
                value=destination+decay*(d+c*dt); velocity=decay*(velocity-spec.omega*c*dt);
            } else {
                float c=(float)Math.cos(spec.frequency*dt),s=(float)Math.sin(spec.frequency*dt),
                        k=(velocity+spec.alpha*d)/spec.frequency;
                value=destination+decay*(d*c+k*s);
                velocity=decay*(-spec.alpha*(d*c+k*s)+spec.frequency*(-d*s+k*c));
            }
            if (settled()) finish();
        }
    }
}
