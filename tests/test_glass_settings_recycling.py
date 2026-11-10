"""Exercise the production slider binding across edits and holder recycling."""
import unittest
from test_glass_blur import ROOT, method
from test_glass_chat_refresh import run_java


class GlassSettingsRecyclingTest(unittest.TestCase):
    def test_rebind_uses_edited_value_without_writing_the_old_value(self):
        source=(ROOT/'zxc/iconic/xenon/settings/BaseNekoSettingsActivity.java').read_text(encoding='utf-8')
        factory=source[source.index('protected static class SeekbarCellFactory'):]
        bind_factory=method(factory,'public void bindView(')
        bind_cell=method(source,'public void bind(SeekbarConfig config, int currentValue, UItem item)')
        harness=r'''
public class SliderRecycleHarness {
 static class View {}
 static class UItem {Object object;int intValue;}
 static class UniversalAdapter {} static class UniversalRecyclerView {}
 static class LayoutHelper {static int MATCH_PARENT=-1,WRAP_CONTENT=-2;static Object createFrame(int w,int h){return null;}}
 interface OnDrag {void run(float progress);}
 static class SeekbarConfig {
  int min=0,max=200,step=1;String title="test",left="0",right="200",description;
  OnDrag onDrag;java.util.function.Function<Integer,String> valueFormatter;
  java.util.function.Consumer<Boolean> onDragStateChanged;
 }
 static class AltSeekbar {
  OnDrag onDrag;float value;
  AltSeekbar(Object c,OnDrag d,int min,int max,String t,String l,String r,Object p,String desc){onDrag=d;}
  void setValueFormatter(java.util.function.Function<Integer,String> f){}
  void setOnDragStateChanged(java.util.function.Consumer<Boolean> c){}
  void setDefaultValue(int v){}void setStep(int step){}
  void setValue(int v){value=v;}
  void edit(float v){value=v;onDrag.run(v);}
 }
 static class SeekbarCell extends View {
  AltSeekbar seekbar;Object resourcesProvider;
  Object getContext(){return null;}void removeView(AltSeekbar s){}void addView(AltSeekbar s,Object p){}
  CELL
 }
 static class Factory {FACTORY}
 static void check(boolean ok){if(!ok)throw new AssertionError();}
 public static void main(String[] args){
  UItem item=new UItem();item.intValue=70;SeekbarConfig config=new SeekbarConfig();item.object=config;
  int[] saved={70},writes={0};config.onDrag=v->{check(item.intValue==Math.round(v));saved[0]=Math.round(v);writes[0]++;};
  Factory factory=new Factory();SeekbarCell cell=new SeekbarCell();
  factory.bindView(cell,item,false,null,null);check(cell.seekbar.value==70 && writes[0]==0);
  cell.seekbar.edit(134);check(item.intValue==134 && saved[0]==134 && writes[0]==1);
  factory.bindView(cell,item,false,null,null);check(cell.seekbar.value==134 && saved[0]==134 && writes[0]==1);
  cell.seekbar.edit(91);SeekbarCell recycled=new SeekbarCell();
  factory.bindView(recycled,item,false,null,null);check(recycled.seekbar.value==91 && saved[0]==91 && writes[0]==2);
  UItem other=new UItem();other.intValue=20;SeekbarConfig otherConfig=new SeekbarConfig();other.object=otherConfig;
  otherConfig.onDrag=v->{};factory.bindView(cell,other,false,null,null);cell.seekbar.edit(30);
  check(other.intValue==30 && item.intValue==91 && saved[0]==91);
 }
}
'''.replace('CELL',bind_cell).replace('FACTORY',bind_factory)
        run_java(self,'SliderRecycleHarness',harness)


if __name__=='__main__':unittest.main()
