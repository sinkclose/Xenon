package zxc.iconic.xenon.deleted;

import android.util.LongSparseArray;

import java.util.ArrayList;

public class XenonDeletedState {
    private static final LongSparseArray<ArrayList<Integer>> deletePermitted = new LongSparseArray<>();

    public static void permitDeleteMessage(long dialogId, int messageId) {
        var list = deletePermitted.get(dialogId);
        if (list == null) {
            list = new ArrayList<>();
            deletePermitted.put(dialogId, list);
        }
        list.add(messageId);
    }

    public static boolean isDeletePermitted(long dialogId, int messageId) {
        var list = deletePermitted.get(dialogId);
        if (list == null) return false;
        return list.contains(messageId);
    }

    public static void messageDeleted(long dialogId, int messageId) {
        var list = deletePermitted.get(dialogId);
        if (list == null) return;
        list.remove((Object) messageId);
    }

    private static final LongSparseArray<Integer> pendingHighlight = new LongSparseArray<>();

    public static void setPendingHighlight(long dialogId, int messageId) {
        pendingHighlight.put(dialogId, messageId);
    }

    public static int consumePendingHighlight(long dialogId) {
        Integer v = pendingHighlight.get(dialogId);
        if (v == null) return 0;
        pendingHighlight.remove(dialogId);
        return v;
    }

    public static int peekPendingHighlight(long dialogId) {
        Integer v = pendingHighlight.get(dialogId);
        return v != null ? v : 0;
    }
}
