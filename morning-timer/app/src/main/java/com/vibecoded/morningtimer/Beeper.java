package com.vibecoded.morningtimer;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Handler;
import android.os.Looper;

/** Synthesised soft chimes, so the sound is unique to this app and needs no audio files. */
public class Beeper {
    public static final String[] NAMES = {"Soft chime", "Rising trio", "Wood knock", "Single ping", "Low bell"};
    private static final int RATE = 22050;

    /** Each note: {startSeconds, frequencyHz, lengthSeconds, decayPerSecond}. */
    private static double[][] notes(int sound) {
        switch (sound) {
            case 1: return new double[][]{{0, 523.25, .6, 6}, {.16, 659.25, .6, 6}, {.32, 783.99, .8, 5}};
            case 2: return new double[][]{{0, 440, .15, 30}, {.18, 440, .15, 30}};
            case 3: return new double[][]{{0, 1318.5, .9, 5}};
            case 4: return new double[][]{{0, 392, 1.4, 3}, {0, 784, 1.0, 5}};
            default: return new double[][]{{0, 783.99, .7, 6}, {.22, 1046.5, .9, 5}};
        }
    }

    public static void play(int sound, int volumePercent) {
        double[][] ns = notes(sound);
        double total = 0;
        for (double[] n : ns) total = Math.max(total, n[0] + n[2]);
        int len = (int) (total * RATE);
        double[] mix = new double[len];
        for (double[] n : ns) {
            int s0 = (int) (n[0] * RATE), nl = (int) (n[2] * RATE);
            for (int i = 0; i < nl && s0 + i < len; i++) {
                double t = i / (double) RATE;
                double attack = Math.min(1, t / 0.01);           // 10 ms fade-in, no click
                double release = Math.min(1, (nl - i) / (0.03 * RATE)); // 30 ms fade-out
                double env = attack * release * Math.exp(-n[3] * t);
                double w = Math.sin(2 * Math.PI * n[1] * t) + 0.25 * Math.sin(4 * Math.PI * n[1] * t);
                mix[s0 + i] += env * w;
            }
        }
        short[] pcm = new short[len];
        double gain = 0.3 * volumePercent / 100.0;
        for (int i = 0; i < len; i++) pcm[i] = (short) (Math.max(-1, Math.min(1, mix[i] * gain)) * Short.MAX_VALUE);

        AudioTrack track = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .setAudioFormat(new AudioFormat.Builder().setSampleRate(RATE)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(len * 2)
                .build();
        track.write(pcm, 0, len);
        track.play();
        new Handler(Looper.getMainLooper()).postDelayed(track::release, (long) (total * 1000) + 300);
    }
}
