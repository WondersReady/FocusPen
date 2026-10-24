package com.focuspen.enhance.v2;

import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;

/** Bounded PCM WAV decoder. Used once on the audio worker, never on input callbacks. */
final class WritingAudioSample {
    final float[] pcm;
    final int sampleRate;
    final float sourceRms, normalizationGain;

    private WritingAudioSample(float[] pcm, int sampleRate, float rms, float gain) {
        this.pcm = pcm;
        this.sampleRate = sampleRate;
        sourceRms = rms;
        normalizationGain = gain;
    }

    static WritingAudioSample read(InputStream input) throws IOException {
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int total = 0, count;
        while (total <= 4 * 1024 * 1024
                && (count = input.read(chunk, 0, Math.min(chunk.length, 4 * 1024 * 1024 + 1 - total))) != -1) {
            if (count == 0) throw new IOException("No WAV read progress");
            data.write(chunk, 0, count); total += count;
        }
        byte[] bytes = data.toByteArray();
        if (bytes.length > 4 * 1024 * 1024 || bytes.length < 44
                || word(bytes, 0) != 0x46464952 || word(bytes, 8) != 0x45564157)
            throw new IOException("Expected bounded RIFF/WAVE file");
        long end = Integer.toUnsignedLong(word(bytes, 4)) + 8;
        if (end > bytes.length || end < 12) throw new IOException("Truncated WAV");
        int rate = 0, channels = 0, dataOffset = -1, dataLength = 0;
        for (long p = 12; p + 8 <= end;) {
            int offset = (int) p, id = word(bytes, offset);
            long size = Integer.toUnsignedLong(word(bytes, offset + 4));
            if (p + 8 + size > end) throw new IOException("Invalid WAV chunk");
            int body = offset + 8;
            if (id == 0x20746d66) {
                if (size < 16 || half(bytes, body + 14) != 16)
                    throw new IOException("Only PCM16 WAV supported");
                int format = half(bytes, body);
                if (format == 0xfffe) {
                    // WAVE_FORMAT_EXTENSIBLE, PCM subtype GUID, 16 valid bits.
                    if (size < 40 || half(bytes, body + 16) < 22 || half(bytes, body + 18) != 16
                            || word(bytes, body + 24) != 1 || word(bytes, body + 28) != 0x00100000
                            || word(bytes, body + 32) != 0xaa000080 || word(bytes, body + 36) != 0x719b3800)
                        throw new IOException("Unsupported extensible WAV subtype");
                } else if (format != 1) throw new IOException("Only PCM16 WAV supported");
                channels = half(bytes, body + 2);
                rate = word(bytes, body + 4);
                if ((channels != 1 && channels != 2) || rate < 8000 || rate > 96000
                        || half(bytes, body + 12) != channels * 2)
                    throw new IOException("Unsupported WAV format");
            } else if (id == 0x61746164) {
                dataOffset = body;
                dataLength = (int) size;
            }
            p += 8 + size + (size & 1);
        }
        if (rate == 0 || dataOffset < 0 || dataLength % (channels * 2) != 0)
            throw new IOException("Missing/unaligned PCM");
        int frames = dataLength / (channels * 2);
        if (frames < rate / 10 || frames > rate * 10) throw new IOException("Invalid loop length");
        float[] pcm = new float[frames];
        double mean = 0;
        for (int i = 0; i < frames; i++) {
            float value = 0;
            for (int c = 0; c < channels; c++)
                value += (short) half(bytes, dataOffset + (i * channels + c) * 2) / 32768f;
            pcm[i] = value / channels;
            mean += pcm[i];
        }
        mean /= frames;
        double squares = 0;
        float peak = 0;
        for (int i = 0; i < frames; i++) {
            pcm[i] -= (float) mean;
            squares += pcm[i] * pcm[i];
            peak = Math.max(peak, Math.abs(pcm[i]));
        }
        float rms = (float) Math.sqrt(squares / frames);
        if (rms < 0.000001f) throw new IOException("Silent sample");
        // Conservative fixed normalization, never time-varying AGC or system-volume writes.
        float gain = Math.min(16f, Math.min(0.10f / rms, 0.80f / peak));
        for (int i = 0; i < frames; i++) pcm[i] *= gain;
        return new WritingAudioSample(pcm, rate, rms, gain);
    }

    private static int half(byte[] b, int p) { return (b[p] & 255) | ((b[p + 1] & 255) << 8); }
    private static int word(byte[] b, int p) { return half(b, p) | (half(b, p + 2) << 16); }
}
