package org.newdawn.slick.opengl;

public final class ImageDataFactory {
    private ImageDataFactory() {
    }

    public static LoadableImageData getImageDataFor(String path) {
        return new AndroidImageData();
    }
}
