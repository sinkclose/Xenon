"""Exercise page lifecycle and recycled containers with production pager methods."""

import unittest

from test_glass_blur import ROOT, method
from test_glass_chat_refresh import run_java


class MainTabTransitionsTest(unittest.TestCase):
    def test_only_physical_pages_receive_lifecycle_callbacks(self):
        pager = (ROOT / 'org/telegram/ui/Components/ViewPagerFixed.java').read_text()
        activity = (ROOT / 'org/telegram/ui/ViewPagerActivity.java').read_text()
        pager_methods = '\n'.join(method(pager, signature) for signature in (
            'public float getPositionVisibility(', 'public float getPageVisibility(',
            'public float getPositionAnimated()', 'public float getCurrentPositionAlpha()',
            'public float getNextPositionAlpha()',
        ))
        harness = r'''
public class MainTabsLifecycleHarness {
    static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    static void near(float a, float b) { check(Math.abs(a-b)<.0001f, a+" != "+b); }
    static class MathUtils { static float clamp(float v,float l,float h) { return Math.max(l,Math.min(h,v)); } }
    static class Utilities { static float clamp(float v,float h,float l) { return Math.max(l,Math.min(h,v)); } }
    static class View {
        static final int VISIBLE=0;
        float translation; int visibility=VISIBLE;
        float getTranslationX() { return translation; }
        int getVisibility() { return visibility; }
    }
    static class Pager {
        View[] viewPages={new View(),new View()};
        int currentPosition,nextPosition,width=1000;
        int getMeasuredWidth() { return width; }
        float getAvailableTranslationX() { return width; }
        int getCurrentPosition() { return currentPosition; }
        PAGER_METHODS
    }
    static class Fragment {
        Object fragmentView=new Object();
        int resumes,pauses,fullyVisible,starts;
        void onResume() { resumes++; } void onPause() { pauses++; }
        void onTransitionAnimationStart(boolean a,boolean b) { starts++; }
        void onTransitionAnimationProgress(boolean a,float p) { }
        void onTransitionAnimationEnd(boolean a,boolean b) { }
        void onBecomeFullyVisible() { fullyVisible++; } void onBecomeFullyHidden() { }
    }
    static class State {
        Fragment fragment=new Fragment();
        boolean isResumed,isInAnimation,isFullyVisible; float lastVisibility;
        VISIBILITY_METHOD
    }
    public static void main(String[] args) {
        for(int from=0;from<6;from++) for(int to=0;to<6;to++) {
            if(from==to) continue;
            Pager pager=new Pager(); pager.currentPosition=from; pager.nextPosition=to;
            State[] states=new State[6];
            for(int i=0;i<6;i++) states[i]=new State();
            states[from].isResumed=states[from].isFullyVisible=true; states[from].lastVisibility=1;
            for(int frame=0;frame<=120;frame++) {
                float p=frame/120f, direction=to>from?1:-1;
                pager.viewPages[0].translation=-direction*1000*p;
                pager.viewPages[1].translation=direction*1000*(1-p);
                near(pager.getPageVisibility(from),1-p);
                near(pager.getPageVisibility(to),p);
                // Preserve the old indicator/background interpolation exactly.
                float indicator=from*(1-p)+to*p;
                near(pager.getPositionAnimated(),indicator);
                near(pager.getPositionVisibility(0),Math.max(0,1-Math.abs(indicator)));
                for(int i=0;i<6;i++) states[i].setVisibility(pager.getPageVisibility(i),1,true,true);
                if(frame==1) check(states[to].fragment.resumes==1,"Incoming page resumed too late");
            }
            for(int i=0;i<6;i++) if(i!=from&&i!=to) {
                Fragment f=states[i].fragment;
                check(f.resumes==0&&f.pauses==0&&f.fullyVisible==0&&f.starts==0,"Hidden tab received lifecycle callbacks");
            }
            check(states[from].fragment.pauses==1,"Outgoing page did not pause");
            check(states[to].fragment.resumes==1&&states[to].fragment.fullyVisible==1,"Incoming lifecycle mismatch");
            // Cancellation/settling must ignore the stale next-position index.
            pager.viewPages[0].translation=0; pager.viewPages[1]=null;
            near(pager.getPageVisibility(from),1); near(pager.getPageVisibility(to),0);
            pager.width=0;
            near(pager.getPageVisibility(from),1); near(pager.getPageVisibility(to),0);
        }
    }
}
'''.replace('PAGER_METHODS', pager_methods).replace(
            'VISIBILITY_METHOD', method(activity, 'public void setVisibility(float visibilityByViewPage,'))
        run_java(self, 'MainTabsLifecycleHarness', harness)

    def test_recycled_page_is_bound_before_attachment(self):
        source = (ROOT / 'org/telegram/ui/Components/ViewPagerFixed.java').read_text()
        harness = r'''
import java.util.*;
public class MainTabsAttachHarness {
    static void check(boolean v,String m) { if(!v)throw new AssertionError(m); }
    static class View {
        static final int VISIBLE=0,GONE=8;
        ViewGroup parent; boolean stale=true; int staleAttachments,attachments; float translation;
        Object getParent() { return parent; }
        void setTranslationX(float value) { translation=value; }
        void setVisibility(int v) { }
    }
    static class ViewGroup {
        void removeView(View v) { v.parent=null; }
        void addView(View v) { v.parent=this; v.attachments++; if(v.stale)v.staleAttachments++; }
    }
    static class Adapter {
        int type,bindings;
        int getItemCount() { return 6; }
        int getItemViewType(int p) { return type; }
        View createView(int t) { return new View(); }
        void bindView(View v,int p,int t) {
            check(v.parent==null,"Stale page was attached before binding");
            check(v.translation==1000,"Incoming page was not positioned offscreen");
            v.stale=false; bindings++;
        }
    }
    static class Pager extends ViewGroup {
        int currentPosition,nextPosition=3;
        int[] viewTypes=new int[2]; View[] viewPages=new View[2];
        Map<Integer,View> viewsByType=new HashMap<>(); Adapter adapter=new Adapter();
        int getMeasuredWidth() { return 1000; }
        UPDATE_METHOD
    }
    public static void main(String[] args) {
        Pager p=new Pager(); View cached=new View(); p.viewsByType.put(0,cached);
        p.updateViewForIndex(1);
        check(p.viewPages[1]==cached&&cached.attachments==1&&cached.staleAttachments==0,"Cached page attached stale contents");
        // Replacing an existing slot with another view type has the same contract.
        p.adapter.type=1; View other=new View(); p.viewsByType.put(1,other);
        p.updateViewForIndex(1);
        check(cached.parent==null&&other.parent==p,"View-type replacement lost its page");
        check(other.attachments==1&&other.staleAttachments==0,"Replacement attached stale contents");
        check(p.adapter.bindings==2,"Binding count changed");
    }
}
'''.replace('UPDATE_METHOD', method(source, 'private void updateViewForIndex(int index)'))
        run_java(self, 'MainTabsAttachHarness', harness)

    def test_rebinding_same_fragment_keeps_its_children(self):
        source = (ROOT / 'org/telegram/ui/ViewPagerActivity.java').read_text()
        harness = r'''
import java.util.*;
public class MainTabsRebindHarness {
    static void check(boolean v,String m) { if(!v)throw new AssertionError(m); }
    static class View {
        FrameLayout parent; Object background;
        Object getParent() { return parent; } Object getBackground() { return background; }
        void setBackgroundColor(int c) { background=c; }
    }
    static class FrameLayout extends View {
        List<View> children=new ArrayList<>(); int removals,adds;
        int getChildCount() { return children.size(); }
        void removeAllViews() { removals++; for(View v:children)v.parent=null; children.clear(); }
        void addView(View v,Object... params) { adds++; children.add(v); v.parent=this; }
    }
    static class ActionBar extends View { boolean shouldAddToContainer() { return true; } }
    static class BaseFragment {
        View fragmentView=new View(); ActionBar actionBar=new ActionBar(); int creations;
        void onFragmentCreate() { } void setParentLayout(Object p) { }
        View getFragmentView() { return fragmentView; } ActionBar getActionBar() { return actionBar; }
        void performCreateView(Object c) { creations++; fragmentView=new View(); }
        void setTitleOverlayText(Object t,int id,Object action) { }
        boolean hasOwnBackground() { return false; }
    }
    static class FragmentState {
        BaseFragment fragment; boolean onCreateCalled;
        FragmentState(BaseFragment f) { fragment=f; }
    }
    static class AndroidUtilities {
        static void removeFromParent(View v) { if(v.parent!=null) { v.parent.children.remove(v); v.parent=null; } }
    }
    static class Theme { static int key_windowBackgroundWhite; static int getColor(int k) { return k; } }
    static class LayoutHelper { static int MATCH_PARENT=-1; static Object createFrame(int a,int b) { return null; } }
    static class ViewCompat { static void requestApplyInsets(View v) { } }
    static class Adapter {
        Map<Integer,FragmentState> fragmentsArr=new HashMap<>();
        Object context,titleOverlay,titleOverlayAction; int titleOverlayId;
        BaseFragment createBaseFragmentAt(int p) { return new BaseFragment(); }
        Object getParentLayout() { return null; }
        void checkSystemBarColors() { } void checkFragmentsVisibility() { }
        BIND_METHOD
    }
    public static void main(String[] args) {
        Adapter a=new Adapter(); FrameLayout container=new FrameLayout();
        a.bindView(container,0,0);
        BaseFragment first=a.fragmentsArr.get(0).fragment;
        check(container.children.get(0)==first.fragmentView&&container.children.get(1)==first.actionBar,"Child order changed");
        int removals=container.removals,adds=container.adds;
        a.bindView(container,0,0);
        check(container.removals==removals&&container.adds==adds,"Unchanged fragment was detached and reattached");
        a.bindView(container,1,0);
        check(first.fragmentView.parent==null&&first.actionBar.parent==null,"Old children remained attached");
        a.bindView(container,0,0);
        check(container.children.get(0)==first.fragmentView&&container.children.get(1)==first.actionBar,"Cached fragment was not restored");
        check(first.creations==0,"Cached fragment was recreated");
    }
}
'''.replace('BIND_METHOD', method(source, 'public void bindView(View view, int position, int viewType)'))
        run_java(self, 'MainTabsRebindHarness', harness)


if __name__ == '__main__':
    unittest.main()
