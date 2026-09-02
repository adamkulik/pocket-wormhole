package org.lwjgl.openal;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import java.util.HashMap;

/**
 * Software mixing engine backing the AL10/AL11 shim.
 *
 * Mixes all playing sources into a single stereo 16-bit 44.1kHz AudioTrack.
 * Sources play either a static buffer (SFX, optionally looping) or a queue of
 * streamed buffers (music). Time-based positions (AL_SEC_OFFSET) are tracked
 * in output frames.
 */
public final class SoftAL {
    public static final int OUTPUT_RATE = 44100;

    /** Set by MainActivity; if a "dump_audio" marker file exists inside, the
     *  mixer writes mix_dump.pcm and the first decoded buffers to it. */
    public static volatile java.io.File dumpDir;

    private static final SoftAL INSTANCE = new SoftAL();
    private static final int CHUNK_FRAMES = 1024;

    private static volatile boolean dumping;
    /** Read-only view for debug logging elsewhere. */
    public static boolean isDumping() { return dumping; }
    private static java.io.FileOutputStream mixDump;
    private static int mixDumpBytes;
    private static int dumpedBuffers;

    private final Object lock = new Object();

    private final HashMap<Integer, Buffer> buffers = new HashMap<>();
    private final HashMap<Integer, Source> sources = new HashMap<>();
    private int nextId = 1;

    private AudioTrack track;
    private Thread mixerThread;
    private volatile boolean running;
    private final short[] mixBuf = new short[CHUNK_FRAMES * 2];
    private final float[] accBuf = new float[CHUNK_FRAMES * 2];

    private SoftAL() {
    }

    public static SoftAL get() {
        return INSTANCE;
    }

    public static IntBuffer createIntBuffer(int size) {
        ByteBuffer bb = ByteBuffer.allocateDirect(size * 4).order(ByteOrder.nativeOrder());
        return bb.asIntBuffer();
    }

    // ------------------------------------------------------------------ //

    public void start() {
        synchronized (lock) {
            if (running) return;
            running = true;

            int minBuf = AudioTrack.getMinBufferSize(
                    OUTPUT_RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
            int bufSize = Math.max(minBuf * 2, CHUNK_FRAMES * 2 * 4 * 2);

            track = new AudioTrack(
                    new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_GAME)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build(),
                    new AudioFormat.Builder()
                            .setSampleRate(OUTPUT_RATE)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                            .build(),
                    bufSize,
                    AudioTrack.MODE_STREAM,
                    AudioManager.AUDIO_SESSION_ID_GENERATE
            );
            track.play();

            startDumpIfNeeded();

            mixerThread = new Thread(this::mixLoop, "SoftAL-Mixer");
            mixerThread.setPriority(Thread.MAX_PRIORITY);
            mixerThread.start();
        }
    }

    public void stop() {
        synchronized (lock) {
            running = false;
        }
        if (mixerThread != null) {
            try {
                mixerThread.join(2000);
            } catch (InterruptedException ignored) {
            }
            mixerThread = null;
            if (track != null) {
                track.stop();
                track.release();
                track = null;
            }
        }
        closeDump();
    }

    private static void startDumpIfNeeded() {
        if (dumpDir == null || !new java.io.File(dumpDir, "dump_audio").exists()) return;
        try {
            mixDump = new java.io.FileOutputStream(new java.io.File(dumpDir, "mix_dump.pcm"));
            mixDumpBytes = 0;
            dumping = true;
            android.util.Log.i("XFTL", "SoftAL audio dumping enabled");
        } catch (Exception e) {
            android.util.Log.w("XFTL", "dump open failed", e);
        }
    }

    private static void closeDump() {
        dumping = false;
        if (mixDump != null) {
            try { mixDump.close(); } catch (Exception ignored) {}
            mixDump = null;
        }
    }

    private static void dumpMixChunk(short[] buf) {
        if (mixDump == null || mixDumpBytes > 60_000_000) return;
        try {
            byte[] arr = new byte[buf.length * 2];
            for (int i = 0; i < buf.length; i++) {
                arr[2 * i] = (byte) (buf[i] & 0xFF);
                arr[2 * i + 1] = (byte) ((buf[i] >> 8) & 0xFF);
            }
            mixDump.write(arr);
            mixDump.flush();
            mixDumpBytes += arr.length;
        } catch (Exception ignored) {
        }
    }

    private static void dumpBufferPcm(short[] pcm, int rate, int channels) {
        if (dumpDir == null || dumpedBuffers >= 12) return;
        try {
            int n = Math.min(pcm.length, 65536);
            byte[] arr = new byte[n * 2];
            for (int i = 0; i < n; i++) {
                arr[2 * i] = (byte) (pcm[i] & 0xFF);
                arr[2 * i + 1] = (byte) ((pcm[i] >> 8) & 0xFF);
            }
            java.io.File f = new java.io.File(dumpDir,
                    "buf" + dumpedBuffers + "_" + rate + "hz_" + channels + "ch.pcm");
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(f)) {
                fos.write(arr);
            }
            dumpedBuffers++;
        } catch (Exception ignored) {
        }
    }

    private void mixLoop() {
        while (running) {
            try {
                mixChunk();
            } catch (Throwable t) {
                // The mixer must never die: an uncaught exception here would
                // take the whole process down.
                android.util.Log.e("XFTL", "SoftAL mixer error", t);
                try { Thread.sleep(10); } catch (InterruptedException ignored) { }
            }
        }
    }

    private void mixChunk() {
        // Mix one chunk
        java.util.Arrays.fill(accBuf, 0f);

        synchronized (lock) {
            for (Source s : sources.values()) {
                if (s.state == AL10.AL_PLAYING) {
                    s.mix(accBuf, CHUNK_FRAMES);
                }
            }
        }

        for (int i = 0; i < accBuf.length; i++) {
            float v = accBuf[i];
            if (v > 32767f) v = 32767f;
            else if (v < -32768f) v = -32768f;
            mixBuf[i] = (short) v;
        }

        int written = 0;
        while (written < mixBuf.length) {
            int n = track.write(mixBuf, written, mixBuf.length - written);
            if (n <= 0) break;
            written += n;
        }

        if (dumping) dumpMixChunk(mixBuf);
    }
    // ------------------------------------------------------------------ //
    // Resource management (called from game thread)
    // ------------------------------------------------------------------ //

    public int newBuffer() {
        synchronized (lock) {
            int id = nextId++;
            buffers.put(id, new Buffer());
            return id;
        }
    }

    public void deleteBuffer(int id) {
        synchronized (lock) {
            buffers.remove(id);
            for (Source s : sources.values()) {
                if (s.staticBufferId == id) s.staticBufferId = 0;
            }
        }
    }

    public Buffer getBuffer(int id) {
        synchronized (lock) {
            return buffers.get(id);
        }
    }

    public void bufferData(int id, int format, ByteBuffer data, int rate) {
        synchronized (lock) {
            Buffer b = buffers.get(id);
            if (b == null) return;
            b.setData(format, data, rate);
        }
    }

    public int newSource() {
        synchronized (lock) {
            int id = nextId++;
            sources.put(id, new Source(id));
            return id;
        }
    }

    public void deleteSource(int id) {
        synchronized (lock) {
            sources.remove(id);
        }
    }

    public Source getSource(int id) {
        synchronized (lock) {
            return sources.get(id);
        }
    }

    // ------------------------------------------------------------------ //

    public static final class Buffer {
        public int channels = 1;
        public int bitsPerSample = 16;
        public int sampleRate = OUTPUT_RATE;
        /**
         * Interleaved signed 16-bit PCM, converted from whatever the source
         * format was.
         */
        public short[] pcm;
        /**
         * Duration of this buffer in seconds at its native rate.
         */
        public float durationSec;

        public void setData(int format, ByteBuffer data, int rate) {
            switch (format) {
                case AL10.AL_FORMAT_MONO8:
                    channels = 1;
                    bitsPerSample = 8;
                    break;
                case AL10.AL_FORMAT_MONO16:
                    channels = 1;
                    bitsPerSample = 16;
                    break;
                case AL10.AL_FORMAT_STEREO8:
                    channels = 2;
                    bitsPerSample = 8;
                    break;
                case AL10.AL_FORMAT_STEREO16:
                    channels = 2;
                    bitsPerSample = 16;
                    break;
                default:
                    throw new IllegalArgumentException("Unknown AL format " + format);
            }
            sampleRate = rate;

            ByteBuffer d = data.duplicate();
            if (bitsPerSample == 16) {
                // OpenAL PCM16 is little-endian; decode explicitly so we don't
                // depend on the buffer's byte-order views behaving as expected
                // across JVMs (asShortBuffer() produced byte-swapped data on
                // device, which played as pure static).
                short[] out = new short[d.remaining() / 2];
                if (dumping && dumpedBuffers < 3) {
                    byte[] first = new byte[Math.min(16, d.remaining())];
                    d.mark();
                    d.get(first);
                    d.reset();
                    StringBuilder hexb = new StringBuilder("raw bytes: ");
                    for (byte b : first) hexb.append(String.format("%02x ", b));
                    android.util.Log.i("XFTL", hexb.toString());
                }
                for (int i = 0; i < out.length; i++) {
                    out[i] = (short) ((d.get() & 0xFF) | (d.get() << 8));
                }
                pcm = out;
            } else {
                short[] out = new short[d.remaining()];
                for (int i = 0; i < out.length; i++) {
                    // unsigned 8-bit -> signed 16-bit
                    out[i] = (short) ((d.get() & 0xFF) << 8);
                }
                pcm = out;
            }
            int frames = channels == 0 ? 0 : pcm.length / channels;
            durationSec = frames / (float) sampleRate;

            if (dumping && pcm.length > 4000) {
                android.util.Log.i("XFTL", "SoftAL buffer: " + sampleRate + "Hz " + channels
                        + "ch " + bitsPerSample + "bit " + frames + " frames ("
                        + durationSec + "s)");
                StackTraceElement[] st = new Exception().getStackTrace();
                StringBuilder sb = new StringBuilder("SoftAL buffer from: ");
                for (int i = 1; i < Math.min(st.length, 8); i++) sb.append(st[i]).append(" | ");
                android.util.Log.i("XFTL", sb.toString());
                dumpBufferPcm(pcm, sampleRate, channels);
            }
        }
    }

    public static final class QueuedBuffer {
        public final int bufferId;
        public final float durationSec;
        public boolean processed;

        QueuedBuffer(int bufferId, float durationSec) {
            this.bufferId = bufferId;
            this.durationSec = durationSec;
        }
    }

    public final class Source {
        public final int id;

        /** Guarded by SoftAL.lock for compound ops; volatile for visibility. */
        public volatile int state = AL10.AL_INITIAL;
        public float gain = 1f;
        public float pitch = 1f;
        public boolean looping = false;

        public int staticBufferId = 0;
        public final ArrayDeque<QueuedBuffer> queue = new ArrayDeque<>();

        /**
         * Seconds of audio (at output rate) already unqueued from the current
         * playback run - subtracted from SEC_OFFSET like OpenAL does when a
         * processed buffer is removed.
         */
        public float secOffsetBaseSec = 0f;

        /**
         * Total seconds of output audio mixed since this source started
         * playing its current buffer chain.
         */
        public float runSec = 0f;

        /**
         * Seconds still to skip (from setting AL_SEC_OFFSET forwards).
         */
        public float skipSec = 0f;

        // Static playback position (frame index within static buffer, in
        // buffer-native frames), and streaming position.
        private double staticPos;
        private double queuePos; // frame index within current queue buffer

        Source(int id) {
            this.id = id;
        }

        // ---- control ----
        // All mutations take SoftAL.lock: the mixer thread iterates `queue`
        // under that lock, so game-thread calls (queue/unqueue/stop/play)
        // must not run concurrently with it.

        public void play() {
            synchronized (lock) {
                if (state == AL10.AL_PLAYING) {
                    // OpenAL restarts playing sources from the beginning
                    restartChain();
                } else if (state == AL10.AL_PAUSED) {
                    state = AL10.AL_PLAYING;
                } else {
                    restartChain();
                }
            }
        }

        public void pause() {
            synchronized (lock) {
                if (state == AL10.AL_PLAYING) {
                    state = AL10.AL_PAUSED;
                }
            }
        }

        public void stop() {
            synchronized (lock) {
                state = AL10.AL_STOPPED;
                staticPos = 0;
                queuePos = 0;
                runSec = 0;
                secOffsetBaseSec = 0;
                skipSec = 0;
                // All queued buffers become processed (so they can be unqueued)
                for (QueuedBuffer qb : queue) {
                    qb.processed = true;
                }
            }
        }

        public void setStaticBuffer(int bufferId) {
            synchronized (lock) {
                staticBufferId = bufferId;
                staticPos = 0;
                queue.clear();
                if (bufferId != AL10.AL_NONE) {
                    state = AL10.AL_STOPPED;
                }
            }
        }

        public void queueBuffer(int bufferId) {
            synchronized (lock) {
                Buffer b = getBuffer(bufferId);
                float dur = b == null ? 0f : b.durationSec;
                queue.addLast(new QueuedBuffer(bufferId, dur));
                if (state == AL10.AL_INITIAL || state == AL10.AL_STOPPED) {
                    state = AL10.AL_STOPPED;
                }
            }
        }

        public int processedInQueue() {
            synchronized (lock) {
                int n = 0;
                for (QueuedBuffer qb : queue) {
                    if (qb.processed) n++;
                    else break;
                }
                return n;
            }
        }

        /** Removes and returns the first processed queued buffer, or null if
         *  the head is still playing. Any thread; lock taken inside. */
        QueuedBuffer unqueueOneProcessed() {
            synchronized (lock) {
                QueuedBuffer qb = queue.peekFirst();
                if (qb == null || !qb.processed) return null;
                queue.removeFirst();
                secOffsetBaseSec += qb.durationSec;
                return qb;
            }
        }

        public float getSecOffset() {
            synchronized (lock) {
                return Math.max(0f, runSec - secOffsetBaseSec);
            }
        }

        public void skipSeconds(float t) {
            if (t > 0) skipSec += t;
        }

        private void restartChain() {
            synchronized (lock) {
                android.util.Log.d("XFTL", "mix: src " + id + " restartChain (queue=" + queue.size() + ")");
                staticPos = 0;
                queuePos = 0;
                runSec = 0;
                secOffsetBaseSec = 0;
                skipSec = 0;
                for (QueuedBuffer qb : queue) {
                    qb.processed = false;
                }
                state = AL10.AL_PLAYING;
            }
        }

        // ---- mixing (mixer thread, lock held) ----

        void mix(float[] acc, int frames) {
            float step = pitch * 1f; // output frames per output frame (rate handled below)

            for (int i = 0; i < frames; i++) {
                // Handle forward-skip first
                if (skipSec > 0f) {
                    float dt = 1f / OUTPUT_RATE;
                    skipSec -= dt;
                    runSec += dt;
                    continue;
                }

                boolean mixedFrame = mixOneFrame(acc, i * 2, step);
                runSec += 1f / OUTPUT_RATE;
                if (!mixedFrame) {
                    // Source stopped; leave the rest silent
                    return;
                }
            }
        }

        /**
         * Mixes a single output frame into acc at the given offset.
         * Returns false if the source has run out of audio entirely.
         */
        private boolean mixOneFrame(float[] acc, int accOff, float step) {
            Buffer b;
            boolean isStatic;

            // Find the first non-processed queued buffer (the current one).
            // Processed buffers stay in the queue until the engine unqueues
            // them, matching OpenAL semantics.
            QueuedBuffer head = null;
            java.util.Iterator<QueuedBuffer> it = queue.iterator();
            while (it.hasNext()) {
                QueuedBuffer qb = it.next();
                if (!qb.processed) {
                    head = qb;
                    break;
                }
            }

            if (staticBufferId != 0) {
                b = getBuffer(staticBufferId);
                isStatic = true;
            } else if (head != null) {
                b = getBuffer(head.bufferId);
                if (b == null) {
                    // Buffer was deleted; skip this queued buffer
                    head.processed = true;
                    return true;
                }
                isStatic = false;
            } else {
                // Queue empty, or everything in it has finished
                android.util.Log.d("XFTL", "mix: src " + id + " queue exhausted ("
                        + queue.size() + " entries) -> STOPPED");
                state = AL10.AL_STOPPED;
                return false;
            }

            if (b == null || b.channels == 0) {
                android.util.Log.d("XFTL", "mix: src " + id + " null/empty buffer -> STOPPED");
                state = AL10.AL_STOPPED;
                return false;
            }

            int framesInBuffer = b.pcm.length / b.channels;
            double pos = isStatic ? staticPos : queuePos;

            if (pos >= framesInBuffer) {
                if (isStatic) {
                    if (looping) {
                        staticPos = 0;
                        pos = 0;
                    } else {
                        state = AL10.AL_STOPPED;
                        return false;
                    }
                } else {
                    // Finished this queued buffer
                    head.processed = true;
                    queuePos = 0;
                    return true; // next frame mixes from the following buffer
                }
            }

            // Read sample (linear interpolation), resampling from buffer rate
            double bufferStep = (double) b.sampleRate / OUTPUT_RATE * pitch;
            int i0 = (int) pos;
            double frac = pos - i0;
            int i1 = Math.min(i0 + 1, framesInBuffer - 1);

            float l, r;
            if (b.channels == 1) {
                float s0 = b.pcm[i0];
                float s1 = b.pcm[i1];
                float s = (float) (s0 + (s1 - s0) * frac);
                l = s;
                r = s;
            } else {
                int c = b.channels;
                float sl0 = b.pcm[i0 * c];
                float sl1 = b.pcm[i1 * c];
                float sr0 = b.pcm[i0 * c + 1];
                float sr1 = b.pcm[i1 * c + 1];
                l = (float) (sl0 + (sl1 - sl0) * frac);
                r = (float) (sr0 + (sr1 - sr0) * frac);
            }

            acc[accOff] += l * gain;
            acc[accOff + 1] += r * gain;

            if (isStatic) {
                staticPos = pos + bufferStep;
            } else {
                queuePos = pos + bufferStep;
            }
            return true;
        }
    }
}
