package zxc.iconic.xenon.osu;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.DispatchQueue;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.R;
import java.io.InputStream;

public final class OsuSampleBank {
    public static volatile OsuWave[] samples;
    private static boolean requested;
    public static synchronized void request() {
        if (requested) return;
        requested = true;
        new DispatchQueue("osu-hit-samples").postRunnable(() -> {
            try {
                int[] ids = {R.raw.osu_logo_downbeat, R.raw.osu_logo_heartbeat, R.raw.osu_logo_downbeat_old,
                        R.raw.osu_logo_heartbeat_old, R.raw.osu_fountain_start, R.raw.osu_fountain_loop};
                OsuWave[] loaded = new OsuWave[ids.length];
                for (int i = 0; i < ids.length; i++) try (InputStream stream = ApplicationLoader.applicationContext.getResources().openRawResource(ids[i])) { loaded[i] = OsuWave.read(stream); }
                samples = loaded;
            } catch (Exception error) { FileLog.e(error); }
        });
    }
}
