package com.localshare.app.server;

import java.util.ArrayDeque;

/** Holds the host's live screen: the newest JPEG frame plus a short queue of PCM audio chunks. Viewers pull from it. */
public final class StreamHub {
    private byte[] frame;
    private long seq;
    private volatile boolean live;
    private volatile int viewers;
    private volatile int audioRate = 24000, audioCh = 2;
    private final ArrayDeque<byte[]> audio = new ArrayDeque<byte[]>();
    private long audioBase; // absolute index of audio.peekFirst()

    public boolean isLive() { return live; }
    public int viewers() { return viewers; }
    public int audioRate() { return audioRate; }
    public int audioChannels() { return audioCh; }

    public synchronized void start(int rate, int channels) {
        frame = null; seq = 0; audio.clear(); audioBase = 0;
        audioRate = rate; audioCh = channels;
        live = true;
        notifyAll();
    }

    public synchronized void stop() {
        live = false;
        frame = null;
        audio.clear();
        notifyAll();
    }

    public synchronized void pushFrame(byte[] jpeg) {
        if (!live) return;
        frame = jpeg;
        seq++;
        notifyAll();
    }

    public synchronized void pushAudio(byte[] pcm) {
        if (!live) return;
        audio.addLast(pcm);
        while (audio.size() > 40) { audio.pollFirst(); audioBase++; }
        notifyAll();
    }

    void viewerDelta(int d) { synchronized (this) { viewers += d; } }

    /** Blocks until a frame newer than {@code last[0]} exists (or timeoutMs passes, returning the current one). Null when the stream ended. */
    synchronized byte[] nextFrame(long[] last, long timeoutMs) throws InterruptedException {
        if (live && seq == last[0]) wait(timeoutMs);
        if (!live) return null;
        last[0] = seq;
        return frame;
    }

    /** Next audio chunk for a listener whose cursor is {@code cur[0]} (absolute index; -1 = join at the live edge). Null on timeout. */
    synchronized byte[] nextAudio(long[] cur, long timeoutMs) throws InterruptedException {
        if (cur[0] < 0 || cur[0] < audioBase) cur[0] = Math.max(audioBase, audioBase + audio.size() - 2);
        if (live && cur[0] >= audioBase + audio.size()) wait(timeoutMs);
        if (!live) return new byte[0];
        long i = cur[0];
        if (i < audioBase) i = audioBase;
        if (i >= audioBase + audio.size()) return null;
        byte[] r = null;
        long k = audioBase;
        for (byte[] b : audio) { if (k == i) { r = b; break; } k++; }
        cur[0] = i + 1;
        return r;
    }
}
