"""Exercise production routing of local message changes and its safe fallbacks."""
import unittest

from test_glass_blur import ROOT, method
from test_glass_chat_refresh import run_java


class GlassRegionRoutingTest(unittest.TestCase):
    def test_stable_rows_and_unknown_footprints(self):
        source = (ROOT / "org/telegram/ui/ChatActivity.java").read_text()
        routing = method(source, "private boolean invalidateGlassMessageRegion(View target)")
        harness = r"""
public class RegionRoutingHarness {
    static void check(boolean value) { if (!value) throw new AssertionError(); }
    static class Build {
        static class VERSION { static int SDK_INT = 33; }
        static class VERSION_CODES { static final int S = 31; }
    }
    static class RectF {
        float left, top, right, bottom;
        RectF() {}
        RectF(RectF r) { set(r); }
        void set(RectF r) { left = r.left; top = r.top; right = r.right; bottom = r.bottom; }
        @Override public boolean equals(Object other) {
            if (!(other instanceof RectF)) return false;
            RectF r = (RectF) other;
            return left == r.left && top == r.top && right == r.right && bottom == r.bottom;
        }
        void inset(float x, float y) { left += x; top += y; right -= x; bottom -= y; }
    }
    static class Matrix { boolean identity = true; boolean isIdentity() { return identity; } }
    static class View {
        static final int VISIBLE = 0;
        View parent; boolean attached = true, layout;
        int width = 360, height = 100, visibility; float alpha = 1;
        Matrix matrix = new Matrix(); Object animation;
        View getParent() { return parent; }
        boolean isAttachedToWindow() { return attached; }
        boolean isLayoutRequested() { return layout; }
        int getVisibility() { return visibility; }
        int getWidth() { return width; } int getHeight() { return height; }
        Matrix getMatrix() { return matrix; }
        Object getAnimation() { return animation; }
        float getAlpha() { return alpha; }
    }
    static class RecyclerView extends View {
        static final int SCROLL_STATE_IDLE = 0;
        int scroll; boolean computing, edges;
        int getScrollState() { return scroll; }
        boolean isComputingLayout() { return computing; }
        boolean hasActiveEdgeEffects() { return edges; }
    }
    static class Transition { float animateChangeProgress = 1; }
    static class ChatMessageCell extends View {
        boolean outbounds, backgroundInParent; Object group;
        Transition transition = new Transition();
        Object getCurrentMessagesGroup() { return group; }
        boolean hasOutboundsContent() { return outbounds; }
        boolean drawBackgroundInParent() { return backgroundInParent; }
        Transition getTransitionParams() { return transition; }
    }
    static class Animator {
        boolean running; float value;
        boolean isRunning() { return running; }
        float getFloatValue() { return value; }
    }
    static class Suppressor {
        int updates; float top, bottom;
        void invalidateSourceRegion(RectF r) { updates++; top = r.top; bottom = r.bottom; }
    }
    static class ViewPositionWatcher {
        static boolean valid = true;
        static float top = 400;
        static boolean computeRectInParent(View row, View root, RectF rect) {
            rect.left = 10; rect.right = 350; rect.top = top; rect.bottom = top + 100; return valid;
        }
    }
    static class Chat {
        static final int BLUR_INVALIDATE_FLAG_CONTENT_REGION = 16;
        Suppressor scrollableViewNoiseSuppressor = new Suppressor();
        Object invalidateBlurredSourcesView = new Object(), parentChatActivity;
        RecyclerView chatListView = new RecyclerView();
        View contentView = new View();
        Animator chatListItemAnimator = new Animator(), animatorSearchResultAsListVisibility = new Animator();
        RectF glassMessageDirtyRect = new RectF();
        java.util.WeakHashMap<View, RectF> glassMessageBounds = new java.util.WeakHashMap<>();
        int flags;
        int dp(int value) { return value * 2; }
        void invalidateMergedVisibleBlurredPositionsAndSources(int value) { flags |= value; }
        ROUTING
    }
    public static void main(String[] args) {
        Chat chat = new Chat();
        ChatMessageCell cell = new ChatMessageCell(); cell.parent = chat.chatListView;
        check(!chat.invalidateGlassMessageRegion(cell)); // First observation uses the full capture.
        check(chat.invalidateGlassMessageRegion(cell));
        check(chat.flags == 16 && chat.scrollableViewNoiseSuppressor.updates == 1);
        check(chat.scrollableViewNoiseSuppressor.top == 304 && chat.scrollableViewNoiseSuppressor.bottom == 596);
        check(chat.glassMessageDirtyRect.left == 0 && chat.glassMessageDirtyRect.right == chat.contentView.width);
        View nested = new View(); nested.parent = cell;
        check(chat.invalidateGlassMessageRegion(nested));
        check(!chat.invalidateGlassMessageRegion(chat.chatListView));
        check(!chat.invalidateGlassMessageRegion(new View()));
        cell.outbounds = true; check(!chat.invalidateGlassMessageRegion(cell)); cell.outbounds = false;
        cell.backgroundInParent = true; check(!chat.invalidateGlassMessageRegion(cell)); cell.backgroundInParent = false;
        cell.group = new Object(); check(!chat.invalidateGlassMessageRegion(cell)); cell.group = null;
        cell.transition.animateChangeProgress = .5f; check(!chat.invalidateGlassMessageRegion(cell)); cell.transition.animateChangeProgress = 1;
        cell.layout = true; check(!chat.invalidateGlassMessageRegion(cell)); cell.layout = false;
        cell.matrix.identity = false; check(!chat.invalidateGlassMessageRegion(cell)); cell.matrix.identity = true;
        cell.animation = new Object(); check(!chat.invalidateGlassMessageRegion(cell)); cell.animation = null;
        cell.attached = false; check(!chat.invalidateGlassMessageRegion(cell)); cell.attached = true;
        cell.alpha = .5f; check(!chat.invalidateGlassMessageRegion(cell)); cell.alpha = 1;
        cell.height = 0; check(!chat.invalidateGlassMessageRegion(cell)); cell.height = 100;
        cell.visibility = 8; check(!chat.invalidateGlassMessageRegion(cell)); cell.visibility = 0;
        chat.chatListView.scroll = 1; check(!chat.invalidateGlassMessageRegion(cell)); chat.chatListView.scroll = 0;
        chat.chatListView.computing = true; check(!chat.invalidateGlassMessageRegion(cell)); chat.chatListView.computing = false;
        chat.chatListView.layout = true; check(!chat.invalidateGlassMessageRegion(cell)); chat.chatListView.layout = false;
        chat.chatListView.edges = true; check(!chat.invalidateGlassMessageRegion(cell)); chat.chatListView.edges = false;
        chat.contentView.matrix.identity = false; check(!chat.invalidateGlassMessageRegion(cell)); chat.contentView.matrix.identity = true;
        chat.chatListItemAnimator.running = true; check(!chat.invalidateGlassMessageRegion(cell)); chat.chatListItemAnimator.running = false;
        chat.animatorSearchResultAsListVisibility.value = .5f; check(!chat.invalidateGlassMessageRegion(cell)); chat.animatorSearchResultAsListVisibility.value = 0;
        chat.parentChatActivity = new Object(); check(!chat.invalidateGlassMessageRegion(cell)); chat.parentChatActivity = null;
        ViewPositionWatcher.valid = false; check(!chat.invalidateGlassMessageRegion(cell)); ViewPositionWatcher.valid = true;
        Build.VERSION.SDK_INT = 30; check(!chat.invalidateGlassMessageRegion(cell)); Build.VERSION.SDK_INT = 33;
        check(chat.scrollableViewNoiseSuppressor.updates == 2);
        check(chat.invalidateGlassMessageRegion(cell));
        ViewPositionWatcher.top = 600;
        check(!chat.invalidateGlassMessageRegion(cell)); // Repair both old and new footprints.
        check(chat.invalidateGlassMessageRegion(cell));
        ViewPositionWatcher.top = 400;
        check(!chat.invalidateGlassMessageRegion(cell)); // Returning to the old position is also a move.
        check(chat.invalidateGlassMessageRegion(cell));
    }
}
""".replace("ROUTING", routing)
        run_java(self, "RegionRoutingHarness", harness)


if __name__ == "__main__":
    unittest.main()
