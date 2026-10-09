"""Verify backdrop anchoring through production liquid transforms and glass recordings."""

import os
import subprocess
import unittest

from test_glass_blur import ROOT, method
from test_glass_chat_refresh import run_java


def methods(path, signatures):
    source = (ROOT / path).read_text()
    return "\n".join(method(source, signature) for signature in signatures).replace("@NonNull ", "")


COMMON = r'''
    static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    static void near(float a, float b) { check(Math.abs(a - b) < .002f, a + " != " + b); }
    static int dp(float value) { return (int) value; }
    static class AndroidUtilities { static float dpf2(float v) { return v; } static Rect rectTmp2 = new Rect(); }
    static class Rect {
        int left, top, right, bottom;
        Rect() {} Rect(int l, int t, int r, int b) { left=l; top=t; right=r; bottom=b; }
        int width() { return right-left; } int height() { return bottom-top; }
        boolean isEmpty() { return width() <= 0 || height() <= 0; }
    }
    static class RectF {
        float left, top, right, bottom;
        void set(float l, float t, float r, float b) { left=l; top=t; right=r; bottom=b; }
        float width() { return right-left; } float height() { return bottom-top; }
        float centerX() { return (left+right)/2; } float centerY() { return (top+bottom)/2; }
        boolean isEmpty() { return width() <= 0 || height() <= 0; }
        void round(Rect r) { r.left=Math.round(left); r.top=Math.round(top); r.right=Math.round(right); r.bottom=Math.round(bottom); }
    }
    static class Canvas {
        float sx=1, sy=1, tx, ty;
        java.util.ArrayList<float[]> saves = new java.util.ArrayList<>();
        int save() { saves.add(new float[]{sx,sy,tx,ty}); return saves.size(); }
        void restoreToCount(int n) { while (saves.size() >= n) restore(); }
        void restore() { float[] m=saves.remove(saves.size()-1); sx=m[0]; sy=m[1]; tx=m[2]; ty=m[3]; }
        void translate(float x, float y) { tx += sx*x; ty += sy*y; }
        void scale(float x, float y) { sx *= x; sy *= y; }
        void drawColor(int color) {} void drawRenderNode(RenderNode n) {}
        boolean isHardwareAccelerated() { return true; }
    }
    static class Paint { void setShadowLayer(float r,float x,float y,int c) {} }
    static class Props {
        Rect boundsWithPadding = new Rect(7,13,247,63);
        float[] radii = new float[8], shaderRadii = new float[8];
        int liquidThickness; float liquidIntensity, liquidIndex, strokeWidthTop, strokeWidthBottom;
        void drawShadows(Canvas c, Paint p, boolean b) {}
    }
    static class BlurredBackgroundDrawable {
        float sourceOffsetX=23, sourceOffsetY=400;
        float sourceScaleX=1, sourceScaleY=1, sourceTranslationX, sourceTranslationY;
        Props boundProps = new Props(); int notifications;
        void onSourceOffsetChange(float x, float y) { notifications++; }
        void setBounds(Rect r) { boundProps.boundsWithPadding = r; }
        void draw(Canvas c) {}
        BASE_METHODS
    }
    static class LiquidTouchEffect {
        RectF bounds = new RectF(); float progress, offsetX, offsetY, dragStrength=1;
        float scaleX=1, scaleY=1, translationX, translationY;
        void drawHighlight(Canvas c, float r, float i) {}
        EFFECT_METHODS
    }
    static class RenderNode {
        int recordings; float alpha=1;
        Canvas beginRecording() { recordings++; return new Canvas(); }
        void endRecording() {} boolean hasDisplayList() { return recordings>0; }
        void setAlpha(float a) { alpha=a; } float getAlpha() { return alpha; }
    }
'''


def common():
    return COMMON.replace("BASE_METHODS", methods(
        "org/telegram/ui/Components/blur3/drawable/BlurredBackgroundDrawable.java", (
            "public boolean setSourceTransform(", "protected void transformSourceCanvas(",
            "public void getPositionRelativeSource("))).replace("EFFECT_METHODS", methods(
        "org/telegram/ui/Components/LiquidTouchEffect.java", (
            "private void updateTransform()", "public static boolean updateBackground(",
            "public int begin(", "public int beginInChild(", "public int beginInParent(")))


class GlassDeformationTest(unittest.TestCase):
    def test_backdrop_anchoring_and_recording_cache(self):
        renderer = ROOT / "org/telegram/ui/Components/blur3/drawable/BlurredBackgroundDrawableRenderNode.java"
        source = renderer.read_text()
        # Optional regression check: run the previous renderer with the same moving surfaces.
        if os.environ.get("GLASS_RENDER_BASELINE"):
            old = subprocess.check_output(["git", "show", os.environ["GLASS_RENDER_BASELINE"] + ":" + str(renderer.relative_to(ROOT.parents[3]))], text=True)
            source = source.replace(method(source, "public void updateDisplayList()"), method(old, "public void updateDisplayList()"))
        renderer_methods = "\n".join(method(source, signature) for signature in (
            "protected void onSourceOffsetChange(", "public void updateDisplayList()", "public void draw("))
        renderer_methods = renderer_methods.replace("@NonNull ", "").replace("@Override", "")
        renderer_methods = renderer_methods.replace("org.telegram.messenger.LiteMode", "LiteMode").replace("zxc.iconic.xenon.NekoConfig", "NekoConfig")
        harness = r'''
public class GlassDeformationHarness {
    COMMON
    static class Color { static int alpha(int c) { return c>>>24; } }
    static class Theme { static int multAlpha(int c,float a) { return c; } }
    static class Build { static class VERSION { static int SDK_INT=36; } }
    static class LiteMode { static int FLAG_LIQUID_GLASS=1; static boolean isEnabled(int f) { return true; } }
    static class NekoConfig {
        static int liquidGlassThickness=11, advancedGlassTintPercent, advancedGlassAlpha=100;
        static float liquidGlassIntensity=1; static boolean useAdvancedLiquidGlass;
    }
    static class Effect { void update(Object... args) {} void drawHighlight(Canvas c,float a) {} }
    static class Source {
        int recordings; float l,t,r,b,sx,sy,tx,ty;
        void prepareToDraw() {} void dispatchOnDrawablesRelativePositionChange() {}
        void draw(Canvas c,float l,float t,float r,float b) {
            recordings++; this.l=l; this.t=t; this.r=r; this.b=b;
            sx=c.sx; sy=c.sy; tx=c.tx; ty=c.ty;
        }
    }
    static class Glass extends BlurredBackgroundDrawable {
        Source source = new Source(); RenderNode renderNode=new RenderNode(), renderNodeFill=new RenderNode();
        boolean renderNodeInvalidated, fixedRefraction, liquidGlassEnabled, inAppKeyboardOptimization;
        Effect liquidGlassEffect=new Effect(); int backgroundColor, strokeColorTop, strokeColorBottom, shadowColor;
        float shadowAlpha=1, shadowLayerRadius, shadowLayerDx, shadowLayerDy;
        Paint paintShadow=new Paint(), paintStrokeTop=new Paint(), paintStrokeBottom=new Paint();
        int getAlpha() { return 255; }
        void recreateLiquidGlassEffect() {} void drawSource(Canvas c,Source s) {}
        void drawStroke(Object... a) {}
        RENDERER_METHODS
    }
    static void verify(Glass glass, LiquidTouchEffect e, float originX, float originY) {
        LiquidTouchEffect.updateBackground(glass,e,originX,originY);
        Canvas outer=new Canvas();
        outer.translate(glass.sourceOffsetX,glass.sourceOffsetY);
        int save=e.beginInChild(outer,originX,originY);
        // Preserve the original press/drag animation around the surface's pivot.
        if (!e.bounds.isEmpty()) {
            float w=e.bounds.width(), h=e.bounds.height(), min=Math.min(w,h), max=Math.max(w,h);
            float drag=Math.min(.12f,4f/h), press=1+drag*e.progress;
            double angle=Math.atan2(e.offsetY,e.offsetX);
            float sx=press+drag*e.dragStrength*(float)Math.abs(Math.cos(angle)*e.offsetX/max)*Math.min(w/h,1);
            float sy=press+drag*e.dragStrength*(float)Math.abs(Math.sin(angle)*e.offsetY/max)*Math.min(h/w,1);
            near(outer.sx,sx); near(outer.sy,sy);
            near(outer.tx,glass.sourceOffsetX+min*(float)Math.tanh(.05f*e.dragStrength*e.offsetX/min)
                    +(1-sx)*(e.bounds.centerX()-originX));
            near(outer.ty,glass.sourceOffsetY+min*(float)Math.tanh(.05f*e.dragStrength*e.offsetY/min)
                    +(1-sy)*(e.bounds.centerY()-originY));
        }
        glass.draw(outer);
        Rect padded=glass.boundProps.boundsWithPadding; Source s=glass.source;
        near(s.l,outer.sx*padded.left+outer.tx); near(s.t,outer.sy*padded.top+outer.ty);
        near(s.r,outer.sx*padded.right+outer.tx); near(s.b,outer.sy*padded.bottom+outer.ty);
        RectF capture=new RectF(); glass.getPositionRelativeSource(capture);
        near(capture.left,s.l); near(capture.top,s.t); near(capture.right,s.r); near(capture.bottom,s.b);
        // At every pixel, sampling the backdrop must use its actual screen position.
        // The old renderer instead scales/translates the captured texture with the surface.
        for (int x=0;x<=padded.width();x+=20) for(int y=0;y<=padded.height();y+=10) {
            float sampleX=(x-s.tx)/s.sx, sampleY=(y-s.ty)/s.sy;
            near(sampleX,outer.sx*(padded.left+x)+outer.tx);
            near(sampleY,outer.sy*(padded.top+y)+outer.ty);
        }
        if(save!=-1) outer.restoreToCount(save);
    }
    public static void main(String[] args) {
        for (float[] size:new float[][]{{0,0,300,56},{38,90,94,146},{120,23,270,75}}) {
            for (float[] origin:new float[][]{{0,0},{31,47},{-25,-13}}) {
                Glass g=new Glass(); LiquidTouchEffect e=new LiquidTouchEffect();
                e.bounds.set(size[0],size[1],size[2],size[3]);
                verify(g,e,origin[0],origin[1]); int initial=g.source.recordings;
                verify(g,e,origin[0],origin[1]); check(g.source.recordings==initial,"idle re-recording");
                for (int frame=1;frame<=24;frame++) {
                    e.progress=.7f; e.dragStrength=1.65f;
                    e.offsetX=(frame-12)*12; e.offsetY=(frame-8)*7;
                    verify(g,e,origin[0],origin[1]);
                    int recordings=g.source.recordings;
                    verify(g,e,origin[0],origin[1]); check(recordings==g.source.recordings,"unchanged transform re-recorded");
                }
                e.progress=e.offsetX=e.offsetY=0;
                verify(g,e,origin[0],origin[1]);
                check(!LiquidTouchEffect.updateBackground(g,null,0,0),"rest must be identity");
                e.bounds.set(0,0,0,0); e.progress=1; verify(g,e,0,0);
            }
        }
        // The software Canvas path must cancel the deformation in source coordinates too.
        BlurredBackgroundDrawable d=new BlurredBackgroundDrawable(); d.setSourceTransform(1.2f,1.1f,17,-9);
        Canvas c=new Canvas(); c.translate(d.sourceOffsetX,d.sourceOffsetY); c.translate(17,-9); c.scale(1.2f,1.1f);
        d.transformSourceCanvas(c); near(c.sx,1); near(c.sy,1); near(c.tx,0); near(c.ty,0);
    }
}
'''.replace("COMMON", common()).replace("RENDERER_METHODS", renderer_methods)
        run_java(self, "GlassDeformationHarness", harness)

    def test_parent_surface_bindings_invalidate_cached_children(self):
        paths = "org/telegram/ui/Components/chat/"
        harness = r'''
public class GlassBindingsHarness {
    COMMON
    static class View {
        float x,y; int invalidations;
        float getX() { return x; } float getY() { return y; }
        void invalidate() { invalidations++; }
    }
    static class Parent extends View {
        boolean drawChild(Canvas c,View v,long time) { return true; }
        int getMeasuredWidth() { return 360; } int getMeasuredHeight() { return 56; }
    }
    static class Suppressor { void sync(Object v,boolean enabled) {} }
    static class ChatActivityBlurredRoundButton extends View {
        BlurredBackgroundDrawable backgroundDrawable=new BlurredBackgroundDrawable();
        BUTTON_METHOD
    }
    static class Holder {
        ChatActivityBlurredRoundButton button=new ChatActivityBlurredRoundButton();
        View optionsView=new View(); LiquidTouchEffect liquid;
    }
    static class Page extends View {
        Holder holder=new Holder(); ChatActivityBlurredRoundButton buttonView=holder.button;
        LiquidTouchEffect liquidTouch=new LiquidTouchEffect(); boolean enabled=true;
        boolean liquidTouchAllowed() { return enabled; }
        PAGE_METHOD
    }
    static class Actions extends Parent {
        View leftLayout=new View(); Holder replyButton=new Holder(),selectButton=new Holder(),forwardButton=new Holder();
        LiquidTouchEffect leftLiquid=new LiquidTouchEffect(),forwardLiquid=new LiquidTouchEffect();
        Suppressor liquidPressAnimations=new Suppressor(); boolean enabled=true;
        boolean liquidTouchAllowed() { return enabled; } void updateLiquidBounds() {}
        ACTIONS_METHOD
    }
    static class Channel extends Parent {
        Holder[] buttonHolders={new Holder()}; View container=new View();
        LiquidTouchEffect centerLiquid=new LiquidTouchEffect(); float centerLiquidX=11,centerLiquidY=37;
        BlurredBackgroundDrawable containerDrawable=new BlurredBackgroundDrawable();
        int totalWidthLeft=50,totalWidthRight=56; RectF tmpRect=new RectF();
        Suppressor liquidPressAnimations=new Suppressor(); boolean enabled=true;
        boolean liquidTouchAllowed() { return enabled; }
        CHANNEL_METHOD
    }
    static class Menu extends View {
        LiquidTouchEffect liquidTouch=new LiquidTouchEffect(); boolean enabled=true;
        boolean liquidTouchAllowed() { return enabled; }
        MENU_METHOD
    }
    static void activate(LiquidTouchEffect e) { e.bounds.set(0,0,300,56); e.progress=.8f; e.offsetX=110; e.offsetY=-15; }
    static void matches(BlurredBackgroundDrawable d,LiquidTouchEffect e,float x,float y) {
        BlurredBackgroundDrawable expected=new BlurredBackgroundDrawable(); LiquidTouchEffect.updateBackground(expected,e,x,y);
        near(d.sourceScaleX,expected.sourceScaleX); near(d.sourceScaleY,expected.sourceScaleY);
        near(d.sourceTranslationX,expected.sourceTranslationX); near(d.sourceTranslationY,expected.sourceTranslationY);
    }
    public static void main(String[] args) {
        Canvas c=new Canvas(); Page p=new Page(); p.x=80;p.y=160;p.buttonView.x=4;p.buttonView.y=24;
        activate(p.liquidTouch); int save=p.beginLiquidDraw(c); c.restoreToCount(save);
        matches(p.buttonView.backgroundDrawable,p.liquidTouch,4,24);
        check(p.buttonView.invalidations==1,"cached page button wasn't invalidated");
        save=p.beginLiquidDraw(c); c.restoreToCount(save); check(p.buttonView.invalidations==1,"idle child invalidated");
        p.enabled=false; p.beginLiquidDraw(c); matches(p.buttonView.backgroundDrawable,null,0,0);
        check(p.buttonView.invalidations==2,"disabled effect didn't reset cached child");
        Actions a=new Actions(); a.leftLayout.x=10;a.leftLayout.y=18;a.replyButton.button.x=3;a.replyButton.button.y=5;
        a.forwardButton.optionsView.x=175;a.forwardButton.optionsView.y=18;a.forwardButton.button.x=7;a.forwardButton.button.y=2;
        activate(a.leftLiquid);activate(a.forwardLiquid);
        a.drawChild(c,a.leftLayout,0);a.drawChild(c,a.forwardButton.optionsView,0);
        matches(a.replyButton.button.backgroundDrawable,a.leftLiquid,13,23);
        matches(a.selectButton.button.backgroundDrawable,a.leftLiquid,10,18);
        matches(a.forwardButton.button.backgroundDrawable,a.forwardLiquid,182,20);
        check(a.replyButton.button.invalidations==1 && a.forwardButton.button.invalidations==1,"nested buttons stayed cached");
        a.enabled=false;a.drawChild(c,a.leftLayout,0);a.drawChild(c,a.forwardButton.optionsView,0);
        matches(a.replyButton.button.backgroundDrawable,null,0,0);matches(a.forwardButton.button.backgroundDrawable,null,0,0);
        Channel channel=new Channel(); Holder h=channel.buttonHolders[0];h.liquid=new LiquidTouchEffect();h.button.x=17;h.button.y=9;
        activate(h.liquid);activate(channel.centerLiquid);
        channel.drawChild(c,h.button,0);channel.drawChild(c,channel.container,0);
        matches(h.button.backgroundDrawable,h.liquid,17,9);matches(channel.containerDrawable,channel.centerLiquid,11,37);
        check(h.button.invalidations==1,"cached channel button wasn't invalidated");
        channel.enabled=false;channel.drawChild(c,h.button,0);channel.drawChild(c,channel.container,0);
        matches(h.button.backgroundDrawable,null,0,0);matches(channel.containerDrawable,null,0,0);
        Menu menu=new Menu();menu.x=130;menu.y=24;activate(menu.liquidTouch);
        BlurredBackgroundDrawable pill=new BlurredBackgroundDrawable();menu.updateLiquidBackground(pill);
        matches(pill,menu.liquidTouch,-130,-24);
        menu.enabled=false;menu.updateLiquidBackground(pill);matches(pill,null,0,0);
    }
}
'''.replace("COMMON", common())
        replacements = {
            "BUTTON_METHOD": (paths + "buttons/ChatActivityBlurredRoundButton.java", "public void updateLiquidBackground("),
            "PAGE_METHOD": (paths + "buttons/ChatActivityBlurredRoundPageDownButton.java", "public int beginLiquidDraw("),
            "ACTIONS_METHOD": (paths + "layouts/ChatActivityActionsButtonsLayout.java", "protected boolean drawChild("),
            "CHANNEL_METHOD": (paths + "layouts/ChatActivityChannelButtonsLayout.java", "protected boolean drawChild("),
            "MENU_METHOD": ("org/telegram/ui/ActionBar/ActionBarMenu.java", "public void updateLiquidBackground("),
        }
        for placeholder, (path, signature) in replacements.items():
            harness = harness.replace(placeholder, methods(path, (signature,)).replace("protected boolean drawChild", "public boolean drawChild").replace("ButtonHolder", "Holder"))
        run_java(self, "GlassBindingsHarness", harness)


if __name__ == "__main__":
    unittest.main()
