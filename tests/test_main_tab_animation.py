"""Exercise production icon selection during an interrupted bottom-bar drag."""

import re
import unittest

from test_glass_blur import ROOT, method
from test_glass_chat_refresh import run_java


class MainTabAnimationTest(unittest.TestCase):
    def test_drag_reversal_keeps_icon_and_current_frame(self):
        source = (ROOT / 'org/telegram/ui/Components/glass/GlassTabView.java').read_text()
        layout = (ROOT / 'org/telegram/ui/MainTabsLayout.java').read_text()
        manager = (ROOT / 'org/telegram/ui/MainTabsManager.java').read_text()
        feed_animation = re.search(r'case FEED:.*?TabAnimation\.(\w+)', manager, re.S).group(1)
        animation_enum = method(source, 'public enum TabAnimation')
        animation_enum = re.sub(r'@(RawRes|DrawableRes)\s+', '', animation_enum)
        resources = sorted(set(re.findall(r'R\.raw\.(\w+)', animation_enum)))
        resource_fields = '\n'.join(f'static int {name} = {i + 1};' for i, name in enumerate(resources))
        harness = r'''
public class MainTabAnimationHarness {
    static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    static class R {
        static class raw { RESOURCE_FIELDS }
        static class drawable { static int tabs_link_24 = 100; }
    }
    enum TabAnimationType { LOTTIE, STATIC }
    ANIMATION_ENUM
    static class View { }
    static class Animator {
        boolean selected;
        boolean getValue() { return selected; }
    }
    static class RLottieDrawable {
        int current, end;
        boolean running, directional, pendingLastFrame;
        int getCurrentFrame() { return current; }
        int getFramesCount() { return 16; }
        void setPlayInDirectionOfCustomEndFrame(boolean value) { directional = value; }
        void setCustomEndFrame(int value) { end = value; }
        void setCurrentFrame(int value) { current = value; }
        void setCurrentFrame(int value, boolean async) { setCurrentFrame(value); }
        void setCurrentFrame(int value, boolean async, boolean resetFrame) {
            setCurrentFrame(value);
            if (resetFrame) pendingLastFrame = false;
        }
        void setProgress(float progress) { setCurrentFrame((int)(16 * progress)); }
        void start() { running = true; }
    }
    static class ImageView {
        RLottieDrawable drawable;
        int replacements, plays;
        RLottieDrawable getAnimatedDrawable() { return drawable; }
        void setAnimation(int resource, int w, int h) {
            drawable = new RLottieDrawable(); replacements++;
        }
        void setImageResource(int resource) { }
        void playAnimation() { plays++; drawable.start(); }
        void stopAnimation() { drawable.running = false; }
    }
    // Unused bot and attachment branches remain in the production method.
    static class TLRPC {
        static class Document { long id; }
        static class TL_attachMenuBot { }
        static class TL_attachMenuBotIcon { Document icon; }
    }
    static class MediaDataController {
        static TLRPC.TL_attachMenuBotIcon getAnimatedAttachMenuBotIcon(Object bot, boolean selected) { return null; }
        static TLRPC.TL_attachMenuBotIcon getStaticAttachMenuBotIcon(Object bot) { return null; }
    }
    static class ImageLocation {
        static Object getForDocument(Object document) { return null; }
    }
    static class DocumentObject {
        static Object getSvgThumb(Object document, int color, float alpha) { return null; }
    }
    static class Theme { static int key_windowBackgroundGray; }
    static class BackupImageView {
        void setImage(Object... args) { }
        void clearImage() { }
    }
    static class GlassTabView extends View {
        Animator isSelectedAnimator = new Animator();
        ImageView imageView = new ImageView();
        BackupImageView backupImageView;
        TabAnimation tabAnimation;
        TLRPC.TL_attachMenuBot tabAnimationBot;
        boolean lastIsSelected;
        int lastIconAnimationRaw;
        long lastBotIconId;
        GlassTabView(TabAnimation animation) { tabAnimation = animation; checkPlayAnimation(false); }
        void updateColors() { }
        void setSelected(boolean selected, boolean animated) {
            isSelectedAnimator.selected = selected;
            checkPlayAnimation(animated);
        }
        ICON_METHOD
    }
    static class MainTabsUiHelper {
        static boolean isMaterial3NavigationBar() { return false; }
    }
    static class AndroidUtilities {
        static void cancelRunOnUIThread(Runnable r) { }
        static void runOnUIThread(Runnable r, int delay) { }
    }
    static class Spring { void animateToFinalPosition(float x) { } }
    static class Layout {
        GlassTabView[] children;
        boolean drawCustomSelector = true, isInLongPress = true, swipeSelectionEnabled = true;
        float animatedLongSelectedViewCenterX;
        Runnable restoreDrawSelector;
        Spring selectedTabPositionX = new Spring();
        Layout(GlassTabView... views) { children = views; }
        int getChildCount() { return children.length; }
        View getChildAt(int index) { return children[index]; }
        View findSelectedTab() {
            for (GlassTabView child : children) if (child.isSelectedAnimator.selected) return child;
            return null;
        }
        boolean isAttachedToWindow() { return true; }
        boolean isViewVisible(View view) { return true; }
        float getCenterX(View view) { return 0; }
        void resetLiquidGesture() { }
        void setSkipDrawSelector(boolean skip) { }
        void animateLiquidPress() { }
        LAYOUT_METHOD
    }
    public static void main(String[] args) {
        GlassTabView feed = new GlassTabView(TabAnimation.FEED_ANIMATION);
        GlassTabView chats = new GlassTabView(TabAnimation.CHATS);
        Layout layout = new Layout(chats, feed);
        RLottieDrawable icon = feed.imageView.drawable;
        layout.setTabSelected(chats, false);
        check(!icon.running, "initial outline must be still");

        layout.setTabSelected(feed, true);
        check(feed.imageView.drawable == icon, "drag must keep the decoded feed icon");
        check(icon.running && icon.end == 16, "drag must animate toward filled icon");
        icon.current = 3; // Finger crosses back before the fill animation completes.
        icon.pendingLastFrame = true; // A decode for the previous target is queued.
        layout.setTabSelected(chats, true);
        check(feed.imageView.drawable == icon, "reversal must not replace the icon");
        check(icon.current == 3, "reversal must retain current frame");
        check(!icon.pendingLastFrame, "queued end frame must not stop the reversed animation");
        check(icon.directional && icon.end == 0, "reversal must animate toward outline");

        int plays = feed.imageView.plays;
        layout.setTabSelected(chats, true); // More MOVE events over the same tab.
        check(feed.imageView.plays == plays, "MOVE events must not restart animation");
        layout.setTabSelected(feed, true);
        check(icon.current == 3 && icon.end == 16, "second reversal must remain continuous");
        check(feed.imageView.replacements == 1, "drag must use one drawable throughout");

        layout.setTabSelected(feed, false);
        check(!icon.running && icon.current == 15, "nonanimated selection must settle filled");
        layout.setTabSelected(chats, false);
        check(!icon.running && icon.current == 0, "nonanimated deselection must settle outline");
        check(!chats.imageView.drawable.running, "other main icons must respect animated=false");
    }
}
'''
        harness = (harness.replace('RESOURCE_FIELDS', resource_fields)
                   .replace('ANIMATION_ENUM', animation_enum)
                   .replace('ICON_METHOD', method(source, 'private void checkPlayAnimation(boolean animated)'))
                   .replace('LAYOUT_METHOD', method(layout, 'public void setTabSelected(View tab, boolean animated)'))
                   .replace('FEED_ANIMATION', feed_animation))
        run_java(self, 'MainTabAnimationHarness', harness)


if __name__ == '__main__':
    unittest.main()
