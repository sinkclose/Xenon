"""Execute production config methods with an in-memory preferences adapter.

This isolates Android storage, not the production setters/getters/reset code.
The whole app is not built. Also checks spectral energy/direction contracts.
"""
import re
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT/'TMessagesProj/src/main/java'
RAW = ROOT/'TMessagesProj/src/main/res/raw'
JDK = Path('C:/Users/miuichina/.gradle/jdks/eclipse_adoptium-21-amd64-windows.2/bin')


def method(source, name):
    match = re.search(r'(?:public|private) static [\w<>]+ '+name+r'\(', source)
    start = match.start()
    pos = source.index('{',match.end())
    depth = 1
    end = pos+1
    while depth:
        depth += (source[end]=='{')-(source[end]=='}')
        end += 1
    return source[start:end]


class Ios27SettingsTest(unittest.TestCase):
    def test_round_trip_reset_clamping_and_legacy_isolation(self):
        source = (JAVA/'zxc/iconic/xenon/NekoConfig.java').read_text()
        fields = re.findall(r'(?:public|private) static (?:boolean|int|float) ios27\w+(?: = [^;]+)?;',source)
        names = ['saveIos27GlassSettings','getGlassRefraction','getGlassDispersion','getGlassTintPercent',
                 'getGlassAlphaPercent','getGlassGlare','getGlassGlareAngle','getGlassBlurRadiusDp',
                 'getIos27GlassRevision','getIos27GlassLightIntensity','getIos27GlassSamplePaddingDp',
                 'setIos27GlassBlur','setIos27GlassRefraction','setIos27GlassDispersion','setIos27GlassAlpha',
                 'setIos27GlassTintPercent','toggleIos27GlassTintBlackWhite','setIos27GlassGlareMode',
                 'setIos27GlassGlare','setIos27GlassGlareAngle','setIos27GlassLightIntensity','resetIos27GlassToDefaults']
        load = '\n'.join(re.findall(r'ios27\w+ = [^;]*preferences\.get[^;]+;',source))
        stub = '''import java.util.*;
public class NekoConfig {
 static final int GLASS_GLARE_FULL=0, GLASS_GLARE_SOLID=1, GLASS_GLARE_DISABLE=2;
 static float advancedGlassFresnel=.31f,advancedGlassDispersion=.41f,advancedGlassGlare=.81f,advancedGlassGlareAngle=71f;
 static int advancedGlassTintPercent=37,advancedGlassAlpha=83,blurStrength=20;
 static class Activity {static int MODE_PRIVATE=0;}
 static class Prefs {
  Map<String,Object> values=new HashMap<>();
  Prefs edit(){return this;} void apply(){}
  Prefs putFloat(String k,float v){values.put(k,v);return this;}
  Prefs putInt(String k,int v){values.put(k,v);return this;}
  Prefs putBoolean(String k,boolean v){values.put(k,v);return this;}
  float getFloat(String k,float d){return (Float)values.getOrDefault(k,d);}
  int getInt(String k,int d){return (Integer)values.getOrDefault(k,d);}
  boolean getBoolean(String k,boolean d){return (Boolean)values.getOrDefault(k,d);}
 }
 static class Context {Prefs p=new Prefs(); Prefs getSharedPreferences(String n,int m){return p;}}
 static class ApplicationLoader {static Context applicationContext=new Context();}
 static void check(boolean ok){if(!ok)throw new AssertionError();}
'''
        probe = '''
 public static void main(String[] args){
  Prefs preferences=ApplicationLoader.applicationContext.p;
  load();
  check(ios27GlassRefraction==.7f && ios27GlassDispersion==.2f && ios27GlassLightIntensity==.25f);
  check(ios27GlassGlare==1f && ios27GlassGlareAngle==0 && ios27GlassGlareMode==GLASS_GLARE_FULL);
  check(ios27GlassAlpha==100 && ios27GlassTintPercent==0 && ios27GlassBlur==6);
  ios27LiquidGlass=true;preferences.putBoolean("ios27LiquidGlass",true);
  setIos27GlassRefraction(1.4f);setIos27GlassDispersion(.6f);setIos27GlassAlpha(61);
  setIos27GlassTintPercent(73);setIos27GlassBlur(9);toggleIos27GlassTintBlackWhite();
  setIos27GlassGlare(1.8f);setIos27GlassGlareAngle(170);setIos27GlassLightIntensity(.75f);
  setIos27GlassGlareMode(GLASS_GLARE_DISABLE);check(getIos27GlassLightIntensity()==0);
  setIos27GlassGlareMode(GLASS_GLARE_FULL);check(getIos27GlassLightIntensity()==.75f);
  ios27GlassRefraction=0;ios27GlassDispersion=0;ios27GlassGlare=0;ios27GlassBlur=0;load();
  check(getGlassRefraction()==1.4f && getGlassDispersion()==.6f && ios27GlassBlur==9);
  check(getGlassAlphaPercent()==61 && getGlassTintPercent()==73 && ios27GlassTintBlackWhite);
  check(getGlassGlare()==1.8f && getGlassGlareAngle()==170);
  ios27LiquidGlass=false;
  check(getGlassRefraction()==.31f && getGlassDispersion()==.41f && getGlassGlare()==.81f);
  check(getGlassAlphaPercent()==83 && getGlassTintPercent()==37 && getGlassGlareAngle()==71);
  for(String key:preferences.values.keySet())check(key.startsWith("ios27Glass") || key.equals("ios27LiquidGlass"));
  int version=getIos27GlassRevision();resetIos27GlassToDefaults();check(getIos27GlassRevision()>version);
  check(ios27GlassRefraction==.7f && ios27GlassDispersion==.2f && ios27GlassGlare==1f && !ios27GlassTintBlackWhite);
  check(ios27GlassAlpha==100 && ios27GlassTintPercent==0 && ios27GlassBlur==6);
  check(advancedGlassFresnel==.31f && advancedGlassDispersion==.41f && advancedGlassGlare==.81f && blurStrength==20);
  setIos27GlassRefraction(9);setIos27GlassDispersion(9);setIos27GlassGlareAngle(-5);
  setIos27GlassLightIntensity(-1);setIos27GlassAlpha(-5);setIos27GlassTintPercent(500);setIos27GlassBlur(90);
  check(ios27GlassRefraction==2 && ios27GlassDispersion==1 && ios27GlassGlareAngle==0);
  check(ios27GlassLightIntensity==0 && ios27GlassAlpha==0 && ios27GlassTintPercent==100 && ios27GlassBlur==40);
  check(getIos27GlassSamplePaddingDp()>=Math.ceil(27.58*2/.7*2+4));
 }
}'''
        program = stub+'\n'.join(fields)+'\n'+'\n'.join(method(source,n) for n in names)
        program += '\nstatic void load(){Prefs preferences=ApplicationLoader.applicationContext.p;'+load+'}\n'+probe
        with tempfile.TemporaryDirectory(prefix='ios27-settings-') as tmp:
            file = Path(tmp)/'NekoConfig.java';file.write_text(program)
            compiled=subprocess.run([str(JDK/'javac.exe'),'-d',tmp,str(file)],capture_output=True,text=True)
            self.assertEqual(0,compiled.returncode,compiled.stderr)
            result=subprocess.run([str(JDK/'java.exe'),'-cp',tmp,'NekoConfig'],capture_output=True,text=True)
            self.assertEqual(0,result.returncode,result.stderr)

    def test_spectral_energy_and_shared_offset_direction(self):
        source=(RAW/'ios27_liquid_glass.agsl').read_text()
        union=(RAW/'liquid_glass_input_union.agsl').read_text()
        self.assertIn('(refractedCoord - coord) * chromaticAberration',source)
        self.assertIn('delta = displacement * dispersion;',union)
        terms=re.findall(r'color\.([rgba]) \+= (\w+)\.([rgba]) / ([\d.]+);',source)
        for channel in 'rgba':
            # Constant-color input, including alpha, must retain energy at every strength.
            weight=sum(1/float(den) for dest,name,src,den in terms if dest==channel)
            self.assertAlmostEqual(1,weight)
        self.assertIn('if (chromaticAberration <= 0.0)',source)
        self.assertIn('dispersion > 0.0',union)
        # Positive and negative normals must reverse fringe positions, not channel weights.
        for strength in [0,.2,1]:
            for displacement in [-27.57047,0,27.57047]:
                delta=displacement*strength
                taps=[displacement+delta*k for k in [-1,-2/3,-1/3,0,1/3,2/3,1]]
                self.assertLessEqual(max(map(abs,taps)),27.57047*(1+strength)+1e-6)
                if strength==0:self.assertEqual(1,len(set(taps)))

    def test_union_and_highlight_uniform_contracts(self):
        group=(JAVA/'org/telegram/ui/Components/blur3/drawable/LiquidGlassInputGroup.java').read_text()
        shader=(RAW/'liquid_glass_input_union.agsl').read_text()
        for name in re.findall(r'setFloatUniform\("([^"]+)"',group):
            self.assertRegex(shader,rf'uniform\s+\w+\s+{name}(?:\[\d+\])?\s*;')
        effect=(JAVA/'org/telegram/ui/Components/blur3/LiquidGlassEffect.java').read_text()
        highlight=(RAW/'ios27_glass_highlight.agsl').read_text()
        for name in ['lightIntensity','lightAngle','lightFalloff','solidGlare']:
            self.assertIn(f'highlightShader.setFloatUniform("{name}"',effect)
            self.assertRegex(highlight,rf'uniform float {name};')


if __name__=='__main__':unittest.main()
