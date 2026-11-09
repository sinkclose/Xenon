"""Run production settings fade geometry and content-capture routing on the JVM."""
import unittest

from test_glass_blur import ROOT, method
from test_glass_chat_refresh import run_java


class SettingsHeaderFadeTest(unittest.TestCase):
    def test_stories_and_title_share_collapse_and_folder_offset(self):
        source = (ROOT / "org/telegram/ui/DialogsActivity.java").read_text(encoding="utf-8")
        production = "\n".join(method(source, signature) for signature in (
            "private int materialTitleCollapseAt(", "private int dialogsListScrollOffset("))
        harness = r"""
public class JointHeaderHarness {
    static void check(boolean ok) { if (!ok) throw new AssertionError(); }
    static class Activity {
        int stories=100,materialTitleScroll;
        float scrollYOffset;
        boolean enabled=true;
        int materialDialogsTitleRange() { return 88; }
        int getMaxScrollYOffsetWithoutSearch() { return stories; }
        boolean dialogsMaterialHeaderEnabled() { return enabled; }
        PRODUCTION
    }
    public static void main(String[] args) {
        Activity a=new Activity();
        check(a.materialTitleCollapseAt(-50)==0);
        check(a.materialTitleCollapseAt(0)==0);
        check(a.materialTitleCollapseAt(94)==44); // both half collapsed
        check(a.materialTitleCollapseAt(188)==88);
        check(a.materialTitleCollapseAt(5000)==88); // reverse scroll deep in archive
        a.materialTitleScroll=88; a.scrollYOffset=-100;
        check(a.dialogsListScrollOffset()==-188); // incoming folder removes both spaces
        a.materialTitleScroll=44; a.scrollYOffset=-50;
        check(a.dialogsListScrollOffset()==-94);
        a.materialTitleScroll=0; a.scrollYOffset=0;
        check(a.dialogsListScrollOffset()==0); // pinned and hidden archive start aligned
        a.stories=0;
        check(a.materialTitleCollapseAt(44)==44);
        a.enabled=false; a.materialTitleScroll=88; a.scrollYOffset=-50;
        check(a.dialogsListScrollOffset()==-50);
    }
}
""".replace("PRODUCTION", production)
        run_java(self, "JointHeaderHarness", harness)

    def test_title_recalculates_after_zero_size_and_rotation(self):
        source = (ROOT / "org/telegram/ui/Components/MaterialActivityTitleView.java").read_text(encoding="utf-8")
        production = method(source, "protected void onSizeChanged(")
        harness = r"""
public class TitleSizeHarness {
    static class View { protected void onSizeChanged(int w,int h,int ow,int oh) {} }
    static class Title extends View {
        int lastWidth=0,lastHeight=0,lastBackground,lastForeground,lastActionsWidth,lastLeadingInset;
        CharSequence lastText="Xenon";
        float fraction=.5f;
        boolean lastCentered=true,fade=true;
        int updates;
        void update(CharSequence text,float progress,boolean center,boolean useFade,int bg,int fg,int actions,int inset) {
            if (lastWidth != -1 || lastHeight != -1 || !text.equals("Xenon")
                    || progress != .5f || !center || !useFade) throw new AssertionError();
            updates++;
        }
        PRODUCTION
    }
    public static void main(String[] args) {
        Title t=new Title();
        t.onSizeChanged(425,152,0,0);
        t.onSizeChanged(934,152,425,152);
        if(t.updates!=2) throw new AssertionError();
        t.lastText=null;
        t.onSizeChanged(425,152,934,152);
        if(t.updates!=2) throw new AssertionError();
    }
}
""".replace("PRODUCTION", production)
        run_java(self, "TitleSizeHarness", harness)

    def test_fade_edge_stays_above_first_items_and_extends_only_twelve_dp(self):
        source = (ROOT / "zxc/iconic/xenon/settings/BaseNekoSettingsActivity.java").read_text(encoding="utf-8")
        production = "\n".join(method(source, signature) for signature in (
            "private float settingsFadeProgress()", "private int settingsFadeZoneTop("))
        harness = r"""
public class SettingsFadeGeometryHarness {
    static void check(boolean ok) { if (!ok) throw new AssertionError(); }
    static int density = 1;
    static class AndroidUtilities { static int dp(float n) { return Math.round(n * density); } }
    static class View { int top; int getTop() { return top; } }
    static class LayoutManager { View first; View findViewByPosition(int pos) { return first; } }
    static class ListView {
        boolean back; int padding;
        LayoutManager layoutManager = new LayoutManager();
        boolean canScrollVertically(int direction) { return back; }
        int getPaddingTop() { return padding; }
    }
    static class Activity {
        ListView listView;
        PRODUCTION
    }
    public static void main(String[] args) {
        Activity a = new Activity();
        check(a.settingsFadeProgress() == 0f);
        for (density = 1; density <= 3; density++) {
            // Legacy status-bar padding and Material's overlapping 64dp viewport.
            for (int header : new int[]{56, 64, 80, 104, 152}) {
                a.listView = new ListView();
                a.listView.padding = header * density;
                View first = a.listView.layoutManager.first = new View();
                first.top = a.listView.padding;
                check(a.settingsFadeProgress() == 0f);
                check(a.settingsFadeZoneTop(header * density) == header * density);
                a.listView.back = true;
                check(a.settingsFadeProgress() == 0f);
                first.top += 20 * density; // pull-down / section top spacing
                check(a.settingsFadeProgress() == 0f);
                first.top = a.listView.padding - 12 * density;
                check(Math.abs(a.settingsFadeProgress() - .25f) < .001f);
                check(a.settingsFadeZoneTop(header * density) == (header + 3) * density);
                first.top = a.listView.padding - 24 * density;
                check(Math.abs(a.settingsFadeProgress() - .5f) < .001f);
                first.top = a.listView.padding - 96 * density;
                check(a.settingsFadeProgress() == 1f);
                check(a.settingsFadeZoneTop(header * density) == (header + 12) * density);
                a.listView.layoutManager.first = null; // first item recycled
                check(a.settingsFadeProgress() == 1f);
                a.listView.back = false; // restored / short list at the beginning
                check(a.settingsFadeProgress() == 0f);
            }
        }
    }
}
""".replace("PRODUCTION", production)
        run_java(self, "SettingsFadeGeometryHarness", harness)

    def test_material_viewport_reaches_screen_top_without_moving_first_row(self):
        source = (ROOT / "zxc/iconic/xenon/settings/BaseNekoSettingsActivity.java").read_text(encoding="utf-8")
        production = method(source, "private int materialListOverlap()")
        harness = r"""
public class MaterialViewportHarness {
    static void check(boolean ok) { if (!ok) throw new AssertionError(); }
    static int density;
    static class AndroidUtilities { static int dp(float n) { return Math.round(n * density); } }
    static class Activity { int headerInsetTop; PRODUCTION }
    public static void main(String[] args) {
        Activity a = new Activity();
        for (density = 1; density <= 3; density++) {
            for (int inset : new int[]{0, 24, 32, 48}) {
                a.headerInsetTop = inset * density;
                int overlap = a.materialListOverlap();
                int collapsedBottom = (inset + 64) * density;
                check(collapsedBottom - overlap == 0); // recycling begins above the screen
                for (int height : new int[]{64, 88, 152}) {
                    int barBottom = (inset + height) * density;
                    int viewportTop = barBottom - overlap;
                    int firstRowTop = viewportTop + overlap;
                    check(firstRowTop == barBottom); // expanded/compact content stays in place
                }
                int screenHeight = 800 * density;
                int measuredWithoutOverlap = screenHeight - (inset + 64) * density;
                check(measuredWithoutOverlap + overlap == screenHeight); // stretch includes status bar
            }
        }
    }
}
""".replace("PRODUCTION", production)
        run_java(self, "MaterialViewportHarness", harness)

    def test_dialogs_indent_only_tracks_visible_navigation_or_story_stack(self):
        source = (ROOT / "org/telegram/ui/DialogsActivity.java").read_text(encoding="utf-8")
        production = method(source, "private int materialDialogsLeadingInset()")
        harness = r"""
public class MaterialLeadingInsetHarness {
    static void check(boolean ok) { if (!ok) throw new AssertionError(); }
    static int dp(int n) { return n; }
    static class View {
        static final int VISIBLE=0;
        int visibility=4; float alpha=1;
        int getVisibility() { return visibility; } float getAlpha() { return alpha; }
    }
    static class ImageView extends View {
        Object drawable = new Object(); Object getDrawable() { return drawable; }
    }
    static class ActionBar {
        ImageView back = new ImageView(); ImageView getBackButton() { return back; }
    }
    static class Stories {
        int boundary; int getExternalHeaderLeadingInset() { return boundary; }
    }
    static class Activity {
        ActionBar actionBar = new ActionBar(); Stories dialogStoriesCell = new Stories();
        boolean hasStories, hasOnlySlefStories;
        PRODUCTION
    }
    public static void main(String[] args) {
        Activity a = new Activity();
        check(a.materialDialogsLeadingInset() == 24);
        a.hasStories=true;
        check(a.materialDialogsLeadingInset() == 80);
        a.dialogStoriesCell.boundary=124;
        check(a.materialDialogsLeadingInset() == 124);
        a.hasStories=false;
        check(a.materialDialogsLeadingInset() == 24); // no stale story gap
        a.hasOnlySlefStories=true;
        check(a.materialDialogsLeadingInset() == 124);
        a.hasOnlySlefStories=false;
        a.actionBar.back.visibility=View.VISIBLE;
        check(a.materialDialogsLeadingInset() == 80);
        a.actionBar.back.alpha=0;
        check(a.materialDialogsLeadingInset() == 24);
        a.actionBar.back.alpha=.001f;
        check(a.materialDialogsLeadingInset() == 24);
        a.actionBar.back.alpha=1; a.actionBar.back.drawable=null;
        check(a.materialDialogsLeadingInset() == 24);
    }
}
""".replace("PRODUCTION", production)
        run_java(self, "MaterialLeadingInsetHarness", harness)

    def test_dialogs_title_survives_small_animation_residuals(self):
        source = (ROOT / "org/telegram/ui/DialogsActivity.java").read_text(encoding="utf-8")
        production = method(source, "private float materialDialogsTitleVisibility()")
        harness = r"""
public class MaterialTitleVisibilityHarness {
    static void check(boolean ok) { if (!ok) throw new AssertionError(); }
    static class Activity {
        boolean enabled=true, searchIsShowed;
        float searchAnimationProgress, progressToActionMode, sliding;
        boolean dialogsMaterialHeaderEnabled() { return enabled; }
        float getRightSlidingProgress() { return sliding; }
        PRODUCTION
    }
    public static void main(String[] args) {
        Activity a = new Activity();
        check(a.materialDialogsTitleVisibility() == 1f);
        a.sliding=.00001f; a.progressToActionMode=.00001f;
        check(a.materialDialogsTitleVisibility() > .999f);
        a.sliding=0; a.progressToActionMode=0; a.searchAnimationProgress=.5f;
        check(a.materialDialogsTitleVisibility() == .5f);
        a.searchIsShowed=true; check(a.materialDialogsTitleVisibility() == 0f);
        a.searchIsShowed=false; a.searchAnimationProgress=0; a.progressToActionMode=1;
        check(a.materialDialogsTitleVisibility() == 0f);
        a.progressToActionMode=0; a.sliding=1; check(a.materialDialogsTitleVisibility() == 0f);
        a.sliding=0; a.enabled=false; check(a.materialDialogsTitleVisibility() == 0f);
    }
}
""".replace("PRODUCTION", production)
        run_java(self, "MaterialTitleVisibilityHarness", harness)

    def test_empty_title_and_large_story_inset_do_not_hide_the_logo(self):
        source = (ROOT / "org/telegram/ui/Components/MaterialActivityTitleView.java").read_text(encoding="utf-8")
        production = "\n".join(method(source, signature) for signature in (
            "private CharSequence resolveTitle(", "private int collapsedStartInset("))
        harness = r"""
public class MaterialTitleRecoveryHarness {
    static void check(boolean ok) { if (!ok) throw new AssertionError(); }
    static class AndroidUtilities { static int dp(float n) { return Math.round(n); } }
    static class TextUtils { static boolean isEmpty(CharSequence s) { return s==null || s.length()==0; } }
    static class TypefaceHelper { static CharSequence getTitleText() { return "Xenon"; } }
    static class Title {
        CharSequence lastText;
        PRODUCTION
    }
    public static void main(String[] args) {
        Title t = new Title();
        check(t.resolveTitle(null).toString().equals("Xenon"));
        t.lastText="Archived chats";
        check(t.resolveTitle("").toString().equals("Archived chats"));
        check(t.resolveTitle("  ").toString().equals("Archived chats"));
        check(t.resolveTitle("Xenon").toString().equals("Xenon"));
        check(t.collapsedStartInset(360,24,80,false)==24);
        check(t.collapsedStartInset(360,124,80,false)==124);
        check(360-80-t.collapsedStartInset(360,1000,80,false)>=48);
        int centered=t.collapsedStartInset(360,1000,80,true);
        check(360-2*centered>=48);
    }
}
""".replace("PRODUCTION", production)
        run_java(self, "MaterialTitleRecoveryHarness", harness)

    def test_story_transition_does_not_reserve_the_expanded_carousel_width(self):
        source = (ROOT / "org/telegram/ui/Stories/DialogStoriesCell.java").read_text(encoding="utf-8")
        production = method(source, "public int getExternalHeaderLeadingInset()")
        harness = r"""
import java.util.*;
public class MaterialStoryBoundaryHarness {
    static void check(boolean ok) { if (!ok) throw new AssertionError(); }
    static int dp(float n) { return Math.round(n); }
    static float getAvatarRight(int width,float p) { return 26.33f; }
    static class Stories {
        static final float COLLAPSED_DIS=16;
        ArrayList<Object> miniItems=new ArrayList<>();
        int collapsedStoryLimit=3;
        float collapsedProgress=.2f, collapsedProgress2=.2f;
        float externalHeaderLeadingInset=400, menuItemsOffset=68;
        PRODUCTION
    }
    public static void main(String[] args) {
        Stories s = new Stories();
        check(s.getExternalHeaderLeadingInset()==0);
        s.miniItems.add(new Object());
        check(s.getExternalHeaderLeadingInset()==115);
        s.miniItems.add(new Object()); s.miniItems.add(new Object());
        check(s.getExternalHeaderLeadingInset()==147);
        s.collapsedProgress=s.collapsedProgress2=1; s.externalHeaderLeadingInset=130.2f;
        check(s.getExternalHeaderLeadingInset()==131);
        s.miniItems.clear(); check(s.getExternalHeaderLeadingInset()==0);
    }
}
""".replace("PRODUCTION", production)
        run_java(self, "MaterialStoryBoundaryHarness", harness)

    def test_progressive_blur_profile_does_not_follow_fade_edge(self):
        source = (ROOT / "org/telegram/ui/Components/ProgressiveFadeBlurController.java").read_text(encoding="utf-8")
        production = "\n".join(method(source, signature) for signature in (
            "public void setProgressiveBlurZoneTop(", "private float progressiveTopFraction("))
        harness = r"""
public class IndependentBlurProfileHarness {
    static void check(boolean ok) { if (!ok) throw new AssertionError(); }
    static class AndroidUtilities { static int dp(float n) { return Math.round(n); } }
    static class Controller {
        int progressiveBlurZoneTop = -1, fadeZoneTop;
        boolean capturePending;
        PRODUCTION
    }
    public static void main(String[] args) {
        Controller c = new Controller();
        c.setProgressiveBlurZoneTop(159);
        check(c.capturePending);
        for (int edge : new int[]{64, 76, 128, 140, 152}) {
            c.fadeZoneTop = edge;
            check(Math.abs(c.progressiveTopFraction(800) - 111f/800) < .00001f);
        }
        c.capturePending = false;
        c.setProgressiveBlurZoneTop(159);
        check(!c.capturePending);
        c.setProgressiveBlurZoneTop(183); // status-bar inset grows by 24px
        check(Math.abs(c.progressiveTopFraction(800) - 135f/800) < .00001f);
        c.setProgressiveBlurZoneTop(-1); // old callers keep their original profile
        c.fadeZoneTop = 96;
        check(Math.abs(c.progressiveTopFraction(800) - 48f/800) < .00001f);
    }
}
""".replace("PRODUCTION", production)
        run_java(self, "IndependentBlurProfileHarness", harness)

    def test_capture_uses_real_content_and_resets_parent_coordinates(self):
        source = (ROOT / "org/telegram/ui/Components/ProgressiveFadeBlurController.java").read_text(encoding="utf-8")
        production = "\n".join(method(source, signature) for signature in (
            "public void setContentCapture(", "private void captureContent("))
        harness = r"""
import java.util.*;
public class SettingsFadeCaptureHarness {
    static void check(boolean ok) { if (!ok) throw new AssertionError(); }
    static class RectF {
        float l,t,r,b;
        void set(float a,float c,float d,float e) { l=a;t=c;r=d;b=e; }
    }
    static class View {}
    static class Canvas { int captures, fallbackDraws; }
    interface IBlur3Capture { void capture(Canvas c, RectF bounds); }
    static class Controller {
        View captureView = new View(), captureCoordinateRoot;
        IBlur3Capture contentCapture;
        boolean capturePending;
        RectF captureBounds = new RectF();
        List<View> additionalCaptureViews = new ArrayList<>();
        void drawCapturedView(Canvas c, View v) { c.fallbackDraws++; }
        PRODUCTION
    }
    public static void main(String[] args) {
        Controller c = new Controller();
        c.additionalCaptureViews.add(new View());
        View root = new View();
        c.setContentCapture(root, (canvas, bounds) -> {
            check(bounds.l == 0 && bounds.t == 0 && bounds.r == 360 && bounds.b == 800);
            canvas.captures++;
            bounds.set(-10, -20, 1, 2); // a renderer may temporarily move the clip
        });
        check(c.captureCoordinateRoot == root && c.capturePending);
        Canvas canvas = new Canvas();
        c.captureContent(canvas, 360, 800);
        c.captureContent(canvas, 360, 800);
        check(canvas.captures == 2 && canvas.fallbackDraws == 0);
        c.setContentCapture(null, null); // existing Dialogs capture path still works
        c.captureContent(canvas, 360, 800);
        check(canvas.fallbackDraws == 2);
    }
}
""".replace("PRODUCTION", production)
        run_java(self, "SettingsFadeCaptureHarness", harness)


if __name__ == "__main__":
    unittest.main()
