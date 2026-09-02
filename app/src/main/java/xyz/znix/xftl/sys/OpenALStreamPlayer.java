package xyz.znix.xftl.sys;

import com.jcraft.jorbis.Info;
import com.jcraft.jorbis.VorbisFile;
import org.jetbrains.annotations.NotNull;
import org.lwjgl.BufferUtils;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.AL11;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.Objects;

// Copied from Slick, modified for XFTL

/**
 * A generic tool to work on a supplied stream, pulling out PCM data and buffered it to OpenAL
 * as required.
 *
 * @author Kevin Glass
 * @author Nathan Sweet <misc@n4te.com>
 * @author Rockstar play and setPosition cleanup
 * @author Campbell Suter - XFTL modifications
 */
public class OpenALStreamPlayer {
    /**
     * The number of buffers to maintain
     */
    public static final int BUFFER_COUNT = 3;
    /**
     * The size of the sections to stream from the stream
     */
    private static final int sectionSize = 4096 * 20;

    /**
     * The buffer read from the data stream
     */
    private final byte[] buffer = new byte[sectionSize];
    /**
     * Holds the OpenAL buffer names
     */
    private IntBuffer bufferNames;
    /**
     * The byte buffer passed to OpenAL containing the section
     */
    private final ByteBuffer bufferData = BufferUtils.createByteBuffer(sectionSize);
    /**
     * The buffer holding the names of the OpenAL buffer thats been fully played back
     */
    private final IntBuffer unqueued = BufferUtils.createIntBuffer(1);
    /**
     * The source we're playing back on
     */
    private final int source;
    /**
     * True if we've completed play back
     */
    private boolean done = true;
    /**
     * The file we're currently playing
     */
    private VorbisFile audio;
    /**
     * The pitch of the music
     */
    private float pitch = 1;
    /**
     * The volume of the music
     */
    private float volume = 1;
    /**
     * The non-fading master volume, which is multiplied into the regular volume.
     */
    private float masterVolume = 1;
    /**
     * Position in seconds of the previously played buffers
     */
    private float positionOffset;
    /**
     * The rate (in volume/second) to adjust the volume at.
     * <p>
     * This is only applied while {@link #volumeFadeTimer} is non-zero.
     */
    private float volumeFadeRate;
    /**
     * The amount of time during which the volume should be faded for.
     * <p>
     * This counts down towards zero.
     */
    private float volumeFadeTimer;

    /**
     * Create a new player to work on an audio stream
     *
     * @param source The source on which we'll play the audio
     */
    public OpenALStreamPlayer(int source) {
        if (source == -1) {
            throw new IllegalArgumentException("Invalid OpenAL source: " + source);
        }

        this.source = source;

        bufferNames = BufferUtils.createIntBuffer(BUFFER_COUNT);
        AL10.alGenBuffers(bufferNames);
    }

    /**
     * Clean up the buffers applied to the sound source
     */
    private void removeBuffers() {
        IntBuffer buffer = BufferUtils.createIntBuffer(1);
        int queued = AL10.alGetSourcei(source, AL10.AL_BUFFERS_QUEUED);

        while (queued > 0) {
            AL10.alSourceUnqueueBuffers(source, buffer);
            queued--;
        }
    }

    /**
     * Start this stream playing
     *
     * @param file The source of the sound data to play
     * @throws IOException Indicates a failure to read from the stream
     */
    public void play(@NotNull VorbisFile file) throws IOException {
        if (audio != null) {
            audio.close();
        }

        audio = Objects.requireNonNull(file);
        positionOffset = 0;

        AL10.alSourceStop(source);
        removeBuffers();

        startPlayback();
    }

    public void stop() {
        AL10.alSourceStop(source);
        removeBuffers();
        done = true;
    }

    /**
     * Set up the playback properties
     *
     * @param pitch  The pitch to play back at
     * @param volume The volume to play at
     */
    public void setup(float pitch, float volume, float masterVolume) {
        this.pitch = pitch;
        this.volume = volume;
        this.masterVolume = masterVolume;

        // Stop fading
        volumeFadeTimer = 0f;

        applyAudioSettings();
    }

    public void setMasterVolume(float masterVolume) {
        this.masterVolume = masterVolume;
        applyAudioSettings();
    }

    private void applyAudioSettings() {
        AL10.alSourcef(source, AL10.AL_PITCH, pitch);
        AL10.alSourcef(source, AL10.AL_GAIN, volume * masterVolume);
    }

    /**
     * Linearly fade the volume towards the given level, over the given duration.
     */
    public void fadeVolume(float finalVolume, float duration) {
        if (finalVolume == volume) {
            volumeFadeTimer = 0f;
            return;
        }

        if (duration == 0) {
            volume = finalVolume;
            volumeFadeTimer = 0f;
            applyAudioSettings();
            return;
        }

        volumeFadeTimer = duration;
        volumeFadeRate = (finalVolume - volume) / duration;
    }

    /**
     * Check if the playback is complete. Note this will never
     * return true if we're looping
     *
     * @return True if we're looping
     */
    public boolean done() {
        return done;
    }

    /**
     * Poll the bufferNames - check if we need to fill the bufferNames with another
     * section.
     * <p>
     * Most of the time this should be reasonably quick
     */
    public void update(float deltaTime) {
        if (done) {
            return;
        }

        // If we just seeked somewhere, the current link ID won't yet be known,
        // so use the first link's info for now.
        Info info = audio.getInfo(-1);
        if (info == null) {
            info = audio.getInfo(0);
        }

        float sampleRate = info.rate;
        float sampleSize;
        if (info.channels > 1) {
            sampleSize = 4; // AL10.AL_FORMAT_STEREO16
        } else {
            sampleSize = 2; // AL10.AL_FORMAT_MONO16
        }

        int processed = AL10.alGetSourcei(source, AL10.AL_BUFFERS_PROCESSED);
        while (processed > 0) {
            unqueued.clear();
            int unqueuedCount = AL10.alSourceUnqueueBuffers(source, unqueued);
            if (unqueuedCount != 1) {
                android.util.Log.w("XFTL", "stream: unqueue got " + unqueuedCount
                        + " (processed=" + processed + ")");
            }

            int bufferIndex = unqueued.get(0);

            float bufferLength = (AL10.alGetBufferi(bufferIndex, AL10.AL_SIZE) / sampleSize) / sampleRate;
            positionOffset += bufferLength;

            if (stream(bufferIndex)) {
                AL10.alSourceQueueBuffers(source, unqueued);
            } else {
                // We're playing the last few buffers, can't queue any more.
                // Since stream() has now set done=true, this won't be called repeatedly.
                android.util.Log.w("XFTL", "stream: refill FAILED - music will end (pos="
                        + positionOffset + "s)");
                break;
            }
            processed--;
        }

        int state = AL10.alGetSourcei(source, AL10.AL_SOURCE_STATE);

        if (state != AL10.AL_PLAYING) {
            // Switch back to playing if we stopped. This can happen if we run
            // out of buffers because something else is using all the CPU time.
            android.util.Log.w("XFTL", "stream: source not PLAYING (" + state
                    + ") - rescuing with play() at pos=" + positionOffset + "s");
            AL10.alSourcePlay(source);
        }

        if (volumeFadeTimer != 0f) {
            volumeFadeTimer -= deltaTime;
            if (volumeFadeTimer < 0f) {
                volumeFadeTimer = 0f;
            }

            volume += volumeFadeRate * deltaTime;
            applyAudioSettings();
        }
    }

    /**
     * Stream some data from the audio stream to the buffer indicates by the ID
     *
     * @param bufferId The ID of the buffer to fill
     * @return True if another section was available
     */
    public boolean stream(int bufferId) {
        // Read at least 80% of the buffer.
        // We can't go all the way since VorbisFile doesn't have its own
        // internal buffer, so once our buffer fills up we'll lose samples.
        // Note that if we don't have enough (for example, only a single
        // read of 500-to-4k-ish bytes) then we can end up running out of
        // buffers faster than we can add new ones, which makes some hard
        // to debug clicking sounds.
        int minimumCount = (int) (buffer.length * 0.8);

        int totalCount = 0;
        int lastCount = 0;
        while (totalCount < minimumCount) {
            int lenRemaining = buffer.length - totalCount;
            int count = audio.read(buffer, totalCount, lenRemaining, 0, 2, 1, null);
            lastCount = count;

            // If we hit EOF, don't return here - we might still
            // have the previous reads to put into a buffer.
            if (count <= 0) {
                done = true;
                break;
            }

            totalCount += count;
        }

        if (totalCount == 0) {
            android.util.Log.w("XFTL", "stream(" + bufferId + "): 0 bytes read (last count="
                    + lastCount + ")");
            return false;
        }

        bufferData.clear();
        bufferData.put(buffer, 0, totalCount);
        bufferData.flip();

        int format = audio.getInfo(-1).channels > 1 ? AL10.AL_FORMAT_STEREO16 : AL10.AL_FORMAT_MONO16;
        AL10.alBufferData(bufferId, format, bufferData, audio.getInfo(-1).rate);

        return true;
    }

    /**
     * Seeks to a position in the music.
     *
     * @param position Position in seconds.
     * @return True if the setting of the position was successful
     */
    public boolean setPosition(float position) {
        if (audio.time_seek(position) != 0) {
            return false;
        }

        // time_seek takes us to the packet (which is usually around
        // 500 samples long) that the target time is contained in.
        positionOffset = audio.time_tell();

        // Clear out and re-populate the buffers
        startPlayback();

        // Seek forwards a little bit, to get to the final position within
        // this sample. Without this, we always start a little behind.
        float timeError = position - positionOffset;
        if (timeError > 0) {
            AL11.alSourcef(source, AL11.AL_SEC_OFFSET, timeError);
        }

        return true;
    }

    /**
     * Starts the streaming.
     */
    private void startPlayback() {
        // If we don't remove the buffers here, we'll end up in a big mess
        // due to queueing them twice.
        stop();
        done = false;

        AL10.alSourcei(source, AL10.AL_LOOPING, AL10.AL_FALSE);
        applyAudioSettings();

        for (int i = 0; i < BUFFER_COUNT; i++) {
            stream(bufferNames.get(i));
        }

        AL10.alSourceQueueBuffers(source, bufferNames);
        AL10.alSourcePlay(source);
    }

    /**
     * Return the current playing position in the sound
     *
     * @return The current position in seconds.
     */
    public float getPosition() {
        return positionOffset + AL10.alGetSourcef(source, AL11.AL_SEC_OFFSET);
    }

    /**
     * Returns true if this stream is currently playing, and a volume fade is ongoing.
     */
    public boolean isVolumeFading() {
        return !done && volumeFadeTimer != 0f;
    }

    /**
     * Stop playing, and release all of the resources for this stream.
     * <p>
     * This player cannot be used again after this call.
     */
    public void close() throws IOException {
        // First un-queue the buffers, otherwise we can't delete them.
        AL10.alSourceStop(source);
        removeBuffers();

        // Then delete them.
        AL10.alDeleteBuffers(bufferNames);

        // Make sure we can't accidentally use them again.
        bufferNames = null;

        positionOffset = 0;
        done = true;

        if (audio != null) {
            audio.close();
            audio = null;
        }
    }
}
