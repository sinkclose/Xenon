import unittest
from test_glass_blur import ROOT, method
from test_glass_chat_refresh import run_java

class FlexProbeCacheTest(unittest.TestCase):
    def test_layout_checks_do_not_recreate_variable_fonts(self):
        source = (ROOT / 'zxc/iconic/xenon/helpers/TypefaceHelper.java').read_text()
        methods = method(source, 'private static synchronized Typeface flexBase()') + method(source, 'public static boolean supportsRoundedTypeface()')
        methods = methods.replace('org.telegram.messenger.ApplicationLoader', 'ApplicationLoader')
        harness = r"""
import java.util.*;
public class FlexProbeHarness {
 static class Build { static class VERSION {static int SDK_INT=35;} }
 static class ClientFonts {static int revision=1;static boolean enabled=true;
  static int generation(){return revision;} static boolean useGoogleSans(){return enabled;}}
 static class Typeface {static int loads;static Typeface system=new Typeface();
  static Typeface createFromAsset(Object a,String path){loads++;return new Typeface();}}
 static class Paint {static int allocations,probes;Paint(){allocations++;}
  void setTypeface(Typeface f){} boolean setFontVariationSettings(String settings){probes++;return true;}}
 static class ApplicationLoader {static App applicationContext=new App();}
 static class App {Object getAssets(){return this;}}
 static Typeface createSystemTypeface(int w,boolean i){return Typeface.system;}
 static Typeface createTypeface(int w,boolean i){return Typeface.system;}
 static Typeface bundledFlex,cachedFlexBase;static int flexBaseGeneration=-1;
 static Map<Typeface,Boolean> roundedSupport=new HashMap<>();
 METHODS
 public static void main(String[] args){
  for(int i=0;i<10000;i++)if(!supportsRoundedTypeface())throw new AssertionError();
  if(Paint.allocations!=2||Paint.probes!=3)throw new AssertionError("Repeated probes: "+Paint.probes);
  ClientFonts.revision++;
  supportsRoundedTypeface();
  if(Paint.allocations!=3)throw new AssertionError("Generation must invalidate base probe");
  if(Typeface.loads!=0)throw new AssertionError("System Flex must avoid asset load");
 }
}
""".replace('METHODS',methods)
        run_java(self,'FlexProbeHarness',harness)

if __name__ == '__main__': unittest.main()
