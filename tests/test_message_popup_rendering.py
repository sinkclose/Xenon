"""Exercise popup draw ownership and RecyclerView holder replacement without a device."""

import re
import unittest

from test_glass_blur import ROOT, method
from test_glass_chat_refresh import run_java


class MessagePopupRenderingTest(unittest.TestCase):
    def test_text_and_caption_have_one_draw_owner(self):
        cell = (ROOT / 'org/telegram/ui/Cells/ChatMessageCell.java').read_text()
        activity = (ROOT / 'org/telegram/ui/ChatActivity.java').read_text()
        popup = method(activity[activity.index('private MessagePopupPreviewLayout.Source createMessagePopupPreviewSource('):],
                       'public void draw(Canvas canvas)')
        parent_pass = method(popup, 'if (groupedPosition != null ||')
        outside_pass = re.search(r'if \(cell.hasOutboundsContent\(\)\) cell.drawOutboundsContent\(canvas\);', popup).group()
        quote_condition = re.search(r'if \(([^\n]+)\) \{\s+if \(!drawForBlur', cell).group(1)
        internal = method(cell, 'public void drawInternal(')
        outside_condition = re.search(r'if \(([^\n]+)\) \{\s+drawOutboundsContent\(canvas\);', internal).group(1)
        caption_condition = method(cell, 'boolean shouldDrawCaptionLayout(')
        draw_popup = method(cell, 'public void drawMessagePopup(')
        harness = r'''
public class PopupDrawHarness {
    static void check(boolean value, String reason) { if (!value) throw new AssertionError(reason); }
    static class Canvas { int text, captions, outside; }
    static class MessageObject {
        static final int POSITION_FLAG_TOP=1, POSITION_FLAG_BOTTOM=2, POSITION_FLAG_LEFT=4;
        boolean preview;
        boolean isSponsored() { return false; }
        boolean isVoice() { return false; }
        static class GroupedMessagePosition { int flags, minX, minY; boolean last; }
        static class GroupedMessages { boolean isDocuments; }
    }
    static class Params { boolean animateBackgroundBoundsInner, animateExpandedQuotes, messageEntering; }
    static class Delegate {
        boolean outside;
        Delegate(boolean outside) { this.outside=outside; }
        boolean canDrawOutboundsContent() { return outside; }
    }
    static class Cell {
        boolean drawingMessagePopup, enterTransitionInProgress, captionAbove, captionPresent;
        float alpha=1;
        MessageObject currentMessageObject=new MessageObject();
        MessageObject.GroupedMessagePosition currentPosition;
        MessageObject.GroupedMessages currentMessagesGroup;
        Params transitionParams=new Params(); Delegate delegate;
        int childPosition=10, visibleHeight=30, childPosition2=20, visibleParent, parentWidth, backgroundHeight;
        int blurredViewTopOffset, blurredViewBottomOffset;
        float visibleParentOffset, visibleTop;
        boolean needNewVisiblePart=true;
        int getHeight() { return 100; }
        void setVisiblePart(int position, int height, int parent, float offset, float top,
                int width, int background, int blurTop, int blurBottom, int position2) {
            childPosition=position; visibleHeight=height; childPosition2=position2;
        }
        float getAlpha() { return alpha; }
        Params getTransitionParams() { return transitionParams; }
        CAPTION_CONDITION
        int captionFlag() { return captionAbove ? MessageObject.POSITION_FLAG_TOP : MessageObject.POSITION_FLAG_BOTTOM; }
        boolean hasOutboundsContent() { return transitionParams.animateExpandedQuotes; }
        void drawOutboundsContent(Canvas c) { c.outside++; if (transitionParams.animateExpandedQuotes) c.text++; }
        void drawCaptionLayout(Canvas c, boolean selectionOnly, float alpha) {
            if (captionPresent && !selectionOnly) c.captions++;
        }
        void drawNamesLayout(Canvas c, float alpha) { }
        void drawTime(Canvas c, float alpha, boolean force) { }
        void drawReactionsLayout(Canvas c, float alpha, Object unused) { }
        DRAW_POPUP
        void draw(Canvas canvas) {
            if (QUOTE_CONDITION) canvas.text++;
            if (shouldDrawCaptionLayout()) drawCaptionLayout(canvas, false, 1f);
            if (OUTSIDE_CONDITION) drawOutboundsContent(canvas);
        }
    }
    static void draw(Canvas canvas, Cell cell) {
        cell.drawMessagePopup(canvas);
        check(!cell.drawingMessagePopup && cell.childPosition==10 && cell.visibleHeight==30
                && cell.childPosition2==20 && cell.needNewVisiblePart, "live cell state must be restored");
        MessageObject.GroupedMessagePosition groupedPosition=cell.currentPosition;
        PARENT_PASS
        OUTSIDE_PASS
    }
    static Cell grouped(int flags) {
        Cell cell=new Cell(); cell.delegate=new Delegate(false); cell.captionPresent=true;
        cell.currentMessagesGroup=new MessageObject.GroupedMessages();
        cell.currentPosition=new MessageObject.GroupedMessagePosition();
        cell.currentPosition.flags=flags;
        return cell;
    }
    public static void main(String[] args) {
        for (boolean quote : new boolean[]{true,false}) {
            for (int delegate=0; delegate<3; delegate++) {
                for (boolean entering : new boolean[]{false,true}) {
                    for (float alpha : new float[]{1f,.5f}) {
                        Cell cell=new Cell(); cell.transitionParams.animateExpandedQuotes=quote;
                        cell.transitionParams.messageEntering=entering; cell.alpha=alpha;
                        cell.delegate=delegate==0 ? null : new Delegate(delegate==1);
                        Canvas canvas=new Canvas(); draw(canvas,cell);
                        check(canvas.text==1, "body/expanded quote must be drawn once: "+quote+","+delegate+","+entering+","+alpha);
                        check(canvas.outside==(quote ? 1 : 0), "popup must own the only external pass");
                    }
                }
            }
        }
        for (boolean above : new boolean[]{false,true}) {
            int edge=above ? MessageObject.POSITION_FLAG_TOP : MessageObject.POSITION_FLAG_BOTTOM;
            Cell left=grouped(edge | MessageObject.POSITION_FLAG_LEFT), right=grouped(edge);
            left.captionAbove=right.captionAbove=above;
            Canvas album=new Canvas(); draw(album,left); draw(album,right);
            check(album.captions==1, "shared album caption must not repeat on the right tile");
        }
        Cell document=grouped(MessageObject.POSITION_FLAG_TOP | MessageObject.POSITION_FLAG_LEFT);
        document.captionAbove=true; document.currentMessagesGroup.isDocuments=true;
        Canvas documents=new Canvas(); draw(documents,document);
        check(documents.captions==1, "document caption already drawn by the cell must not repeat in parent pass");
        Cell changing=new Cell(); changing.captionPresent=true;
        changing.transitionParams.animateBackgroundBoundsInner=true;
        Canvas bounds=new Canvas(); draw(bounds,changing);
        check(bounds.captions==1, "bounds animation must retain its parent caption pass");
    }
}
'''.replace('CAPTION_CONDITION', caption_condition).replace('DRAW_POPUP', draw_popup).replace('QUOTE_CONDITION', quote_condition)
        harness = harness.replace('OUTSIDE_CONDITION', outside_condition).replace('PARENT_PASS', parent_pass).replace('OUTSIDE_PASS', outside_pass)
        run_java(self, 'PopupDrawHarness', harness)

    def test_replaced_holders_are_not_duplicate_messages(self):
        activity = (ROOT / 'org/telegram/ui/ChatActivity.java').read_text()
        source = activity[activity.index('private MessagePopupPreviewLayout.Source createMessagePopupPreviewSource('):]
        collect = method(source, 'private void collectCells(')
        self.assertIn('collectCells();', method(source, 'public boolean getBounds('))
        harness = r'''
import java.util.*;
public class PopupHoldersHarness {
    static void check(boolean value, String reason) { if (!value) throw new AssertionError(reason); }
    static class View { }
    static class MessageObject {
        int id; long dialog=10, group;
        MessageObject(int id, long group) { this.id=id; this.group=group; }
        int getId() { return id; } long getDialogId() { return dialog; } long getGroupId() { return group; }
    }
    static class ChatMessageCell extends View {
        MessageObject object; int adapterPosition;
        ChatMessageCell(int id, long group, int position) {
            object=new MessageObject(id,group); adapterPosition=position;
        }
        MessageObject getMessageObject() { return object; }
    }
    static class RecyclerView {
        static final int NO_POSITION=-1;
        static class ViewHolder { View itemView; ViewHolder(View view) { itemView=view; } }
        ArrayList<View> children=new ArrayList<>(); HashMap<Integer,ViewHolder> current=new HashMap<>();
        int getChildCount() { return children.size(); } View getChildAt(int i) { return children.get(i); }
        int getChildAdapterPosition(ChatMessageCell cell) { return cell.adapterPosition; }
        ViewHolder findViewHolderForAdapterPosition(int position) { return current.get(position); }
    }
    static class SparseArray<T> {
        TreeMap<Integer,T> values=new TreeMap<>();
        void clear() { values.clear(); } T get(int key) { return values.get(key); }
        void put(int key,T value) { values.put(key,value); }
        int size() { return values.size(); } T valueAt(int i) { return new ArrayList<T>(values.values()).get(i); }
    }
    static class Source {
        ArrayList<ChatMessageCell> cells=new ArrayList<>();
        SparseArray<ChatMessageCell> cellsByMessageId=new SparseArray<>();
        RecyclerView chatListView=new RecyclerView();
        MessageObject message=new MessageObject(1,0); long groupId;
        COLLECT
    }
    public static void main(String[] args) {
        for (boolean reverse : new boolean[]{false,true}) {
            for (int oldPosition : new int[]{-1,7}) {
                Source source=new Source();
                ChatMessageCell old=new ChatMessageCell(1,0,oldPosition), fresh=new ChatMessageCell(1,0,7);
                source.chatListView.children.add(reverse ? fresh : old);
                source.chatListView.children.add(reverse ? old : fresh);
                source.chatListView.current.put(7,new RecyclerView.ViewHolder(fresh));
                source.collectCells();
                check(source.cells.size()==1 && source.cells.get(0)==fresh, "prefer current holder in either child order");
                source.groupId=source.message.group=42; old.object.group=fresh.object.group=42;
                ChatMessageCell sibling=new ChatMessageCell(2,42,8), other=new ChatMessageCell(3,99,9);
                source.chatListView.children.add(sibling); source.chatListView.children.add(other);
                source.collectCells();
                check(source.cells.size()==2 && source.cells.contains(fresh) && source.cells.contains(sibling),
                        "deduplicate by message ID, keeping distinct album messages");
                source.chatListView.children.clear(); source.collectCells();
                check(source.cells.isEmpty(), "recycled holders must not survive the next frame");
            }
        }
    }
}
'''.replace('COLLECT', collect)
        run_java(self, 'PopupHoldersHarness', harness)


if __name__ == '__main__':
    unittest.main()
