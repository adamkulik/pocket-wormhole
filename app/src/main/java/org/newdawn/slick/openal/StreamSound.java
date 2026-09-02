package org.newdawn.slick.openal;

/**
 * Sound wrapper around a streaming player, matching Slick's API.
 *
 * Note: the engine (RealSoundManager) does its own streaming through its
 * fork of OpenALStreamPlayer; this class only needs to exist for
 * SoundStore.getOggStream compatibility, which the engine never calls.
 */
public class StreamSound implements Audio {
    private final OpenALStreamPlayer player;

    public StreamSound(OpenALStreamPlayer player) {
        this.player = player;
    }

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
        return player.setPosition(position);
    }

    @Override
    public float getPosition() {
        return player.getPosition();
    }
}
