package zxc.iconic.xenon.helpers;

import org.telegram.messenger.*;
import org.telegram.tgnet.*;
import zxc.iconic.xenon.NekoConfig;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Searches and downloads the newest matching document from the chosen account's Saved Messages. */
public final class CloudBackupRestore implements NotificationCenter.NotificationCenterDelegate {
    public interface Callback { void done(int messageResource); }
    private final int account;
    private final long userId;
    private final Callback callback;
    private boolean finished;
    private String downloading;
    private int requestId;
    private final Runnable timeout = () -> complete(R.string.ImportSettingsFailed);

    private CloudBackupRestore(int account, Callback callback) {
        this.account = account;
        this.userId = UserConfig.getInstance(account).getClientUserId();
        this.callback = callback;
    }
    public static CloudBackupRestore restore(int account, Callback callback) {
        CloudBackupRestore operation = new CloudBackupRestore(account, callback);
        AndroidUtilities.runOnUIThread(operation.timeout, 120_000);
        operation.search(0);
        return operation;
    }
    public void cancel() {
        if (finished) return;
        if (requestId != 0) ConnectionsManager.getInstance(account).cancelRequest(requestId, true);
        finished = true;
        cleanup();
    }
    private boolean sameUser() {
        return UserConfig.getInstance(account).isClientActivated() && UserConfig.getInstance(account).getClientUserId() == userId;
    }
    private void search(int offset) {
        if (finished) return;
        if (!sameUser()) { complete(R.string.ImportSettingsFailed); return; }
        TLRPC.TL_messages_search request = new TLRPC.TL_messages_search();
        request.peer = new TLRPC.TL_inputPeerSelf();
        request.filter = new TLRPC.TL_inputMessagesFilterDocument();
        request.q = "";
        request.limit = 100;
        request.offset_id = offset;
        requestId = ConnectionsManager.getInstance(account).sendRequest(request, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            if (finished) return;
            if (error != null || !(response instanceof TLRPC.messages_Messages) || !sameUser()) {
                complete(R.string.ImportSettingsFailed); return;
            }
            TLRPC.messages_Messages result = (TLRPC.messages_Messages) response;
            TLRPC.Message newest = null;
            int nextOffset = Integer.MAX_VALUE;
            for (TLRPC.Message message : result.messages) {
                if (message.id <= 0) continue;
                nextOffset = Math.min(nextOffset, message.id);
                TLRPC.Document document = MessageObject.getDocument(message);
                if (document != null && AutoBackupPolicy.isConfigFile(FileLoader.getDocumentFileName(document))
                        && (newest == null || message.id > newest.id)) newest = message;
            }
            if (newest != null) { download(newest); return; }
            if (result.messages.isEmpty() || nextOffset == Integer.MAX_VALUE || offset != 0 && nextOffset >= offset) {
                complete(R.string.XenonBackupNotFound); return;
            }
            search(nextOffset);
        }));
    }
    private void download(TLRPC.Message message) {
        FileLoader loader = FileLoader.getInstance(account);
        File file = loader.getPathToMessage(message);
        if (file.isFile()) { importFile(file); return; }
        TLRPC.Document document = MessageObject.getDocument(message);
        downloading = FileLoader.getAttachFileName(document);
        NotificationCenter.getInstance(account).addObserver(this, NotificationCenter.fileLoaded);
        NotificationCenter.getInstance(account).addObserver(this, NotificationCenter.fileLoadFailed);
        loader.loadFile(document, message, FileLoader.PRIORITY_HIGH, 0);
    }
    @Override public void didReceivedNotification(int id, int account, Object... args) {
        if (finished || args.length == 0 || !args[0].equals(downloading)) return;
        if (id == NotificationCenter.fileLoadFailed) { complete(R.string.ImportSettingsFailed); return; }
        if (args.length > 1 && args[1] instanceof File) importFile((File) args[1]);
        else complete(R.string.ImportSettingsFailed);
    }
    private void importFile(File file) {
        if (!sameUser()) { complete(R.string.ImportSettingsFailed); return; }
        try {
            String json = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            new org.json.JSONObject(json); // Reject malformed JSON before modifying preferences.
            NekoConfig.importConfigs(json);
            complete(R.string.ImportSettingsSuccess);
        } catch (Exception e) {
            FileLog.e(e);
            complete(R.string.ImportSettingsFailed);
        }
    }
    private void cleanup() {
        if (requestId != 0) ConnectionsManager.getInstance(account).cancelRequest(requestId, true);
        requestId = 0;
        AndroidUtilities.cancelRunOnUIThread(timeout);
        NotificationCenter.getInstance(account).removeObserver(this, NotificationCenter.fileLoaded);
        NotificationCenter.getInstance(account).removeObserver(this, NotificationCenter.fileLoadFailed);
    }
    private void complete(int result) {
        if (finished) return;
        finished = true;
        cleanup();
        callback.done(result);
    }
}
