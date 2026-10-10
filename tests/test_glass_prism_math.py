"""Run production AGSL arithmetic as C++ floats against the original programs.

This checks sampling coordinates/colors and operation counts, not Android shader
compilation, half-precision rounding, GPU timing or driver behavior.
"""
import pathlib
import re
import subprocess
import tempfile
import unittest

RAW = pathlib.Path(__file__).resolve().parents[1] / "TMessagesProj/src/main/res/raw"


def shader(name):
    code = (RAW / name).read_text()
    code = code.replace("uniform shader img;", "Image img;")
    code = code.replace("uniform ", "").replace("in float2", "float2")
    # Expand the few AGSL swizzles into equivalent C++ field expressions.
    code = code.replace("r.xy = (p.x > 0.0) ? r.xy : r.zw;", "if (p.x <= 0.0) { r.x = r.z; r.y = r.w; }")
    for value in ("src", "dst"):
        code = code.replace(value + ".rgb", f"half3({value}.r, {value}.g, {value}.b)")
    code = code.replace("refract_vec.xy", "half2(refract_vec.x, refract_vec.y)")
    # AGSL literals are single precision; don't promote expressions to doubles.
    code = re.sub(r"(?<![\w.])(\d+\.\d+)(?![\w.])", r"\1f", code)
    return code


RUNTIME = r'''
#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstdlib>
static long roots, samples;
float sqrt(float v) { ++roots; return std::sqrt(v); }
float min(float a,float b) { return std::min(a,b); }
float max(float a,float b) { return std::max(a,b); }
float clamp(float v,float a,float b) { return min(max(v,a),b); }
float step(float edge,float x) { return x >= edge ? 1.f : 0.f; }
float sign(float x) { return x > 0 ? 1.f : x < 0 ? -1.f : 0.f; }
struct float2 {
    float x,y;
    float2(float a=0):x(a),y(a){} float2(float a,float b):x(a),y(b){}
    float2 operator+(float2 b)const{return {x+b.x,y+b.y};}
    float2 operator-(float2 b)const{return {x-b.x,y-b.y};}
    float2 operator*(float2 b)const{return {x*b.x,y*b.y};}
    float2 operator/(float2 b)const{return {x/b.x,y/b.y};}
    float2& operator+=(float2 b){return *this=*this+b;}
    float2& operator/=(float b){return *this=*this/b;}
};
float2 operator*(float a,float2 b){return b*a;}
float2 max(float2 a,float b){return {max(a.x,b),max(a.y,b)};}
float2 abs(float2 a){return {std::abs(a.x),std::abs(a.y)};}
float2 sign(float2 a){return {sign(a.x),sign(a.y)};}
float length(float2 a){return sqrt(a.x*a.x+a.y*a.y);}
struct half3 {
    float x,y,z;
    half3(float a,float b,float c):x(a),y(b),z(c){}
    half3 operator+(half3 b)const{return {x+b.x,y+b.y,z+b.z};}
    half3 operator-(half3 b)const{return {x-b.x,y-b.y,z-b.z};}
    half3 operator*(float b)const{return {x*b,y*b,z*b};}
};
half3 normalize(half3 a){float l=sqrt(a.x*a.x+a.y*a.y+a.z*a.z);return a*(1.f/l);}
half3 refract(half3 i,half3 n,float eta){
    float d=n.x*i.x+n.y*i.y+n.z*i.z;
    float k=1.f-eta*eta*(1.f-d*d);
    if(k<0)return {0,0,0};
    return i*eta-n*(eta*d+sqrt(k));
}
struct half4 {
    union {struct{float r,g,b,a;};struct{float x,y,z,w;};};
    half4(float v=0):r(v),g(v),b(v),a(v){}
    half4(float r,float g,float b,float a):r(r),g(g),b(b),a(a){}
    half4(half3 c,float a):r(c.x),g(c.y),b(c.z),a(a){}
};
using half=float; using half2=float2; using float4=half4;
struct Image {
    half4 eval(float2 p){
        ++samples;
        float alpha=.65f+.3f*std::sin(p.x*.031f+p.y*.017f);
        return {alpha*(.5f+.5f*std::sin(p.x*.21f)),
                alpha*(.5f+.5f*std::sin(p.y*.27f)),
                alpha*(.5f+.5f*std::cos(p.x*.11f-p.y*.19f)),alpha};
    }
};
'''


class PrismShaderMathTest(unittest.TestCase):
    def test_production_optics_and_arithmetic_cost(self):
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
void equal(half4 a,half4 b) {
    float error=max(max(std::abs(a.r-b.r),std::abs(a.g-b.g)),max(std::abs(a.b-b.b),std::abs(a.a-b.a)));
    if(!std::isfinite(error) || error>.0002f){std::fprintf(stderr,"optical error %.9f\n",error);std::exit(1);}
}
int main(){
    long oldRoots=0,newRoots=0,oldSamples=0,newSamples=0,pixels=0;
    for(float width:{48.f,240.f,1080.f}) for(float height:{48.f,96.f,160.f})
    for(float corner:{0.f,12.f,80.f}) for(float material:{.25f,1.f,2.f}) {
        float2 center(width/2,height/2);
        float4 radii(corner,corner*.6f,corner*.3f,corner*.9f);
        base::center=prism::center=advanced::center=prismAdvanced::center=center;
        base::size=prism::size=center;
        advanced::size=prismAdvanced::size=float2(width,height);
        base::radius=prism::radius=advanced::radius=prismAdvanced::radius=radii;
        base::thickness=prism::thickness=11*material;
        base::refract_index=prism::refract_index=1.5f;
        base::refract_intensity=prism::refract_intensity=material;
        base::foreground_color_premultiplied=prism::foreground_color_premultiplied=half4(.1f,.2f,.05f,.3f);
        advanced::refractionHeight=prismAdvanced::refractionHeight=32*material;
        advanced::refractionAmount=prismAdvanced::refractionAmount=-64*material;
        for(float depth:{0.f,.25f}) for(float dispersion:{0.f,.5f,1.f}) {
            advanced::depthEffect=prismAdvanced::depthEffect=depth;
            advanced::chromaticAberration=prismAdvanced::chromaticAberration=dispersion;
            for(int y=-2;y<=100;y++) for(int x=-2;x<=100;x++){
                float2 p(width*x/100.f,height*y/100.f);
                roots=samples=0; half4 a=base::main(p);oldRoots+=roots;oldSamples+=samples;
                roots=samples=0; half4 b=prism::main(p);newRoots+=roots;newSamples+=samples;equal(a,b);
                roots=samples=0;a=advanced::main(p);oldRoots+=roots;oldSamples+=samples;
                roots=samples=0;b=prismAdvanced::main(p);newRoots+=roots;newSamples+=samples;equal(a,b);
                pixels+=2;
            }
        }
    }
    if(newRoots>=oldRoots || newSamples>oldSamples)return 2;
    std::printf("%ld pixel comparisons; sqrt calls %ld -> %ld; source evaluations %ld -> %ld\n",
                pixels,oldRoots,newRoots,oldSamples,newSamples);
}
'''
        with tempfile.TemporaryDirectory() as directory:
            path = pathlib.Path(directory)
            (path / "shaders.cpp").write_text(RUNTIME + programs + main)
            compile_result = subprocess.run(
                ["c++", "-O2", "-std=c++17", str(path / "shaders.cpp"), "-o", str(path / "shaders")],
                capture_output=True, text=True,
            )
            self.assertEqual(compile_result.returncode, 0, compile_result.stderr)
            result = subprocess.run([str(path / "shaders")], capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            print(result.stdout.strip())


if __name__ == "__main__":
    unittest.main()
