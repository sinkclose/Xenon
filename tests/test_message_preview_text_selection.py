"""Exercise preview touch arbitration and live selection coordinates as Java."""

import unittest

from test_glass_blur import ROOT, method
from test_glass_chat_refresh import run_java


class MessagePreviewTextSelectionTest(unittest.TestCase):
    def test_live_cell_coordinates_padding_and_recycling(self):
        source = (ROOT / 'org/telegram/ui/ChatActivity.java').read_text()
        routing = method(source[source.index('private MessagePopupPreviewLayout.Source createMessagePopupPreviewSource('):],
                         'public void onTextTouchEvent(')
        native_touch = method(source[source.index('private void setupMessagePopupTextSelection('):],
                              'public boolean onTouchEvent(')
        harness = r'''
import java.util.ArrayList;
public class PreviewCellTouchHarness {
    static void check(boolean value, String reason) { if(!value) throw new AssertionError(reason); }
    static class MotionEvent {
        static final int ACTION_DOWN=0, ACTION_UP=1, ACTION_MOVE=2, ACTION_CANCEL=3;
        int action; float x,y;
        MotionEvent(int a,float x,float y) { action=a; this.x=x; this.y=y; }
        static MotionEvent obtain(MotionEvent e) { return new MotionEvent(e.action,e.x,e.y); }
        int getActionMasked() { return action; }
        float getX() { return x; } float getY() { return y; }
        void setLocation(float x,float y) { this.x=x; this.y=y; }
        void offsetLocation(float x,float y) { this.x+=x; this.y+=y; }
        void recycle() { }
    }
    static class BaseHelper {
        ChatMessageCell maybeSelectedView;
        float x,y; int touches, cancels;
        public boolean onTouchEvent(MotionEvent event) { x=event.x; y=event.y; touches++; return true; }
        void cancelTextSelectionRunnable() { cancels++; }
    }
    static class Helper extends BaseHelper { NATIVE_TOUCH }
    static class ChatMessageCell {
        Object parent; Helper helper;
        int screenX=12, screenY=200, padding=6;
        float scale=1;
        void getLocationOnScreen(int[] position) { position[0]=screenX; position[1]=screenY; }
        float getScaleX() { return scale; } float getScaleY() { return scale; }
        int getBackgroundDrawableTop() { return 0; }
        int getBackgroundDrawableBottom() { return 100; }
        boolean isInsideBackground(float x,float y) { return x>=0 && x<300; }
        int getPaddingTop() { return padding; }
        Object getParent() { return parent; }
        boolean checkTextSelectionInPreview(MotionEvent event) {
            helper.maybeSelectedView=this;
            return helper.onTouchEvent(event);
        }
    }
    static class Source {
        ArrayList<ChatMessageCell> cells=new ArrayList<>();
        int[] position=new int[2];
        Object chatListView=new Object();
        Helper messagePopupTextSelectionHelper=new Helper();
        ChatMessageCell textTouchCell;
        ROUTING
    }
    public static void main(String[] args) {
        Source source=new Source();
        ChatMessageCell cell=new ChatMessageCell();
        cell.parent=source.chatListView; cell.helper=source.messagePopupTextSelectionHelper;
        source.cells.add(cell);
        MotionEvent down=new MotionEvent(MotionEvent.ACTION_DOWN,42,226);
        source.onTextTouchEvent(down);
        check(cell.helper.touches==1 && cell.helper.x==30 && cell.helper.y==20,
                "native word lookup must receive cell text coordinates excluding top padding");
        check(down.x==42 && down.y==226, "source screen event must stay unchanged");
        source.onTextTouchEvent(new MotionEvent(MotionEvent.ACTION_UP,42,226));
        check(source.textTouchCell==null, "release must drop the touched live cell");
        int touches=cell.helper.touches;
        source.onTextTouchEvent(new MotionEvent(MotionEvent.ACTION_DOWN,400,226));
        check(cell.helper.touches==touches, "menu or background must not arm text selection");
        source.onTextTouchEvent(down);
        cell.parent=null;
        source.onTextTouchEvent(new MotionEvent(MotionEvent.ACTION_MOVE,42,230));
        check(cell.helper.cancels==1 && source.textTouchCell==null,
                "detached or recycled source must cancel pending word selection");
    }
}
'''.replace('NATIVE_TOUCH', native_touch).replace('ROUTING', routing)
        run_java(self, 'PreviewCellTouchHarness', harness)

    def test_word_hold_handles_scroll_and_dismissal(self):
        source = (ROOT / 'org/telegram/ui/Components/MessagePopupPreviewLayout.java').read_text()
        methods = '\n'.join(method(source, signature) for signature in (
            'public void getSelectionPosition(', 'public void onTextSelectionChanged(',
            'private void clearTextSelection()', 'public boolean dispatchTouchEvent(',
            'public boolean dispatchKeyEvent('))
        harness = r'''
public class PreviewSelectionHarness {
    static void check(boolean value, String reason) {
        if (!value) throw new AssertionError(reason);
    }
    static class RectF {
        float left, top, right, bottom;
        void set(float l, float t, float r, float b) { left=l; top=t; right=r; bottom=b; }
        boolean contains(float x, float y) { return x>=left && x<right && y>=top && y<bottom; }
    }
    static class View {
        static int VISIBLE=0, INVISIBLE=4;
        int visibility, screenX, screenY, padding;
        void getLocationOnScreen(int[] result) { result[0]=screenX; result[1]=screenY; }
        int getPaddingTop() { return padding; }
        void setVisibility(int value) { visibility=value; }
        void invalidate() { }
    }
    static class FrameLayout extends View {
        int nativeEvents;
        public boolean dispatchTouchEvent(MotionEvent event) { nativeEvents++; return true; }
        public boolean dispatchKeyEvent(KeyEvent event) { return false; }
        Object getContext() { return null; }
    }
    static class Scroll {
        int cancels;
        boolean disallow;
        void dispatchTouchEvent(MotionEvent event) { if(event.action==MotionEvent.ACTION_CANCEL) cancels++; }
        void requestDisallowInterceptTouchEvent(boolean value) { disallow=value; }
    }
    static class MotionEvent {
        static final int ACTION_DOWN=0, ACTION_UP=1, ACTION_MOVE=2, ACTION_CANCEL=3;
        int action; float x,y;
        MotionEvent(int action,float x,float y) { this.action=action; this.x=x; this.y=y; }
        static MotionEvent obtain(MotionEvent event) { return new MotionEvent(event.action,event.x,event.y); }
        static MotionEvent obtain(long down,long now,int action,float x,float y,int flags) { return new MotionEvent(action,x,y); }
        int getActionMasked() { return action; }
        float getX() { return x; } float getY() { return y; }
        float getRawX() { return x; } float getRawY() { return y; }
        void offsetLocation(float dx,float dy) { x+=dx; y+=dy; }
        void recycle() { }
    }
    static class KeyEvent {
        static final int KEYCODE_BACK=4, ACTION_UP=1;
        int getKeyCode() { return KEYCODE_BACK; }
        int getAction() { return ACTION_UP; }
    }
    static class SystemClock { static long uptimeMillis() { return 1; } }
    static class ViewConfiguration {
        static ViewConfiguration get(Object context) { return new ViewConfiguration(); }
        int getScaledTouchSlop() { return 8; }
    }
    static class TextSelectionHelper {
        static class ChatListTextSelectionHelper {
            boolean selected, moving, pending, actionsHidden, animatorsCancelled;
            boolean isTouched() { return moving; }
            boolean isInSelectionMode() { return selected; }
            void cancelTextSelectionRunnable() { pending=false; }
            void clear(boolean instant) { selected=false; moving=false; pending=false; }
            void hideActionsMenu() { actionsHidden=true; }
            void cancelAllAnimators() { animatorsCancelled=true; }
        }
        static class TextSelectionOverlay {
            ChatListTextSelectionHelper helper;
            float downX, downY;
            TextSelectionOverlay(ChatListTextSelectionHelper helper) { this.helper=helper; }
            boolean onTouchEvent(MotionEvent event) {
                boolean consumed=helper.moving;
                if(event.action==MotionEvent.ACTION_UP || event.action==MotionEvent.ACTION_CANCEL) helper.moving=false;
                // Native overlay returns false on the terminal event after ending the drag.
                return consumed && helper.moving;
            }
            boolean checkOnTap(MotionEvent event) {
                if (!helper.selected || helper.moving) return false;
                if (event.action==MotionEvent.ACTION_DOWN) { downX=event.x; downY=event.y; }
                if (event.action==MotionEvent.ACTION_UP && Math.hypot(event.x-downX,event.y-downY)<8) {
                    helper.clear(true); return true;
                }
                return false;
            }
        }
    }
    static class Source {
        TextSelectionHelper.ChatListTextSelectionHelper helper;
        float x,y, downX,downY;
        void getViewport(RectF viewport) { viewport.set(0,0,400,800); }
        boolean contains(float x,float y) { return x>=0 && x<400 && y>=512 && y<580; }
        void onTextTouchEvent(MotionEvent event) {
            x=event.x; y=event.y;
            if(event.action==MotionEvent.ACTION_DOWN) {
                downX=x; downY=y; helper.pending=contains(x,y);
            } else if(event.action==MotionEvent.ACTION_MOVE) {
                if(Math.hypot(x-downX,y-downY)>8) helper.pending=false;
            } else helper.pending=false;
        }
    }
    static class Preview extends FrameLayout {
        boolean closing, outsideDown, overflow=true, dragged;
        float progress=1, downX,downY;
        int dismissals;
        Runnable dismiss=()->dismissals++;
        RectF drawnBounds=new RectF(), sourceBounds=new RectF(), viewport=new RectF();
        View menu=new View(); Scroll scroll=new Scroll(); Source source=new Source();
        TextSelectionHelper.ChatListTextSelectionHelper textSelectionHelper=new TextSelectionHelper.ChatListTextSelectionHelper();
        TextSelectionHelper.TextSelectionOverlay selectionOverlay=new TextSelectionHelper.TextSelectionOverlay(textSelectionHelper);
        Preview() {
            source.helper=textSelectionHelper;
            sourceBounds.set(0,512,400,580); drawnBounds.set(0,20,400,88);
        }
        void cancelOverscrollAnimation() { }
        void releaseOverscroll() { }
        boolean containsMenu(float x,float y) { return y>=100 && y<=300; }
        METHODS
    }
    public static void main(String[] args) {
        Preview preview=new Preview();
        MotionEvent down=new MotionEvent(MotionEvent.ACTION_DOWN,120,50);
        preview.dispatchTouchEvent(down);
        check(preview.textSelectionHelper.pending, "holding preview text must arm native word selection");
        check(preview.source.x==120 && preview.source.y==542, "touch must map into the live message");
        check(down.x==120 && down.y==50, "mapping must not mutate the scrolling event");
        int nativeEvents=preview.nativeEvents;
        preview.dispatchTouchEvent(new MotionEvent(MotionEvent.ACTION_MOVE,120,75));
        check(!preview.textSelectionHelper.pending, "ordinary swipe must cancel word selection");
        check(preview.nativeEvents==nativeEvents+1, "ordinary swipe must reach the scroller");

        preview.dispatchTouchEvent(new MotionEvent(MotionEvent.ACTION_DOWN,120,50));
        preview.textSelectionHelper.selected=true;
        preview.textSelectionHelper.moving=true; // Native long-press runnable selected the word.
        preview.onTextSelectionChanged(true);
        check(preview.menu.visibility==View.VISIBLE, "message menu must stay visible while selecting text");
        check(preview.scroll.cancels==1 && preview.scroll.disallow, "selection must take over the active scroll gesture");
        nativeEvents=preview.nativeEvents;
        preview.dispatchTouchEvent(new MotionEvent(MotionEvent.ACTION_MOVE,180,50));
        preview.dispatchTouchEvent(new MotionEvent(MotionEvent.ACTION_UP,180,50));
        check(preview.nativeEvents==nativeEvents, "handle drag including UP must stay out of scroller");
        check(preview.textSelectionHelper.selected && !preview.textSelectionHelper.moving, "release must keep selected text");
        check(preview.dismissals==0, "handle release must keep the preview open");

        View cell=new View(); cell.screenY=520; cell.padding=6;
        int[] position=new int[2]; preview.getSelectionPosition(cell,position);
        check(position[0]==0 && position[1]==34, "handles must use preview position plus text padding");
        preview.drawnBounds.top=-180; // Independent preview scrolling.
        preview.getSelectionPosition(cell,position);
        check(position[1]==-166, "handles must follow scrolling rather than the original chat position");

        preview.overflow=false;
        preview.dispatchTouchEvent(new MotionEvent(MotionEvent.ACTION_DOWN,410,50));
        preview.dispatchTouchEvent(new MotionEvent(MotionEvent.ACTION_UP,410,50));
        check(!preview.textSelectionHelper.selected && preview.dismissals==0, "outside tap must clear selection while keeping preview open");
        preview.textSelectionHelper.selected=true;
        preview.dispatchKeyEvent(new KeyEvent());
        check(!preview.textSelectionHelper.selected && preview.dismissals==0, "first Back must exit text selection");
        check(preview.textSelectionHelper.actionsHidden && preview.textSelectionHelper.animatorsCancelled, "exit must cancel delayed text actions and animations");
        preview.onTextSelectionChanged(false);
        check(preview.menu.visibility==View.VISIBLE && !preview.scroll.disallow, "exit must restore menu and scrolling");
        preview.dispatchKeyEvent(new KeyEvent());
        check(preview.dismissals==1, "second Back must dismiss the preview");

        Preview opening=new Preview(); opening.progress=.5f;
        opening.dispatchTouchEvent(new MotionEvent(MotionEvent.ACTION_DOWN,120,50));
        check(!opening.textSelectionHelper.pending, "animated message must not begin text selection");
        opening.closing=true; nativeEvents=opening.nativeEvents;
        opening.dispatchTouchEvent(new MotionEvent(MotionEvent.ACTION_DOWN,120,50));
        check(opening.nativeEvents==nativeEvents, "closing preview must ignore new gestures");
    }
}
'''.replace('METHODS', methods)
        run_java(self, 'PreviewSelectionHarness', harness)


if __name__ == '__main__':
    unittest.main()
