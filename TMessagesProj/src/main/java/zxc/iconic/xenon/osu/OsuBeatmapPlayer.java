package zxc.iconic.xenon.osu;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;
import org.telegram.messenger.*;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AudioPlayerAlert;
import org.telegram.ui.LaunchActivity;
import java.io.*;
import java.util.*;

/** Local map library feeding Telegram's normal audio player. UI state lives on the main thread. */
public final class OsuBeatmapPlayer {
    private static final int REQUEST_AUDIO = 49421;
    private static final DispatchQueue QUEUE = new DispatchQueue("osu-beatmaps");
    private static final DispatchQueue AUDIO_QUEUE = new DispatchQueue("osu-audio-analysis");
    private static final HashMap<String, List<OsuArchive.Track>> cachedSets = new HashMap<>(); // Import queue only.
    private static final HashMap<String, MessageObject> registered = new HashMap<>();
    private static final IdentityHashMap<MessageObject, OsuArchive.Track> tracks = new IdentityHashMap<>();
    private static final HashMap<String, OsuAudioData> audio = new HashMap<>();
    private static final ArrayList<MessageObject> maps = new ArrayList<>(), otherAudio = new ArrayList<>();
    private static int nextId = -1000000000;
    private static boolean includeOtherAudio;
    private static long sourceDialog;
    private static int sourceMessage, sourceAccount;
    public static int seekGeneration;
    public static void onSeek(MessageObject message) { if (track(message) != null) seekGeneration++; }
    private static final HashSet<String> pendingAnalysis = new HashSet<>();
    private static Runnable pickCancelled;
    private static java.util.function.Consumer<Uri> pickedAudio;

    public static boolean supports(String name) {
        if (name == null) return false;
        name = name.toLowerCase(Locale.ROOT);
        return name.endsWith(".osu") || name.endsWith(".osz");
    }
    public static OsuArchive.Track track(MessageObject message) { return tracks.get(message); }
    public static OsuArchive.Track current() { return track(MediaController.getInstance().getPlayingMessageObject()); }
    public static void startAutoplay(Activity activity,MessageObject message){
        OsuArchive.Track selected=track(message);if(selected==null||activity==null)return;
        try{activity.startActivity(new Intent(activity,OsuReplayActivity.class)
                .putExtra(OsuReplayActivity.EXTRA_AUTOPLAY_MAP,selected.mapFile.getAbsolutePath())
                .putExtra(OsuReplayActivity.EXTRA_AUTOPLAY_AUDIO,selected.audioFile.getAbsolutePath()));}
        catch(Exception error){FileLog.e(error);Toast.makeText(activity,LocaleController.getString(R.string.OsuAutoplayFailed),Toast.LENGTH_LONG).show();}
    }
    public static OsuAudioData audio(OsuArchive.Track track) { return audio.get(track.audioFile.getAbsolutePath()); }
    private static void cacheAudio(String path, OsuAudioData data) {
        if (!audio.containsKey(path) && audio.size() >= 2) {
            OsuArchive.Track playing = current();
            String active = playing == null ? null : playing.audioFile.getAbsolutePath();
            Iterator<String> keys = audio.keySet().iterator();
            while (keys.hasNext()) if (!keys.next().equals(active)) { keys.remove(); break; }
        }
        audio.put(path, data);
    }
    public static void ensureAudio(OsuArchive.Track track) {
        String path = track.audioFile.getAbsolutePath();
        if (audio.containsKey(path) || !pendingAnalysis.add(path)) return;
        AUDIO_QUEUE.postRunnable(() -> {
            OsuAudioData decoded = null;
            try { decoded = OsuAudioData.decode(track.audioFile, partial -> AndroidUtilities.runOnUIThread(() -> {
                cacheAudio(path, partial);
            })); } catch (IOException error) { FileLog.e(error); }
            OsuAudioData result = decoded;
            AndroidUtilities.runOnUIThread(() -> {
                cacheAudio(path, result); pendingAnalysis.remove(path);
            });
        });
    }
    public static boolean isMapPlaylist() {
        for (MessageObject item : MediaController.getInstance().getPlaylist()) if (track(item) != null) return true;
        return false;
    }
    public static boolean includesOtherAudio() { return includeOtherAudio; }
    public static CharSequence author(MessageObject message, Theme.ResourcesProvider resources) {
        String value = message.getMusicAuthor();
        OsuArchive.Track track = track(message);
        if (track == null || !track.map.hasKiai) return value;
        int star = track.map.artist.length() + 3;
        if (star >= value.length() || value.charAt(star) != '★') return value;
        android.text.SpannableStringBuilder styled = new android.text.SpannableStringBuilder(value);
        styled.setSpan(new org.telegram.ui.Components.ForegroundColorSpanThemable(Theme.key_player_buttonActive, resources),
                star, star + 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return styled;
    }

    private static File library() {
        File root = new File(ApplicationLoader.getFilesDirFixed(), "osu-beatmaps");
        root.mkdirs(); return root;
    }
    public static void open(File source, String name, Activity activity, Theme.ResourcesProvider resources) {
        open(source, name, activity, resources, null);
    }
    public static void open(File source, String name, Activity activity, Theme.ResourcesProvider resources, MessageObject context) {
        sourceDialog = context == null ? 0 : context.getDialogId();
        sourceMessage = context == null ? 0 : context.getId();
        sourceAccount = context == null ? UserConfig.selectedAccount : context.currentAccount;
        importAndPlay(source, name, null, activity, resources);
    }
    private static void importAndPlay(File source, String name, File suppliedAudio, Activity activity, Theme.ResourcesProvider resources) {
        AlertDialog progress = new AlertDialog(activity, AlertDialog.ALERT_TYPE_SPINNER);
        progress.setCanCancel(false); progress.show();
        final int account = sourceAccount;
        QUEUE.postRunnable(() -> {
            List<OsuArchive.Track> loaded = new ArrayList<>(), imported = null;
            Exception failure = null;
            try {
                File[] sets = library().listFiles();
                if (sets != null) for (File set : sets) if (set.isDirectory()) {
                    try {
                        String key = set.getCanonicalPath();
                        List<OsuArchive.Track> cached = cachedSets.get(key);
                        if (cached == null) { cached = OsuArchive.load(set); cachedSets.put(key, cached); }
                        loaded.addAll(cached);
                    } catch (IOException error) { FileLog.e(error); }
                }
                File directory = new File(library(), fingerprint(source, suppliedAudio));
                if(directory.isDirectory()&&name.toLowerCase(Locale.ROOT).endsWith(".osz"))OsuArchive.restoreMedia(source,directory);
                imported = cachedSets.get(directory.getCanonicalPath());
                if (imported == null) {
                    imported = directory.isDirectory() ? OsuArchive.load(directory) : OsuArchive.importFile(source, name, directory, suppliedAudio);
                    cachedSets.put(directory.getCanonicalPath(), imported);
                }
                if (imported.isEmpty()) throw new IOException("No playable beatmaps");
                loaded.addAll(imported);
            } catch (Exception error) { failure = error; FileLog.e(error); }
            finally { if (suppliedAudio != null) suppliedAudio.delete(); }
            final Exception resultError = failure;
            final List<OsuArchive.Track> result = loaded, opened = imported;
            AndroidUtilities.runOnUIThread(() -> {
                progress.dismiss();
                if (activity.isFinishing()) return;
                if (resultError != null) {
                    if (resultError instanceof OsuArchive.MissingAudio && suppliedAudio == null) {
                        chooseAudio(source, name, activity, resources);
                    } else Toast.makeText(activity, LocaleController.getString(R.string.OsuMapOpenFailed), Toast.LENGTH_LONG).show();
                    return;
                }
                MessageObject selected = null;
                OsuArchive.Track preferred = OsuArchive.preferredTrack(opened);
                for (OsuArchive.Track entry : result) {
                    String key = account + ":" + entry.mapFile.getAbsolutePath();
                    MessageObject message = registered.get(key);
                    if (message == null) { message = message(entry, account); maps.add(message); tracks.put(message, entry); registered.put(key, message); }
                    if (entry == preferred) selected = message;
                }
                rememberOtherAudio(); includeOtherAudio = false;
                MediaController.getInstance().currentSavedMusicList = null;
                ArrayList<MessageObject> queue = new ArrayList<>();
                for (MessageObject item : maps) if (item.currentAccount == account) queue.add(item);
                MediaController.getInstance().setPlaylist(queue, selected, 0, false, null);
                BaseFragment fragment = LaunchActivity.getSafeLastFragment();
                AudioPlayerAlert sheet = new AudioPlayerAlert(activity, resources);
                if (fragment != null && fragment.getParentActivity() == activity) fragment.showDialog(sheet); else sheet.show();
            });
        });
    }
    private static String fingerprint(File source, File suppliedAudio) throws Exception {
        java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[32768];
        for (File file : suppliedAudio == null ? new File[]{source} : new File[]{source, suppliedAudio}) {
            try (InputStream input = new FileInputStream(file)) { for (int n; (n = input.read(buffer)) >= 0;) digest.update(buffer, 0, n); }
        }
        return Utilities.bytesToHex(digest.digest());
    }
    private static void chooseAudio(File source, String name, Activity activity, Theme.ResourcesProvider resources) {
        new AlertDialog.Builder(activity, resources).setTitle(LocaleController.getString(R.string.OsuMapAudioMissing))
                .setMessage(LocaleController.getString(R.string.OsuMapChooseAudioHint))
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .setPositiveButton(LocaleController.getString(R.string.OsuMapChooseAudio), (dialog, which) -> {
                    pickedAudio = uri -> {
                        QUEUE.postRunnable(() -> {
                            File temporary = new File(activity.getCacheDir(), "osu-audio-" + UUID.randomUUID());
                            try (InputStream input = activity.getContentResolver().openInputStream(uri); OutputStream output = new FileOutputStream(temporary)) {
                                byte[] buffer = new byte[32768]; long size = 0;
                                for (int n; (n = input.read(buffer)) >= 0;) {
                                    size += n; if (size > 64 * 1024 * 1024) throw new IOException("Audio too large");
                                    output.write(buffer, 0, n);
                                }
                                AndroidUtilities.runOnUIThread(() -> importAndPlay(source, name, temporary, activity, resources));
                            } catch (Exception error) { FileLog.e(error); temporary.delete(); AndroidUtilities.runOnUIThread(() -> Toast.makeText(activity, LocaleController.getString(R.string.OsuMapOpenFailed), Toast.LENGTH_LONG).show()); }
                        });
                    };
                    pickCancelled = () -> { };
                    Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("audio/*").addCategory(Intent.CATEGORY_OPENABLE);
                    try { activity.startActivityForResult(intent, REQUEST_AUDIO); }
                    catch (Exception error) { pickedAudio = null; pickCancelled = null; FileLog.e(error); }
                }).show();
    }
    public static boolean onActivityResult(int request, int result, Intent data) {
        if (request != REQUEST_AUDIO) return false;
        java.util.function.Consumer<Uri> callback = pickedAudio; pickedAudio = null;
        if (result == Activity.RESULT_OK && data != null && data.getData() != null && callback != null) callback.accept(data.getData());
        else if (pickCancelled != null) pickCancelled.run();
        pickCancelled = null; return true;
    }
    private static MessageObject message(OsuArchive.Track track, int account) {
        TLRPC.TL_message message = new TLRPC.TL_message();
        message.id = nextId--; message.out = true; message.noforwards = true;
        message.peer_id = new TLRPC.TL_peerUser(); message.from_id = new TLRPC.TL_peerUser();
        message.peer_id.user_id = message.from_id.user_id = UserConfig.getInstance(account).getClientUserId();
        message.date = (int) (System.currentTimeMillis() / 1000); message.message = "";
        message.attachPath = track.audioFile.getAbsolutePath();
        message.flags |= TLRPC.MESSAGE_FLAG_HAS_MEDIA | TLRPC.MESSAGE_FLAG_HAS_FROM_ID;
        message.media = new TLRPC.TL_messageMediaDocument(); message.media.flags |= 3;
        TLRPC.Document document = message.media.document = new TLRPC.TL_document();
        document.file_reference = new byte[0]; document.mime_type = "audio/" + FileLoader.getFileExtension(track.audioFile);
        document.size = track.audioFile.length(); document.date = message.date;
        TLRPC.TL_documentAttributeAudio attribute = new TLRPC.TL_documentAttributeAudio();
        attribute.title = track.map.title;
        attribute.performer = track.map.authorLabel();
        attribute.flags = 3;
        OsuAudioData decoded = audio(track); attribute.duration = decoded == null ? 0 : (int) (decoded.durationMs / 1000);
        document.attributes.add(attribute);
        TLRPC.TL_documentAttributeFilename filename = new TLRPC.TL_documentAttributeFilename();
        filename.file_name = track.audioFile.getName(); document.attributes.add(filename);
        MessageObject result = new MessageObject(account, message, false, true); result.mediaExists = true; result.attachPathExists = true;
        return result;
    }
    private static void rememberOtherAudio() {
        for (MessageObject message : MediaController.getInstance().getPlaylist()) if (track(message) == null) remember(message);
    }
    private static void remember(MessageObject message) {
        for (MessageObject item : otherAudio) if (item.currentAccount == message.currentAccount
                && item.getId() == message.getId() && item.getDialogId() == message.getDialogId()) return;
        otherAudio.add(message);
    }
    public static void toggleOtherAudio() {
        MessageObject current = MediaController.getInstance().getPlayingMessageObject();
        if (current == null) return;
        rememberOtherAudio(); includeOtherAudio = !includeOtherAudio;
        if (includeOtherAudio && sourceDialog != 0 && sourceAccount == current.currentAccount) {
            final long dialog = sourceDialog;
            MediaDataController.getInstance(sourceAccount).loadMusic(dialog, sourceMessage, sourceMessage, loaded -> {
                if (!includeOtherAudio || sourceDialog != dialog || !isMapPlaylist()) return;
                for (MessageObject item : loaded) remember(item);
                rebuildQueue();
            });
        }
        rebuildQueue();
    }
    private static void rebuildQueue() {
        MessageObject current = MediaController.getInstance().getPlayingMessageObject();
        if (current == null) return;
        ArrayList<MessageObject> list = new ArrayList<>();
        for (MessageObject map : maps) if (map.currentAccount == current.currentAccount) list.add(map);
        if (includeOtherAudio) for (MessageObject item : otherAudio) if (item.currentAccount == current.currentAccount
                && (sourceDialog == 0 || item.getDialogId() == sourceDialog)) list.add(item);
        MessageObject selected = !includeOtherAudio && track(current) == null && !list.isEmpty() ? list.get(0) : current;
        if (selected == current) {
            // setPlaylist returns early for the same object; update its live list without restarting audio.
            MediaController.getInstance().replaceLocalMapPlaylist(list);
        } else MediaController.getInstance().setPlaylist(list, selected, 0, false, null);
        NotificationCenter.getInstance(current.currentAccount).postNotificationName(NotificationCenter.moreMusicDidLoad, 0);
    }
}
