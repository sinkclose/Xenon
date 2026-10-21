"""Check MD3 arc geometry and draw-state restoration without Android hardware."""

import re
import unittest

from test_glass_blur import ROOT, method
from test_glass_chat_refresh import run_java


class WavyProgressPathTest(unittest.TestCase):
    def test_seams_bounds_direction_and_empty_geometry(self):
        source = (ROOT / 'org/telegram/ui/Components/WavyProgressPath.java').read_text()
        source = re.sub(r'^(package|import) .*;\n', '', source, flags=re.M)
        source = source.replace('final class WavyProgressPath', 'static final class WavyProgressPath')
        harness = r'''
import java.util.ArrayList;
public class WavyPathHarness {
    static void check(boolean condition, String reason) { if (!condition) throw new AssertionError(reason); }
    static class RectF {
        float left,top,right,bottom;
        RectF() {} RectF(float l,float t,float r,float b) { left=l;top=t;right=r;bottom=b; }
        float width() { return right-left; } float height() { return bottom-top; }
        float centerX() { return (left+right)/2; } float centerY() { return (top+bottom)/2; }
        void set(RectF r) { left=r.left;top=r.top;right=r.right;bottom=r.bottom; }
        public boolean equals(Object o) {
            if (!(o instanceof RectF)) return false;
            RectF r=(RectF)o; return left==r.left && top==r.top && right==r.right && bottom==r.bottom;
        }
    }
    static class Path {
        ArrayList<float[]> points=new ArrayList<>();
        void rewind() { points.clear(); }
        void moveTo(float x,float y) { check(points.isEmpty(), "arc must have one contour"); points.add(new float[]{x,y}); }
        void lineTo(float x,float y) { check(!points.isEmpty(), "no implicit origin"); points.add(new float[]{x,y}); }
    }
    static class Paint {
        enum Join { MITER,ROUND,BEVEL }
        Join join=Join.MITER;
        Join getStrokeJoin() { return join; } void setStrokeJoin(Join join) { this.join=join; }
    }
    static class Canvas {
        RectF oval; float start,sweep; int paths,arcs,saveCount; double rotation;
        boolean failDraw;
        int save() { check(saveCount==0, "balanced canvas"); saveCount++; return saveCount; }
        void restoreToCount(int count) { check(count==saveCount, "restore canvas"); saveCount--; rotation=0; }
        void rotate(float degrees,float cx,float cy) { rotation=degrees; }
        void drawArc(float l,float t,float r,float b,float angle,float sweep,boolean center,Paint paint) {
            check(!center && Float.isFinite(l+t+r+b+angle+sweep), "finite smooth arc");
            check(Math.abs(sweep)<=360 && sweep!=0, "bounded smooth sweep");
            arcs++;
        }
        double angle(float[] point) {
            return Math.toDegrees(Math.atan2(point[1]-oval.centerY(), point[0]-oval.centerX()))+rotation;
        }
        void nearAngle(double actual,double expected) {
            double difference=(actual-expected)%360;
            if (difference>180) difference-=360;
            if (difference< -180) difference+=360;
            check(Math.abs(difference)<.12, "arc endpoint angle: "+actual+" vs "+expected);
        }
        void drawPath(Path path,Paint paint) {
            paths++;
            check(paint.getStrokeJoin()==Paint.Join.ROUND, "round joins during GPU draw");
            if (failDraw) throw new IllegalStateException("draw interrupted");
            check(path.points.size()<=182, "at most one revolution");
            float radius=Math.min(oval.width(),oval.height())/2;
            float[] previous=null;
            for (float[] point : path.points) {
                float dx=point[0]-oval.centerX(), dy=point[1]-oval.centerY();
                double distance=Math.hypot(dx,dy);
                check(Double.isFinite(distance) && distance>radius*.94 && distance<radius*1.06,
                        "stroke centerline must stay in the indicator ring");
                if (previous!=null) {
                    double edge=Math.hypot(point[0]-previous[0],point[1]-previous[1]);
                    check(edge>=.00099 && edge<radius*.08, "no zero, microscopic or spiking edges: "+edge);
                }
                previous=point;
            }
            if (path.points.size()>1) {
                nearAngle(angle(path.points.get(0)),start+(sweep<0 ? Math.max(-360,sweep) : 0));
                nearAngle(angle(previous),start+(sweep>0 ? Math.min(360,sweep) : 0));
            }
        }
    }
    HELPER
    static void draw(WavyProgressPath helper,Canvas canvas,Paint paint,RectF oval,float start,float sweep,float phase,float amplitude) {
        canvas.oval=oval; canvas.start=start; canvas.sweep=sweep;
        helper.draw(canvas,oval,start,sweep,phase,amplitude,paint);
        check(canvas.saveCount==0 && paint.getStrokeJoin()==Paint.Join.MITER, "restore caller paint and canvas");
    }
    public static void main(String[] args) {
        WavyProgressPath helper=new WavyProgressPath(); Canvas canvas=new Canvas(); Paint paint=new Paint();
        int cases=0;
        for (float radius : new float[]{6,17,51}) {
            RectF oval=new RectF(73-radius,97-radius,73+radius,97+radius);
            for (float amplitude : new float[]{1,.001f,.5f,0,1}) {
                for (float sweep : new float[]{.0001f,4,36,180,313,359.9999f,360,720,-4,-180,-359.9999f,-360}) {
                    for (int frame=0;frame<370;frame++) {
                        float phase=frame<360 ? frame : new float[]{359.99997f,360,720,-360,-.00001f,Float.MAX_VALUE,0,.00001f,179.99998f,180}[frame-360];
                        draw(helper,canvas,paint,oval,-90,sweep,phase,amplitude); cases++;
                    }
                }
            }
        }
        int draws=canvas.paths+canvas.arcs;
        RectF valid=new RectF(0,0,34,34);
        for (float bad : new float[]{Float.NaN,Float.POSITIVE_INFINITY,Float.NEGATIVE_INFINITY}) {
            draw(helper,canvas,paint,valid,bad,90,0,1);
            draw(helper,canvas,paint,valid,0,bad,0,1);
            draw(helper,canvas,paint,valid,0,90,bad,1);
            draw(helper,canvas,paint,valid,0,90,0,bad);
        }
        draw(helper,canvas,paint,new RectF(),0,90,0,1);
        draw(helper,canvas,paint,new RectF(2,2,1,1),0,90,0,1);
        draw(helper,canvas,paint,valid,0,0,0,1);
        check(draws==canvas.paths+canvas.arcs, "invalid or empty input must not produce an origin stroke");
        canvas.failDraw=true;
        try { draw(helper,canvas,paint,valid,0,180,359,1); throw new AssertionError("expected interrupt"); }
        catch (IllegalStateException expected) { }
        check(canvas.saveCount==0 && paint.getStrokeJoin()==Paint.Join.MITER, "restore caller after interrupted draw");
        System.out.println(cases+" arc frames checked");
    }
}
'''.replace('HELPER', source)
        run_java(self, 'WavyPathHarness', harness)

    def test_all_four_renderers_share_the_safe_path(self):
        wrappers = []
        for name in ('MediaActionDrawable', 'RadialProgress', 'RadialProgressView', 'InfiniteProgress'):
            source = (ROOT / f'org/telegram/ui/Components/{name}.java').read_text()
            wrapper = method(source, 'private void drawWavyArc(')
            self.assertNotIn('getSegment(', wrapper)
            self.assertNotIn('rLineTo(', wrapper)
            wrappers.append(f'static class {name} extends Base {{ {wrapper} }}')
        harness = r'''
public class WavyRenderersHarness {
    static class Canvas {} static class RectF {} static class Paint {}
    static class NekoConfig { static boolean wavyProgressEnabled; }
    static class WavyProgressPath {
        float amplitude; int draws;
        void draw(Canvas c,RectF r,float start,float sweep,float phase,float amplitude,Paint p) {
            this.amplitude=amplitude; draws++;
            if (start!=45 || sweep!=270 || phase!=359) throw new AssertionError("forward geometry");
        }
    }
    static class Base {
        float wavePhaseAngle=359,wavyAmplitudeSmooth=.75f; int invalidations;
        WavyProgressPath wavyProgressPath=new WavyProgressPath();
        void invalidateParent() { invalidations++; }
    }
    WRAPPERS
    public static void main(String[] args) {
        Canvas c=new Canvas(); RectF oval=new RectF(); Paint paint=new Paint();
        MediaActionDrawable media=new MediaActionDrawable();
        media.drawWavyArc(c,oval,45,270,paint);
        if(media.wavyProgressPath.amplitude!=0) throw new AssertionError("smooth mode must use native arc");
        NekoConfig.wavyProgressEnabled=true;
        media.drawWavyArc(c,oval,45,270,paint);
        if(media.wavyProgressPath.amplitude!=.75f) throw new AssertionError("live style toggle must update geometry");
        RadialProgress radial=new RadialProgress(); radial.drawWavyArc(c,oval,45,270,paint);
        if(radial.invalidations!=1) throw new AssertionError("keep animation invalidation");
        new RadialProgressView().drawWavyArc(c,oval,45,270,paint);
        new InfiniteProgress().drawWavyArc(c,oval,45,270,paint);
    }
}
'''.replace('WRAPPERS', '\n'.join(wrappers))
        run_java(self, 'WavyRenderersHarness', harness)


if __name__ == '__main__':
    unittest.main()
