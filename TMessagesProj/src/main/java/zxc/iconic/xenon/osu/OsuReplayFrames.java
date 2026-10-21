package zxc.iconic.xenon.osu;

import java.io.IOException;
import java.util.Arrays;

/** Bounded osu!standard replay timeline, in original beatmap milliseconds. */
public final class OsuReplayFrames {
    private int[] times = new int[4096], keys = new int[4096];
    private float[] xs = new float[4096], ys = new float[4096];
    public int size;
    public static final class Sample { public float x, y; public int keys; }
    static OsuReplayFrames generated(int[] times,float[] xs,float[] ys,int[] keys)throws IOException{
        int count=times.length;if(count==0||count>1_000_000||xs.length!=count||ys.length!=count||keys.length!=count)throw new IOException("Invalid generated timeline");
        for(int i=0;i<count;i++)if(times[i]<0||times[i]>86400000||(i>0&&times[i]<=times[i-1])||!Float.isFinite(xs[i])||!Float.isFinite(ys[i]))throw new IOException("Invalid generated frame");
        OsuReplayFrames frames=new OsuReplayFrames();frames.times=times;frames.xs=xs;frames.ys=ys;frames.keys=keys;frames.size=count;return frames;
    }
    public static OsuReplayFrames parse(String text) throws IOException {
        if (text == null || text.length() > 32 * 1024 * 1024) throw new IOException("Invalid replay frames");
        OsuReplayFrames frames = new OsuReplayFrames();
        long time = 0;
        try {
            for (int offset = 0; offset < text.length();) {
                int end = text.indexOf(',', offset); if (end < 0) end = text.length();
                String record = text.substring(offset, end).trim(); offset = end + 1;
                if (record.isEmpty()) continue;
                String[] fields = record.split("\\|", -1);
                if (fields.length != 4) throw new IOException("Invalid replay frame");
                long delta = Long.parseLong(fields[0]);
                if (delta == -12345) continue; // RNG seed, never a movement.
                if (delta < 0 && frames.size > 0) throw new IOException("Replay time moves backwards");
                time += delta;
                float x = Float.parseFloat(fields[1]), y = Float.parseFloat(fields[2]);
                int keys = Integer.parseInt(fields[3]);
                if (time < 0) continue;
                if (time > 24 * 60 * 60 * 1000 || !Float.isFinite(x) || !Float.isFinite(y) || Math.abs(x) > 10000 || Math.abs(y) > 10000) throw new IOException("Invalid replay coordinates/time");
                if (x == -256 && y == -256) continue; // Legacy initial cursor sentinel.
                if (frames.size >= 1_000_000) throw new IOException("Too many replay frames");
                if (frames.size == frames.times.length) {
                    int capacity = Math.min(1_000_000, frames.size * 2);
                    frames.times = Arrays.copyOf(frames.times, capacity); frames.keys = Arrays.copyOf(frames.keys, capacity);
                    frames.xs = Arrays.copyOf(frames.xs, capacity); frames.ys = Arrays.copyOf(frames.ys, capacity);
                }
                int index = frames.size++;
                frames.times[index] = (int)time; frames.xs[index] = x; frames.ys[index] = y; frames.keys[index] = keys;
            }
        } catch (NumberFormatException error) { throw new IOException("Invalid replay number", error); }
        if (frames.size == 0) throw new IOException("Empty replay");
        return frames;
    }
    public int duration() { return times[size - 1]; }
    public int time(int index){return times[index];}
    public int rawKeys(int index){return keys[index];}
    public int buttons(int index){int value=keys[index];return ((value&5)!=0?1:0)|((value&10)!=0?2:0);}
    public float x(int index){return xs[index];}
    public float y(int index){return ys[index];}
    public void at(double time, Sample out) {
        int low = 0, high = size - 1;
        while (low < high) { int mid = (low + high + 1) >>> 1; if (times[mid] <= time) low = mid; else high = mid - 1; }
        int next = Math.min(size - 1, low + 1);
        float mix = times[next] > times[low] ? (float)Math.max(0, Math.min(1, (time - times[low]) / (times[next] - times[low]))) : 0;
        out.x = xs[low] + (xs[next] - xs[low]) * mix; out.y = ys[low] + (ys[next] - ys[low]) * mix;
        out.keys = time < times[0] ? 0 : keys[low];
    }
}
