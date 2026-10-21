package zxc.iconic.xenon.triangles;

import android.media.audiofx.Visualizer;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import org.telegram.messenger.FileLog;
import java.lang.ref.WeakReference;

/** View-owned Visualizer(0). No MediaProjection, service, microphone or audio storage. */
public final class LogoAudioVisualizer {
    public interface Listener { void changed(boolean error); }
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Object SPECTRUM_LOCK = new Object();
    private static final float[] spectrum = new float[200];
    private static WeakReference<Listener> listener = new WeakReference<>(null);
    private static Handler worker;
    private static volatile Session current;
    public static volatile float amplitude, beatAmplitude, beatLength = 500;
    public static volatile long sampleTime, soundTime, beatTime, beatSequence;

    private LogoAudioVisualizer() { }
    public static void setListener(Listener value) { listener = new WeakReference<>(value); }
    public static void clearListener(Listener value) { if (listener.get() == value) listener.clear(); }
    public static boolean isRequested() { return current != null; }
    private static void notifyState(boolean error) {
        Listener callback = listener.get();
        if (callback != null) callback.changed(error);
    }

    public static void start() {
        if (current != null) return;
        if (worker == null) {
            HandlerThread thread = new HandlerThread("osu-logo-visualizer");
            thread.start();
            worker = new Handler(thread.getLooper());
        }
        Session next = new Session();
        current = next;
        clearAudio();
        worker.post(next::initialize);
        notifyState(false);
    }

    public static void stop() {
        Session old = current;
        current = null;
        clearAudio();
        if (old != null) worker.post(old::destroy);
        notifyState(false);
    }

    public static void setConsumerVisible(boolean visible) {
        Session session = current;
        if (session == null || session.visible == visible) return;
        session.visible = visible;
        worker.post(session::applyVisibility);
    }

    public static void copySpectrum(float[] target) {
        synchronized (SPECTRUM_LOCK) { System.arraycopy(spectrum, 0, target, 0, spectrum.length); }
    }

    private static void clearAudio() {
        synchronized (SPECTRUM_LOCK) {
            amplitude = beatAmplitude = 0;
            sampleTime = soundTime = beatTime = 0;
            beatLength = 500;
            java.util.Arrays.fill(spectrum, 0);
        }
    }

    private static final class Session {
        private Visualizer visualizer;
        private volatile boolean visible;
        private boolean enabled;
        private byte[] wave, fft;
        private int sampleRate;
        private long previousSample;
        private OsuBeatDetector detector;
        private final OsuVisualizerSpectrum decoded = new OsuVisualizerSpectrum();
        private final Runnable poll = this::sample;

        private static void requireSuccess(int result) {
            if (result != Visualizer.SUCCESS) throw new IllegalStateException("Visualizer status " + result);
        }

        private void initialize() {
            if (current != this) return;
            try {
                visualizer = new Visualizer(0);
                requireSuccess(visualizer.setEnabled(false));
                int[] range = Visualizer.getCaptureSizeRange();
                int size = Math.max(range[0], Math.min(1024, range[1]));
                requireSuccess(visualizer.setCaptureSize(size));
                requireSuccess(visualizer.setScalingMode(Visualizer.SCALING_MODE_NORMALIZED));
                sampleRate = visualizer.getSamplingRate() / 1000; // API returns millihertz.
                if (sampleRate <= 0) throw new IllegalStateException("Invalid Visualizer sample rate");
                wave = new byte[size]; fft = new byte[size];
                detector = new OsuBeatDetector(sampleRate);
                applyVisibility();
            } catch (Exception e) { fail(e); }
        }

        private void applyVisibility() {
            if (current != this || visualizer == null) return;
            try {
                worker.removeCallbacks(poll);
                if (enabled != visible) {
                    requireSuccess(visualizer.setEnabled(visible));
                    enabled = visible;
                    previousSample = 0;
                    detector = new OsuBeatDetector(sampleRate);
                }
                if (enabled) worker.post(poll);
                else clearAudio();
            } catch (Exception e) { fail(e); }
        }

        private void sample() {
            if (current != this || !visible || !enabled) return;
            try {
                requireSuccess(visualizer.getWaveForm(wave));
                requireSuccess(visualizer.getFft(fft));
                decoded.decode(wave, fft, sampleRate);
                long now = SystemClock.uptimeMillis();
                float elapsed = previousSample == 0 ? 20 : now - previousSample;
                previousSample = now;
                boolean beat = detector.processEnergy(decoded.amplitude, decoded.bass, decoded.rms, now, elapsed);
                synchronized (SPECTRUM_LOCK) {
                    if (current != this) return;
                    amplitude = decoded.amplitude;
                    sampleTime = now;
                    if (decoded.rms > 0.002f) soundTime = now;
                    System.arraycopy(decoded.bands, 0, spectrum, 0, spectrum.length);
                    if (beat) {
                        beatAmplitude = amplitude; beatLength = detector.beatLength; beatTime = now; beatSequence++;
                    }
                }
                if (current == this && visible) worker.postDelayed(poll, 20);
            } catch (Exception e) { fail(e); }
        }

        private void fail(Exception error) {
            FileLog.e(error);
            destroy();
            MAIN.post(() -> {
                if (current != this) return;
                current = null;
                clearAudio();
                notifyState(true);
            });
        }

        private void destroy() {
            worker.removeCallbacks(poll);
            if (visualizer != null) {
                try { visualizer.setEnabled(false); } catch (Exception ignored) { }
                try { visualizer.release(); } catch (Exception error) { FileLog.e(error); }
                visualizer = null;
            }
            enabled = false;
        }
    }
}
