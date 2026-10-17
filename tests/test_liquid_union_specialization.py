"""Compare production slot-specialized reductions with the original eight-slot math.

This checks arithmetic and removed coordinate divisions, not Android compilation
or GPU timing. The seven dispersion samples and optical equations stay untouched.
"""
import re
import subprocess
import tempfile
import unittest
from pathlib import Path

from test_glass_blur import method
from test_glass_prism_math import RAW, RUNTIME


class LiquidUnionSpecializationTest(unittest.TestCase):
    def test_all_slot_counts_preserve_reductions(self):
        source = (RAW / "liquid_glass_input_union.agsl").read_text()
        signatures = (
            "float touchLight(", "float surfaceOpacity(", "float surfaceDepth(",
            "float dispersionSpread(", "float4 surfaceTint(", "float connectedLight(",
        )
        code = "\n".join(method(source, signature) for signature in signatures)
        code = re.sub(r"(shapes\[\d\]|glow)\.xy", r"float2(\1.x, \1.y)", code)
        code = re.sub(r"(shapes\[\d\])\.zw", r"float2(\1.z, \1.w)", code)
        code = re.sub(r"(?<![\w.])(\d+\.\d+)(?![\w.])", r"\1f", code)
        programs = "\n".join(
            f"namespace slots{n} {{ const int SHAPE_COUNT = {n};\n{code}\n}}"
            for n in range(1, 9)
        )
        runtime = "static long divisions;\n" + RUNTIME.replace(
            "float2 operator/(float2 b)const{", "float2 operator/(float2 b)const{++divisions;"
        ) + r"""
#include <random>
float2 max(float2 a,float2 b){return {max(a.x,b.x),max(a.y,b.y)};}
float smoothstep(float a,float b,float v){float t=clamp((v-a)/(b-a),0,1);return t*t*(3-2*t);}
float4 operator+(float4 a,float4 b){return {a.x+b.x,a.y+b.y,a.z+b.z,a.w+b.w};}
float4 operator*(float4 a,float b){return {a.x*b,a.y*b,a.z*b,a.w*b};}
float4& operator+=(float4& a,float4 b){a=a+b;return a;}
struct Surface {float d;float2 normal;float4 a,b;};
float4 shapes[8],tints[8],glows[8];
float opacity[8],links[64];
"""
        main = r"""
float weight(Surface s,int i){float w[]={s.a.x,s.a.y,s.a.z,s.a.w,s.b.x,s.b.y,s.b.z,s.b.w};return w[i];}
void equal(float a,float b){if(!std::isfinite(a)||a!=b){std::fprintf(stderr,"Reduction changed: %.9g != %.9g\n",a,b);std::exit(1);}}
void check(int n,float2 p,Surface s,float(*opacityFn)(Surface),float(*depthFn)(Surface),
           float(*spreadFn)(float2,Surface),float4(*tintFn)(Surface),float(*lightFn)(float2,Surface)){
    float alpha=0,depth=0,spread=0,light=0;float4 tint(0);
    for(int i=0;i<8;i++){
        float w=weight(s,i);
        alpha+=opacity[i]*w;
        depth+=w*min(shapes[i].z,shapes[i].w);
        float2 local=(p-float2(shapes[i].x,shapes[i].y))/max(float2(shapes[i].z,shapes[i].w),float2(.001f));
        spread+=local.x*local.y*w;
        tint+=tints[i]*w;
        float connection=0;for(int j=0;j<8;j++)connection+=weight(s,j)*links[i*8+j];
        light+=slots8::touchLight(p,glows[i])*connection;
    }
    equal(alpha,opacityFn(s));equal(depth,depthFn(s));
    divisions=0;equal(spread,spreadFn(p,s));
    if(divisions!=n){std::fprintf(stderr,"Unused slot divisions remain\n");std::exit(2);}
    float4 actual=tintFn(s);equal(tint.x,actual.x);equal(tint.y,actual.y);equal(tint.z,actual.z);equal(tint.w,actual.w);
    equal(light,lightFn(p,s));
}
int main(){
    std::mt19937 rng(9017);std::uniform_real_distribution<float> u(0,1);
    for(int n=1;n<=8;n++)for(int scene=0;scene<1000;scene++){
        float weights[8];
        for(int i=0;i<8;i++){
            weights[i]=i<n && u(rng)>.2f?u(rng):0;
            shapes[i]={u(rng)*400,u(rng)*200,u(rng)*40,u(rng)*30};
            tints[i]={u(rng),u(rng),u(rng),u(rng)};opacity[i]=i<n?u(rng):0;
            glows[i]={u(rng)*400,u(rng)*200,1+u(rng)*100,i<n?u(rng):0};
            for(int j=0;j<8;j++)links[i*8+j]=i<n&&j<n?u(rng):0;
        }
        Surface s{0,float2(0),{weights[0],weights[1],weights[2],weights[3]},
                    {weights[4],weights[5],weights[6],weights[7]}};
        float2 p(u(rng)*400,u(rng)*200);
        SWITCH
    }
    std::printf("8000 scenes: unchanged opacity/depth/dispersion/tint/light; coordinate divisions 8 -> slot count\n");
}
"""
        cases = "\n".join(
            f"case {n}: check(n,p,s,slots{n}::surfaceOpacity,slots{n}::surfaceDepth,"
            f"slots{n}::dispersionSpread,slots{n}::surfaceTint,slots{n}::connectedLight); break;"
            for n in range(1, 9)
        )
        main = main.replace("SWITCH", "switch(n){" + cases + "}")
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)
            (path / "slots.cpp").write_text(runtime + programs + main)
            result = subprocess.run(
                ["c++", "-std=c++17", "-O2", "-ffp-contract=off", str(path / "slots.cpp"), "-o", str(path / "slots")],
                capture_output=True, text=True,
            )
            self.assertEqual(0, result.returncode, result.stderr)
            result = subprocess.run([str(path / "slots")], capture_output=True, text=True)
            self.assertEqual(0, result.returncode, result.stderr)
            print(result.stdout.strip())


if __name__ == "__main__":
    unittest.main()
