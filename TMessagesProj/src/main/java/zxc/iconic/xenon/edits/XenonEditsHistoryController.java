package zxc.iconic.xenon.edits;

import android.content.Context;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.tgnet.NativeByteBuffer;
import org.telegram.tgnet.TLRPC;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class XenonEditsHistoryController {

    private static XenonEditsHistoryController instance;
    private final File storageDir;

    public static XenonEditsHistoryController getInstance() {
        if (instance == null) {
            instance = new XenonEditsHistoryController();
        }
        return instance;
    }

    private XenonEditsHistoryController() {
        storageDir = new File(ApplicationLoader.applicationContext.getDir("edits_history", Context.MODE_PRIVATE), "revisions");
        if (!storageDir.exists()) {
            storageDir.mkdirs();
        }
    }

    private File messageDir(long dialogId, int messageId, int accountId) {
        return new File(storageDir, dialogId + "_" + accountId + File.separator + messageId);
    }

    public void onMessageEdited(TLRPC.Message oldMessage, long dialogId, int accountId) {
        if (oldMessage == null) return;
        try {
            File dir = messageDir(dialogId, oldMessage.id, accountId);
            if (!dir.exists()) dir.mkdirs();
            int size = oldMessage.getObjectSize();
            NativeByteBuffer buffer = new NativeByteBuffer(size);
            oldMessage.serializeToStream(buffer);
            buffer.rewind();
            byte[] data = new byte[buffer.remaining()];
            buffer.buffer.get(data);
            buffer.reuse();
            File f = new File(dir, System.currentTimeMillis() + ".dat");
            try (FileOutputStream fos = new FileOutputStream(f)) {
                fos.write(data);
            }
            FileLog.d("XenonEdits: saved revision " + oldMessage.id + " dialog " + dialogId);
        } catch (Exception e) {
            FileLog.e("XenonEditsHistory save", e);
        }
    }

    public boolean hasAnyRevisions(long dialogId, int messageId, int accountId) {
        File dir = messageDir(dialogId, messageId, accountId);
        File[] files = dir.listFiles();
        return files != null && files.length > 0;
    }

    public List<TLRPC.Message> getRevisions(long dialogId, int messageId, int accountId) {
        List<TLRPC.Message> result = new ArrayList<>();
        File dir = messageDir(dialogId, messageId, accountId);
        File[] files = dir.listFiles();
        if (files == null) return result;
        List<String> names = new ArrayList<>();
        for (File f : files) {
            if (f.getName().endsWith(".dat")) names.add(f.getName());
        }
        Collections.sort(names);
        for (String name : names) {
            try {
                File f = new File(dir, name);
                byte[] data = new byte[(int) f.length()];
                try (FileInputStream fis = new FileInputStream(f)) {
                    fis.read(data);
                }
                NativeByteBuffer buffer = new NativeByteBuffer(data.length);
                buffer.buffer.put(java.nio.ByteBuffer.wrap(data));
                buffer.rewind();
                int constructor = buffer.readInt32(false);
                TLRPC.Message m = TLRPC.Message.TLdeserialize(buffer, constructor, false);
                buffer.reuse();
                if (m != null) {
                    if (m.dialog_id == 0) m.dialog_id = dialogId;
                    result.add(m);
                }
            } catch (Exception e) {
                FileLog.e("XenonEditsHistory load", e);
            }
        }
        return result;
    }

    public void clearForDialog(long dialogId, int accountId) {
        File dir = new File(storageDir, dialogId + "_" + accountId);
        deleteRecursive(dir);
    }

    /**
     * Wipes the entire edits-history store for all dialogs and accounts.
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

    /**
     * Total size in bytes of the edits-history store on disk.
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

    private void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        File[] children = f.listFiles();
        if (children != null) {
            for (File c : children) deleteRecursive(c);
        }
        f.delete();
    }
}