package com.focuspen.enhance.v2;

public final class WritingAudioSynthesizerTest {
    public static void main(String[] args) {
        windowAndBounds();
        motionChangesOutput();
        stereoPan();
        System.out.println("Writing audio synthesizer: PASS");
    }

    private static void windowAndBounds() {
        WritingAudioSynthesizer synth = new WritingAudioSynthesizer(1234);
        short[] pcm = new short[WritingAudioSynthesizer.MAX_GRAIN_FRAMES * 2];
        int frames = render(synth, pcm, 0.5f, 0.4f, 0f);
        check(frames >= WritingAudioSynthesizer.MIN_GRAIN_FRAMES
                && frames <= WritingAudioSynthesizer.MAX_GRAIN_FRAMES, "grain duration bounded");
        check(pcm[0] == 0 && pcm[1] == 0, "Hann window starts at zero");
        check(Math.abs(pcm[(frames - 1) * 2]) <= 2 && Math.abs(pcm[(frames - 1) * 2 + 1]) <= 2,
                "Hann window ends at zero");
        int peak = 0;
        for (int i = 0; i < frames * 2; i++) peak = Math.max(peak, Math.abs((int) pcm[i]));
        check(peak > 100 && peak < 32767, "output audible and below hard clipping");
        check(normalizedRms(pcm, frames) > 0.03, "nominal writing has useful PCM loudness");
    }

    private static void motionChangesOutput() {
        short[] light = new short[WritingAudioSynthesizer.MAX_GRAIN_FRAMES * 2];
        short[] heavy = new short[WritingAudioSynthesizer.MAX_GRAIN_FRAMES * 2];
        WritingAudioSynthesizer a = new WritingAudioSynthesizer(77);
        WritingAudioSynthesizer b = new WritingAudioSynthesizer(77);
        int lightFrames = a.render(light, 0.15f, 0.08f, 0f, 0f, 0f, 1f, false,
                0.7f, 0.3f, 0f, 0.10f, 0.82f, 0.2f, 0.2f);
        int heavyFrames = b.render(heavy, 0.90f, 1.1f, 0.02f, 0.2f, 0f, 1f, false,
                0f, 0.2f, 0.8f, 0.30f, 1.25f, 0.9f, 0.9f);
        check(heavyFrames < lightFrames, "faster motion uses shorter grains");
        check(rms(heavy, heavyFrames) > rms(light, lightFrames) * 1.25, "heavy writing has more energy");
        boolean differs = false;
        int samples = Math.min(lightFrames, heavyFrames) * 2;
        for (int i = 0; i < samples; i++) if (light[i] != heavy[i]) { differs = true; break; }
        check(differs, "continuous motion parameters change PCM");
    }

    private static void stereoPan() {
        short[] left = new short[WritingAudioSynthesizer.MAX_GRAIN_FRAMES * 2];
        short[] right = new short[WritingAudioSynthesizer.MAX_GRAIN_FRAMES * 2];
        int leftFrames = render(new WritingAudioSynthesizer(91), left, 0.5f, 0.4f, -0.8f);
        int rightFrames = render(new WritingAudioSynthesizer(91), right, 0.5f, 0.4f, 0.8f);
        check(channelEnergy(left, leftFrames, 0) > channelEnergy(left, leftFrames, 1) * 2,
                "left pan favors left channel");
        check(channelEnergy(right, rightFrames, 1) > channelEnergy(right, rightFrames, 0) * 2,
                "right pan favors right channel");
    }

    private static int render(WritingAudioSynthesizer synth, short[] pcm,
            float pressure, float speed, float pan) {
        return synth.render(pcm, pressure, speed, 0.01f, 0.05f, pan, 0.2f, false,
                0.2f, 0.6f, 0.2f, 0.22f, 1f, 0.5f, 0.5f);
    }

    private static double rms(short[] pcm, int frames) {
        return Math.sqrt(channelEnergy(pcm, frames, 0) + channelEnergy(pcm, frames, 1)) / frames;
    }

    private static double normalizedRms(short[] pcm, int frames) {
        double energy = channelEnergy(pcm, frames, 0) + channelEnergy(pcm, frames, 1);
        return Math.sqrt(energy / (frames * 2.0)) / 32767.0;
    }

    private static double channelEnergy(short[] pcm, int frames, int channel) {
        double energy = 0;
        for (int i = 0; i < frames; i++) {
            double value = pcm[i * 2 + channel];
            energy += value * value;
        }
        return energy;
    }

    private static void check(boolean pass, String message) {
        if (!pass) throw new AssertionError(message);
    }
}
