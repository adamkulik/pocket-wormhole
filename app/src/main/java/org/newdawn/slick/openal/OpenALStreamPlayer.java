package org.newdawn.slick.openal;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;

/**
 * Slick's streaming player. The engine never uses this (it has its own
 * streaming player in xyz.znix.xftl.sys that works against the software
 * OpenAL shim); this minimal implementation exists so SoundStore compiles.
 */
public class OpenALStreamPlayer {
    private final int source;
    private final String ref;

    public OpenALStreamPlayer(int source, String ref) {
        this.source = source;
        this.ref = ref;
    }

    public OpenALStreamPlayer(int source, URL url) {
        this.source = source;
        this.ref = url.toString();
    }

    public String getSource() {
        return ref;
    }

    public void play(boolean loop) throws IOException {
        throw new IOException("Slick streaming audio is not supported on Android");
    }

    public void setup(float pitch) {
    }

    public boolean done() {
        return true;
    }

    public void update() {
    }

    public boolean stream(int bufferId) {
        return false;
    }

    public boolean setPosition(float position) {
        return false;
    }

    public float getPosition() {
        return 0;
    }
}
