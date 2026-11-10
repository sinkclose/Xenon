"""Regression checks for ordinary list/form header scroll coordinates."""
import unittest
from test_glass_blur import ROOT, method
from test_glass_chat_refresh import run_java


class SharedActivityHeadersTest(unittest.TestCase):
    def test_padding_change_preserves_visible_anchor_before_next_layout(self):
        source = (ROOT / "org/telegram/ui/Components/ActivityHeadersController.java").read_text(encoding="utf-8")
        production = method(source, "private boolean applyScrollPadding(")
        harness = r"""
public class HeaderPaddingAnchorHarness {
    static class View {
        int padding,bottom,height=600,firstTop,firstBottom;
        int getPaddingBottom(){return bottom;}
        void setPadding(int l,int t,int r,int b){padding=t;bottom=b;}
    }
    static class RecyclerView extends View {
        static int NO_POSITION=-1;
        static class LayoutManager {}
        LayoutManager layout;
        LayoutManager getLayoutManager(){return layout;}
    }
    static class LinearLayoutManager extends RecyclerView.LayoutManager {
        RecyclerView owner; View first=new View();
        int position,calls,restoredPosition,restoredOffset; boolean reverse;
        int findFirstVisibleItemPosition(){return position;}
        View findViewByPosition(int p){return p<0?null:first;}
        boolean getReverseLayout(){return reverse;}
        void scrollToPositionWithOffset(int p,int o){calls++;restoredPosition=p;restoredOffset=o;}
    }
    static class OrientationHelper {
        LinearLayoutManager manager;
        static OrientationHelper createVerticalHelper(LinearLayoutManager m){OrientationHelper h=new OrientationHelper();h.manager=m;return h;}
        int getStartAfterPadding(){return manager.owner.padding;}
        int getEndAfterPadding(){return manager.owner.height-manager.owner.bottom;}
        int getDecoratedStart(View v){return v.firstTop;}
        int getDecoratedEnd(View v){return v.firstBottom;}
    }
    static class Controller {
        View scrolling; int left,right;
        int logicalPaddingTop(){return scrolling.padding;}
        PRODUCTION
    }
    static void check(boolean ok){if(!ok)throw new AssertionError();}
    public static void main(String[] args){
        Controller c=new Controller();RecyclerView list=new RecyclerView();c.scrolling=list;
        LinearLayoutManager m=new LinearLayoutManager();list.layout=m;m.owner=list;
        check(c.applyScrollPadding(152,0));check(m.restoredPosition==0 && m.restoredOffset==0);
        m.position=12;list.padding=100;m.first.firstTop=91;
        check(c.applyScrollPadding(180,20));check(m.restoredPosition==12 && m.restoredOffset==-9);
        int calls=m.calls;check(!c.applyScrollPadding(180,20));check(m.calls==calls);
        m.reverse=true;m.first.firstBottom=600; // 20 beyond the padded end
        check(c.applyScrollPadding(180,40));check(m.restoredOffset==-20);
        m.position=-1;calls=m.calls;check(c.applyScrollPadding(190,40));check(m.calls==calls);
        c.scrolling=new View();check(c.applyScrollPadding(152,88));
        check(c.scrolling.padding==152 && c.scrolling.bottom==88);
    }
}
""".replace("PRODUCTION",production)
        run_java(self,"HeaderPaddingAnchorHarness",harness)

    def test_resume_keeps_controller_instead_of_reapplying_padding(self):
        source = (ROOT / "org/telegram/ui/Components/ActivityHeadersController.java").read_text(encoding="utf-8")
        production = method(source, "public boolean resume()")
        harness = r"""
public class HeaderResumeHarness {
    static class View {Object parent=new Object();Object getParent(){return parent;}}
    static class Bar {}
    static class NekoConfig {static boolean materialActivityHeaders=true,fade;static boolean fadeViewOtherActivities(){return fade;}}
    static class Fragment {View content;Bar bar;View getFragmentView(){return content;}Bar getActionBar(){return bar;}}
    static class Controller {
        boolean paused=true;int updates;View content=new View(),overlay=new View();Bar bar=new Bar();
        Fragment fragment=new Fragment();boolean update(){updates++;return true;}
        PRODUCTION
    }
    static void check(boolean ok){if(!ok)throw new AssertionError();}
    public static void main(String[] args){
        Controller c=new Controller();c.fragment.content=c.content;c.fragment.bar=c.bar;c.overlay.parent=c.content.parent;
        check(c.resume() && !c.paused && c.updates==1);
        c.paused=true;check(c.resume() && c.updates==2);
        NekoConfig.materialActivityHeaders=false;check(!c.resume() && c.updates==2);
        NekoConfig.fade=true;check(c.resume());
        c.fragment.content=new View();check(!c.resume());c.fragment.content=c.content;
        Object host=c.content.parent;c.content.parent=new Object();check(!c.resume());c.content.parent=host;
        c.overlay.parent=null;check(!c.resume());
    }
}
""".replace("PRODUCTION",production)
        run_java(self,"HeaderResumeHarness",harness)

    def test_partial_header_finishes_only_at_content_end_and_after_dragging(self):
        source = (ROOT / "org/telegram/ui/Components/ActivityHeadersController.java").read_text(encoding="utf-8")
        production = method(source, "private void finishCollapse()")
        production = production.replace("androidx.core.widget.NestedScrollView", "NestedScrollView")
        harness = r"""
public class HeaderSettleHarness {
    static class AndroidUtilities { static int dp(float n) { return Math.round(n); } }
    static class NekoConfig { static boolean materialActivityHeaders=true; }
    static class View { Object getParent() { return this; } }
    static class RecyclerView extends View {
        static int SCROLL_STATE_IDLE=0;
        int state,amount,calls;
        int getScrollState() { return state; }
        void smoothScrollBy(int x,int y) { amount=y; calls++; }
    }
    static class ScrollView extends View { int amount; void smoothScrollBy(int x,int y) { amount=y; } }
    static class NestedScrollView extends ScrollView {}
    static class Bar {
        boolean search,action;
        boolean isSearchFieldVisible() { return search; }
        boolean isActionModeShowed() { return action; }
    }
    static class Controller {
        boolean settlePosted,paused,atContentEnd,collapseGestureStarted;
        View overlay=new View(),scrolling;
        Bar bar=new Bar(); float distance;
        float distance() { return distance; }
        PRODUCTION
    }
    static void check(boolean ok) { if(!ok) throw new AssertionError(); }
    public static void main(String[] args) {
        Controller c=new Controller(); RecyclerView list=new RecyclerView(); c.scrolling=list;
        c.distance=40; c.finishCollapse(); check(list.calls==0);
        c.atContentEnd=true; c.finishCollapse(); check(list.calls==0); // loading is not a drag
        c.collapseGestureStarted=true; list.state=1; c.finishCollapse(); check(list.calls==0);
        list.state=0; c.finishCollapse(); check(list.calls==1 && list.amount==48);
        c.finishCollapse(); check(list.calls==1); // the same gesture cannot settle twice
        c.paused=true; c.finishCollapse(); check(list.calls==1); c.paused=false;
        c.bar.search=true; c.finishCollapse(); check(list.calls==1); c.bar.search=false;
        c.distance=0; c.finishCollapse(); check(list.calls==1);
        c.distance=88; c.finishCollapse(); check(list.calls==1);
        c.distance=40; ScrollView form=new ScrollView(); c.scrolling=form;
        c.finishCollapse(); check(form.amount==48);
    }
}
""".replace("PRODUCTION", production)
        run_java(self, "HeaderSettleHarness", harness)

    def test_short_content_keeps_enough_scroll_to_finish_collapse(self):
        source = (ROOT / "org/telegram/ui/Components/ActivityHeadersController.java").read_text(encoding="utf-8")
        production = method(source, "private static int collapseSpace(")
        harness = r"""
public class ShortHeaderContentHarness {
    PRODUCTION
    static void check(boolean ok) { if(!ok) throw new AssertionError(); }
    public static void main(String[] args) {
        check(collapseSpace(88,0,0)==88); // fits viewport at entry
        check(collapseSpace(88,40,0)==48); // reaches end partway through collapse
        check(collapseSpace(88,40,20)==28);
        check(collapseSpace(88,40,1000)==0); // long list needs no extra blank tail
        check(collapseSpace(88,88,0)==0);
        check(collapseSpace(88,0,-100)==188); // content shorter than the viewport
        check(collapseSpace(88,40,-140)==188); // tail stays stable during collapse
        check(collapseSpace(0,0,0)==0); // no Material header
    }
}
""".replace("PRODUCTION", production)
        run_java(self, "ShortHeaderContentHarness", harness)

    def test_theme_updates_cannot_cover_external_header(self):
        source = (ROOT / "org/telegram/ui/ActionBar/ActionBar.java").read_text(encoding="utf-8")
        production = method(source, "public void setBackgroundColor(int color)")
        # ActionBar also has an anonymous action-mode view with the same signature.
        start = source.index("    public void setBackgroundColor(int color) {\n        actionBarColor = color;")
        production = method(source[start:], "public void setBackgroundColor(int color)")
        harness = r"""
public class ExternalHeaderBackgroundHarness {
    static class Color { static int TRANSPARENT=0; }
    static class Drawable {}
    static class MenuDrawable extends Drawable { void setBackColor(int color) {} }
    static class ImageView { Drawable getDrawable() { return null; } }
    static class FrameLayout { int painted; void setBackgroundColor(int color) { painted=color; } }
    static class Bar extends FrameLayout {
        boolean blurredBackground,materialActivityHeader,externalHeaderFade;
        int actionBarColor;
        ImageView backButtonImageView;
        PRODUCTION
    }
    static void check(boolean ok) { if(!ok) throw new AssertionError(); }
    public static void main(String[] args) {
        Bar bar=new Bar(); bar.setBackgroundColor(123); check(bar.painted==123);
        bar.materialActivityHeader=true; bar.setBackgroundColor(456);
        check(bar.painted==0 && bar.actionBarColor==456);
        bar.materialActivityHeader=false; bar.externalHeaderFade=true;
        bar.setBackgroundColor(789); check(bar.painted==0 && bar.actionBarColor==789);
        bar.externalHeaderFade=false; bar.setBackgroundColor(123); check(bar.painted==123);
    }
}
""".replace("PRODUCTION", production)
        run_java(self, "ExternalHeaderBackgroundHarness", harness)

    def test_actionbar_titles_work_with_or_without_a_container(self):
        source = (ROOT / "org/telegram/ui/ActionBar/ActionBar.java").read_text(encoding="utf-8")
        production = method(source, "private boolean isExternalHeaderTitleChild(")
        harness = r"""
public class HeaderTitleChildrenHarness {
    static class View {}
    static class Bar {
        View titlesContainer,subtitleTextView,additionalSubtitleTextView;
        View[] titleTextView=new View[2];
        PRODUCTION
    }
    static void check(boolean value) { if (!value) throw new AssertionError(); }
    public static void main(String[] args) {
        Bar bar=new Bar();
        check(!bar.isExternalHeaderTitleChild(null));
        View back=new View(), menu=new View();
        bar.titleTextView[0]=new View();
        bar.titleTextView[1]=new View(); // title replacement animation
        bar.subtitleTextView=new View();
        check(bar.isExternalHeaderTitleChild(bar.titleTextView[0]));
        check(bar.isExternalHeaderTitleChild(bar.titleTextView[1]));
        check(bar.isExternalHeaderTitleChild(bar.subtitleTextView));
        check(!bar.isExternalHeaderTitleChild(back));
        check(!bar.isExternalHeaderTitleChild(menu));
        bar.titlesContainer=new View();
        check(bar.isExternalHeaderTitleChild(bar.titlesContainer));
        check(!bar.isExternalHeaderTitleChild(back));
    }
}
""".replace("PRODUCTION", production)
        run_java(self, "HeaderTitleChildrenHarness", harness)

    def test_capture_shares_the_displayed_node_instead_of_redrawing_edge_effects(self):
        source = (ROOT / "org/telegram/ui/Components/ActivityHeadersController.java").read_text(encoding="utf-8")
        production = method(source, "void capture(android.graphics.Canvas canvas, View child)")
        production = production.replace("android.graphics.Canvas", "Canvas").replace("android.os.SystemClock", "SystemClock")
        harness = r"""
public class HeaderNodeCaptureHarness {
    static class Canvas {}
    static class View { void draw(Canvas c) { throw new AssertionError("EdgeEffect advanced twice"); } }
    static class SystemClock { static long uptimeMillis() { return 123; } }
    static class FrameLayout {
        View node; int calls;
        boolean drawChild(Canvas c,View child,long time) { node=child; calls++; return true; }
    }
    static class Capture extends FrameLayout { PRODUCTION }
    public static void main(String[] args) {
        Capture capture=new Capture(); View view=new View();
        capture.capture(new Canvas(),view);
        if(capture.calls!=1 || capture.node!=view) throw new AssertionError();
    }
}
""".replace("PRODUCTION", production)
        run_java(self, "HeaderNodeCaptureHarness", harness)

    def test_scroll_forms_and_lists_expand_only_at_the_beginning(self):
        source = (ROOT / "org/telegram/ui/Components/ActivityHeadersController.java").read_text(encoding="utf-8")
        production = "\n".join(method(source, signature) for signature in (
            "private float distance(", "private int logicalPaddingTop("))
        production = production.replace("androidx.core.widget.NestedScrollView", "NestedScrollView")
        harness = r"""
public class SharedHeaderScrollHarness {
    static void check(boolean ok) { if (!ok) throw new AssertionError(); }
    static class AndroidUtilities { static int dp(float n) { return Math.round(n); } }
    static class View {
        int y,padding,scroll;
        int getTop() { return y; }
        int getPaddingTop() { return padding; }
        int getScrollY() { return scroll; }
    }
    static class ScrollView extends View {}
    static class NestedScrollView extends View {}
    static class RecyclerView extends View {
        static class LayoutManager { View first; View findViewByPosition(int p) { return first; } }
        LayoutManager manager=new LayoutManager();
        boolean back;
        LayoutManager getLayoutManager() { return manager; }
        boolean canScrollVertically(int d) { return back; }
    }
    static class BlurredRecyclerView extends RecyclerView { int topPadding; }
    static class Controller { View scrolling; PRODUCTION }
    public static void main(String[] args) {
        Controller c=new Controller();
        RecyclerView r=new RecyclerView(); c.scrolling=r;
        r.padding=200; r.manager.first=new View(); r.manager.first.y=200;
        check(c.distance()==0);
        r.manager.first.y=156; check(c.distance()==44);
        r.manager.first.y=220; check(c.distance()==0); // overscroll
        r.manager.first=null; r.back=true; check(c.distance()==88);
        r.back=false; check(c.distance()==0); // empty list
        c.scrolling=new ScrollView(); c.scrolling.scroll=70; check(c.distance()==70);
        c.scrolling=new NestedScrollView(); c.scrolling.scroll=40; check(c.distance()==40);
        BlurredRecyclerView b=new BlurredRecyclerView(); b.topPadding=152; b.padding=200;
        c.scrolling=b; check(c.logicalPaddingTop()==152); // capture inset is separate
        c.scrolling=r; check(c.logicalPaddingTop()==200);
    }
}
""".replace("PRODUCTION", production)
        run_java(self, "SharedHeaderScrollHarness", harness)


if __name__ == "__main__":
    unittest.main()
