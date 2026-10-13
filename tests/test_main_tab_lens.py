"""Preserve lens coordinates/live icon frames while eliminating repeated recording."""

import unittest

from test_glass_blur import ROOT, method
from test_glass_chat_refresh import run_java


class MainTabLensTest(unittest.TestCase):
    def test_live_foreground_replays_match_direct_drawing(self):
        source = (ROOT / 'org/telegram/ui/MainTabsLayout.java').read_text()
        maths = (ROOT / 'zxc/iconic/xenon/helpers/LiquidNavigationMath.java').read_text()
        methods = '\n'.join(method(source, signature) for signature in (
            'private void drawLiquidForeground(Canvas canvas, float scale)',
            'private void drawLiquidForegroundFrame(Canvas canvas)',
            'private void recordLiquidForegroundFrame(float progress)',
        ))
        harness = r'''
import java.util.*;
public class MainTabsLensHarness {
    static void check(boolean v,String m) { if(!v)throw new AssertionError(m); }
    static class Build { static class VERSION { static int SDK_INT=33; } static class VERSION_CODES { static int Q=29; } }
    static class LiquidNavigationMath { MATH_METHODS }
    static class View {
        static final int VISIBLE=0;
        float x,y; int width=64,height=56,visibility,frame;
        View(float x,int v) { this.x=x; visibility=v; }
        int getVisibility() { return visibility; }
        float getY() { return y; } int getHeight() { return height; }
    }
    static class Canvas {
        float sx=1,sy=1,tx,ty; boolean hardware=true;
        List<float[]> points=new ArrayList<>(),saves=new ArrayList<>();
        boolean isHardwareAccelerated() { return hardware; }
        void save() { saves.add(new float[]{sx,sy,tx,ty}); }
        void restore() { float[] m=saves.remove(saves.size()-1);sx=m[0];sy=m[1];tx=m[2];ty=m[3]; }
        void translate(float x,float y) { tx+=sx*x;ty+=sy*y; }
        void scale(float x,float y,float px,float py) { translate(px,py);sx*=x;sy*=y;translate(-px,-py); }
        void point(float x,float y,float frame) { points.add(new float[]{sx*x+tx,sy*y+ty,frame}); }
        void drawRenderNode(RenderNode node) { for(float[] p:node.canvas.points)point(p[0],p[1],p[2]); }
    }
    static class RenderNode {
        Canvas canvas; boolean clip=true; int recordings;
        RenderNode(String name) { }
        void setClipToBounds(boolean v) { clip=v; }
        void setPosition(int l,int t,int r,int b) { }
        Canvas beginRecording() { recordings++;return canvas=new Canvas(); }
        void endRecording() { } boolean hasDisplayList() { return canvas!=null; }
    }
    static class Layout {
        View[] children={new View(0,0),new View(64,0),new View(128,8),new View(192,0)};
        RenderNode liquidForegroundNode; float liquidPressProgress; int childDraws;
        int getWidth() { return 256; } int getHeight() { return 72; }
        int getChildCount() { return children.length; } View getChildAt(int i) { return children[i]; }
        float getCenterX(View v) { return v.x+v.width/2f; } long getDrawingTime() { return 0; }
        void drawChild(Canvas c,View v,long time) {
            childDraws++;c.point(v.x,v.y,v.frame);c.point(v.x+v.width,v.y+v.height,v.frame);
        }
        DRAW_METHODS
    }
    static void same(Canvas a,Canvas b) {
        check(a.points.size()==b.points.size(),"Visible child count changed");
        for(int i=0;i<a.points.size();i++) for(int j=0;j<3;j++)
            check(Math.abs(a.points.get(i)[j]-b.points.get(i)[j])<.0001f,"Lens coordinates or icon frame changed");
    }
    public static void main(String[] args) {
        Layout direct=new Layout(),cached=new Layout();
        for(int frame=0;frame<=120;frame++) {
            float progress=(float)Math.sin(frame*Math.PI/120);
            cached.liquidPressProgress=direct.liquidPressProgress=progress;
            for(View v:direct.children)v.frame=frame;
            for(View v:cached.children)v.frame=frame;
            int before=cached.childDraws;
            cached.recordLiquidForegroundFrame(progress);
            check(!cached.liquidForegroundNode.clip,"Expanded contents were clipped to the bar");
            for(float[] origin:new float[][]{{0,0},{-150,-12},{80,40}}) {
                Canvas old=new Canvas(),now=new Canvas();
                old.translate(origin[0],origin[1]);now.translate(origin[0],origin[1]);
                direct.drawLiquidForeground(old,LiquidNavigationMath.contentScale(progress));
                cached.drawLiquidForegroundFrame(now);
                same(old,now);
            }
            check(cached.childDraws-before==3,"Foreground was traversed more than once per frame");
        }
        Canvas software=new Canvas(),reference=new Canvas();software.hardware=false;
        cached.drawLiquidForegroundFrame(software);
        direct.drawLiquidForeground(reference,LiquidNavigationMath.contentScale(direct.liquidPressProgress));
        same(reference,software);
    }
}
'''.replace('DRAW_METHODS', methods).replace('MATH_METHODS', '\n'.join(
            method(maths, signature) for signature in ('public static float clamp(', 'public static float contentScale(')))
        run_java(self, 'MainTabsLensHarness', harness)

    def test_unchanged_radius_does_not_rebuild_geometry(self):
        source = (ROOT / 'org/telegram/ui/MainTabsLayout.java').read_text()
        harness = r'''
public class MainTabsRadiusHarness {
    static void check(boolean v) { if(!v)throw new AssertionError(); }
    static class Bounds { float left,top,right=64,bottom=56; }
    static class Drawable {
        int radiusUpdates;float radius;int l,t,r,b;
        void setBounds(int l,int t,int r,int b) { this.l=l;this.t=t;this.r=r;this.b=b; }
        void setRadius(float radius) { this.radius=radius;radiusUpdates++; }
    }
    static class Layout {
        Drawable liquidLens=new Drawable();Bounds liquidBounds=new Bounds();
        float lastLiquidLensRadius=Float.NaN;
        RADIUS_METHOD
    }
    public static void main(String[] args) {
        Layout d=new Layout();
        d.updateLiquidLensBounds(23);
        for(int frame=0;frame<120;frame++) {
            d.liquidBounds.left=frame+.25f;d.liquidBounds.right=frame+64.25f;
            d.updateLiquidLensBounds(23);
            check(d.liquidLens.l==frame&&d.liquidLens.r==frame+64);
        }
        check(d.liquidLens.radiusUpdates==1&&d.liquidLens.radius==23);
        d.updateLiquidLensBounds(31);check(d.liquidLens.radiusUpdates==2&&d.liquidLens.radius==31);
        d.liquidLens=new Drawable();d.lastLiquidLensRadius=Float.NaN;
        d.updateLiquidLensBounds(31);check(d.liquidLens.radiusUpdates==1&&d.liquidLens.radius==31);
    }
}
'''.replace('RADIUS_METHOD', method(source, 'private void updateLiquidLensBounds(float radius)'))
        run_java(self, 'MainTabsRadiusHarness', harness)

    def test_search_blur_tracks_radius_without_recreating_stable_effects(self):
        source = (ROOT / 'org/telegram/ui/MainTabsActivity.java').read_text()
        production = method(source, 'private float updateIosSearchVisibility(float visibility)')
        production = production.replace('android.graphics.RenderEffect', 'RenderEffect').replace(
            'android.graphics.Shader', 'Shader')
        harness = r'''
public class MainTabsSearchBlurHarness {
    static void check(boolean v) { if(!v)throw new AssertionError(); }
    static int dp(int v) { return v*3; }
    static class NekoConfig { static boolean iosBottomBar=true,showMainTabs=true; }
    static class Build { static class VERSION { static int SDK_INT=33; } }
    static class Shader { enum TileMode { CLAMP } }
    static class RenderEffect {
        float radius;
        static RenderEffect createBlurEffect(float x,float y,Shader.TileMode mode) {
            check(x>0&&x==y);RenderEffect e=new RenderEffect();e.radius=x;return e;
        }
    }
    static class View {
        static final int VISIBLE=0,GONE=8;
        int effectUpdates;RenderEffect effect;
        boolean isAttachedToWindow() { return true; }
        void setAlpha(float f) { }void setVisibility(int v) { }void setEnabled(boolean b) { }
        void setRenderEffect(RenderEffect e) { effectUpdates++;effect=e; }
    }
    static class Animator {
        float value;
        void setValue(boolean b,boolean animated) { }
        float getFloatValue() { return value; }
    }
    static class Activity extends View {
        View iosBottomSearch=new View(),contentView=new View();
        Animator animatorIosSearchVisible=new Animator();float lastIosSearchBlur=Float.NaN;
        boolean isIosSearchDestinationDialogs() { return true; }
        SEARCH_METHOD
    }
    public static void main(String[] args) {
        Activity a=new Activity();
        for(float presence:new float[]{0,.25f,.5f,.75f,1}) {
            a.animatorIosSearchVisible.value=presence;
            a.updateIosSearchVisibility(1);
            RenderEffect effect=a.iosBottomSearch.effect;int updates=a.iosBottomSearch.effectUpdates;
            for(int frame=0;frame<120;frame++)a.updateIosSearchVisibility(frame/120f);
            check(a.iosBottomSearch.effectUpdates==updates&&a.iosBottomSearch.effect==effect);
            if(presence==1)check(effect==null);else check(effect.radius==30*(1-presence));
        }
        // Recreated search controls must receive an effect even at the same radius.
        a.iosBottomSearch=new View();a.lastIosSearchBlur=Float.NaN;
        a.updateIosSearchVisibility(1);check(a.iosBottomSearch.effectUpdates==1);
        Build.VERSION.SDK_INT=30;
        a.animatorIosSearchVisible.value=0;a.updateIosSearchVisibility(1);
        check(a.iosBottomSearch.effectUpdates==1);
    }
}
'''.replace('SEARCH_METHOD', production)
        run_java(self, 'MainTabsSearchBlurHarness', harness)


if __name__ == '__main__':
    unittest.main()
