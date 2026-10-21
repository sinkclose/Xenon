package zxc.iconic.xenon.osu;

import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Predecoded audio features addressed by the media player's actual position, including seeks. */
public final class OsuAudioData extends OsuAudioTimeline {
    public static OsuAudioData decode(File file) throws IOException {
        return decode(file, null);
    }
    public static OsuAudioData decode(File file, java.util.function.Consumer<OsuAudioData> ready) throws IOException {
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec codec = null;
        try {
            extractor.setDataSource(file.getAbsolutePath());
            MediaFormat format = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat candidate = extractor.getTrackFormat(i);
                String mime = candidate.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) { format = candidate; extractor.selectTrack(i); break; }
            }
            if (format == null) throw new IOException("No audio track");
            codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME));
            codec.configure(format, null, null, 0);
            codec.start();
            OsuAudioData data = new OsuAudioData();
            data.complete = false;
            data.durationMs = format.containsKey(MediaFormat.KEY_DURATION) ? format.getLong(MediaFormat.KEY_DURATION) / 1000 : 0;
            if (data.durationMs > 30 * 60 * 1000) throw new IOException("Audio analysis exceeds 30 minutes");
            if (ready != null) ready.accept(data);
            int channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT), rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            int encoding = AudioFormat.ENCODING_PCM_16BIT, used = 0;
            boolean inputEnded = false, outputEnded = false;
            float[] samples = new float[OsuPcmAnalysis.SIZE];
            OsuPcmAnalysis fft = new OsuPcmAnalysis();
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            long nextTime = 0, windowTime = 0, lastOutput = android.os.SystemClock.elapsedRealtime();
            while (!outputEnded) {
                if (android.os.SystemClock.elapsedRealtime() - lastOutput > 15000) throw new IOException("Audio decoder stalled");
                if (!inputEnded) {
                    int index = codec.dequeueInputBuffer(10000);
                    if (index >= 0) {
                        ByteBuffer buffer = codec.getInputBuffer(index);
                        int size = extractor.readSampleData(buffer, 0);
                        if (size < 0) { codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputEnded = true; }
                        else { codec.queueInputBuffer(index, 0, size, extractor.getSampleTime(), 0); extractor.advance(); }
                    }
                }
                int index = codec.dequeueOutputBuffer(info, 10000);
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat decoded = codec.getOutputFormat();
                    channels = decoded.getInteger(MediaFormat.KEY_CHANNEL_COUNT); rate = decoded.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    encoding = decoded.containsKey(MediaFormat.KEY_PCM_ENCODING) ? decoded.getInteger(MediaFormat.KEY_PCM_ENCODING) : AudioFormat.ENCODING_PCM_16BIT;
                    if (encoding != AudioFormat.ENCODING_PCM_16BIT && encoding != AudioFormat.ENCODING_PCM_FLOAT) throw new IOException("Unsupported decoded PCM");
                } else if (index >= 0) {
                    lastOutput = android.os.SystemClock.elapsedRealtime();
                    if (info.size == 0) {
                        outputEnded = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                        codec.releaseOutputBuffer(index, false); continue;
                    }
                    ByteBuffer buffer = codec.getOutputBuffer(index).order(ByteOrder.LITTLE_ENDIAN);
                    buffer.position(info.offset); buffer.limit(info.offset + info.size);
                    int bytes = encoding == AudioFormat.ENCODING_PCM_FLOAT ? 4 : 2;
                    int frame = 0;
                    long lastTime = info.presentationTimeUs / 1000;
                    while (buffer.remaining() >= channels * bytes) {
                        long time = info.presentationTimeUs / 1000 + frame * 1000L / rate;
                        if (used == 0) windowTime = time;
                        float mono = 0;
                        for (int channel = 0; channel < channels; channel++) mono += encoding == AudioFormat.ENCODING_PCM_FLOAT ? buffer.getFloat() : buffer.getShort() / 32768f;
                        samples[used++] = mono / channels; frame++;
                        if (used == samples.length) {
                            if (windowTime >= nextTime) {
                                float[] values = new float[OsuPcmAnalysis.BANDS + 1];
                                fft.analyse(samples, values); data.append(windowTime, values);
                                nextTime = windowTime + 40;
                                if (data.frames.size() > 45000) throw new IOException("Audio analysis too large");
                            }
                            used = 0;
                        }
                        lastTime = time;
                    }
                    data.durationMs = Math.max(data.durationMs, lastTime);
                    outputEnded = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    codec.releaseOutputBuffer(index, false);
                }
            }
            data.complete = true;
            return data;
        } catch (RuntimeException error) { throw new IOException("Cannot decode beatmap audio", error); }
        finally {
            if (codec != null) { try { codec.stop(); } catch (Exception ignored) { } codec.release(); }
            extractor.release();
        }
    }
}
