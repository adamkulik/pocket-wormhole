package org.newdawn.slick.openal;

import java.io.IOException;

/** Slick's audio stream interface, implemented by OggInputStream. */
public interface AudioInputStream {
    int getChannels();

    int getRate();

    int read() throws IOException;

    int read(byte[] b) throws IOException;

    int read(byte[] b, int off, int len) throws IOException;

    boolean atEnd();

    void close() throws IOException;
}
