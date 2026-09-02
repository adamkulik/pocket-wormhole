package org.newdawn.slick.openal;

import org.lwjgl.openal.AL10;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;

/**
 * WAV file loader, matching Slick's WaveData API. Implements a plain RIFF
 * parser (Slick uses javax.sound, which isn't available on Android).
 */
public class WaveData {
    public final ByteBuffer data;
    public final int format;
    public final int samplerate;

    private WaveData(ByteBuffer data, int format, int samplerate) {
        this.data = data;
        this.format = format;
        this.samplerate = samplerate;
    }

    public void dispose() {
        // nothing to do with the software AL
    }

    public static WaveData create(InputStream input) throws IOException {
        return create(new DataInputStream(input));
    }

    public static WaveData create(String path) throws IOException {
        throw new IOException("Not supported");
    }

    public static WaveData create(java.net.URL url) throws IOException {
        throw new IOException("Not supported");
    }

    public static WaveData create(byte[] bytes) throws IOException {
        return create(new java.io.ByteArrayInputStream(bytes));
    }

    public static WaveData create(ByteBuffer buffer) throws IOException {
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        return create(bytes);
    }

    private static WaveData create(DataInputStream dis) throws IOException {
        try {
            readString(dis, 4); // "RIFF"
            readIntLE(dis); // total length
            readString(dis, 4); // "WAVE"

            int channels = 0, sampleRate = 0, bitsPerSample = 0;
            ByteBuffer pcm = null;

            while (true) {
                String chunk;
                int len;
                try {
                    chunk = readString(dis, 4);
                    len = readIntLE(dis);
                } catch (EOFException e) {
                    break;
                }

                if (chunk.equals("fmt ")) {
                    int audioFormat = readShortLE(dis);
                    channels = readShortLE(dis);
                    sampleRate = readIntLE(dis);
                    readIntLE(dis); // byte rate
                    readShortLE(dis); // block align
                    bitsPerSample = readShortLE(dis);
                    if (len > 16) {
                        skipFully(dis, len - 16);
                    }
                } else if (chunk.equals("data")) {
                    byte[] bytes = new byte[len];
                    dis.readFully(bytes);
                    // WAV PCM samples are little-endian
                    pcm = ByteBuffer.allocateDirect(len)
                            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                            .put(bytes);
                    pcm.flip();
                } else {
                    skipFully(dis, len);
                }

                if (len % 2 == 1) {
                    dis.skipBytes(1); // padding
                }
            }

            if (pcm == null) {
                throw new IOException("No data chunk in WAV file");
            }

            int format;
            if (bitsPerSample == 8) {
                format = channels == 1 ? AL10.AL_FORMAT_MONO8 : AL10.AL_FORMAT_STEREO8;
            } else if (bitsPerSample == 16) {
                format = channels == 1 ? AL10.AL_FORMAT_MONO16 : AL10.AL_FORMAT_STEREO16;
            } else {
                throw new IOException("Unsupported WAV bit depth: " + bitsPerSample);
            }

            return new WaveData(pcm, format, sampleRate);
        } finally {
            dis.close();
        }
    }

    private static void skipFully(DataInputStream dis, int n) throws IOException {
        while (n > 0) {
            int skipped = dis.skipBytes(n);
            if (skipped <= 0) {
                if (dis.read() < 0) throw new EOFException();
                skipped = 1;
            }
            n -= skipped;
        }
    }

    private static String readString(DataInputStream dis, int len) throws IOException {
        byte[] buf = new byte[len];
        dis.readFully(buf);
        return new String(buf, "ASCII");
    }

    private static int readIntLE(DataInputStream dis) throws IOException {
        int b1 = dis.read();
        int b2 = dis.read();
        int b3 = dis.read();
        int b4 = dis.read();
        if (b4 < 0) throw new EOFException();
        return (b1) | (b2 << 8) | (b3 << 16) | (b4 << 24);
    }

    private static short readShortLE(DataInputStream dis) throws IOException {
        int b1 = dis.read();
        int b2 = dis.read();
        if (b2 < 0) throw new EOFException();
        return (short) ((b1) | (b2 << 8));
    }
}
