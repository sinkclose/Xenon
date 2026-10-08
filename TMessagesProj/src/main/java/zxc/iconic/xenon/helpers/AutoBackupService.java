package zxc.iconic.xenon.helpers;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import org.telegram.messenger.*;
import org.telegram.tgnet.TLRPC;
import zxc.iconic.xenon.NekoConfig;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class AutoBackupService extends JobService {
    private static final int JOB_ID = 0x58424b;
    private static boolean observing;
    public static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("xenon_auto_backup", Context.MODE_PRIVATE);
    }
    public static boolean enabled() { return prefs().getBoolean("enabled", false); }
    public static long interval() { return prefs().getLong("interval", 86_400_000L); }
    public static int account() { return prefs().getInt("account", UserConfig.selectedAccount); }
    private static boolean validAccount(int account) {
        return account >= 0 && account < UserConfig.MAX_ACCOUNT_COUNT && UserConfig.getInstance(account).isClientActivated()
                && UserConfig.getInstance(account).getClientUserId() == prefs().getLong("user", 0);
    }
    public static void selectAccount(int account) {
        prefs().edit().putInt("account", account).putLong("user", UserConfig.getInstance(account).getClientUserId()).apply();
    }
    public static void setEnabled(boolean enabled) {
        if (enabled && !validAccount(account())) selectAccount(UserConfig.selectedAccount);
        prefs().edit().putBoolean("enabled", enabled).putLong("next", System.currentTimeMillis() + interval()).apply();
        schedule();
        if (enabled) runBackup(true);
    }
    public static void setInterval(long interval) {
        prefs().edit().putLong("interval", interval).putLong("next", System.currentTimeMillis() + interval).apply();
        schedule();
    }
    public static void initialize() {
        if (!observing) {
            observing = true;
            for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
                NotificationCenter.getInstance(a).addObserver((id, account, args) -> {
                    if (args.length < 3 || !(args[2] instanceof TLRPC.Message)) return;
                    TLRPC.Message message = (TLRPC.Message) args[2];
                    SharedPreferences p = prefs();
                    String key = "pending_" + UserConfig.getInstance(account).getClientUserId();
                    String caption = p.getString(key + "caption", "");
                    if (!caption.isEmpty() && caption.equals(message.message) && message.dialog_id == UserConfig.getInstance(account).getClientUserId()) {
                        p.edit().putString("hash_" + UserConfig.getInstance(account).getClientUserId(), p.getString(key + "hash", ""))
                                .remove(key + "caption").remove(key + "hash").remove(key + "time").apply();
                    }
                }, NotificationCenter.messageReceivedByServer);
            }
        }
        JobScheduler scheduler = (JobScheduler) ApplicationLoader.applicationContext.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (scheduler != null) {
            for (JobInfo job : scheduler.getAllPendingJobs()) if (job.getId() == JOB_ID) return;
            schedule();
        }
    }
    public static void schedule() {
        Context context = ApplicationLoader.applicationContext;
        JobScheduler scheduler = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (scheduler == null) return;
        if (!enabled()) { scheduler.cancel(JOB_ID); return; }
        long delay = Math.max(0, prefs().getLong("next", 0) - System.currentTimeMillis());
        scheduler.schedule(new JobInfo.Builder(JOB_ID, new ComponentName(context, AutoBackupService.class))
                .setMinimumLatency(delay).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPersisted(true).build());
    }
    public static String runBackup(boolean forced) {
        if (!enabled()) return null;
        int account = account();
        if (!validAccount(account)) return null; // Never fall back to a different logged-in user.
        return sendBackup(account, forced);
    }
    public static int targetAccount(int fallback) {
        return validAccount(account()) ? account() : fallback;
    }
    public static String saveNow(int account) {
        initialize();
        return sendBackup(account, true);
    }
    private static String sendBackup(int account, boolean forced) {
        if (account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT || !UserConfig.getInstance(account).isClientActivated()) return null;
        SharedPreferences p = prefs();
        long user = UserConfig.getInstance(account).getClientUserId();
        String key = "pending_" + user;
        if (!forced && System.currentTimeMillis() - p.getLong(key + "time", 0) < 3_600_000L) return null;
        try {
            String hash = AutoBackupPolicy.fingerprint(ApplicationLoader.applicationContext.getSharedPreferences("nekoconfig", Context.MODE_PRIVATE).getAll());
            if (!AutoBackupPolicy.shouldSend(forced, p.getBoolean("onlyChanged", true), hash, p.getString("hash_" + user, ""))) return null;
            long now = System.currentTimeMillis();
            File file = AutoBackupSender.createFile(ApplicationLoader.applicationContext, user, now);
            try (FileOutputStream stream = new FileOutputStream(file)) {
                stream.write(NekoConfig.exportConfigs().getBytes(StandardCharsets.UTF_8));
            }
            String caption = LocaleController.formatString("XenonAutoBackupCaption", R.string.XenonAutoBackupCaption,
                    new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date(now)));
            p.edit().putString(key + "hash", hash).putString(key + "caption", caption).putLong(key + "time", now).apply();
            try {
                AutoBackupSender.send(account, user, file, caption);
            } catch (Exception e) {
                p.edit().remove(key + "hash").remove(key + "caption").remove(key + "time").apply();
                throw e;
            }
            return caption;
        } catch (Exception e) { FileLog.e(e); }
        return null;
    }
    private Runnable finishJob;
    private NotificationCenter.NotificationCenterDelegate confirmation;
    private int sendingAccount;
    private boolean stopped;

    private void clearJob() {
        if (finishJob != null) AndroidUtilities.cancelRunOnUIThread(finishJob);
        finishJob = null;
        if (confirmation != null) NotificationCenter.getInstance(sendingAccount).removeObserver(confirmation, NotificationCenter.messageReceivedByServer);
        confirmation = null;
    }

    @Override public boolean onStartJob(JobParameters params) {
        stopped = false;
        ApplicationLoader.postInitApplication();
        AndroidUtilities.runOnUIThread(() -> {
            if (stopped) return;
            if (!enabled()) { jobFinished(params, false); return; }
            sendingAccount = account();
            String caption = runBackup(false);
            finishJob = () -> {
                clearJob();
                prefs().edit().putLong("next", System.currentTimeMillis() + interval()).apply();
                jobFinished(params, false);
                schedule();
            };
            if (caption == null) { finishJob.run(); return; }
            // Keep the process alive while Telegram uploads the configuration.
            confirmation = (id, account, args) -> {
                if (args.length > 2 && args[2] instanceof TLRPC.Message
                        && caption.equals(((TLRPC.Message) args[2]).message) && finishJob != null) finishJob.run();
            };
            NotificationCenter.getInstance(sendingAccount).addObserver(confirmation, NotificationCenter.messageReceivedByServer);
            AndroidUtilities.runOnUIThread(finishJob, 120_000);
        });
        return true;
    }
    @Override public boolean onStopJob(JobParameters params) {
        stopped = true;
        clearJob();
        return enabled();
    }
}
