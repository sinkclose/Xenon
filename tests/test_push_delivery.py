"""Exercise push routing, completion and keep-alive failure recovery without Android."""

import re
import unittest

from test_glass_blur import ROOT, method
from test_glass_chat_refresh import run_java

MESSENGER = ROOT / "org/telegram/messenger"


class PushDeliveryTest(unittest.TestCase):
    def test_fcm_routes_only_telegram_payloads_to_decryption(self):
        source = (MESSENGER / "GcmPushListenerService.java").read_text()
        receive = method(source, "public void onMessageReceived(")
        run_java(self, "PushRoutingHarness", r"""
import java.util.*;
public class PushRoutingHarness {
    static class RemoteMessage {
        Map<String, String> data = new HashMap<>();
        String getFrom() { return "server"; }
        Map<String, String> getData() { return data; }
        long getSentTime() { return 42; }
    }
    static class TextUtils {
        static boolean isEmpty(String s) { return s == null || s.isEmpty(); }
    }
    static class BuildVars { static boolean LOGS_ENABLED; }
    static class FileLog { static void d(String s) {} }
    static class AndroidUtilities {
        static void runOnUIThread(Runnable r) { r.run(); }
    }
    static class ApplicationLoader { static void postInitApplication() { initialized = true; } }
    static class PushHelper {
        static void processRemoteMessage(String s) {
            check(initialized, "remote action before initialization"); actions++;
        }
    }
    static class PushListenerController {
        static final int PUSH_TYPE_FIREBASE = 2;
        static void processRemoteMessage(int type, String data, long time) {
            check(type == 2 && time == 42 && "encrypted".equals(data), "wrong payload"); pushes++;
        }
    }
    static int pushes, actions;
    static boolean initialized;
    RECEIVE
    static void check(boolean b, String reason) { if (!b) throw new AssertionError(reason); }
    public static void main(String[] args) {
        PushRoutingHarness service = new PushRoutingHarness();
        RemoteMessage m = new RemoteMessage();
        service.onMessageReceived(m);
        m.data.put("other", "message"); service.onMessageReceived(m);
        m.data.put("p", ""); service.onMessageReceived(m);
        check(pushes == 0 && actions == 0 && !initialized, "empty payload woke Telegram");
        m.data.clear(); m.data.put("n", "action"); service.onMessageReceived(m);
        check(actions == 1, "lost remote action");
        m.data.put("p", "encrypted"); service.onMessageReceived(m);
        check(pushes == 1 && actions == 1, "Telegram payload lost priority");
    }
}
""".replace("RECEIVE", receive))

    def test_each_push_waits_for_its_own_notification(self):
        source = (MESSENGER / "PushListenerController.java").read_text()
        process = method(source, "public static void processRemoteMessage(")
        # Stub payload decoding; retain the production dispatch and completion wrapper.
        decode = method(process, "Utilities.stageQueue.postRunnable(() ->")
        process = process.replace(decode, "Utilities.stageQueue.postRunnable(() -> { completions.add(countDownLatch); }")
        process = process.replace("@PushType ", "")
        shared = re.search(r"private static CountDownLatch countDownLatch[^;]+;", source)
        run_java(self, "PushCompletionHarness", r"""
import java.util.concurrent.*;
public class PushCompletionHarness {
    static class BuildVars { static boolean LOGS_ENABLED, DEBUG_VERSION; }
    static class FileLog { static void d(String s) {} }
    static class SystemClock { static long elapsedRealtime() { return 0; } }
    static class ApplicationLoader { static void postInitApplication() {} }
    static class Queue {
        BlockingQueue<Runnable> pending = new LinkedBlockingQueue<>();
        void postRunnable(Runnable r) { pending.add(r); }
        void runNext() throws Exception {
            Runnable r = pending.poll(5, TimeUnit.SECONDS);
            check(r != null, "missing dispatch"); r.run();
        }
    }
    static class Utilities { static Queue stageQueue = new Queue(); }
    static class AndroidUtilities {
        static Queue ui = new Queue();
        static void runOnUIThread(Runnable r) { ui.postRunnable(r); }
    }
    static final int PUSH_TYPE_FIREBASE = 2;
    static BlockingQueue<CountDownLatch> completions = new LinkedBlockingQueue<>();
    SHARED
    PROCESS
    static void check(boolean b, String reason) { if (!b) throw new AssertionError(reason); }
    public static void main(String[] args) throws Exception {
        for (int i = 0; i < 3; ++i) {
            Thread worker = new Thread(() -> processRemoteMessage(2, "encrypted", 0));
            worker.setDaemon(true); worker.start();
            AndroidUtilities.ui.runNext(); Utilities.stageQueue.runNext();
            CountDownLatch done = completions.poll(5, TimeUnit.SECONDS);
            check(done != null && done.getCount() == 1, "later push reused a completed latch");
            done.countDown(); worker.join(5000);
            check(!worker.isAlive(), "notification completion did not release FCM");
        }
    }
}
""".replace("PROCESS", process).replace("SHARED", shared.group() if shared else ""))

    def test_keep_alive_can_retry_after_enqueue_failure(self):
        source = (MESSENGER / "KeepAliveJob.java").read_text()
        start = method(source, "public static void startJob()")
        finish = method(source, "private static void finishJobInternal()")
        handle = method(source, "protected void onHandleWork(")
        run_java(self, "KeepAliveHarness", r"""
import java.util.concurrent.CountDownLatch;
public class KeepAliveHarness {
    static class BuildVars { static boolean LOGS_ENABLED; }
    static class FileLog { static void d(String s) {} }
    static class Intent {}
    static class ApplicationLoader { static Object applicationContext = new Object(); }
    static class Utilities { static Queue globalQueue = new Queue(); }
    static class Queue {
        void postRunnable(Runnable r) { r.run(); }
        void postRunnable(Runnable r, long delay) {}
        void cancelRunnable(Runnable r) {}
    }
    static class KeepAliveJob {
        static volatile CountDownLatch countDownLatch;
        static volatile boolean startingJob;
        static final Object sync = new Object();
        static int attempts;
        static Runnable finishJobByTimeoutRunnable = KeepAliveJob::finishJobInternal;
        static void enqueueWork(Object c, Class<?> type, int id, Intent intent) {
            if (++attempts == 1) throw new IllegalStateException("scheduler unavailable");
        }
        START
        FINISH
        HANDLE
    }
    static void check(boolean b, String reason) { if (!b) throw new AssertionError(reason); }
    public static void main(String[] args) {
        KeepAliveJob.startJob();
        check(!KeepAliveJob.startingJob, "enqueue failure permanently blocks later jobs");
        KeepAliveJob.startJob();
        check(KeepAliveJob.attempts == 2 && KeepAliveJob.startingJob, "job did not retry");
        KeepAliveJob.startJob();
        check(KeepAliveJob.attempts == 2, "duplicate keep-alive job");
        KeepAliveJob.finishJobInternal();
        check(!KeepAliveJob.startingJob, "queued job did not finish");
        KeepAliveJob.startJob();
        Thread.currentThread().interrupt();
        new KeepAliveJob().onHandleWork(new Intent());
        check(KeepAliveJob.countDownLatch == null && !KeepAliveJob.startingJob,
              "interrupted work permanently blocks later jobs");
        KeepAliveJob.startJob();
        check(KeepAliveJob.attempts == 4, "job did not retry after interruption");
    }
}
""".replace("START", start).replace("FINISH", finish).replace("HANDLE", handle))


if __name__ == "__main__":
    unittest.main()
