"""Bottom-bar captures must include background beyond the Gaussian blur's edges."""

import unittest

from test_glass_blur import ROOT, method
from test_glass_chat_refresh import run_java


class GlassCapturePaddingTest(unittest.TestCase):
    def test_bottom_tabs_keep_blur_padding_with_liquid_glass_enabled(self):
        classes = []
        for name in ("SettingsActivity", "ProfileActivity", "CallLogActivity", "ContactsActivity",
                     "StatisticActivity", "TopicsFragment", "DialogsActivity"):
            source = (ROOT / f"org/telegram/ui/{name}.java").read_text()
            capture = method(source, "private void blur3_InvalidateBlur()")
            capture = capture.replace("private void blur3_InvalidateBlur()", "public void capture()")
            classes.append(f"static class {name} extends Screen {{\n{capture}\n}}")
        harness = r'''
import java.util.*;
public class CapturePaddingHarness {
    static void check(boolean value, String message) { if(!value) throw new AssertionError(message); }
    static float density=1;
    static int dp(float value) { return Math.round(value*density); }
    static float lerp(float a,float b,float t) { return a+(b-a)*t; }
    static class Build {
        static class VERSION { static int SDK_INT=33; }
        static class VERSION_CODES { static int S=31; }
    }
    static class LiteMode {
        static int FLAG_LIQUID_GLASS=1; static boolean liquid;
        static boolean isEnabled(int flag) { return liquid; }
    }
    static class AndroidUtilities { static int navigationBarHeight; }
    static class View {
        static final int VISIBLE=0;
        int width=360,height=800;
        int getMeasuredWidth() { return width; } int getMeasuredHeight() { return height; }
        int getVisibility() { return VISIBLE; }
        float getAnimatedHeightWithPadding(int padding) { return padding; }
        int getSumHeightOfAllVisibleChild() { return 0; }
    }
    static class RectF {
        float left,top,right,bottom;
        void set(float l,float t,float r,float b) { left=l; top=t; right=r; bottom=b; }
        void inset(float x,float y) { left+=x; top+=y; right-=x; bottom-=y; }
    }
    static class Suppressor {
        int count;
        void setupRenderNodes(Object positions,int count) { this.count=count; }
        void invalidateResultRenderNodes(Object capture,int w,int h) {}
    }
    static class Source { void setSize(int w,int h) {} void updateDisplayListIfNeeded() {} }
    static class Animator { float getFloatValue() { return 0; } }
    static class DialogStoriesCell { static int HEIGHT_IN_DP=60; }
    static abstract class Screen {
        static int MAIN_TABS_MARGIN=8, MAIN_TABS_HEIGHT=56, SEARCH_FIELD_HEIGHT=40, SEARCH_TABS_HEIGHT=40;
        Suppressor scrollableViewNoiseSuppressor=new Suppressor();
        View fragmentView=new View(),actionBar=new View(),topPanelLayout=new View();
        View filterTabsView,searchTabsAndFiltersLayout,commentView,chatInputViewsContainer;
        Screen parentDialogsActivity;
        Source iBlur3SourceGlassFrosted,iBlur3SourceGlass;
        Object iBlur3Capture;
        ArrayList<RectF> iBlur3Positions=new ArrayList<>();
        RectF iBlur3PositionMainTabs=new RectF(),iBlur3PositionActionBar=new RectF();
        int navigationBarHeight,iosBottomSearchInset;
        boolean hasMainTabs=true,hasStories,searching,iosSearch;
        float scrollYOffset;
        Animator animatorSearchVisible=new Animator();
        int getFilterTabsHeight() { return 0; } int getInlineSearchHeight() { return 0; }
        int getSearchTopBarHeight() { return 0; } int calculateListViewPaddingBottom() { return dp(100); }
        boolean bottomFilterTabs() { return false; } boolean isIosBottomSearch() { return iosSearch; }
        View getActionBar() { return actionBar; }
        abstract void capture();
    }
    CLASSES
    static void verify(Screen screen,int bottom) {
        RectF capture=screen.iBlur3PositionMainTabs;
        int top=bottom-dp(Screen.MAIN_TABS_HEIGHT);
        check(capture.top<=top-dp(48), screen.getClass().getSimpleName()+": missing pixels above glass");
        check(capture.bottom>=bottom+dp(48), screen.getClass().getSimpleName()+": missing pixels below glass");
        check(capture.left==0 && capture.right==screen.fragmentView.width, "Changed horizontal capture");
    }
    public static void main(String[] args) {
        for(float scale:new float[]{1,1.5f,2,3,4}) for(boolean liquid:new boolean[]{false,true}) {
            density=scale; LiteMode.liquid=liquid;
            for(Screen screen:new Screen[]{new SettingsActivity(),new ProfileActivity(),new CallLogActivity(),
                    new ContactsActivity(),new StatisticActivity(),new TopicsFragment(),new DialogsActivity()}) {
                screen.fragmentView.width=dp(360); screen.fragmentView.height=dp(800);
                screen.actionBar.height=dp(56);
                screen.navigationBarHeight=AndroidUtilities.navigationBarHeight=dp(24);
                screen.parentDialogsActivity=screen;
                screen.capture();
                verify(screen,dp(800)-dp(24)-dp(Screen.MAIN_TABS_MARGIN));
                check(screen.scrollableViewNoiseSuppressor.count==2,"Lost bottom capture");
            }
            DialogsActivity search=new DialogsActivity();
            search.fragmentView.width=dp(360); search.fragmentView.height=dp(800);
            search.iosSearch=search.searching=true; search.iosBottomSearchInset=dp(280);
            search.capture();
            int bottom=dp(800)-dp(280);
            check(search.iBlur3PositionMainTabs.top<=bottom-dp(72+Screen.SEARCH_TABS_HEIGHT+12)-dp(48),
                    "Bottom search lost upper blur padding");
            check(search.iBlur3PositionMainTabs.bottom>=bottom+dp(48),"Bottom search lost lower blur padding");
        }
    }
}
'''.replace("CLASSES", "\n".join(classes))
        run_java(self, "CapturePaddingHarness", harness)


if __name__ == "__main__":
    unittest.main()
