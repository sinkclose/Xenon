"""Preset/resource contracts and edge-compositing invariants; no Android build."""
import math
import json
import sys
import re
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
RAW = ROOT / 'TMessagesProj/src/main/res/raw'
sys.path.insert(0, str(ROOT / "scripts"))
from calibrate_ios27_glass import production_params, prediction, previous_prediction, rmse

JAVA = ROOT / 'TMessagesProj/src/main/java'


class Ios27GlassTest(unittest.TestCase):
    def test_effect_uniform_contract(self):
        java = (JAVA / 'org/telegram/ui/Components/blur3/LiquidGlassEffect.java').read_text()
        # Every uniform uploaded by the advanced branch exists in the preset program.
        branch = java.split('if (advanced) {')[-1].split('} else {')[0]
        uniforms = re.findall(r'setFloatUniform\("([^"]+)"', branch)
        shader = (RAW / 'ios27_liquid_glass.agsl').read_text()
        for name in uniforms:
            self.assertRegex(shader, rf'uniform\s+\w+\s+{name}\s*;')
        for filename in ['ios27_liquid_glass.agsl', 'ios27_glass_highlight.agsl']:
            source = (RAW / filename).read_text()
            self.assertEqual(source.count('{'), source.count('}'))
            self.assertEqual(1, source.count('half4 main('))

    def test_preset_does_not_overwrite_manual_tuning(self):
        source = (JAVA / 'zxc/iconic/xenon/NekoConfig.java').read_text()
        toggle = source.split('public static void toggleIos27LiquidGlass() {')[1].split('public static void toggleUseAdvancedLiquidGlass')[0]
        for key in ['advancedGlassFresnel', 'advancedGlassDispersion', 'advancedGlassGlare', 'useAdvancedLiquidGlass', 'usePrismGlass', 'blurStrength']:
            self.assertNotIn(key + ' =', toggle)
            self.assertNotIn('"' + key + '"', toggle)
        self.assertIn('ios27LiquidGlass ? ios27GlassRefraction', source)
        self.assertIn('ios27LiquidGlass ? ios27GlassDispersion', source)
        self.assertIn('ios27LiquidGlass ? ios27GlassBlur', source)

    def test_edge_overlay_is_premultiplied_and_density_independent(self):
        source = (RAW / 'ios27_glass_highlight.agsl').read_text()
        # Evaluate the production scalar expressions over distances and unit normals.
        white_expr = re.search(r'float white = (.*);', source).group(1)
        black_expr = re.search(r'float black = (.*);', source).group(1).replace('n.y', 'ny')
        clamp = lambda v, lo, hi: max(lo, min(hi, v))
        def smoothstep(lo, hi, v):
            t = clamp((v - lo) / (hi - lo), 0, 1)
            return t * t * (3 - 2 * t)
        for density in [1, 1.5, 2, 3, 4]:
            for i in range(401):
                inward = (i / 20 * density) / density
                for ny in [-1, -.5, 0, .5, 1]:
                    env = dict(clamp=clamp, max=max, vertical=abs(ny), ny=ny, lightIntensity=.25,
                               innerLight=.101961, contour=.349020,
                               edge=math.exp(-inward * inward / .9),
                               soft=math.exp(-inward / 10),
                               rim=1-smoothstep(.25, .75, inward))
                    white = eval(white_expr, {'__builtins__': {}}, env)
                    black = eval(black_expr, {'__builtins__': {}}, env)
                    alpha = white + black * (1-white)
                    self.assertTrue(0 <= white <= alpha <= 1)

    def test_localized_resources_and_independent_settings(self):
        for locale in ['values', 'values-ru']:
            tree = ET.parse(ROOT / 'TMessagesProj/src/main/res' / locale / 'strings.xml')
            names = [n.attrib.get('name') for n in tree.getroot()]
            for key in ['Ios27LiquidGlass', 'Ios27LiquidGlassInfo', 'Ios27GlassSettingsInfo', 'Ios27GlassLightIntensity']:
                self.assertEqual(1, names.count(key))
        source = (JAVA / 'zxc/iconic/xenon/settings/NekoLiquidGlassSettingsActivity.java').read_text()
        ios27 = source.split('private void fillIos27Items')[1].split('private void invalidatePreview')[0]
        self.assertIn('setIos27GlassBlur', ios27)
        self.assertIn('setIos27GlassRefraction', ios27)
        self.assertIn('setIos27GlassDispersion', ios27)
        self.assertIn('setIos27GlassGlareAngle', ios27)
        self.assertNotIn('setAdvancedGlass', ios27)
        other = (JAVA / 'zxc/iconic/xenon/settings/NekoExperimentalSettingsActivity.java').read_text()
        self.assertNotIn('ios27LiquidGlassRow', other)
        self.assertIn('id == ios27LiquidGlassRow', source)

    def test_calibrated_parameters_scale_with_density(self):
        cases = [(w*d,h*d,d) for w,h in [(286,48),(48,48),(110,19),(24,12)] for d in [1,2,3,4]]
        params = production_params(cases)
        for i in range(0,len(params),4):
            a = params[i]
            for d,p in zip([1,2,3,4],params[i:i+4]):
                self.assertAlmostEqual(a[0]*d,p[0],places=4)
                self.assertAlmostEqual(a[3]*d,p[3],places=4)
                self.assertAlmostEqual(a[1],p[1],places=5)
                self.assertAlmostEqual(a[2],p[2],places=5)
                self.assertLess(p[0]*1.2,40*d)
                self.assertTrue(all(math.isfinite(v) for v in p))

    def test_dark_material_has_own_backing(self):
        for filename in ['ios27_liquid_glass.agsl', 'liquid_glass_input_union.agsl']:
            source = (RAW / filename).read_text()
            dark = source.split('if (darkMode > 0.5) {')[1].split('}')[0]
            self.assertIn('(0.6 - luminance) * 0.17', dark)
            self.assertNotIn('0.266667', dark)

    def test_measured_native_coordinate_maps(self):
        fixtures = [json.loads((ROOT / 'tests/fixtures/ios27-glass' / (n+'.json')).read_text())
                    for n in ['capsule','circle','thin']]
        params = production_params([(f['width'],f['height'],1) for f in fixtures])
        for f,p in zip(fixtures,params):
            error = rmse(f['samples'],lambda s:prediction(s,p))
            previous = rmse(f['samples'],lambda s:previous_prediction(s,f['height']))
            self.assertLess(error,1.1, f"{f['width']}x{f['height']} error {error}")
            self.assertLess(error,previous*.3)
        source = (RAW / 'ios27_liquid_glass.agsl').read_text()
        self.assertIn('return -lensParams.x * pow(circleMap(edge), lensParams.y);',source)
        self.assertIn('grad += lensParams.z * radial;',source)
        union = (RAW / 'liquid_glass_input_union.agsl').read_text()
        self.assertIn('displacement = -normal * lens.x * profile * slope;',union)


if __name__ == '__main__':
    unittest.main()
