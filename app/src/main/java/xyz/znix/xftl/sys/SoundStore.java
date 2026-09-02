package xyz.znix.xftl.sys;

import org.lwjgl.BufferUtils;
import org.lwjgl.openal.*;
import org.newdawn.slick.openal.Audio;
import org.newdawn.slick.openal.NullAudio;
import org.newdawn.slick.openal.OpenALStreamPlayer;
import org.newdawn.slick.openal.StreamSound;
import org.newdawn.slick.util.Log;

import java.io.IOException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.HashMap;

import static org.lwjgl.openal.ALC10.ALC_DEVICE_SPECIFIER;
import static org.lwjgl.system.MemoryUtil.NULL;

// Copied from Slick, modified for XFTL

/**
 * Responsible for holding and playing the sounds used in the game.
 *
 * @author Kevin Glass
 * @author Rockstar setVolume cleanup
 */
public class SoundStore {

    /**
     * The single instance of this class
     */
    private static final SoundStore store = new SoundStore();

    /**
     * True if sound effects are turned on
     */
    private boolean sounds;
    /**
     * True if music is turned on
     */
    private boolean music;
    /**
     * True if sound initialisation succeeded
     */
    private boolean soundWorks;
    /**
     * The number of sound sources enabled - default 8
     */
    private int sourceCount;
    /**
     * The map of references to IDs of previously loaded sounds
     */
    private HashMap loaded = new HashMap();
    /**
     * The ID of the buffer containing the music currently being played
     */
    private int currentMusic = -1;
    /**
     * The OpenGL AL sound sources in use
     */
    private IntBuffer sources;
    /**
     * The next source to be used for sound effects
     */
    private int nextSource;
    /**
     * True if the sound system has been initialise
     */
    private boolean inited = false;

    /**
     * The OpenAL device handle.
     */
    private long device;

    /**
     * The stream to be updated
     */
    private OpenALStreamPlayer stream;

    /**
     * True if the music is paused
     */
    private boolean paused;
    /**
     * True if we're returning deferred versions of resources
     */
    private boolean deferred;

    /**
     * The maximum number of sources
     */
    private int maxSources = 64;

    /**
     * The number of sources to reserve for music tracks.
     */
    private final int musicSourceCount = 2;

    /**
     * Create a new sound store
     */
    private SoundStore() {
    }

    /**
     * Disable use of the Sound Store
     */
    public void disable() {
        inited = true;
    }

    /**
     * True if we should only record the request to load in the intention
     * of loading the sound later
     *
     * @param deferred True if the we should load a token
     */
    public void setDeferredLoading(boolean deferred) {
        this.deferred = deferred;
    }

    /**
     * Check if we're using deferred loading
     *
     * @return True if we're loading deferred sounds
     */
    public boolean isDeferredLoading() {
        return deferred;
    }

    /**
     * Check if sound works at all
     *
     * @return True if sound works at all
     */
    public boolean soundWorks() {
        return soundWorks;
    }

    /**
     * Check if music is currently enabled
     *
     * @return True if music is currently enabled
     */
    public boolean musicOn() {
        return music;
    }

    /**
     * Get the ID of a given source
     *
     * @param index The ID of a given source
     * @return The ID of the given source
     */
    public int getSource(int index) {
        if (!soundWorks) {
            return -1;
        }
        if (index < 0) {
            return -1;
        }
        return sources.get(index);
    }

    /**
     * Get the ID of a given source, verifying that it's reserved
     * for playing music.
     *
     * @param index The ID of a given source
     * @return The ID of the given source
     */
    public int getMusicSource(int index) {
        if (!soundWorks) {
            return -1;
        }
        if (index < 0 || index >= musicSourceCount) {
            return -1;
        }
        return sources.get(index);
    }

    /**
     * Indicate whether sound effects should be played
     *
     * @param sounds True if sound effects should be played
     */
    public void setSoundsOn(boolean sounds) {
        if (soundWorks) {
            this.sounds = sounds;
        }
    }

    /**
     * Check if sound effects are currently enabled
     *
     * @return True if sound effects are currently enabled
     */
    public boolean soundsOn() {
        return sounds;
    }

    /**
     * Set the maximum number of concurrent sound effects that will be
     * attempted
     *
     * @param max The maximum number of sound effects/music to mix
     */
    public void setMaxSources(int max) {
        this.maxSources = max;
    }

    /**
     * Initialise the sound effects stored. This must be called
     * before anything else will work
     */
    public void init() {
        if (inited) {
            return;
        }
        Log.info("Initialising sounds..");
        inited = true;

        soundWorks = false;
        sounds = false;
        music = false;

        device = ALC10.alcOpenDevice((ByteBuffer) null);

        if (device == NULL) {
            Log.error("Sound initialisation failure (alcOpenDevice)");
            return;
        }
        ALCCapabilities alcCaps = ALC.createCapabilities(device);

        long context = ALC10.alcCreateContext(device, (int[]) null);
        if (!ALC10.alcMakeContextCurrent(context)) {
            Log.error("Sound initialisation failure (alcMakeContextCurrent)");
            return;
        }
        AL.createCapabilities(alcCaps);

        // Clear any previous errors.
        AL10.alGetError();

        String deviceName = ALC10.alcGetString(device, ALC_DEVICE_SPECIFIER);
        Log.info("- Sound works, using sound device: " + deviceName);
        soundWorks = true;
        sounds = true;
        music = true;

        sources = BufferUtils.createIntBuffer(maxSources);
        while (AL10.alGetError() == AL10.AL_NO_ERROR) {
            IntBuffer temp = BufferUtils.createIntBuffer(1);

            AL10.alGenSources(temp);

            if (AL10.alGetError() == AL10.AL_NO_ERROR) {
                sourceCount++;
                sources.put(temp.get(0));
                if (sourceCount > maxSources - 1) {
                    break;
                }
            }
        }
        Log.info("- " + sourceCount + " OpenAL source available");

        if (AL10.alGetError() != AL10.AL_NO_ERROR) {
            sounds = false;
            music = false;
            soundWorks = false;
            Log.error("- AL init failed");
        } else {
            FloatBuffer listenerOri = BufferUtils.createFloatBuffer(6).put(
                    new float[]{0.0f, 0.0f, -1.0f, 0.0f, 1.0f, 0.0f});
            listenerOri.flip();
            AL10.alListener3f(AL10.AL_POSITION, 0f, 0f, 0f);
            AL10.alListener3f(AL10.AL_VELOCITY, 0f, 0f, 0f);
            AL10.alListenerfv(AL10.AL_ORIENTATION, listenerOri);

            Log.info("- Sounds source generated");
        }
    }

    /**
     * Stop a particular sound source
     *
     * @param index The index of the source to stop
     */
    public void stopSource(int index) {
        AL10.alSourceStop(sources.get(index));
    }

    /**
     * Stop a particular sound source, and release the associated buffer[s].
     *
     * @param index The index of the source to stop
     */
    public void stopAndResetSource(int index) {
        AL10.alSourceStop(sources.get(index));
        AL10.alSourcei(sources.get(index), AL10.AL_BUFFER, AL10.AL_NONE);
    }

    /**
     * Play the specified buffer as a sound effect with the specified
     * pitch and gain.
     *
     * @param buffer The ID of the buffer to play
     * @param pitch  The pitch to play at
     * @param gain   The gain to play at
     * @param loop   True if the sound should loop
     * @return source The source that will be used
     */
    public int playAsSound(int buffer, float pitch, float gain, boolean loop) {
        return playAsSoundAt(buffer, pitch, gain, loop, 0, 0, 0);
    }

    /**
     * Play the specified buffer as a sound effect with the specified
     * pitch and gain.
     *
     * @param buffer The ID of the buffer to play
     * @param pitch  The pitch to play at
     * @param gain   The gain to play at
     * @param loop   True if the sound should loop
     * @param x      The x position to play the sound from
     * @param y      The y position to play the sound from
     * @param z      The z position to play the sound from
     * @return source The source that will be used
     */
    int playAsSoundAt(int buffer, float pitch, float gain, boolean loop, float x, float y, float z) {
        if (gain == 0) {
            gain = 0.001f;
        }
        if (soundWorks) {
            if (sounds) {
                int nextSource = findFreeSource();
                if (nextSource == -1) {
                    return -1;
                }

                AL10.alSourceStop(sources.get(nextSource));

                AL10.alSourcei(sources.get(nextSource), AL10.AL_BUFFER, buffer);
                AL10.alSourcef(sources.get(nextSource), AL10.AL_PITCH, pitch);
                AL10.alSourcef(sources.get(nextSource), AL10.AL_GAIN, gain);
                AL10.alSourcei(sources.get(nextSource), AL10.AL_LOOPING, loop ? AL10.AL_TRUE : AL10.AL_FALSE);

                AL10.alSource3f(sources.get(nextSource), AL10.AL_POSITION, x, y, z);
                AL10.alSource3f(sources.get(nextSource), AL10.AL_VELOCITY, 0f, 0f, 0f);

                AL10.alSourcePlay(sources.get(nextSource));

                return nextSource;
            }
        }

        return -1;
    }

    /**
     * Check if a particular source is playing
     *
     * @param index The index of the source to check
     * @return True if the source is playing
     */
    boolean isPlaying(int index) {
        int state = AL10.alGetSourcei(sources.get(index), AL10.AL_SOURCE_STATE);

        return (state == AL10.AL_PLAYING);
    }

    /**
     * Find a free sound source
     *
     * @return The index of the free sound source
     */
    private int findFreeSource() {
        for (int i = musicSourceCount; i < sourceCount - 1; i++) {
            int state = AL10.alGetSourcei(sources.get(i), AL10.AL_SOURCE_STATE);

            if ((state != AL10.AL_PLAYING) && (state != AL10.AL_PAUSED)) {
                return i;
            }
        }

        return -1;
    }

    /**
     * Play the specified buffer as music (i.e. use the music channel)
     *
     * @param buffer The buffer to be played
     * @param pitch  The pitch to play the music at
     * @param gain   The gaing to play the music at
     * @param loop   True if we should loop the music
     */
    void playAsMusic(int buffer, float pitch, float gain, boolean loop) {
        paused = false;

        if (soundWorks) {
            if (currentMusic != -1) {
                AL10.alSourceStop(sources.get(0));
            }

            getMusicSource();

            AL10.alSourcei(sources.get(0), AL10.AL_BUFFER, buffer);
            AL10.alSourcef(sources.get(0), AL10.AL_PITCH, pitch);
            AL10.alSourcei(sources.get(0), AL10.AL_LOOPING, loop ? AL10.AL_TRUE : AL10.AL_FALSE);

            currentMusic = sources.get(0);

            if (!music) {
                pauseLoop();
            } else {
                AL10.alSourcePlay(sources.get(0));
            }
        }
    }

    /**
     * Get the OpenAL source used for music
     *
     * @return The open al source used for music
     */
    private int getMusicSource() {
        return sources.get(0);
    }

    /**
     * Set the pitch at which the current music is being played
     *
     * @param pitch The pitch at which the current music is being played
     */
    public void setMusicPitch(float pitch) {
        if (soundWorks) {
            AL10.alSourcef(sources.get(0), AL10.AL_PITCH, pitch);
        }
    }

    /**
     * Pause the music loop that is currently playing
     */
    public void pauseLoop() {
        if ((soundWorks) && (currentMusic != -1)) {
            paused = true;
            AL10.alSourcePause(currentMusic);
        }
    }

    /**
     * Restart the music loop that is currently paused
     */
    public void restartLoop() {
        if ((music) && (soundWorks) && (currentMusic != -1)) {
            paused = false;
            AL10.alSourcePlay(currentMusic);
        }
    }

    /**
     * Check if the supplied player is currently being polled by this
     * sound store.
     *
     * @param player The player to check
     * @return True if this player is currently in use by this sound store
     */
    boolean isPlaying(OpenALStreamPlayer player) {
        return stream == player;
    }

    /**
     * Get the Sound based on a specified OGG file
     *
     * @param ref The reference to the OGG file in the classpath
     * @return The Sound read from the OGG file
     * @throws IOException Indicates a failure to load the OGG
     */
    public Audio getOggStream(String ref) throws IOException {
        if (!soundWorks) {
            return new NullAudio();
        }

        setStream(null);

        if (currentMusic != -1) {
            AL10.alSourceStop(sources.get(0));
        }

        getMusicSource();
        currentMusic = sources.get(0);

        return new StreamSound(new OpenALStreamPlayer(currentMusic, ref));
    }

    /**
     * Get the Sound based on a specified OGG file
     *
     * @param ref The reference to the OGG file in the classpath
     * @return The Sound read from the OGG file
     * @throws IOException Indicates a failure to load the OGG
     */
    public Audio getOggStream(URL ref) throws IOException {
        if (!soundWorks) {
            return new NullAudio();
        }

        setStream(null);

        if (currentMusic != -1) {
            AL10.alSourceStop(sources.get(0));
        }

        getMusicSource();
        currentMusic = sources.get(0);

        return new StreamSound(new OpenALStreamPlayer(currentMusic, ref));
    }

    /**
     * Set the stream being played
     *
     * @param stream The stream being streamed
     */
    void setStream(OpenALStreamPlayer stream) {
        if (!soundWorks) {
            return;
        }

        currentMusic = sources.get(0);
        this.stream = stream;
        paused = false;
    }

    /**
     * Poll the streaming system
     *
     * @param delta The amount of time passed since last poll (in milliseconds)
     */
    public void poll(int delta) {
        if (!soundWorks) {
            return;
        }
        if (paused) {
            return;
        }

        if (music && stream != null) {
            stream.update();
        }
    }

    /**
     * Check if the music is currently playing
     *
     * @return True if the music is playing
     */
    public boolean isMusicPlaying() {
        if (!soundWorks) {
            return false;
        }

        int state = AL10.alGetSourcei(sources.get(0), AL10.AL_SOURCE_STATE);
        return ((state == AL10.AL_PLAYING) || (state == AL10.AL_PAUSED));
    }

    /**
     * Get the single instance of this class
     *
     * @return The single instnace of this class
     */
    public static SoundStore get() {
        return store;
    }

    /**
     * Stop a playing sound identified by the ID returned from playing. This utility method
     * should only be used when needing to stop sound effects that may have been played
     * more than once and need to be explicitly stopped.
     *
     * @param id The ID of the underlying OpenAL source as returned from playAsSoundEffect
     */
    public void stopSoundEffect(int id) {
        AL10.alSourceStop(id);
    }

    /**
     * Retrieve the number of OpenAL sound sources that have been
     * determined at initialisation.
     *
     * @return The number of sources available
     */
    public int getSourceCount() {
        return sourceCount;
    }
}
