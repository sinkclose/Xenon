"""Exercise global bulletin routing with production Java methods."""

import unittest

from test_glass_blur import ROOT, method
from test_glass_chat_refresh import run_java


class GlobalBulletinLifecycleTest(unittest.TestCase):
    def test_missing_host_is_noop_and_live_hosts_keep_their_container(self):
        source = (ROOT / "org/telegram/ui/Components/BulletinFactory.java").read_text()
        factory_methods = "\n".join(method(source, signature) for signature in (
            "public static BulletinFactory of(BaseFragment fragment)",
            "public static BulletinFactory of(FrameLayout containerLayout,",
            "public static boolean canShowBulletin(",
            "public static BulletinFactory global()",
            "private BulletinFactory(BaseFragment fragment)",
            "private BulletinFactory(FrameLayout containerLayout,",
            "public Bulletin create(Bulletin.Layout layout, int duration)",
            "public Context getContext()",
        ))
        bulletin = (ROOT / "org/telegram/ui/Components/Bulletin.java").read_text()
        make = method(bulletin, "public static Bulletin make(@NonNull FrameLayout")
        utilities = (ROOT / "org/telegram/messenger/AndroidUtilities.java").read_text()
        running = method(utilities, "public static boolean isActivityRunning(")
        harness = r'''
public class GlobalBulletinHarness {
    @interface NonNull {}
    static void check(boolean value, String reason) {
        if (!value) throw new AssertionError(reason);
    }
    static class Context {}
    static class Activity extends Context {
        boolean finishing, destroyed;
        boolean isFinishing() { return finishing; }
        boolean isDestroyed() { return destroyed; }
    }
    static class AndroidUtilities { RUNNING }
    static class ApplicationLoader { static Context applicationContext = new Context(); }
    static class Theme { static class ResourcesProvider {} }
    static class FrameLayout {
        Context context;
        FrameLayout(Context context) { this.context = context; }
        Context getContext() { return context; }
    }
    static class StoryViewer {
        boolean attachedToParent() { return false; }
        FrameLayout getContainerForBulletin() { return null; }
        Theme.ResourcesProvider getResourceProvider() { return null; }
    }
    static class BaseFragment {
        Activity activity = new Activity();
        FrameLayout container = new FrameLayout(activity);
        Object visibleDialog;
        Activity getParentActivity() { return activity; }
        FrameLayout getLayoutContainer() { return container; }
        StoryViewer getLastStoryViewer() { return null; }
        Theme.ResourcesProvider getResourceProvider() { return null; }
    }
    static class BottomSheet {
        FrameLayout container;
        BottomSheet(Context context) { container = new FrameLayout(context); }
    }
    static class LaunchActivity {
        static BaseFragment last;
        static BaseFragment getSafeLastFragment() { return last; }
    }
    static class Bulletin {
        static class Layout {}
        static class EmptyBulletin extends Bulletin {
            EmptyBulletin() { super(null, null, null, 0); }
        }
        static class BulletinWindow {
            static FrameLayout make(Context context) {
                throw new AssertionError("global fallback must not open a tokenless dialog");
            }
        }
        FrameLayout container;
        BaseFragment fragment;
        boolean shown;
        Bulletin(BaseFragment fragment, FrameLayout container, Layout layout, int duration) {
            this.fragment = fragment;
            this.container = container;
        }
        MAKE
        static Bulletin make(BaseFragment fragment, Layout layout, int duration) {
            return new Bulletin(fragment, fragment.container, layout, duration);
        }
        Bulletin show() { shown = container != null; return this; }
    }
    static class BulletinFactory {
        final BaseFragment fragment;
        final FrameLayout containerLayout;
        final Theme.ResourcesProvider resourcesProvider;
        FACTORY_METHODS
    }
    public static void main(String[] args) {
        for (int condition = 0; condition < 5; condition++) {
            BaseFragment f = new BaseFragment();
            LaunchActivity.last = f;
            switch (condition) {
                case 0: LaunchActivity.last = null; break;
                case 1: f.activity = null; break;
                case 2: f.container = null; break;
                case 3: f.activity.finishing = true; break;
                case 4: f.activity.destroyed = true; break;
            }
            BulletinFactory factory = BulletinFactory.global();
            check(factory.getContext() == ApplicationLoader.applicationContext,
                    "layouts still need a context when the UI host is unavailable");
            Bulletin b = factory.create(new Bulletin.Layout(), 1000).show();
            check(b instanceof Bulletin.EmptyBulletin && !b.shown,
                    "unavailable host must return an empty bulletin: " + condition);
        }
        BaseFragment f = new BaseFragment();
        LaunchActivity.last = f;
        BulletinFactory factory = BulletinFactory.global();
        Bulletin b = factory.create(new Bulletin.Layout(), 1000).show();
        check(b.fragment == f && b.container == f.container && b.shown,
                "a live fragment must still display its bulletin");
        check(factory.getContext() == f.activity, "live layouts use the activity context");
        BottomSheet sheet = new BottomSheet(f.activity);
        f.visibleDialog = sheet;
        b = BulletinFactory.global().create(new Bulletin.Layout(), 1000).show();
        check(b.container == sheet.container && b.shown,
                "bulletins above a bottom sheet must keep using its container");
    }
}
'''.replace("RUNNING", running).replace("MAKE", make).replace("FACTORY_METHODS", factory_methods)
        run_java(self, "GlobalBulletinHarness", harness)


if __name__ == "__main__":
    unittest.main()
