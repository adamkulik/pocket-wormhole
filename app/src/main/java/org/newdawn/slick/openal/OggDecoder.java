package org.newdawn.slick.openal;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** OGG decoder using the engine's modified OggInputStream (jorbis). */
public class OggDecoder {
    public OggData getData(InputStream input) throws IOException {
        OggInputStream stream = new OggInputStream(input);
        try {
            OggData data = new OggData();
            data.rate = stream.getRate();
            data.channels = stream.getChannels();

            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = stream.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            byte[] bytes = bos.toByteArray();
            // jorbis outputs little-endian PCM bytes
            data.data = ByteBuffer.allocateDirect(bytes.length)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .put(bytes);
            data.data.flip();
            return data;
        } finally {
            stream.close();
        }
    }
}
