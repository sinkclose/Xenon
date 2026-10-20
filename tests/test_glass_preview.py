"""Check preview backdrop coverage and real shader sampling without an Android device.

AGSL runs as C++ floats here; this does not emulate GPU half precision or drivers.
"""

import re
import subprocess
import tempfile
import unittest
from pathlib import Path

from test_glass_blur import ROOT, method
from test_glass_chat_refresh import run_java
from test_glass_prism_math import RUNTIME, shader


class GlassPreviewTest(unittest.TestCase):
    def test_wallpaper_padding_covers_edges_and_preserves_visible_crop(self):
        source = (ROOT / "zxc/iconic/xenon/settings/GlassPreviewCell.java").read_text()
        harness = r'''
import java.util.*;
public class PreviewBackdropHarness {
    static void check(boolean value) { if (!value) throw new AssertionError(); }
    static class RenderNode {}
    static class Canvas {
        final int width, height, padding;
        final int[][] coverage;
        float sx=1, sy=1, tx, ty;
        int left, top, right, bottom;
        ArrayList<float[]> states = new ArrayList<>();
        Canvas(int width, int height, int padding) {
            this.width=width; this.height=height; this.padding=padding;
            coverage = new int[height+2*padding][width+2*padding];
        }
        int save() {
            states.add(new float[]{sx,sy,tx,ty,left,top,right,bottom}); return states.size();
        }
        void restoreToCount(int count) {
            float[] s=states.remove(states.size()-1);
            sx=s[0]; sy=s[1]; tx=s[2]; ty=s[3];
            left=(int)s[4]; top=(int)s[5]; right=(int)s[6]; bottom=(int)s[7];
        }
        void clipRect(int l,int t,int r,int b) { left=l; top=t; right=r; bottom=b; }
        void translate(float x,float y) { tx+=x*sx; ty+=y*sy; }
        void scale(float x,float y) { sx*=x; sy*=y; }
        void drawRenderNode(RenderNode node) {
            for(int y=top;y<bottom;y++) for(int x=left;x<right;x++) {
                float sampleX=(x+.5f-tx)/sx, sampleY=(y+.5f-ty)/sy;
                check(sampleX>=0 && sampleX<width && sampleY>=0 && sampleY<height);
                if (x>=padding && x<padding+width && y>=padding && y<padding+height) {
                    check(Math.abs(sampleX-(x-padding+.5f))<.001f);
                    check(Math.abs(sampleY-(y-padding+.5f))<.001f);
                }
                coverage[y][x]++;
            }
        }
    }
    static class Preview {
        int samplePadding;
        RenderNode wallpaperNode = new RenderNode();
        METHOD
    }
    public static void main(String[] args) {
        for(int width:new int[]{1,2,37,360}) for(int height:new int[]{1,2,140})
        for(int padding:new int[]{1,7,256}) {
            Preview preview=new Preview(); preview.samplePadding=padding;
            Canvas canvas=new Canvas(width,height,padding);
            preview.drawPaddedWallpaper(canvas,width,height);
            for(int[] row:canvas.coverage) for(int count:row) check(count==1);
            check(canvas.states.isEmpty() && canvas.sx==1 && canvas.sy==1 && canvas.tx==0 && canvas.ty==0);
        }
    }
}
'''.replace("METHOD", method(source, "private void drawPaddedWallpaper("))
        run_java(self, "PreviewBackdropHarness", harness)

    def test_extreme_settings_never_sample_transparent_backdrop(self):
        source = (ROOT / "zxc/iconic/xenon/settings/GlassPreviewCell.java").read_text()
        padding = int(re.search(r"samplePadding = dp\((\d+)\)", source)[1])
        # Replace the procedural image with the finite opaque RenderNode input.
        runtime = RUNTIME[:RUNTIME.index("struct Image {")] + r'''
static float inputWidth, inputHeight;
static long outside;
struct Image {
    half4 eval(float2 p) {
        if (!std::isfinite(p.x) || !std::isfinite(p.y)) std::abort();
        if(p.x<0 || p.y<0 || p.x>=inputWidth || p.y>=inputHeight) {
            outside++; return half4(0);
        }
        return half4(1);
    }
};
'''
        programs = "\n".join(
            f"namespace {namespace} {{\n{shader(filename)}\n}}"
            for namespace, filename in (
                ("base", "liquid_glass_shader.agsl"),
                ("prism", "prism_glass_shader.agsl"),
                ("advanced", "liquid_glass_shader_advanced.agsl"),
                ("prismAdvanced", "prism_glass_shader_advanced.agsl"),
            )
        )
        main = r'''
int main() {
    for(float density:{1.f,1.5f,2.f,3.f,4.f}) for(bool padded:{false,true}) {
        float width=240*density, height=56*density;
        float padding=padded ? PADDING*density : 0;
        inputWidth=width+2*padding; inputHeight=height+2*padding;
        base::resolution=prism::resolution=advanced::resolution=prismAdvanced::resolution=float2(inputWidth,inputHeight);
        float2 center(padding+width/2,padding+height/2);
        float4 radius(20*density);
        base::center=prism::center=advanced::center=prismAdvanced::center=center;
        base::size=prism::size=float2(width/2,height/2);
        advanced::size=prismAdvanced::size=float2(width,height);
        base::radius=prism::radius=advanced::radius=prismAdvanced::radius=radius;
        base::refract_index=prism::refract_index=1.5f;
        base::foreground_color_premultiplied=prism::foreground_color_premultiplied=half4(0);
        advanced::depthEffect=prismAdvanced::depthEffect=0;
        outside=0;
        for(float thickness:{5.f,11.f,20.f}) for(float intensity:{0.f,.01f,.75f,1.5f})
        for(float refraction:{0.f,.25f,1.f,2.f}) for(float dispersion:{0.f,.5f,1.f}) {
            base::thickness=prism::thickness=thickness*density;
            base::refract_intensity=prism::refract_intensity=intensity;
            advanced::refractionHeight=prismAdvanced::refractionHeight=16*density*max(.25f,refraction);
            advanced::refractionAmount=prismAdvanced::refractionAmount=-32*density*max(.25f,refraction);
            advanced::chromaticAberration=prismAdvanced::chromaticAberration=dispersion;
            for(int y=0;y<56;y++) for(int x=0;x<240;x+=2) {
                float2 p(padding+(x+.5f)*density,padding+(y+.5f)*density);
                if(advanced::sdRoundedRect(p-center,float2(width/2,height/2),radius.x)>0) continue;
                half4 colors[]={base::main(p),prism::main(p),advanced::main(p),prismAdvanced::main(p)};
                for(half4 c:colors) {
                    if(!std::isfinite(c.a) || std::abs(c.a-1.f)>.00001f) std::abort();
                }
            }
        }
        if(outside) { std::fprintf(stderr,"Backdrop lost %ld samples\n",outside); return 1; }
    }
}
'''.replace("PADDING", str(padding))
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "preview.cpp"
            executable = Path(directory) / "preview"
            path.write_text(runtime + programs + main)
            result = subprocess.run(["c++", "-std=c++17", "-O2", str(path), "-o", str(executable)],
                                    capture_output=True, text=True)
            self.assertEqual(0, result.returncode, result.stderr)
            result = subprocess.run([str(executable)], capture_output=True, text=True)
            self.assertEqual(0, result.returncode, result.stderr)


if __name__ == "__main__":
    unittest.main()
