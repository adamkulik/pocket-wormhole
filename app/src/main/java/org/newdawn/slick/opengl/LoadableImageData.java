package org.newdawn.slick.opengl;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;

public interface LoadableImageData extends ImageData {
    void configureEdging(boolean edging);

    ByteBuffer loadImage(InputStream fis) throws IOException;

    ByteBuffer loadImage(InputStream fis, boolean flipped, int[] transparent) throws IOException;

    ByteBuffer loadImage(InputStream fis, boolean flipped, boolean forceAlpha, int[] transparent) throws IOException;
}
