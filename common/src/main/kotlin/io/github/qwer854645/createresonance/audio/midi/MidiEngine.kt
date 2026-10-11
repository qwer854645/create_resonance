package io.github.qwer854645.createresonance.audio.midi

import io.github.qwer854645.createresonance.CreateResonanceMod
import io.github.qwer854645.createresonance.content.midi.MidiLimits
import io.github.qwer854645.createresonance.foundation.info
import io.github.qwer854645.createresonance.foundation.warn
import net.minecraft.client.Minecraft
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.sound.midi.MidiChannel
import javax.sound.midi.MidiSystem
import javax.sound.midi.Receiver
import javax.sound.midi.Sequence
import javax.sound.midi.Sequencer
import javax.sound.midi.Soundbank
import javax.sound.midi.Synthesizer
import kotlin.math.abs

/**
 * Client-side MIDI playback with channel filtering, forced GM instrument, and SoundFont loading.
 */
object MidiEngine {
    const val MAX_MIDI_BYTES = MidiLimits.MAX_MIDI_BYTES

    /** GM percussion channel — must not receive melodic program changes. */
    const val DRUM_CHANNEL = 9

    private val players = ConcurrentHashMap<String, MidiPlayerInstance>()
    private var sharedSoundbank: Soundbank? = null
    private val soundbankTried = AtomicBoolean(false)

    fun soundfontDir(): File {
        val gameDir = Minecraft.getInstance().gameDirectory
        return File(gameDir, "config/${CreateResonanceMod.MOD_ID}/soundfonts").also { it.mkdirs() }
    }

    /** Same layout as Create's `schematics/` — folder at the game root. */
    fun importDir(): File {
        val gameDir = Minecraft.getInstance().gameDirectory
        return File(gameDir, "midi").also { it.mkdirs() }
    }

    fun ensureSoundbank() {
        if (!soundbankTried.compareAndSet(false, true)) return
        val dir = soundfontDir()
        val sf2 =
            dir.listFiles()?.firstOrNull {
                it.isFile && (it.name.endsWith(".sf2", true) || it.name.endsWith(".sf3", true))
            }
        if (sf2 != null) {
            try {
                sharedSoundbank = MidiSystem.getSoundbank(sf2)
            } catch (e: Exception) {
                "Failed to load SoundFont ${sf2.name}: ${e.message}".warn()
            }
        }
    }

    fun getOrCreate(id: String): MidiPlayerInstance =
        players.getOrPut(id) {
            ensureSoundbank()
            MidiPlayerInstance(id, sharedSoundbank)
        }

    fun release(id: String) {
        players.remove(id)?.close()
    }

    /** Silence without creating a player — used when a paused sync packet arrives. */
    fun pauseIfPresent(id: String) {
        players[id]?.pause()
    }

    /** True while any music-box / conductor MIDI instance is emitting audio. */
    fun anyAudiblyPlaying(): Boolean =
        players.values.any { it.playing && !it.muted }

    fun listImportFiles(): List<File> =
        importDir()
            .listFiles()
            ?.filter { it.isFile && (it.extension.equals("mid", true) || it.extension.equals("midi", true)) }
            ?.sortedBy { it.name.lowercase() }
            ?: emptyList()
}

class MidiPlayerInstance(
    val id: String,
    private val soundbank: Soundbank?,
) {
    private var synthesizer: Synthesizer? = null
    /** Extra SoftSynth banks when a single device cannot raise polyphony past Gervill's 64. */
    private var auxiliarySynthesizers: List<Synthesizer> = emptyList()
    private var sequencer: Sequencer? = null
    private var sequence: Sequence? = null
    private var stateTimeline: MidiStateTimeline? = null
    private var routingReceiver: MidiRoutingReceiver? = null
    private var tempoFactor: Float = 1f

    private fun allSynthesizers(): List<Synthesizer> = listOfNotNull(synthesizer) + auxiliarySynthesizers

    @Volatile
    var allowedChannels: Set<Int>? = null // null = all channels
        private set

    /** When set, each melodic channel uses this instrument while retaining its own controllers. */
    @Volatile
    var forcedProgram: Int? = null
        private set

    @Volatile
    var playing: Boolean = false
        private set

    /** When true, sequencer advances for UI progress but no audio is emitted. */
    @Volatile
    var muted: Boolean = false
        private set

    @Volatile
    var durationSeconds: Double = 0.0
        private set

    fun load(midiBytes: ByteArray): Boolean {
        stop()
        return try {
            val seq = MidiSystem.getSequence(ByteArrayInputStream(midiBytes))
            sequence = seq
            stateTimeline = MidiStateTimeline(seq)
            durationSeconds = seq.microsecondLength / 1_000_000.0
            true
        } catch (e: Exception) {
            "Invalid MIDI for $id: ${e.message}".warn()
            sequence = null
            stateTimeline = null
            durationSeconds = 0.0
            false
        }
    }

    fun setChannelFilter(channels: Set<Int>?) {
        val next = channels?.let { HashSet(it) }
        // Content equality — callers often allocate a fresh set each tick.
        if (allowedChannels == next) return
        // Do not stop/restart the sequencer: that desyncs ensemble sections from the conductor.
        // Clearing sounding voices is enough when the part filter changes mid-play.
        routingReceiver?.reconfigure { allowedChannels = next } ?: run { allowedChannels = next }
    }

    fun setForcedProgram(program: Int?) {
        val next = program?.let { GeneralMidiInstruments.clamp(it) }
        if (forcedProgram == next) return
        routingReceiver?.reconfigure { forcedProgram = next } ?: run { forcedProgram = next }
    }

    fun play(fromSeconds: Double = 0.0) {
        val seq = sequence ?: return
        try {
            ensureDevices()
            val sequ = sequencer ?: return
            sequ.sequence = seq
            val micros = (fromSeconds.coerceAtLeast(0.0) * 1_000_000).toLong().coerceAtMost(seq.microsecondLength)
            hardSilence()
            sequ.microsecondPosition = micros
            routingReceiver?.restoreState()
            sequ.tempoFactor = tempoFactor
            sequ.start()
            playing = true
        } catch (e: Exception) {
            "MIDI play failed: ${e.message}".warn()
            playing = false
        }
    }

    fun pause() {
        try {
            sequencer?.stop()
        } catch (_: Exception) {
        }
        playing = false
        hardSilence()
    }

    fun stop() {
        try {
            sequencer?.stop()
            sequencer?.microsecondPosition = 0
        } catch (_: Exception) {
        }
        playing = false
        hardSilence()
    }

    fun seek(seconds: Double) {
        val seq = sequence ?: return
        val wasPlaying = playing
        try {
            ensureDevices()
            val sequ = sequencer ?: return
            if (sequ.sequence !== seq) sequ.sequence = seq
            val micros = (seconds.coerceAtLeast(0.0) * 1_000_000).toLong().coerceAtMost(seq.microsecondLength)
            if (wasPlaying) {
                try {
                    sequ.stop()
                } catch (_: Exception) {
                }
            }
            hardSilence()
            sequ.microsecondPosition = micros
            routingReceiver?.restoreState()
            if (wasPlaying) {
                sequ.start()
                playing = true
            }
        } catch (e: Exception) {
            "MIDI seek failed: ${e.message}".warn()
        }
    }

    /**
     * Position correction used only when ensemble drift exceeds the caller's tolerance.
     * The sequencer is stopped before changing position to avoid receiver/pump lock inversion.
     */
    fun syncPosition(seconds: Double) {
        val seq = sequence ?: return
        try {
            ensureDevices()
            val sequ = sequencer ?: return
            if (sequ.sequence !== seq) {
                sequ.sequence = seq
            }
            val micros = (seconds.coerceAtLeast(0.0) * 1_000_000).toLong().coerceAtMost(seq.microsecondLength)
            val current =
                try {
                    sequ.microsecondPosition
                } catch (_: Exception) {
                    Long.MIN_VALUE / 2
                }
            // Sub-frame nudges only skip MIDI events (lost note-offs → swallowed notes / stuck voices).
            if (abs(micros - current) < 40_000L) return // <40ms
            // Clear sounding voices before the jump so stolen polyphony and hangs do not accumulate.
            // RealTimeSequencer dispatches while holding its pump lock. Never take
            // that lock from inside the receiver lock: use the stop/seek/resume path.
            seek(seconds)
        } catch (e: Exception) {
            // Fallback to full seek if the sequencer rejects live position updates.
            seek(seconds)
        }
    }

    fun setTempoFactor(factor: Float) {
        tempoFactor = factor.coerceIn(0.25f, 4.0f)
        try {
            sequencer?.tempoFactor = tempoFactor
        } catch (_: Exception) {
        }
    }

    fun setMuted(value: Boolean) {
        if (muted == value) return
        routingReceiver?.reconfigure { muted = value } ?: run { muted = value }
    }

    fun currentSeconds(): Double =
        try {
            (sequencer?.microsecondPosition ?: 0L) / 1_000_000.0
        } catch (_: Exception) {
            0.0
        }

    fun isSequencerRunning(): Boolean =
        try {
            sequencer?.isRunning == true
        } catch (_: Exception) {
            false
        }

    /**
     * True when playback was active but the sequencer has finished the sequence.
     * Also clears the stale [playing] flag so callers can stop the block entity.
     */
    fun pollNaturalEnd(): Boolean {
        if (!playing) return false
        // Unknown / unloaded length must never count as "finished".
        if (durationSeconds < 0.5) return false
        val sequ = sequencer ?: return false
        return try {
            if (sequ.isRunning) return false
            // Require actually reaching the end — not a sequencer that never started.
            val pos = currentSeconds()
            if (pos < durationSeconds * 0.9 && pos < durationSeconds - 0.25) return false
            playing = false
            hardSilence()
            true
        } catch (_: Exception) {
            false
        }
    }

    fun close() {
        stop()
        try {
            sequencer?.close()
        } catch (_: Exception) {
        }
        try {
            // Silence again after closing the sequencer — SoftSynth can emit a pop if
            // voices are still decaying when the audio line is torn down mid-note.
            hardSilence()
            for (synth in allSynthesizers()) {
                try {
                    synth.close()
                } catch (_: Exception) {
                }
            }
        } catch (_: Exception) {
        }
        routingReceiver = null
        sequencer = null
        synthesizer = null
        auxiliarySynthesizers = emptyList()
    }

    private fun ensureDevices() {
        if (synthesizer?.isOpen == true && sequencer?.isOpen == true) return
        close()
        val synth = MidiSystem.getSynthesizer()
        SoftSynthPolyphony.openPrimary(synth, MAX_POLYPHONY)
        soundbank?.let {
            try {
                synth.loadAllInstruments(it)
            } catch (_: Exception) {
            }
        }

        val sinkReceiver: Receiver =
            if (synth.maxPolyphony < SoftSynthPolyphony.MIN_ACCEPTABLE_POLYPHONY) {
                warnPolyphonyFallback(synth.maxPolyphony)
                val (banks, fanout) = SoftSynthPolyphony.openPolyphonyBanks(synth, soundbank, MAX_POLYPHONY)
                auxiliarySynthesizers = banks.drop(1)
                fanout
            } else {
                auxiliarySynthesizers = emptyList()
                "MIDI SoftSynth ready polyphony=${synth.maxPolyphony}".info()
                synth.receiver
            }

        val sequ = MidiSystem.getSequencer(false)
        sequ.open()
        val receiver =
            MidiRoutingReceiver(
                sinkReceiver,
                { allowedChannels },
                { forcedProgram },
                { muted },
                { stateTimeline?.at(sequ.tickPosition) ?: emptyList() },
            )
        sequ.transmitter.receiver = receiver
        routingReceiver = receiver
        synthesizer = synth
        sequencer = sequ
    }

    companion object {
        /** SoftSynthesizer default is 64; 1024 covers dense / multi-box MIDI with headroom. */
        const val MAX_POLYPHONY = SoftSynthPolyphony.TARGET_POLYPHONY
    }

    private fun hardSilence() {
        try {
            allSynthesizers().forEach { synth ->
                synth.channels?.forEach { ch: MidiChannel ->
                    try {
                        ch.controlChange(64, 0) // sustain off
                        ch.controlChange(123, 0) // all notes off CC
                        ch.controlChange(120, 0) // all sound off CC
                        ch.controlChange(121, 0) // reset all controllers
                        ch.pitchBend = 8192
                    } catch (_: Exception) {
                    }
                    try {
                        ch.allNotesOff()
                        ch.allSoundOff()
                    } catch (_: Exception) {
                    }
                    // SoftSynthesizer often keeps voices after CC all-sound-off when stopped mid-note.
                    try {
                        for (note in 0..127) {
                            ch.noteOff(note)
                        }
                    } catch (_: Exception) {
                    }
                }
            }
        } catch (_: Exception) {
        }
        // Bypass filters so muted/disabled channels and fan-out assignments are cleared too.
        try {
            routingReceiver?.silence()
        } catch (_: Exception) {
        }
    }
}

fun rpmToTempoFactor(rpm: Float): Float {
    val absRpm = abs(rpm)
    // Solo / conductor: 64 RPM = 1.0x tempo. Section boxes use the conductor RPM instead.
    return (absRpm / 64f).coerceIn(0.25f, 4.0f)
}
