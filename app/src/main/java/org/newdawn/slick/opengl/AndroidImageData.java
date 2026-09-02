package org.newdawn.slick.opengl;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

/**
 * ImageData implementation for Android: PNG files are decoded with the
 * engine's modified PNGDecoder (which handles FTL's indexed PNGs); anything
 * else falls back to Android's BitmapFactory.
 *
 * Data is always produced as RGBA with power-of-two texture dimensions,
 * matching Slick's ImageIOImageData behaviour.
 */
public class AndroidImageData implements LoadableImageData {
    private int width;
    private int height;
    private int texWidth;
    private int texHeight;
    private boolean hasAlpha;
    private ByteBuffer imageData;

    @Override
    public void configureEdging(boolean edging) {
    }

    @Override
    public int getDepth() {
        return hasAlpha ? 32 : 24;
    }

    @Override
    public int getWidth() {
        return width;
    }

    @Override
    public int getHeight() {
        return height;
    }

    @Override
    public int getTexWidth() {
        return texWidth;
    }

    @Override
    public int getTexHeight() {
        return texHeight;
    }

    @Override
    public ByteBuffer getImageBufferData() {
        return imageData;
    }

    @Override
    public ByteBuffer loadImage(InputStream fis) throws IOException {
        return loadImage(fis, false, null);
    }

    @Override
    public ByteBuffer loadImage(InputStream fis, boolean flipped, int[] transparent) throws IOException {
        return loadImage(fis, flipped, false, transparent);
    }

    @Override
    public ByteBuffer loadImage(InputStream fis, boolean flipped, boolean forceAlpha, int[] transparent)
            throws IOException {
        // BitmapFactory fully buffers the stream, so read it into memory once
        // and decode from the bytes - works for both decoder paths.
        byte[] all = readAll(fis);

        if (isPng(all)) {
            loadPng(all);
        } else {
            loadBitmap(all);
        }

        if (forceAlpha && !hasAlpha) {
            hasAlpha = true;
            // Data is stored as RGBA bytes anyway; just fix up the reported depth
        }
        return imageData;
    }

    private static byte[] readAll(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream(
                Math.max(4096, in.available()));
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            bos.write(buf, 0, n);
        }
        return bos.toByteArray();
    }

    private static boolean isPng(byte[] data) {
        return data.length >= 8
                && (data[0] & 0xFF) == 137 && data[1] == 80 && data[2] == 78 && data[3] == 71
                && data[4] == 13 && data[5] == 10 && data[6] == 26 && data[7] == 10;
    }

    private void loadPng(byte[] data) throws IOException {
        java.io.ByteArrayInputStream in = new java.io.ByteArrayInputStream(data);
        PNGDecoder decoder = new PNGDecoder(in);

        width = decoder.getWidth();
        height = decoder.getHeight();
        hasAlpha = decoder.hasAlpha() || decoder.decideTextureFormat(PNGDecoder.RGBA).isHasAlpha();

        // Decode into a tightly-packed RGBA buffer first
        ByteBuffer packed = ByteBuffer.allocateDirect(width * height * 4);
        decoder.decode(packed, width * 4, PNGDecoder.RGBA);
        packed.flip();

        fromPackedRgba(packed, width, height);
    }

    private void loadBitmap(byte[] data) {
        Bitmap bmp = BitmapFactory.decodeByteArray(data, 0, data.length);
        if (bmp == null) {
            throw new RuntimeException("Could not decode image");
        }
        width = bmp.getWidth();
        height = bmp.getHeight();
        hasAlpha = bmp.hasAlpha();

        int[] pixels = new int[width * height];
        bmp.getPixels(pixels, 0, width, 0, 0, width, height);

        ByteBuffer packed = ByteBuffer.allocateDirect(width * height * 4);
        for (int y = height - 1; y >= 0; y--) {
            // BitmapFactory gives ARGB rows top-down; GL wants bottom-up
            // rows of RGBA, matching Slick's flipped=false behaviour.
            for (int x = 0; x < width; x++) {
                int p = pixels[y * width + x];
                packed.put((byte) ((p >> 16) & 0xFF));
                packed.put((byte) ((p >> 8) & 0xFF));
                packed.put((byte) (p & 0xFF));
                packed.put((byte) ((p >> 24) & 0xFF));
            }
        }
        packed.flip();
        bmp.recycle();

        fromPackedRgba(packed, width, height);
    }

    /**
     * Copies a tightly-packed width*height RGBA buffer into a power-of-two
     * padded buffer (transparent padding), as Slick does.
     */
    private void fromPackedRgba(ByteBuffer packed, int w, int h) {
        texWidth = InternalTextureLoader.get2Fold(w);
        texHeight = InternalTextureLoader.get2Fold(h);

        imageData = ByteBuffer.allocateDirect(texWidth * texHeight * 4);

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                imageData.put(packed.get());
                imageData.put(packed.get());
                imageData.put(packed.get());
                imageData.put(packed.get());
            }
            // pad the rest of the row with transparent
            for (int x = w; x < texWidth; x++) {
                imageData.putInt(0);
            }
        }
        // remaining rows stay zero (transparent)

        imageData.flip();
    }
}
