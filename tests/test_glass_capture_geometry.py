"""Exercise capture alignment, empty regions and downsampling using production Java."""

import unittest

from test_glass_blur import ROOT, method
from test_glass_chat_refresh import run_java


class GlassCaptureGeometryTest(unittest.TestCase):
    def test_alignment_and_small_capture_sizes(self):
        source = (ROOT / "org/telegram/ui/Components/blur3/DownscaleScrollableNoiseSuppressor.java").read_text()
        methods = "\n".join(method(source, signature) for signature in (
            "private static int roundDown(", "public static int roundUp(",
            "public void setupRenderNodes(GlassPositionsArray positions)",
            "public void setupRenderNodes(List<RectF> positions, int count)",
            "public static float convertRadiusToSigma(", "public static float convertSigmaToRadius(",
            "public static float downscaleRadius("))
        harness = r'''
import java.util.*;
public class CaptureGeometryHarness {
    static void check(boolean value,String message) { if(!value) throw new AssertionError(message); }
    static class Rect {
        int left,top,right,bottom;
    }
    static class RectF {
        float left,top,right,bottom;
        RectF(float l,float t,float r,float b) { left=l; top=t; right=r; bottom=b; }
        boolean isEmpty() { return left>=right || top>=bottom; }
    }
    static class GlassPositionsArray extends ArrayList<RectF> {}
    static class MediaDataController { static long calcHash(long h,long value) { return h*31+value; } }
    static class Shader { enum TileMode { CLAMP } }
    static class RenderEffect {
        static RenderEffect createBlurEffect(float x,float y,Shader.TileMode mode) { return new RenderEffect(); }
        static RenderEffect createChainEffect(RenderEffect a,RenderEffect b) { return new RenderEffect(); }
    }
    static class Canvas {
        void scale(float x,float y) { check(Float.isFinite(x)&&Float.isFinite(y),"Nonfinite capture scale"); }
        void drawRenderNode(RenderNode node) {}
    }
    static class RenderNode {
        static long next; long id=++next;
        int width,height,recordings; boolean recorded;
        RenderNode(String name) {}
        int getWidth() { return width; } int getHeight() { return height; }
        long getUniqueId() { return id; } boolean hasDisplayList() { return recorded; }
        void setPosition(int l,int t,int r,int b) { width=r-l; height=b-t; }
        Canvas beginRecording(int w,int h) {
            check(w>0&&h>0,"Empty GPU viewport"); recordings++; return new Canvas();
        }
        void endRecording() { recorded=true; }
        void setRenderEffect(RenderEffect effect) {} void setPivotX(float v) {} void setPivotY(float v) {}
        void setScaleX(float v) { check(Float.isFinite(v),"Nonfinite X scale"); }
        void setScaleY(float v) { check(Float.isFinite(v),"Nonfinite Y scale"); }
        void setTranslationX(float v) {} void setTranslationY(float v) {}
    }
    static class Capture {
        static final float BLUR_SIGMA_SCALE=.57735f;
        int k=1,rectRenderNodesCount; boolean allowNoiseSuppress;
        ArrayList<SourcePart> rectRenderNodes=new ArrayList<>();
        static class SourcePart {
            Rect position=new Rect();
            POSITION
        }
        METHODS
        DOWNSCALED
    }
    public static void main(String[] args) {
        Capture capture=new Capture();
        for(float v=-1000.5f;v<=1000.5f;v+=.25f) {
            int lower=Capture.roundDown(v,16),upper=Capture.roundUp(v,16);
            check(lower<=v && upper>=v,"Alignment cropped requested pixels");
            check(v-lower<16 && upper-v<16,"Alignment expanded more than one tile");
            check(lower%16==0&&upper%16==0,"Unaligned capture");
        }
        GlassPositionsArray positions=new GlassPositionsArray();
        positions.add(new RectF(0,0,0,20)); positions.add(new RectF(0,20,30,10));
        positions.add(new RectF(-.25f,-16.25f,15.25f,1.25f));
        capture.setupRenderNodes(positions);
        check(capture.rectRenderNodesCount==1,"Empty captures reached GPU");
        Rect bounds=capture.rectRenderNodes.get(0).position;
        check(bounds.left==-16&&bounds.top==-32&&bounds.right==16&&bounds.bottom==16,"Wrong capture bounds");
        capture.setupRenderNodes((List<RectF>)positions,2);
        check(capture.rectRenderNodesCount==0,"List overload retained empty regions");
        for(int k:new int[]{1,8}) for(int scale:new int[]{1,2,8,16})
        for(int width:new int[]{1,2,7,16,1080}) for(int height:new int[]{1,2,7,16,56}) {
            capture.k=k;
            Capture.DownscaledRenderNode filter=capture.new DownscaledRenderNode("test",0,true);
            filter.setScale(scale,scale);
            RenderNode original=new RenderNode("source"); original.setPosition(0,0,width,height);
            filter.invalidateRenderNodes(original);
            check(filter.renderNodeDownsampled[0].width>=1&&filter.renderNodeDownsampled[0].height>=1,"Zero filter size");
            int recordings=filter.renderNodeDownsampled[0].recordings;
            filter.invalidateRenderNodes(original);
            check(filter.renderNodeDownsampled[0].recordings==recordings,"Idle filter re-recorded");
            filter.renderNodeDownsampled[0].recorded=false;
            filter.invalidateRenderNodes(original);
            check(filter.renderNodeDownsampled[0].recordings==recordings+1,"Trimmed filter did not recover");
        }
    }
}
'''.replace("POSITION", method(source, "private void setPosition(RectF position)"))
        harness = harness.replace("METHODS", methods).replace("DOWNSCALED", method(source, "public class DownscaledRenderNode"))
        run_java(self, "CaptureGeometryHarness", harness)


if __name__ == "__main__":
    unittest.main()
