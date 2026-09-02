package xyz.znix.xftl.game

import com.jcraft.jorbis.VorbisFile
import org.jdom2.Element
import org.lwjgl.openal.AL10
import org.newdawn.slick.openal.OggDecoder
import org.newdawn.slick.openal.OggInputStream
import org.newdawn.slick.openal.WaveData
import xyz.znix.xftl.Datafile
import xyz.znix.xftl.FTLFile
import xyz.znix.xftl.Ship
import xyz.znix.xftl.VanillaDatafile
import xyz.znix.xftl.sys.INativeResource
import xyz.znix.xftl.sys.OpenALStreamPlayer
import xyz.znix.xftl.sys.ResourceContext
import xyz.znix.xftl.sys.SoundStore
import java.io.InputStream
import java.io.RandomAccessFile

// Interface, to allow a no-op one for automated testing.
interface SoundManager {
    fun getSample(name: String): FTLSound
    fun getSampleOrWarn(name: String): FTLSound?
    fun getLoop(name: String): LoopHandle
    fun getTrack(name: String): MusicSpec
    fun switchMusicList(tracks: List<MusicSpec>)
}

class RealSoundManager(private val df: Datafile, context: ResourceContext) : SoundManager, INativeResource {
    private val sounds = HashMap<SoundSpec, RealFTLSound>()
    private val rawBuffers = HashMap<String, Int>()

    // Nebula is both a sample and a loop
    private val samples = HashMap<String, SoundSpec>()
    private val loops = HashMap<String, SoundSpec>()

    private val musicTracks = HashMap<String, MusicSpec>()

    private val playingLoops = ArrayList<LoopHandle>()
    private val loopSounds = HashMap<SoundSpec, SoundInstance>()
    private val loopCounts = HashMap<SoundSpec, Int>()

    /**
     * The currently-playing music track.
     */
    var currentMusic: MusicSpec? = null
        private set

    private var usingCombatMusic: Boolean = false

    private val trackList = ArrayList<MusicSpec>()

    /**
     * The players for playing our music. We need two for cross-fading.
     *
     * The main one (which is the next one during a crossfade) is in index 0,
     * while the inactive (outgoing during a crossfade) one is in index 1.
     */
    private val musicPlayers: Array<OpenALStreamPlayer>

    /**
     * The current primary music player.
     *
     * This is exposed in case mods want to read the current music time or
     * something like that, if there's no cleaner way to do it.
     *
     * Be careful when poking around at this (and in particular, don't
     * write to it) - it's an implementation detail of the sound manager.
     */
    val activeMusicPlayer: OpenALStreamPlayer get() = musicPlayers[0]

    /**
     * Set to true every frame to play the out-of-fuel distress beacon sound.
     *
     * This is a very special case, as it's a looped sound that has to play
     * while the game is paused.
     */
    var playingFuelDistressSound: Boolean = false
    private var fuelDistressSound: SoundInstance? = null

    override var freed: Boolean = false
        private set

    // The SFX/music volumes, as set in the UI
    var soundEffectVolume: Float = 1f
        set(value) {
            if (field == value)
                return

            field = value

            // Update the volumes of any currently-playing sounds
            RealSoundInstance.updateVolumes()
        }

    var musicVolume: Float = 1f
        set(value) {
            if (field == value)
                return

            field = value

            for (player in musicPlayers) {
                player.setMasterVolume(musicVolume)
            }
        }

    init {
        // Make sure we've classloaded our modified copy of OggInputStream
        OggInputStream.FTL_MARKER = 2
        VorbisFile.XFTL_MARKER = 2

        loadXml("data/sounds.xml")
        loadXml("data/dlcSounds.xml")

        SoundStore.get().init()

        musicPlayers = arrayOf(
            OpenALStreamPlayer(SoundStore.get().getMusicSource(0)),
            OpenALStreamPlayer(SoundStore.get().getMusicSource(1)),
        )

        context.register(this)
    }

    private fun loadXml(path: String) {
        val doc = df.parseXML(df[path])
        for (elem in doc.rootElement.children) {
            if (elem.name == "music") {
                loadMusic(elem)
                continue
            }

            val spec = SoundSpec(elem)
            if (spec.loop) {
                if (loops.containsKey(spec.name)) {
                    println("[WARN] Duplicate sound loop '${spec.name}' in sounds.xml")
                }
                loops[spec.name] = spec
            } else {
                if (samples.containsKey(spec.name)) {
                    println("[WARN] Duplicate sound sample '${spec.name}' in sounds.xml")
                }
                samples[spec.name] = spec
            }
        }
    }

    private fun loadMusic(elem: Element) {
        for (child in elem.getChildren("track")) {
            val track = MusicSpec(child)
            musicTracks[track.name] = track
        }
    }

    override fun getSample(name: String): FTLSound {
        val spec = samples[name] ?: error("No sound sample listed in sounds.xml for '$name'")
        return get(spec)
    }

    override fun getSampleOrWarn(name: String): FTLSound? {
        val spec = samples[name]
        if (spec == null) {
            println("No sound sample listed in sounds.xml for '$name'")
            return null
        }
        return get(spec)
    }

    /**
     * Create a new handle that can trigger this sound loop.
     *
     * This doesn't actually produce any audio unless [LoopHandle.continueLoopAnyShip] is called.
     */
    override fun getLoop(name: String): LoopHandle {
        val spec = loops[name] ?: error("No sound loop listed in sounds.xml for '$name'")
        return LoopHandle(spec, this)
    }

    /**
     * Gets the description of a music track by its ID.
     *
     * This doesn't create any resources, but it can be passed to [switchToMusic].
     */
    override fun getTrack(name: String): MusicSpec {
        return musicTracks[name] ?: error("No music track listed in sounds.xml for '$name'")
    }

    // Should only be called by SoundInstance!
    fun registerPlayingLoop(loop: LoopHandle) {
        playingLoops.add(loop)
    }

    fun updateLoopedSounds(gamePaused: Boolean) {
        // This is a bit of a horrible hack, but we have to handle the out-of-fuel
        // distress beacon sound specially since it's a loop that plays while
        // the game is paused.
        if (playingFuelDistressSound) {
            if (fuelDistressSound == null) {
                val sound = get(loops["distress"] ?: error("Missing distress sound loop!"))
                fuelDistressSound = sound.internalPlayRawLoop()
            }

            // Must be set to true again every frame
            playingFuelDistressSound = false
        } else {
            fuelDistressSound?.stop()
            fuelDistressSound = null
        }

        if (gamePaused) {
            for (sound in loopSounds.values) {
                sound.isPaused = true
            }
            return
        }

        // Calculate how many handles are currently playing for each loop.
        for (handle in playingLoops) {
            loopCounts[handle.spec] = (loopCounts[handle.spec] ?: 0) + 1
        }
        playingLoops.clear()

        // Update the currently playing loops based on those counts.
        // Note this effectively clears the loop counts.

        // Removing one sound per update is fine - note we still stop
        // them playing in a single update.
        var toRemove: SoundSpec? = null

        for ((spec, sound) in loopSounds.entries) {
            val count = loopCounts.remove(spec) ?: 0

            // TODO fade down the loops when they stop, like FTL does
            if (count == 0) {
                sound.stop()
                toRemove = spec
                continue
            }

            // Un-pause sounds that were paused by pausing the game.
            sound.isPaused = false

            // Clamp to ensure that having more than maxCount sources
            // doesn't adjust the volume.
            sound.volume = (1f * count / spec.maxCount).coerceIn(0f..1f)
        }

        // Start any new sounds
        if (loopCounts.isNotEmpty()) {
            for ((spec, count) in loopCounts.entries) {
                val sound = get(spec).internalPlayRawLoop()
                sound.volume = 1f * count / spec.maxCount
                loopSounds[spec] = sound
            }
            loopCounts.clear()
        }

        // Remove stopped sounds so they can be GCed.
        // We can't do this in the loop above since that'd mean modifying
        // the map while it's being iterated over.
        if (toRemove != null) {
            loopSounds.remove(toRemove)
        }
    }

    fun updateMusic(dt: Float) {
        // Decode and queue up the next buffer of audio as required.
        for (player in musicPlayers) {
            player.update(dt)
        }

        // If the 2nd player has finished fading, pause it to save CPU.
        if (!musicPlayers[1].isVolumeFading) {
            musicPlayers[1].stop()
        }

        // If the main player has stopped, move onto the next track in
        // this sector's playlist.
        if (musicPlayers[0].done() && trackList.isNotEmpty()) {
            // The index of the current track, or -1 if it's not there
            val currentIdx = currentMusic?.let { trackList.indexOf(it) } ?: -1

            val nextIdx = (currentIdx + 1).mod(trackList.size)
            switchToMusic(trackList[nextIdx], usingCombatMusic, 0f, 0f)
        }
    }

    /**
     * Switch to a new track.
     *
     * This must NOT be used to switch between the combat/non-combat versions
     * of the same track, as that needs special handling for it's timing.
     */
    fun switchToMusic(track: MusicSpec, combat: Boolean, fadeOut: Float, fadeIn: Float) {
        currentMusic = track
        switchMusicInternal(track, combat, fadeOut, fadeIn)
    }

    /**
     * Set the list of soundtracks to loop through, as specified by the current sector.
     */
    override fun switchMusicList(tracks: List<MusicSpec>) {
        trackList.clear()
        trackList.addAll(tracks)
        switchToMusic(tracks.first(), usingCombatMusic, 0.75f, 2f)
    }

    private fun switchMusicInternal(track: MusicSpec, combat: Boolean, fadeOut: Float, fadeIn: Float) {
        // Move the current player to the 2nd slot, so we fade away from it.
        val previous = musicPlayers[0]
        musicPlayers[0] = musicPlayers[1]
        musicPlayers[1] = previous

        val fileName = when (combat) {
            true -> track.combat
            false -> track.explore
        }
        val finalVolume = when (combat) {
            true -> 1f
            false -> 0.8f
        }

        val inputStream = VorbisInputStream(df, df[fileName])
        val file = VorbisFile(inputStream, null, 0)
        if (org.lwjgl.openal.SoftAL.isDumping()) {
            android.util.Log.i("XFTL", "switchToMusic: $fileName")
        }

        // Start muted, and we'll fade in
        musicPlayers[0].setup(1f, 0f, musicVolume)
        musicPlayers[0].play(file)

        // Set up the cross-fade
        musicPlayers[0].fadeVolume(finalVolume, fadeIn)
        musicPlayers[1].fadeVolume(0f, fadeOut)
    }

    /**
     * Switch between the combat and explore versions of the current music track.
     */
    fun setCombatMusic(combat: Boolean) {
        val track = currentMusic ?: return

        if (combat == usingCombatMusic)
            return
        usingCombatMusic = combat

        switchMusicInternal(track, combat, 2f, 2f)

        // The combat and explore tracks match up with each other, so we fade
        // between the two versions while they're synced up in time.
        musicPlayers[0].position = musicPlayers[1].position

        // Use this for debugging the OGG and streaming code:
        // println("Times:")
        // println("New: " + musicPlayers[0].position)
        // println("Old: " + musicPlayers[1].position)
    }

    override fun free() {
        require(!freed)
        freed = true

        // Stop all the sources, otherwise we can't remove the buffers
        for (i in 0 until SoundStore.get().sourceCount) {
            SoundStore.get().stopAndResetSource(i)
        }

        // Delete all the buffers, to save memory.
        for (buffer in rawBuffers.values) {
            AL10.alDeleteBuffers(buffer)
        }
    }

    private fun get(spec: SoundSpec): RealFTLSound {
        sounds[spec]?.let { return it }

        val sound = loadRawSound(spec)
        sounds[spec] = sound
        return sound
    }

    private fun loadRawSound(spec: SoundSpec): RealFTLSound {
        val path = "audio/waves/" + spec.path

        // Only load the actual sounds on-demand, to speed up loading large
        // mods (this is taking about a 5th the time spent loading Multiverse).
        // TODO load sounds in the background, to avoid pauses from them being
        //  loaded up on demand.
        val buffer = lazy { loadBuffer(path) }

        return RealFTLSound(spec, buffer, this)
    }

    private fun loadBuffer(path: String): Int {
        rawBuffers[path]?.let { return it }

        if (!SoundStore.get().soundWorks()) {
            return RealSoundInstance.NO_SOUND_BUFFER
        }

        // Rather than using Slick's audio abstractions, use OpenAL directly.
        // It'll be easier to handle stuff like playing the same sound multiple
        // times concurrently.
        val buffer = try {
            if (org.lwjgl.openal.SoftAL.isDumping()) {
                android.util.Log.i("XFTL", "loadRawSound: $path")
            }
            df.open(df[path]).use { stream ->
                if (path.endsWith(".ogg")) {
                    loadOGG(stream)
                } else if (path.endsWith(".wav")) {
                    getWAV(stream)
                } else {
                    error("Unsupported sound format for file '$path'")
                }
            }
        } catch (e: Exception) {
            throw RuntimeException("Failed to load sound from path '$path'", e)
        }

        rawBuffers[path] = buffer
        return buffer
    }

    private fun loadOGG(stream: InputStream): Int {
        // Based off SoundStore.getOgg
        val decoder = OggDecoder()
        val ogg = decoder.getData(stream)
        val buf = AL10.alGenBuffers()
        require(buf != -1)
        AL10.alBufferData(
            buf,
            if (ogg.channels > 1) AL10.AL_FORMAT_STEREO16 else AL10.AL_FORMAT_MONO16,
            ogg.data,
            ogg.rate
        )
        return buf
    }

    private fun getWAV(stream: InputStream): Int {
        // Based off SoundStore.getWAV
        val data = WaveData.create(stream)
        val buf = AL10.alGenBuffers()
        require(buf != -1)
        AL10.alBufferData(buf, data.format, data.data, data.samplerate)
        return buf
    }
}

/**
 * This is our representation of a sound that can be played.
 *
 * Note it doesn't represent a currently-playing sound - since
 * a sound can be playing twice at once, [SoundInstance]s are
 * used for that purpose.
 *
 * This is an interface for automated testing.
 */
interface FTLSound {
    /**
     * Play a non-looping sound.
     */
    fun play(): SoundInstance
}

private class RealFTLSound(
    val spec: SoundSpec,
    private val buffer: Lazy<Int>,
    private val manager: RealSoundManager
) : FTLSound {
    override fun play(): SoundInstance {
        require(!spec.loop)
        return RealSoundInstance(spec, buffer.value, manager)
    }

    /**
     * Don't use this outside of SoundManager!
     *
     * This plays a loop by itself, which should instead be done via a [LoopHandle].
     */
    fun internalPlayRawLoop(): SoundInstance {
        require(spec.loop)
        return RealSoundInstance(spec, buffer.value, manager)
    }
}

/**
 * A PlaybackInstance represents a single playback of a sound. It can
 * be paused or stopped independent of all other currently-playing instances
 * of this sound.
 *
 * Instances of this class should be created via [FTLSound.play].
 *
 * This is an interface for automated testing.
 */
interface SoundInstance {
    var isPaused: Boolean
    var volume: Float

    fun stop()
}

private class RealSoundInstance(
    private val spec: SoundSpec,
    buffer: Int,
    private val manager: RealSoundManager
) : SoundInstance {
    // This is only used by the single instance for a loop that the
    // sound manager owns and controls.
    private val loop: Boolean = spec.loop

    // The index into SoundStore's list of OpenAL sources, or NO_SOURCE.
    private var source: Int = NO_SOURCE

    val isStopped: Boolean
        get() {
            // Check if we've previously marked ourselves as stopped.
            if (source == NO_SOURCE)
                return true

            // Check if we finished and another sound reused our source.
            if (SOURCE_PLAYBACK[source] != this)
                return true

            // Check the OpenAL sound isn't in the stopped state.
            val sourceHandle = SoundStore.get().getSource(source)
            if (sourceHandle == -1) {
                source = NO_SOURCE
                return true
            }
            val state = AL10.alGetSourcei(sourceHandle, AL10.AL_SOURCE_STATE)

            if (state == AL10.AL_PLAYING || state == AL10.AL_PAUSED) {
                return false
            }

            // We've stopped playing, clear the source so it's
            // quicker to check next time.
            source = NO_SOURCE
            return true
        }

    private var isPausedInternal: Boolean = false
    override var isPaused: Boolean
        get() {
            if (source == NO_SOURCE)
                return false
            return isPausedInternal
        }
        set(value) {
            // Don't call pause/play twice, since that can
            // do stuff like restart the track.
            if (isPausedInternal == value)
                return

            // We're not paused if we've finished
            if (isStopped) {
                return
            }

            val sourceHandle = SoundStore.get().getSource(source)
            if (value) {
                AL10.alSourcePause(sourceHandle)
            } else {
                AL10.alSourcePlay(sourceHandle)
            }
            isPausedInternal = value
        }

    override var volume: Float = 1f
        set(value) {
            if (value == field)
                return
            field = value
            updateVolume()
        }

    init {
        // If this is a placeholder sound because audio isn't
        // working or something like that, do nothing.
        if (buffer == NO_SOUND_BUFFER) {
            source = NO_SOURCE
        } else {
            source = SoundStore.get().playAsSound(buffer, 1f, calculateGain(), loop)

            if (source == -1) {
                println("[WARN] No source available to play sound ${spec.name}")
            } else {
                SOURCE_PLAYBACK[source] = this
            }
        }
    }

    private fun updateVolume() {
        val sourceHandle = SoundStore.get().getSource(source)
        AL10.alSourcef(sourceHandle, AL10.AL_GAIN, calculateGain())
    }

    private fun calculateGain(): Float {
        return volume * manager.soundEffectVolume * (spec.volume / 10f)
    }

    /**
     * Stop playing this sound.
     */
    override fun stop() {
        if (isStopped) {
            return
        }

        SoundStore.get().stopSource(source)
        source = NO_SOURCE
    }

    companion object {
        const val NO_SOUND_BUFFER = -1

        private const val NO_SOURCE = -1

        // The mapping from an OpenAL source to the sound it's playing.
        // This lets us reliably check if we've been stopped, as otherwise
        // if our buffer is marked as playing we may have finished and
        // another sound started.
        private val SOURCE_PLAYBACK = HashMap<Int, RealSoundInstance>()

        fun updateVolumes() {
            for (sound in SOURCE_PLAYBACK.values) {
                if (sound.isStopped)
                    return

                sound.updateVolume()
            }
        }
    }
}

/**
 * This represents a way to contribute to a sound loop. If multiple things
 * are all trying to play a sound loop, only a single copy of the sound
 * is played but its volume is increased, upto the limit of [SoundSpec.maxCount].
 *
 * Holding one of these instances is very cheap, and doesn't cause the
 * sound to be played unless [continueLoopAnyShip] is being called every update.
 *
 * A handle can be re-used, calling [continueLoopAnyShip] on some frames and not
 * on others. It doesn't have to be re-created after it's first stopped.
 */
class LoopHandle(val spec: SoundSpec, private val manager: RealSoundManager) {
    /**
     * Sound loops will play until you stop calling this every
     * update, rather than playing until you tell them to stop.
     *
     * This is intended to prevent cases where a loop isn't stopped
     * and gets stuck playing continuously.
     *
     * The name contains 'AnyShip' to remind the user that sounds
     * played from this will be audible regardless of what ship
     * is causing them - see [continueLoopPlayerOnly] for sounds that
     * should only be heard from the player's ship.
     */
    fun continueLoopAnyShip() {
        manager.registerPlayingLoop(this)
    }

    /**
     * This is like [continueLoopAnyShip], but only plays the sound on the player's ship.
     */
    fun continueLoopPlayerOnly(ship: Ship) {
        if (ship.isPlayerShip) {
            continueLoopAnyShip()
        }
    }
}

class SoundSpec(elem: Element) {
    val name: String = elem.name
    val path: String = elem.textTrim

    val volume: Int = elem.getAttributeValue("volume")!!.toInt()
    val loop: Boolean = elem.getAttributeValue("loop")?.toBoolean() ?: false

    /**
     * This is for loops, as they can be played by multiple sources.
     *
     * For something like fire, there's a count specified by whatever is
     * triggering the audio (eg, the number of fires on the ship). This is then
     * clamped to this, and divided by it to get a multiplier for a volume.
     *
     * So with fire set with a maxCount of 5, one fire would only play at 20% volume.
     */
    val maxCount: Int = elem.getAttributeValue("count")?.toInt() ?: 1
}

class MusicSpec(elem: Element) {
    val name: String = elem.getChildTextTrim("name")

    val explore: String = "audio/music/" + elem.getChildTextTrim("explore")
    val combat: String = elem.getChildTextTrim("combat")?.let { "audio/music/$it" } ?: explore
}

private class VorbisInputStream(df: Datafile, file: FTLFile) : VorbisFile.SeekableInputStream() {
    /**
     * To avoid loading the whole track into memory (which is quite unnecessary),
     * we open the raw FTL database.
     *
     * TODO this will need special-casing for mods.
     */
    private val fi: RandomAccessFile
    private val file: VanillaDatafile.Entry

    init {
        // This won't work for tests, but we don't load sounds there.
        val vanilla = df.vanilla as VanillaDatafile

        fi = RandomAccessFile(vanilla.underlyingFile, "r")
        this.file = df.vanilla[file.name]

        // Without this, we start before the OGG file's position in the DAT file.
        seek(0)
    }

    override fun read(): Int {
        // VorbisFile doesn't use this
        error("Single-byte reading not is supported")
    }

    override fun read(data: ByteArray, off: Int, len: Int): Int {
        val remaining = file.offset + file.length - fi.filePointer
        assert(remaining >= 0) // Negatives mean we've gone past EOF
        if (remaining <= 0)
            return -1

        val clampedLen = len.coerceAtMost(remaining.toInt())
        return fi.read(data, off, clampedLen)
    }

    override fun getLength(): Long {
        return file.length.toLong()
    }

    override fun tell(): Long {
        return fi.filePointer - file.offset
    }

    override fun seek(pos: Long) {
        if (pos < 0 || pos > file.length) {
            throw IllegalArgumentException("Illegal seek position $pos, file length ${file.length}")
        }

        fi.seek(file.offset + pos)
    }

    override fun close() {
        fi.close()
    }
}
