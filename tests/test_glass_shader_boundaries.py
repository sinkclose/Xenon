"""Run shader boundary math, including 16-bit texture-coordinate rounding."""

import subprocess
import tempfile
import unittest
from pathlib import Path

from test_glass_prism_math import RUNTIME, shader


class GlassShaderBoundariesTest(unittest.TestCase):
    def run_cpp(self, code):
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory)
            (path / "shader.cpp").write_text(code)
            result=subprocess.run(["c++", "-std=c++17", "-O2", str(path / "shader.cpp"), "-o", str(path / "shader")],
                                  capture_output=True,text=True)
            self.assertEqual(0,result.returncode,result.stderr)
            result=subprocess.run([str(path / "shader")],capture_output=True,text=True)
            self.assertEqual(0,result.returncode,result.stderr)

    def test_full_resolution_sampling_with_half_precision_locals(self):
        # Model half2 assignment rounding; the other optics stay C++ floats.
        runtime=RUNTIME.replace("using half=float; using half2=float2;", r'''
struct Half2 : float2 {
    Half2(float a=0):float2(float(_Float16(a))){}
    Half2(float a,float b):float2(float(_Float16(a)),float(_Float16(b))){}
    Half2(float2 a):Half2(a.x,a.y){}
};
using half=float; using half2=Half2;
''')
        runtime=runtime[:runtime.index("struct Image {")] + r'''
static float width,height;
struct Image {
    half4 eval(float2 p) {
        if(p.x<.5f || p.y<.5f || p.x>width-.5f || p.y>height-.5f) std::abort();
        return half4(1);
    }
};
'''
        programs="\n".join(f"namespace {name} {{\n{shader(file)}\n}}" for name,file in (
            ("base","liquid_glass_shader.agsl"),("prism","prism_glass_shader.agsl")))
        self.run_cpp(runtime+programs+r'''
int main() {
    for(float w:{1080.f,1440.f,2160.f,4096.f}) {
        width=w; height=224;
        base::resolution=prism::resolution={width,height};
        base::size=prism::size=base::center=prism::center={width/2,height/2};
        base::radius=prism::radius=half4(0);
        base::thickness=prism::thickness=20;
        base::refract_intensity=prism::refract_intensity=0;
        base::refract_index=prism::refract_index=1.5f;
        for(float x:{.5f,width/2,width-.5f}) for(float y:{.5f,height/2,height-.5f}) {
            base::main({x,y}); prism::main({x,y});
        }
    }
}
''')

    def test_highlight_is_finite_and_tracks_its_shape_origin(self):
        code=shader("liquid_glass_highlight.agsl").replace("layout(color) ", "")
        code=code.replace("half4 main(", "half4 highlight(")
        self.run_cpp(RUNTIME+r'''
float dot(float2 a,float2 b){return a.x*b.x+a.y*b.y;}
float abs(float v){return std::abs(v);}
half4 operator*(half4 a,float b){return {a.r*b,a.g*b,a.b*b,a.a*b};}
'''+code+r'''
int main() {
    size={240,56}; cornerRadii={28,12,4,20}; color=half4(1); angle=.7f; falloff=1;
    for(float x=0;x<=240;x+=.5f) for(float y=0;y<=56;y+=.5f) {
        origin={0,0}; half4 original=highlight({x,y});
        origin={256,512}; half4 shifted=highlight({x+256,y+512});
        if(!std::isfinite(original.a) || !std::isfinite(shifted.a) || std::abs(original.a-shifted.a)>.0001f) std::abort();
    }
    if(radiusAt({-10,-10},cornerRadii)!=28 || radiusAt({10,-10},cornerRadii)!=12
            || radiusAt({10,10},cornerRadii)!=4 || radiusAt({-10,10},cornerRadii)!=20) std::abort();
}
''')


if __name__ == "__main__":
    unittest.main()
