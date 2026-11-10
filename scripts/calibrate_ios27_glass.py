"""Measure the native GLASS coordinate charts. Pillow only; no Android build.

Inputs are the lossless 2x renders of the five charts in the isolated Figma
calibration file. Odd symmetry cancels constant RGB offsets. Quantisation
limits displacement precision to about 1 dp in X and .5 dp in Y.
"""
import argparse
import json
import math
import subprocess
import tempfile
from pathlib import Path
from PIL import Image

ROOT = Path(__file__).resolve().parents[1]


def production_params(cases):
    """Execute the Android-independent production Java class, not a Python copy."""
    jdk = Path('C:/Users/miuichina/.gradle/jdks/eclipse_adoptium-21-amd64-windows.2/bin')
    source = ROOT/'TMessagesProj/src/main/java/org/telegram/ui/Components/blur3/Ios27GlassMaterial.java'
    with tempfile.TemporaryDirectory(prefix='ios27-lens-') as temp:
        probe = Path(temp)/'LensProbe.java'
        probe.write_text('''import org.telegram.ui.Components.blur3.Ios27GlassMaterial;
public class LensProbe {
 public static void main(String[] args) {
  for(int i=0;i<args.length;i+=3){float[] p=new float[4];
   Ios27GlassMaterial.lens(Float.parseFloat(args[i]),Float.parseFloat(args[i+1]),Float.parseFloat(args[i+2]),p,0);
   System.out.println(p[0]+","+p[1]+","+p[2]+","+p[3]);
  }
 }
}''')
        subprocess.run([str(jdk/'javac.exe'),'-d',temp,str(source),str(probe)],check=True,capture_output=True)
        result = subprocess.run([str(jdk/'java.exe'),'-cp',temp,'LensProbe',
            *[str(v) for case in cases for v in case]],check=True,capture_output=True,text=True)
        return [tuple(map(float,line.split(','))) for line in result.stdout.splitlines()]


def prediction(sample, params):
    amplitude, exponent, radial, band = params
    nx, ny = direction(sample,radial)
    edge = max(0,min(1,1-sample[2]/max(band,.001)))
    amount = amplitude*(1-math.sqrt(max(0,1-edge*edge)))**exponent
    return -nx*amount,-ny*amount


def previous_prediction(sample, height):
    band = min(30,height/2)
    edge = max(0,min(1,1-sample[2]/band))
    amount = 21*band/30*(1-math.sqrt(max(0,1-edge*edge)))
    return sample[3]*amount,sample[4]*amount


def rmse(data, predict):
    return math.sqrt(sum((s[5]-predict(s)[0])**2+(s[6]-predict(s)[1])**2 for s in data)/len(data))


def field(x, y, half_x, half_y):
    r = half_y
    qx, qy = abs(x) - half_x + r, abs(y)
    vx, vy = max(qx, 0), max(qy, 0)
    length = math.hypot(vx, vy)
    sd = length - r
    if length > 1e-6:
        nx, ny = vx / length, vy / length
    else:
        nx, ny = 0, 0
    return sd, math.copysign(nx, x), math.copysign(ny, y)


def samples(path, width, height):
    image = Image.open(path).convert('RGB')
    assert image.size == (960, 480)
    px = image.load()
    result = []
    for iy in range(194, 286, 2):
        for ix in range(196, 764, 4):
            x, y = (ix+.5)/2-240, (iy+.5)/2-120
            sd, nx, ny = field(x, y, width/2, height/2)
            if not 2 < -sd < height/2-1:
                continue
            # Red encodes X/480; green encodes Y/240. Use opposite pixels
            # rather than assuming the glass introduces no constant color bias.
            dx = (px[ix, iy][0]-px[959-ix, iy][0])/2*480/255-x
            dy = (px[ix, iy][1]-px[ix, 479-iy][1])/2*240/255-y
            result.append((x, y, -sd, nx, ny, dx, dy))
    return result


def direction(sample, radial):
    x, y, inset, nx, ny, dx, dy = sample
    length = max(math.hypot(x, y), 1e-6)
    vx, vy = nx+radial*x/length, ny+radial*y/length
    norm = max(math.hypot(vx, vy), 1e-6)
    return vx/norm, vy/norm


def score(data, radial):
    bins = {}
    for s in data:
        nx, ny = direction(s, radial)
        b = round(s[2]*2)
        bins.setdefault(b, []).append((nx, ny, s[5], s[6]))
    profile = {}
    error = 0
    for b, rows in bins.items():
        amount = max(0, -sum(nx*dx+ny*dy for nx, ny, dx, dy in rows)/len(rows))
        profile[b/2] = amount
        error += sum((dx+nx*amount)**2+(dy+ny*amount)**2 for nx, ny, dx, dy in rows)
    return math.sqrt(error/len(data)), profile


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--directory', type=Path, default=Path('.tmp-glass-calibration'))
    args = parser.parse_args()
    results = {}
    for index, name, width, height, depth in [(1,'capsule',286,48,30),
            (2,'circle',48,48,30),(3,'thin',110,19,30),(4,'shallow',286,48,15)]:
        data = samples(args.directory/f'chart-{index}.png', width, height)
        candidates = [(score(data,k/100)[0],k/100) for k in range(0,101,2)]
        error, radial = min(candidates)
        _, profile = score(data,radial)
        result = dict(width=width,height=height,depth=depth,samples=len(data),
            sdf_only_rmse_dp=score(data,0)[0], fitted_rmse_dp=error,
            radial_normal_weight=radial,profile=profile)
        results[name] = result
        print(name, 'samples',len(data),'normal-only',round(result['sdf_only_rmse_dp'],3),
              'fitted',round(error,3),'radial',radial)
    (args.directory/'measurements.json').write_text(json.dumps(results,indent=2))


if __name__ == '__main__':
    main()
