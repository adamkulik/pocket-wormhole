package org.newdawn.slick.openal;

/** Audio interface, matching Slick. */
public interface Audio {
    void stop();

    int getBufferID();

    boolean isPlaying();

    int playAsSoundEffect(float pitch, float gain, boolean loop);

    int playAsSoundEffect(float pitch, float gain, boolean loop, float x, float y, float z);

    int playAsMusic(float pitch, float gain, boolean loop);

    boolean setPosition(float position);

    float getPosition();
}
