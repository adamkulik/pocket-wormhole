package org.newdawn.slick.openal;

/** No-op audio implementation. */
public class NullAudio implements Audio {
    @Override
    public void stop() {
    }

    @Override
    public int getBufferID() {
        return -1;
    }

    @Override
    public boolean isPlaying() {
        return false;
    }

    @Override
    public int playAsSoundEffect(float pitch, float gain, boolean loop) {
        return -1;
    }

    @Override
    public int playAsSoundEffect(float pitch, float gain, boolean loop, float x, float y, float z) {
        return -1;
    }

    @Override
    public int playAsMusic(float pitch, float gain, boolean loop) {
        return -1;
    }

    @Override
    public boolean setPosition(float position) {
        return false;
    }

    @Override
    public float getPosition() {
        return 0;
    }
}
