package org.lwjgl.openal;

import org.lwjgl.openal.SoftAL.Buffer;
import org.lwjgl.openal.SoftAL.Source;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

/**
 * Minimal software OpenAL implementation over Android's AudioTrack, providing
 * exactly the surface used by the Wormhole (xftl) engine. Android port.
 *
 * Semantics implemented:
 *  - static buffer playback (SFX) with gain/pitch/looping
 *  - buffer queue streaming (music) with unqueue/processed bookkeeping
 *  - AL_SEC_OFFSET get/set (get uses time since start of currently-queued
 *    chain, matching what the engine compensates for when unqueueing)
 *  - all sounds are non-spatial (the engine only ever uses position 0,0,0
 *    and manages gains itself)
 */
public class AL10 {
    // constants (real OpenAL values)
    public static final int AL_NO_ERROR = 0;
    public static final int AL_INVALID_NAME = 0xA001;
    public static final int AL_INVALID_ENUM = 0xA002;
    public static final int AL_INVALID_VALUE = 0xA003;
    public static final int AL_INVALID_OPERATION = 0xA004;
    public static final int AL_OUT_OF_MEMORY = 0xA005;
    public static final int AL_NONE = 0;
    public static final int AL_FALSE = 0;
    public static final int AL_TRUE = 1;
    public static final int AL_SOURCE_RELATIVE = 0x202;
    public static final int AL_CONE_INNER_ANGLE = 0x1001;
    public static final int AL_CONE_OUTER_ANGLE = 0x1002;
    public static final int AL_PITCH = 0x1003;
    public static final int AL_POSITION = 0x1004;
    public static final int AL_DIRECTION = 0x1005;
    public static final int AL_VELOCITY = 0x1006;
    public static final int AL_LOOPING = 0x1007;
    public static final int AL_BUFFER = 0x1009;
    public static final int AL_GAIN = 0x100A;
    public static final int AL_MIN_GAIN = 0x100D;
    public static final int AL_MAX_GAIN = 0x100E;
    public static final int AL_ORIENTATION = 0x100F;
    public static final int AL_SOURCE_STATE = 0x1010;
    public static final int AL_INITIAL = 0x1011;
    public static final int AL_PLAYING = 0x1012;
    public static final int AL_PAUSED = 0x1013;
    public static final int AL_STOPPED = 0x1014;
    public static final int AL_BUFFERS_QUEUED = 0x1015;
    public static final int AL_BUFFERS_PROCESSED = 0x1016;
    public static final int AL_SEC_OFFSET = 0x1024;
    public static final int AL_SIZE = 0x2004;
    public static final int AL_FREQUENCY = 0x2001;
    public static final int AL_BITS = 0x2002;
    public static final int AL_CHANNELS = 0x2003;
    public static final int AL_MONO8 = 0x1100;
    public static final int AL_MONO16 = 0x1101;
    public static final int AL_STEREO8 = 0x1102;
    public static final int AL_STEREO16 = 0x1103;
    public static final int AL_FORMAT_MONO8 = AL_MONO8;
    public static final int AL_FORMAT_MONO16 = AL_MONO16;
    public static final int AL_FORMAT_STEREO8 = AL_STEREO8;
    public static final int AL_FORMAT_STEREO16 = AL_STEREO16;

    // ---- error ----
    private static int lastError = AL_NO_ERROR;

    public static int alGetError() {
        int e = lastError;
        lastError = AL_NO_ERROR;
        return e;
    }

    // ---- buffers ----
    public static int alGenBuffers() {
        IntBuffer tmp = SoftAL.createIntBuffer(1);
        alGenBuffers(tmp);
        return tmp.get(0);
    }

    public static void alGenBuffers(IntBuffer buffers) {
        for (int i = buffers.position(); i < buffers.limit(); i++) {
            buffers.put(i, SoftAL.get().newBuffer());
        }
    }

    public static void alDeleteBuffers(int buffer) {
        SoftAL.get().deleteBuffer(buffer);
    }

    public static void alDeleteBuffers(IntBuffer buffers) {
        for (int i = buffers.position(); i < buffers.limit(); i++) {
            SoftAL.get().deleteBuffer(buffers.get(i));
        }
    }

    public static void alBufferData(int buffer, int format, ByteBuffer data, int freq) {
        SoftAL.get().bufferData(buffer, format, data, freq);
    }

    public static int alGetBufferi(int buffer, int pname) {
        Buffer b = SoftAL.get().getBuffer(buffer);
        if (b == null) return 0;
        switch (pname) {
            case AL_SIZE:
                return b.pcm.length * 2; // OpenAL AL_SIZE is in bytes
            case AL_FREQUENCY:
                return b.sampleRate;
            case AL_BITS:
                return b.bitsPerSample;
            case AL_CHANNELS:
                return b.channels;
            default:
                return 0;
        }
    }

    // ---- sources ----
    public static int alGenSources() {
        IntBuffer tmp = SoftAL.createIntBuffer(1);
        alGenSources(tmp);
        return tmp.get(0);
    }

    public static void alGenSources(IntBuffer sources) {
        for (int i = sources.position(); i < sources.limit(); i++) {
            sources.put(i, SoftAL.get().newSource());
        }
    }

    public static void alDeleteSources(int source) {
        SoftAL.get().deleteSource(source);
    }

    public static void alSourcei(int source, int pname, int value) {
        Source s = SoftAL.get().getSource(source);
        if (s == null) return;
        switch (pname) {
            case AL_BUFFER:
                s.setStaticBuffer(value);
                break;
            case AL_LOOPING:
                s.looping = value != AL_FALSE;
                break;
            case AL_SOURCE_RELATIVE:
                // no-op: everything is non-spatial in this implementation
                break;
            default:
                lastError = AL_INVALID_ENUM;
        }
    }

    public static void alSourcef(int source, int pname, float value) {
        Source s = SoftAL.get().getSource(source);
        if (s == null) return;
        switch (pname) {
            case AL_GAIN:
                s.gain = value;
                break;
            case AL_PITCH:
                s.pitch = value <= 0 ? 1f : value;
                break;
            case org.lwjgl.openal.AL11.AL_SEC_OFFSET:
                s.skipSeconds(value);
                break;
            default:
                lastError = AL_INVALID_ENUM;
        }
    }

    public static void alSource3f(int source, int pname, float v1, float v2, float v3) {
        // positions are always (0,0,0) in this engine - ignore
    }

    public static void alSourcePlay(int source) {
        Source s = SoftAL.get().getSource(source);
        if (s != null) s.play();
    }

    public static void alSourcePause(int source) {
        Source s = SoftAL.get().getSource(source);
        if (s != null) s.pause();
    }

    public static void alSourceStop(int source) {
        Source s = SoftAL.get().getSource(source);
        if (s != null) s.stop();
    }

    public static int alGetSourcei(int source, int pname) {
        Source s = SoftAL.get().getSource(source);
        if (s == null) return 0;
        switch (pname) {
            case AL_SOURCE_STATE:
                return s.state;
            case AL_BUFFERS_QUEUED:
                return s.queue.size();
            case AL_BUFFERS_PROCESSED:
                return s.processedInQueue();
            default:
                lastError = AL_INVALID_ENUM;
                return 0;
        }
    }

    public static float alGetSourcef(int source, int pname) {
        Source s = SoftAL.get().getSource(source);
        if (s == null) return 0f;
        switch (pname) {
            case org.lwjgl.openal.AL11.AL_SEC_OFFSET:
                return s.getSecOffset();
            case AL_GAIN:
                return s.gain;
            case AL_PITCH:
                return s.pitch;
            default:
                lastError = AL_INVALID_ENUM;
                return 0f;
        }
    }

    // ---- queueing ----
    public static void alSourceQueueBuffers(int source, IntBuffer buffers) {
        Source s = SoftAL.get().getSource(source);
        if (s == null) return;
        for (int i = buffers.position(); i < buffers.limit(); i++) {
            s.queueBuffer(buffers.get(i));
        }
    }

    /**
     * Unqueues processed buffers from the source, writing their names into
     * {@code buffers}. Returns the number of buffers unqueued.
     *
     * Matches LWJGL semantics: names are written at the buffer's current
     * position WITHOUT advancing it — the engine (Slick's streaming player)
     * calls {@link #alSourceQueueBuffers} with the same buffer afterwards and
     * expects the written names to be read back from position.
     */
    public static int alSourceUnqueueBuffers(int source, IntBuffer buffers) {
        Source s = SoftAL.get().getSource(source);
        if (s == null) return 0;
        int n = Math.min(buffers.remaining(), s.processedInQueue());
        int written = 0;
        for (int i = 0; i < n; i++) {
            SoftAL.QueuedBuffer qb = s.unqueueOneProcessed();
            if (qb == null) break;
            buffers.put(buffers.position() + written, qb.bufferId);
            written++;
        }
        return written;
    }

    // ---- listener ----
    public static void alListenerf(int pname, float value) {
    }

    public static void alListener3f(int pname, float v1, float v2, float v3) {
    }

    public static void alListenerfv(int pname, FloatBuffer values) {
    }

    public static void alListeneri(int pname, int value) {
    }

    public static void alDistanceModel(int model) {
    }

    public static void alDopplerFactor(float factor) {
    }
}
