"""Execute the production union field as C++ and check its analytic normal.

Distance/coverage and smooth-union weights must stay unchanged. The normal is
compared to a numerical derivative away from non-differentiable boundaries.
This is arithmetic validation; device Perfetto measurements cover GPU cost.
"""
import re
import subprocess
import tempfile
import unittest
from pathlib import Path

from test_glass_blur import method
from test_glass_prism_math import RUNTIME, RAW


class LiquidUnionMathTest(unittest.TestCase):
    def test_distance_weights_normal_and_cost(self):
        source = (RAW / 'liquid_glass_input_union.agsl').read_text()
        code = 'const int SHAPE_COUNT = 8;\n' + method(source, 'float3 shapeDistance(')
        code += '\nstruct Surface { float d; float2 normal; float4 a; float4 b; };\n'
        code += method(source, 'Surface surface(')
        code = code.replace('shape.xy', 'float2(shape.x, shape.y)')
        code = code.replace('shape.zw', 'float2(shape.z, shape.w)')
        code = re.sub(r'field(\d)\.yz', r'float2(field\1.y, field\1.z)', code)
        code = re.sub(r'(?<![\w.])(\d+\.\d+)(?![\w.])', r'\1f', code)
        runtime = RUNTIME + r'''
#include <random>
struct float3 {
    float x,y,z;
    float3(float a,float b,float c):x(a),y(b),z(c){}
    float3(float a,float2 b):x(a),y(b.x),z(b.y){}
};
float mix(float a,float b,float t){return a*(1-t)+b*t;}
float2 mix(float2 a,float2 b,float t){return a*(1-t)+b*t;}
half4& operator*=(half4& a,float t){a.x*=t;a.y*=t;a.z*=t;a.w*=t;return a;}
float4 shapes[8],cornerRadii[8];
float opacity[8],merges[64];
'''
        reference = r'''
// Reference distance and union formula, independent of gradient propagation.
float referenceDistance(float2 p,int i){
    float4 shape=shapes[i],corners=cornerRadii[i];
    if(opacity[i]<=0 || min(shape.z,shape.w)<=0)return 100000;
    float2 local=p-float2(shape.x,shape.y);
    float radius=local.x>=0?(local.y>=0?corners.z:corners.y):(local.y>=0?corners.w:corners.x);
    float2 q=abs(local)-float2(shape.z,shape.w)+radius;
    return length(max(q,0))+min(max(q.x,q.y),0)-radius;
}
float reference(float2 p,float* weights){
    float distances[8];
    for(int i=0;i<8;i++){distances[i]=referenceDistance(p,i);weights[i]=0;}
    float d=100000;
    if(opacity[0]>0){d=distances[0];weights[0]=1;}
    for(int i=1;i<8;i++)if(opacity[i]>0){
        float closest=100000,merge=8;
        for(int j=0;j<i;j++)if(opacity[j]>0 && distances[j]<closest){closest=distances[j];merge=merges[i*8+j];}
        float h=clamp(.5f+.5f*(distances[i]-d)/merge,0,1);
        d=mix(distances[i],d,h)-merge*h*(1-h);
        for(int j=0;j<8;j++)weights[j]*=h;
        weights[i]+=1-h;
    }
    return d;
}
void check(bool ok,const char* why){if(!ok){std::fprintf(stderr,"%s\n",why);std::exit(1);}}
int main(){
    std::mt19937 rng(7419);
    std::uniform_real_distribution<float> u(0,1);
    long checked=0,normals=0,oldRoots=0,newRoots=0;
    for(int scene=0;scene<300;scene++){
        for(int i=0;i<8;i++){
            float w=10+u(rng)*80,h=10+u(rng)*35;
            shapes[i]={i*45.f,30+u(rng)*40,w,h};
            float r=min(w,h);
            cornerRadii[i]={u(rng)*r,u(rng)*r,u(rng)*r,u(rng)*r};
            opacity[i]=i<scene%8+1 && u(rng)>.15f ? .2f+.8f*u(rng):0;
            for(int j=0;j<8;j++)merges[i*8+j]=4+u(rng)*20;
        }
        opacity[scene%8]=1;
        for(int n=0;n<2000;n++){
            float2 p(-90+u(rng)*540,-40+u(rng)*200);
            roots=0;Surface s=surface(p);newRoots+=roots;
            roots=0;float weights[8];float d=reference(p,weights);
            float epsilon=.05f;
            float xp=reference(p+float2(epsilon,0),weights),xm=reference(p-float2(epsilon,0),weights);
            float yp=reference(p+float2(0,epsilon),weights),ym=reference(p-float2(0,epsilon),weights);
            oldRoots+=roots;
            reference(p,weights);
            check(std::abs(d-s.d)<.0001f,"Union silhouette changed");
            float actual[]={s.a.x,s.a.y,s.a.z,s.a.w,s.b.x,s.b.y,s.b.z,s.b.w};
            for(int i=0;i<8;i++)check(std::abs(actual[i]-weights[i])<.0001f,"Union tint/opacity weights changed");
            check(std::isfinite(s.normal.x)&&std::isfinite(s.normal.y),"Non-finite normal");
            // Exclude axes/corner quadrant changes and pair-selection seams.
            float xpp=reference(p+float2(2*epsilon,0),weights),xmm=reference(p-float2(2*epsilon,0),weights);
            float ypp=reference(p+float2(0,2*epsilon),weights),ymm=reference(p-float2(0,2*epsilon),weights);
            float2 numeric((xp-xm)/(2*epsilon),(yp-ym)/(2*epsilon));
            bool smooth=std::abs(xp+xm-2*d)<.0002f && std::abs(yp+ym-2*d)<.0002f
                && std::abs(xpp+xmm-2*d)<.0008f && std::abs(ypp+ymm-2*d)<.0008f;
            if(smooth){
                check(length(numeric-s.normal)<.03f,"Analytic union normal differs from derivative");
                normals++;
            }
            checked++;
        }
    }
    check(normals>checked*.8,"Insufficient normal coverage");
    check(newRoots<oldRoots/4,"Repeated field evaluation returned");
    std::printf("%ld pixels, %ld smooth normals; sqrt calls %ld -> %ld\n",checked,normals,oldRoots,newRoots);
}
'''
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)
            (path / 'union.cpp').write_text(runtime + code + reference)
            result = subprocess.run(['c++', '-std=c++17', '-O2', str(path / 'union.cpp'), '-o', str(path / 'union')], capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            result = subprocess.run([str(path / 'union')], capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            print(result.stdout.strip())


if __name__ == '__main__':
    unittest.main()
