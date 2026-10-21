package zxc.iconic.xenon.triangles;

/** Android Visualizer's unsigned PCM8 waveform and packed signed complex FFT. No allocations. */
public final class OsuVisualizerSpectrum {
    public final float[] bands = new float[200];
    public float amplitude, rms, bass;

    public void decode(byte[] waveform, byte[] fft, int sampleRate) {
        float peak = 0;
        double energy = 0;
        for (byte sample : waveform) {
            float value = ((sample & 255) - 128) / 128f;
            peak = Math.max(peak, Math.abs(value));
            energy += value * value;
        }
        amplitude = peak;
        rms = waveform.length == 0 ? 0 : (float) Math.sqrt(energy / waveform.length);
        double bassEnergy = 0, spectrumEnergy = 0;
        float spectralPeak = 0;
        int bins = fft.length / 2;
        for (int bin = 1; bin < bins; bin++) {
            float real = fft[bin * 2] / 128f, imaginary = fft[bin * 2 + 1] / 128f;
            spectralPeak = Math.max(spectralPeak, real * real + imaginary * imaginary);
            spectrumEnergy += real * real + imaginary * imaginary;
            float frequency = bin * (float) sampleRate / fft.length;
            if (frequency >= 35 && frequency <= 180) {
                bassEnergy += real * real + imaginary * imaginary;
            }
        }
        // Waveform and FFT can have different native gains. Put bass in the same
        // RMS units as the detector's full-band energy, independent of FFT gain.
        bass = spectrumEnergy == 0 ? 0 : rms * (float) Math.sqrt(bassEnergy / spectrumEnergy);
        // Some unsupported audio paths return zero-filled waveforms rather than PCM8 silence.
        if (spectralPeak == 0) amplitude = rms = 0;
        int lastBin = Math.min(200, bins - 1);
        for (int i = 0; i < bands.length; i++) {
            int bin = 1 + i * Math.max(0, lastBin - 1) / (bands.length - 1);
            bands[i] = bins < 2 ? 0 : Math.min(1, (float) Math.hypot(fft[bin * 2], fft[bin * 2 + 1]) / 128f);
        }
    }
}
