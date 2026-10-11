"""Exercise floating toolbar lifecycle guards and invalid-token recovery as Java."""

import unittest

from test_glass_blur import ROOT, method
from test_glass_chat_refresh import run_java


class FloatingToolbarLifecycleTest(unittest.TestCase):
    def test_delayed_callbacks_skip_inactive_views_and_finished_modes(self):
        source = (ROOT / 'org/telegram/ui/ActionBar/FloatingActionMode.java').read_text()
        active = method(source, 'private boolean isViewStillActive()')
        moving = method(source[source.index('private final Runnable mMovingOff'):],
                        'public void run()')
        hiding = method(source[source.index('private final Runnable mHideOff'):],
                        'public void run()')
        helper = method(source, 'private static final class FloatingToolbarVisibilityHelper')
        harness = r'''
public class FloatingCallbackHarness {
    static void check(boolean value, String reason) {
        if (!value) throw new AssertionError(reason);
    }
    static class Context { boolean finishing, destroyed; }
    static class AndroidUtilities {
        static boolean isSafeToShow(Context c) { return !c.finishing && !c.destroyed; }
    }
    static class View {
        static final int VISIBLE = 0;
        boolean attached = true, shown = true;
        Object token = new Object();
        int visibility;
        Context context = new Context();
        boolean isAttachedToWindow() { return attached; }
        Object getWindowToken() { return token; }
        Context getContext() { return context; }
        int getWindowVisibility() { return visibility; }
        boolean isShown() { return shown; }
    }
    static class FloatingToolbar {
        int shows;
        public void show() { shows++; }
        public void hide() { }
        public void dismiss() { }
    }
    HELPER
    static class Mode {
        View mOriginatingView = new View();
        FloatingToolbar toolbar = new FloatingToolbar();
        FloatingToolbarVisibilityHelper mFloatingToolbarVisibilityHelper =
                new FloatingToolbarVisibilityHelper(toolbar);
        Runnable moving = new Runnable() { MOVING };
        Runnable hiding = new Runnable() { HIDING };
        ACTIVE
    }
    public static void main(String[] args) {
        for (int condition = 0; condition < 8; condition++) {
            for (boolean move : new boolean[] {true, false}) {
                Mode mode = new Mode();
                mode.mFloatingToolbarVisibilityHelper.activate();
                View view = mode.mOriginatingView;
                switch (condition) {
                    case 1: view.attached = false; break;
                    case 2: view.token = null; break;
                    case 3: view.context.finishing = true; break;
                    case 4: view.context.destroyed = true; break;
                    case 5: view.visibility = 4; break;
                    case 6: view.shown = false; break;
                    case 7: mode.mFloatingToolbarVisibilityHelper.deactivate(); break;
                }
                (move ? mode.moving : mode.hiding).run();
                check(mode.toolbar.shows == (condition == 0 ? 1 : 0),
                        "delayed callback must respect lifecycle condition " + condition);
            }
        }
    }
}
'''.replace('HELPER', helper).replace('MOVING', moving).replace('HIDING', hiding).replace('ACTIVE', active)
        run_java(self, 'FloatingCallbackHarness', harness)

    def test_invalid_token_cleanup_and_retry(self):
        source = (ROOT / 'org/telegram/ui/ActionBar/FloatingToolbar.java').read_text()
        popup = source[source.index('private final class FloatingToolbarPopup'):]
        outer_show = method(source, 'private void doShow()')
        popup_methods = '\n'.join(method(popup, signature) for signature in (
            'public boolean show(Rect contentRectOnScreen)', 'public boolean isShowing()',
            'public boolean isHidden()'))
        harness = r'''
import java.util.*;
public class FloatingPopupHarness {
    static void check(boolean value, String reason) {
        if (!value) throw new AssertionError(reason);
    }
    static class Context { boolean finishing, destroyed; }
    static class AndroidUtilities {
        static boolean isSafeToShow(Context c) { return !c.finishing && !c.destroyed; }
    }
    static class View {
        boolean attached = true;
        Object token = new Object();
        Context context = new Context();
        int removedCallbacks;
        boolean isAttachedToWindow() { return attached; }
        Object getWindowToken() { return token; }
        Context getContext() { return context; }
        void removeCallbacks(Runnable r) { removedCallbacks++; }
    }
    static class Rect { void set(Rect other) { } }
    static class Point { int x, y; }
    static class MenuItem { }
    static class Gravity { static final int NO_GRAVITY = 0; }
    static class WindowManager {
        static class BadTokenException extends RuntimeException { }
    }
    static class FileLog { static int errors; static void e(Exception e) { errors++; } }
    static class PopupWindow {
        boolean showing, rejectToken;
        RuntimeException unexpected;
        int attempts, dismissals;
        void showAtLocation(View parent, int gravity, int x, int y) {
            attempts++;
            // Android sets its showing flag before WindowManager.addView can fail.
            showing = true;
            if (unexpected != null) throw unexpected;
            if (rejectToken) throw new WindowManager.BadTokenException();
        }
        void dismiss() { showing = false; dismissals++; }
    }
    static class Toolbar {
        View mWindowView = new View();
        Popup mPopup = new Popup(mWindowView);
        Rect mContentRect = new Rect(), mPreviousContentRect = new Rect();
        Object mMenu, mMenuItemClickListener;
        List<MenuItem> mShowingMenuItems;
        Comparator<MenuItem> mMenuItemComparator = (a, b) -> 0;
        boolean mWidthChanged, orientationRegistered = true;
        int mSuggestedWidth;
        List<MenuItem> getVisibleAndEnabledMenuItems(Object menu) { return new ArrayList<>(); }
        boolean isCurrentlyShowing(List<MenuItem> items) { return true; }
        void unregisterOrientationHandler() { orientationRegistered = false; }
        void dismiss() { unregisterOrientationHandler(); mPopup.mDismissed = true; }
        OUTER_SHOW
    }
    static class Popup {
        View mParent, mContentContainer = new View();
        PopupWindow mPopupWindow = new PopupWindow();
        Point mCoordsOnWindow = new Point();
        Runnable mPreparePopupContentRTLHelper = () -> {};
        boolean mHidden, mDismissed = true;
        int shows, touchable, zeroed;
        Popup(View parent) { mParent = parent; }
        void cancelDismissAndHideAnimations() { }
        void cancelOverflowAnimations() { }
        void refreshCoordinatesAndOverflowDirection(Rect rect) { }
        void preparePopupContent() { }
        void setTouchableSurfaceInsetsComputer() { touchable++; }
        void setZeroTouchableSurface() { zeroed++; }
        void runShowAnimation() { shows++; }
        void dismiss() { mDismissed = true; }
        void layoutMenuItems(List<MenuItem> items, Object listener, int width) { }
        void updateCoordinates(Rect rect) { }
        POPUP_METHODS
    }
    public static void main(String[] args) {
        for (int condition = 0; condition < 4; condition++) {
            Toolbar t = new Toolbar();
            switch (condition) {
                case 0: t.mWindowView.attached = false; break;
                case 1: t.mWindowView.token = null; break;
                case 2: t.mWindowView.context.finishing = true; break;
                case 3: t.mWindowView.context.destroyed = true; break;
            }
            t.doShow();
            check(t.mPopup.mPopupWindow.attempts == 0 && !t.orientationRegistered,
                    "direct show must reject an invalid host and remove its listener");
        }
        Toolbar t = new Toolbar();
        Popup p = t.mPopup;
        p.mHidden = true;
        p.mPopupWindow.rejectToken = true;
        t.doShow();
        check(!p.isShowing() && !p.isHidden() && p.mDismissed,
                "failed popup must return to dismissed state");
        check(!p.mPopupWindow.showing && p.mPopupWindow.dismissals == 1,
                "failed native popup must be dismissed before retry");
        check(p.shows == 0 && p.touchable == 0 && p.zeroed == 1,
                "failed popup must not animate or install touch handlers");
        check(p.mContentContainer.removedCallbacks == 1 && !t.orientationRegistered && FileLog.errors == 1,
                "failed popup must clear pending RTL work and its layout listener, and log the error");
        p.mPopupWindow.rejectToken = false;
        t.orientationRegistered = true;
        t.doShow();
        check(p.isShowing() && p.mPopupWindow.showing && p.shows == 1 && p.touchable == 1,
                "a later valid show must recover normally");
        t.doShow();
        check(p.mPopupWindow.attempts == 2, "an already visible popup must not be added twice");

        Popup broken = new Popup(new View());
        broken.mPopupWindow.unexpected = new IllegalStateException("unrelated error");
        boolean propagated = false;
        try { broken.show(new Rect()); } catch (IllegalStateException expected) { propagated = true; }
        check(propagated, "unrelated programming errors must remain visible");
    }
}
'''.replace('OUTER_SHOW', outer_show).replace('POPUP_METHODS', popup_methods)
        run_java(self, 'FloatingPopupHarness', harness)


if __name__ == '__main__':
    unittest.main()
