package zxc.iconic.xenon.deleted;

import android.content.Context;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.NativeByteBuffer;
import org.telegram.tgnet.TLRPC;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class XenonDeletedMessagesController {

    private static XenonDeletedMessagesController instance;
    private File storageDir;

    public static XenonDeletedMessagesController getInstance() {
        if (instance == null) {
            instance = new XenonDeletedMessagesController();
        }
        return instance;
    }

    private XenonDeletedMessagesController() {
        storageDir = new File(ApplicationLoader.applicationContext.getDir("deleted_messages", Context.MODE_PRIVATE), "messages");
        if (!storageDir.exists()) {
            storageDir.mkdirs();
        }
    }

    public void onMessageDeleted(TLRPC.Message message, long dialogId, int accountId) {
        if (message == null) return;
        Utilities.globalQueue.postRunnable(() -> saveMessage(message, dialogId, accountId));
    }

    private void saveMessage(TLRPC.Message message, long dialogId, int accountId) {
        try {
            int size = message.getObjectSize();
            FileLog.d("XenonSave: msg " + message.id + " dialog " + dialogId + " size " + size);
            NativeByteBuffer buffer = new NativeByteBuffer(size);
            message.serializeToStream(buffer);
            buffer.rewind();

            byte[] data = new byte[buffer.remaining()];
            buffer.buffer.get(data);
            buffer.reuse();

            File dialogDir = new File(storageDir, dialogId + "_" + accountId);
            if (!dialogDir.exists()) dialogDir.mkdirs();

            File msgFile = new File(dialogDir, message.id + ".dat");
            try (FileOutputStream fos = new FileOutputStream(msgFile)) {
                fos.write(data);
            }
            FileLog.d("XenonSave: saved to " + msgFile.getAbsolutePath());
        } catch (Exception e) {
            FileLog.e("XenonSaveDeletedMessage err", e);
        }
    }

    public TLRPC.Message getMessage(long dialogId, int messageId, int accountId) {
        File dialogDir = new File(storageDir, dialogId + "_" + accountId);
        File msgFile = new File(dialogDir, messageId + ".dat");
        if (!msgFile.exists()) return null;

        try {
            byte[] data = new byte[(int) msgFile.length()];
            try (FileInputStream fis = new FileInputStream(msgFile)) {
                fis.read(data);
            }

            NativeByteBuffer buffer = new NativeByteBuffer(data.length);
            buffer.buffer.put(java.nio.ByteBuffer.wrap(data));
            buffer.rewind();

            int constructor = buffer.readInt32(false);
            TLRPC.Message message = TLRPC.Message.TLdeserialize(buffer, constructor, false);
            buffer.reuse();
            return message;
        } catch (Exception e) {
            FileLog.e("XenonLoadDeletedMessage", e);
            return null;
        }
    }

    public List<Integer> getExistingMessageIds(long dialogId, List<Integer> messageIds, int accountId) {
        List<Integer> existing = new ArrayList<>();
        File dialogDir = new File(storageDir, dialogId + "_" + accountId);
        if (!dialogDir.exists()) return existing;

        for (int msgId : messageIds) {
            if (new File(dialogDir, msgId + ".dat").exists()) {
                existing.add(msgId);
            }
        }
        return existing;
    }

    public void deleteMessages(long dialogId, List<Integer> messageIds, int accountId) {
        File dialogDir = new File(storageDir, dialogId + "_" + accountId);
        if (!dialogDir.exists()) return;

        for (int msgId : messageIds) {
            File f = new File(dialogDir, msgId + ".dat");
            if (f.exists()) f.delete();
        }
    }

    public void deleteCurrent(long dialogId) {
        File dialogDir = new File(storageDir, dialogId + "_0");
        if (dialogDir.exists()) {
            File[] files = dialogDir.listFiles();
            if (files != null) {
                for (File f : files) f.delete();
            }
        }
    }

    public void getMessagesForRange(long dialogId, int accountId, long startId, long endId, int limit, Utilities.Callback<ArrayList<MessageObject>> callback) {
        Utilities.globalQueue.postRunnable(() -> {
            ArrayList<MessageObject> result = new ArrayList<>();
            try {
                File dialogDir = new File(storageDir, dialogId + "_" + accountId);
                if (!dialogDir.exists()) {
                    finishReinject(result, callback);
                    return;
                }
                File[] files = dialogDir.listFiles();
                if (files == null) {
                    finishReinject(result, callback);
                    return;
                }
                long lo = Math.min(startId, endId);
                long hi = Math.max(startId, endId);
                List<Integer> matchingIds = new ArrayList<>();
                for (File f : files) {
                    String name = f.getName();
                    if (!name.endsWith(".dat")) continue;
                    int msgId;
                    try {
                        msgId = Integer.parseInt(name.substring(0, name.length() - 4));
                    } catch (NumberFormatException ignored) {
                        continue;
                    }
                    if (msgId >= lo && msgId <= hi) {
                        matchingIds.add(msgId);
                    }
                }
                if (matchingIds.isEmpty()) {
                    finishReinject(result, callback);
                    return;
                }
                Collections.sort(matchingIds);
                int added = 0;
                for (int msgId : matchingIds) {
                    if (limit > 0 && added >= limit) break;
                    TLRPC.Message message = getMessage(dialogId, msgId, accountId);
                    if (message == null) continue;
                    message.ayuDeleted = true;
                    if (message.dialog_id == 0) {
                        message.dialog_id = dialogId;
                    }
                    try {
                        MessageObject obj = new MessageObject(accountId, message, true, true);
                        // ensure the message is rendered as already read
                        obj.setIsRead();
                        result.add(obj);
                        added++;
                    } catch (Exception e) {
                        FileLog.e("XenonReinject build MessageObject", e);
                    }
                }
                finishReinject(result, callback);
            } catch (Exception e) {
                FileLog.e("XenonReinject", e);
                finishReinject(result, callback);
            }
        });
    }

    private void finishReinject(ArrayList<MessageObject> result, Utilities.Callback<ArrayList<MessageObject>> callback) {
        if (callback == null) return;
        AndroidUtilities.runOnUIThread(() -> callback.run(result));
    }

    /**
     * Loads every saved deleted message for a dialog, sorted by message id ascending
     * (oldest first), as a list of {@link MessageObject} ready for display. Runs the
     * file I/O off the main thread and delivers the result on the UI thread.
     */
    public void getAllMessagesForDialog(long dialogId, int accountId, Utilities.Callback<ArrayList<MessageObject>> callback) {
        Utilities.globalQueue.postRunnable(() -> {
            ArrayList<MessageObject> result = new ArrayList<>();
            try {
                List<Integer> ids = new ArrayList<>(getAllSavedMessageIds(dialogId, accountId));
                Collections.sort(ids);
                for (int msgId : ids) {
                    TLRPC.Message message = getMessage(dialogId, msgId, accountId);
                    if (message == null) continue;
                    message.ayuDeleted = true;
                    if (message.dialog_id == 0) {
                        message.dialog_id = dialogId;
                    }
                    try {
                        MessageObject obj = new MessageObject(accountId, message, true, true);
                        obj.setIsRead();
                        result.add(obj);
                    } catch (Exception e) {
                        FileLog.e("XenonViewDeleted build MessageObject", e);
                    }
                }
            } catch (Exception e) {
                FileLog.e("XenonViewDeleted load", e);
            }
            finishReinject(result, callback);
        });
    }

    /**
     * Wipes the entire deleted-messages store for all dialogs and accounts.
     */
    public int getItemCount() {
        return countDatFilesRecursive(storageDir);
    }

    private int countDatFilesRecursive(File f) {
        if (f == null || !f.exists()) return 0;
        if (f.isFile()) return f.getName().endsWith(".dat") ? 1 : 0;
        int total = 0;
        File[] children = f.listFiles();
        if (children != null) {
            for (File c : children) total += countDatFilesRecursive(c);
        }
        return total;
    }

    public void clearAll() {
        deleteRecursive(storageDir);
        if (!storageDir.exists()) {
            storageDir.mkdirs();
        }
    }

    private void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        File[] children = f.listFiles();
        if (children != null) {
            for (File c : children) deleteRecursive(c);
        }
        f.delete();
    }

    /**
     * Total size in bytes of the deleted-messages store on disk.
     */
    public long getStorageSize() {
        return sizeRecursive(storageDir);
    }

    private long sizeRecursive(File f) {
        if (f == null || !f.exists()) return 0;
        if (f.isFile()) return f.length();
        long total = 0;
        File[] children = f.listFiles();
        if (children != null) {
            for (File c : children) total += sizeRecursive(c);
        }
        return total;
    }

    public Set<Integer> getAllSavedMessageIds(long dialogId, int accountId) {
        Set<Integer> ids = new HashSet<>();
        File dialogDir = new File(storageDir, dialogId + "_" + accountId);
        if (!dialogDir.exists()) return ids;
        File[] files = dialogDir.listFiles();
        if (files != null) {
            for (File f : files) {
                String name = f.getName();
                if (name.endsWith(".dat")) {
                    try {
                        ids.add(Integer.parseInt(name.substring(0, name.length() - 4)));
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
        }
        return ids;
    }
}
