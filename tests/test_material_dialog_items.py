"""Exercise Material dialog row filtering and original action indices without Android."""
import shutil
import re
import subprocess
import tempfile
import unittest
from pathlib import Path

from test_glass_blur import ROOT, method


class MaterialDialogItemsTest(unittest.TestCase):
    def test_hidden_rows_do_not_reach_adapter_and_actions_keep_original_indices(self):
        source = (ROOT / 'org/telegram/ui/ActionBar/AlertDialog.java').read_text()
        setup = method(source, 'private void setMaterialDialogItems(')
        material = method(source, 'private boolean showAsMaterialDialogInternalWithRed(')
        self.assertIn('setMaterialDialogItems(builder);', material)
        self.assertNotIn('builder.setItems(items,', material)
        settings = method((ROOT / 'org/telegram/ui/SettingsActivity.java').read_text(), 'public void openDebugMenu()')
        start = settings.index('items = new CharSequence[]{')
        debug_items = settings[start:settings.index('};', start) + 2].replace('items =', 'return', 1)
        string_ids = sorted(set(re.findall(r'R\.string\.(\w+)', debug_items)))
        config_flags = sorted(set(re.findall(r'SharedConfig\.(\w+)\b(?!\s*\()', debug_items)))
        debug_setup = r'''
    static class BuildVars { static boolean DEBUG_VERSION,DEBUG_PRIVATE_VERSION,LOGS_ENABLED; }
    static class Build { static class VERSION { static int SDK_INT=36; } static class VERSION_CODES { static final int P=28; } }
    static class ApplicationLoader { static boolean isStandaloneBuild() { return false; } static boolean isBetaBuild() { return false; } }
    static class AndroidUtilities { static boolean isTabletInternal() { return false; } }
    static class InstantCameraView { static boolean allowBigSizeCameraDebug() { return false; } }
    static class DualCameraView { static boolean dualAvailableStatic(Object context) { return false; } }
    static class SharedSettings {
        static class Flag { boolean get() { return false; } }
        static final Flag experimentalSettingsAllowed=new Flag();
    }
    static class SharedConfig {
        static boolean CONFIG_FLAGS;
        static boolean isUsingCamera2(int account) { return false; }
        static boolean canBlurChat() { return false; }
    }
    static class R { static class string { STRING_IDS } }
    static String getString(int id) { return "string"+id; }
    static String getString(String key,int id) { return key; }
    static String getString(String key) { return key; }
    static Object getContext() { return null; }
    static final int currentAccount=0;
    static CharSequence[] debugMenuItems() { DEBUG_ITEMS }
'''.replace('CONFIG_FLAGS', ','.join(config_flags)).replace('STRING_IDS', '\n'.join(
            f'static final int {name}={i};' for i, name in enumerate(string_ids))).replace('DEBUG_ITEMS', debug_items)
        harness = r'''
import java.util.*;
public class MaterialDialogItemsHarness {
    DEBUG_SETUP
    interface OnClickListener { void onClick(Object dialog,int which); }
    static class MaterialAlertDialogBuilder {
        CharSequence[] items;
        OnClickListener listener;
        int calls;
        void setItems(CharSequence[] items,OnClickListener listener) {
            this.items=items; this.listener=listener; calls++;
        }
        void measureRows() {
            // Reproduce ArrayAdapter.createViewFromResource's text conversion.
            for(var item:items) item.toString();
        }
    }
    static class AlertDialog {
        CharSequence[] items;
        OnClickListener onClickListener;
        SETUP
    }
    static void check(boolean condition,String message) {
        if(!condition) throw new AssertionError(message);
    }
    static void verify(CharSequence[] items,int... expectedIndices) {
        var dialog=new AlertDialog(); dialog.items=items;
        var builder=new MaterialAlertDialogBuilder();
        ArrayList<Integer> clicked=new ArrayList<>();
        dialog.onClickListener=(owner,index)->{
            check(owner==dialog,"callback receives original dialog"); clicked.add(index);
        };
        dialog.setMaterialDialogItems(builder);
        if(expectedIndices.length==0) {
            check(builder.calls==0,"no list for an absent or fully hidden menu"); return;
        }
        check(builder.calls==1 && builder.items.length==expectedIndices.length,"only visible rows reach adapter");
        builder.measureRows();
        for(int i=0;i<expectedIndices.length;i++) {
            check(builder.items[i]==items[expectedIndices[i]],"retain CharSequence identity and styling");
            builder.listener.onClick(builder,i);
            check(clicked.get(i)==expectedIndices[i],"preserve original action index");
        }
        dialog.onClickListener=null;
        builder.listener.onClick(builder,0);
    }
    public static void main(String[] args) {
        verify(new CharSequence[]{"Copy",null,"Share",null,"Open"},0,2,4);
        verify(new CharSequence[]{null,null,null,"Copy"},3);
        verify(new CharSequence[]{"Open","Share","Copy"},0,1,2);
        verify(new CharSequence[]{null,new StringBuilder("styled"),""},1,2);
        verify(new CharSequence[]{null,null});
        verify(new CharSequence[]{});
        verify(null);
        for(boolean debug:new boolean[]{false,true}) for(boolean internal:new boolean[]{false,true}) {
            BuildVars.DEBUG_VERSION=debug; BuildVars.DEBUG_PRIVATE_VERSION=internal;
            CharSequence[] menu=debugMenuItems();
            check(menu[8]==null,"real debug menu contains a hidden item in every build");
            var unfiltered=new MaterialAlertDialogBuilder(); unfiltered.setItems(menu,null);
            try { unfiltered.measureRows(); throw new AssertionError("original debug menu must reproduce crash"); }
            catch(NullPointerException expected) {}
            int[] visible=java.util.stream.IntStream.range(0,menu.length).filter(i->menu[i]!=null).toArray();
            verify(menu,visible);
        }
        System.out.println("OK");
    }
}
'''.replace('DEBUG_SETUP', debug_setup).replace('        SETUP', '        ' + setup)
        self.assertIsNotNone(shutil.which('javac'), 'Put JDK 17+ on PATH')
        with tempfile.TemporaryDirectory() as directory:
            java = Path(directory) / 'MaterialDialogItemsHarness.java'
            java.write_text(harness)
            compiled = subprocess.run(['javac', str(java)], capture_output=True, text=True)
            self.assertEqual(compiled.returncode, 0, compiled.stderr)
            result = subprocess.run(['java', '-cp', directory, java.stem],
                                    capture_output=True, text=True, timeout=30)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertIn('OK', result.stdout)


if __name__ == '__main__':
    unittest.main()
