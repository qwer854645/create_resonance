package io.github.qwer854645.createresonance.audio.midi

import io.github.qwer854645.createresonance.foundation.info
import io.github.qwer854645.createresonance.foundation.warn
import java.lang.invoke.MethodHandles
import java.lang.reflect.Modifier
import javax.sound.midi.MidiMessage
import javax.sound.midi.MidiSystem
import javax.sound.midi.MidiUnavailableException
import javax.sound.midi.Receiver
import javax.sound.midi.ShortMessage
import javax.sound.midi.Soundbank
import javax.sound.midi.Synthesizer
import javax.sound.sampled.SourceDataLine

/**
 * SoftSynthesizer (Gervill) defaults to **64** voices. Dense MIDI steals notes long before
 * Minecraft's sound engine is involved. Raising polyphony normally needs
 * `AudioSynthesizer.open(line, map)`, but JPMS blocks `com.sun.media.sound` for our mod module.
 *
 * Strategies (first success wins):
 * 1. Reflective Map-open (works when the package is exported/opened)
 * 2. Patch private `maxpoly` via Unsafe, then default [Synthesizer.open]
 * 3. Trusted MethodHandles invoke of Map-open
 * 4. Caller falls back to multi-bank fan-out ([openPolyphonyBanks])
 */
internal object SoftSynthPolyphony {
    const val TARGET_POLYPHONY = 2048

    /** Cap extra SoftSynth instances so fan-out does not explode CPU/audio lines. */
    const val MAX_FANOUT_BANKS = 8

    /** Below this after open(), we add auxiliary synthesizers. */
    const val MIN_ACCEPTABLE_POLYPHONY = 128

    fun openPrimary(synth: Synthesizer, target: Int = TARGET_POLYPHONY): Boolean {
        if (synth.isOpen && synth.maxPolyphony >= MIN_ACCEPTABLE_POLYPHONY) return true
        if (synth.isOpen) {
            try {
                synth.close()
            } catch (_: Exception) {
            }
        }
        if (tryOpenWithPropertyMap(synth, target)) return true
        if (tryTrustedMapOpen(synth, target)) return true
        if (tryPatchMaxPolyThenOpen(synth, target)) return true
        return tryDefaultOpen(synth)
    }

    /**
     * When a single SoftSynth stays at the Gervill default (64), open additional banks and
     * return a fan-out [Receiver] so note-ons are spread across banks (~64 × N voices).
     */
    fun openPolyphonyBanks(
        primary: Synthesizer,
        soundbank: Soundbank?,
        target: Int = TARGET_POLYPHONY,
    ): Pair<List<Synthesizer>, Receiver> {
        val perBank = primary.maxPolyphony.coerceAtLeast(1)
        val banksWanted =
            ((target + perBank - 1) / perBank)
                .coerceIn(1, MAX_FANOUT_BANKS)
        val synths = ArrayList<Synthesizer>(banksWanted)
        synths.add(primary)
        repeat(banksWanted - 1) {
            val extra = MidiSystem.getSynthesizer()
            if (!openPrimary(extra, perBank)) {
                tryDefaultOpen(extra)
            }
            soundbank?.let { sb ->
                try {
                    extra.loadAllInstruments(sb)
                } catch (_: Exception) {
                }
            }
            synths.add(extra)
        }
        val total = synths.sumOf { it.maxPolyphony }
        "MIDI SoftSynth fan-out: ${synths.size} banks, ~$total voices (target $target)".info()
        return synths to PolyphonyFanoutReceiver(synths.map { it.receiver })
    }

    private fun propertyMap(target: Int): Map<String, Any> =
        mapOf(
            "max polyphony" to target,
            // Slightly lower default latency (200ms) helps dense passages feel tighter.
            "latency" to 80_000L,
        )

    private fun tryOpenWithPropertyMap(
        synth: Synthesizer,
        target: Int,
    ): Boolean {
        return try {
            val audioSynthClass = Class.forName("com.sun.media.sound.AudioSynthesizer")
            if (!audioSynthClass.isInstance(synth)) return false
            val open =
                audioSynthClass.getMethod(
                    "open",
                    SourceDataLine::class.java,
                    Map::class.java,
                )
            open.invoke(synth, null, propertyMap(target))
            logPolyphony(synth, "AudioSynthesizer.open(map)")
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun tryPatchMaxPolyThenOpen(
        synth: Synthesizer,
        target: Int,
    ): Boolean {
        val unsafe = acquireUnsafe() ?: return false
        return try {
            var clazz: Class<*>? = synth.javaClass
            var patched = false
            while (clazz != null && clazz != Any::class.java) {
                try {
                    val field = clazz.getDeclaredField("maxpoly")
                    val offset = unsafe.objectFieldOffset(field)
                    unsafe.putInt(synth, offset, target)
                    patched = true
                    break
                } catch (_: NoSuchFieldException) {
                    clazz = clazz.superclass
                }
            }
            if (!patched) return false
            synth.open()
            logPolyphony(synth, "maxpoly-patch")
            synth.maxPolyphony >= MIN_ACCEPTABLE_POLYPHONY || synth.maxPolyphony >= target / 2
        } catch (_: Throwable) {
            false
        }
    }

    private fun tryTrustedMapOpen(
        synth: Synthesizer,
        target: Int,
    ): Boolean {
        val lookup = acquireTrustedLookup() ?: return false
        return try {
            val open =
                synth.javaClass.getMethod(
                    "open",
                    SourceDataLine::class.java,
                    Map::class.java,
                )
            val handle = lookup.unreflect(open)
            handle.bindTo(synth).invokeWithArguments(null, propertyMap(target))
            logPolyphony(synth, "trusted-lookup")
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun tryDefaultOpen(synth: Synthesizer): Boolean =
        try {
            if (!synth.isOpen) synth.open()
            true
        } catch (_: MidiUnavailableException) {
            false
        }

    private fun logPolyphony(
        synth: Synthesizer,
        via: String,
    ) {
        "MIDI SoftSynth polyphony=${synth.maxPolyphony} via $via".info()
    }

    private fun acquireUnsafe(): UnsafeFacade? {
        try {
            val unsafeClass = Class.forName("sun.misc.Unsafe")
            for (field in unsafeClass.declaredFields) {
                if (!Modifier.isStatic(field.modifiers)) continue
                if (!unsafeClass.isAssignableFrom(field.type)) continue
                if (!field.trySetAccessible()) continue
                val instance = field.get(null) ?: continue
                return UnsafeFacade(instance)
            }
        } catch (_: Throwable) {
        }
        try {
            val unsafeClass = Class.forName("jdk.internal.misc.Unsafe")
            val getUnsafe = unsafeClass.getDeclaredMethod("getUnsafe")
            if (getUnsafe.trySetAccessible()) {
                val instance = getUnsafe.invoke(null)
                return UnsafeFacade(instance)
            }
        } catch (_: Throwable) {
        }
        return null
    }

    private fun acquireTrustedLookup(): MethodHandles.Lookup? {
        val unsafe = acquireUnsafe() ?: return null
        return try {
            val lookupClass = MethodHandles.Lookup::class.java
            val implLookup = lookupClass.getDeclaredField("IMPL_LOOKUP")
            val base = unsafe.staticFieldBase(implLookup)
            val offset = unsafe.staticFieldOffset(implLookup)
            unsafe.getObject(base, offset) as MethodHandles.Lookup
        } catch (_: Throwable) {
            null
        }
    }

    /** Minimal reflection wrapper so we do not hard-depend on sun.misc at compile time. */
    private class UnsafeFacade(
        private val unsafe: Any,
    ) {
        private val objectFieldOffset =
            unsafe.javaClass.getMethod("objectFieldOffset", java.lang.reflect.Field::class.java)
        private val staticFieldOffset =
            unsafe.javaClass.getMethod("staticFieldOffset", java.lang.reflect.Field::class.java)
        private val staticFieldBase =
            unsafe.javaClass.getMethod("staticFieldBase", java.lang.reflect.Field::class.java)
        private val putInt =
            unsafe.javaClass.getMethod("putInt", Any::class.java, Long::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        private val getObject =
            unsafe.javaClass.getMethod("getObject", Any::class.java, Long::class.javaPrimitiveType)

        fun objectFieldOffset(field: java.lang.reflect.Field): Long =
            objectFieldOffset.invoke(unsafe, field) as Long

        fun staticFieldOffset(field: java.lang.reflect.Field): Long =
            staticFieldOffset.invoke(unsafe, field) as Long

        fun staticFieldBase(field: java.lang.reflect.Field): Any =
            staticFieldBase.invoke(unsafe, field)

        fun putInt(
            o: Any,
            offset: Long,
            value: Int,
        ) {
            putInt.invoke(unsafe, o, offset, value)
        }

        fun getObject(
            o: Any,
            offset: Long,
        ): Any? = getObject.invoke(unsafe, o, offset)
    }
}

/**
 * Spreads NOTE_ON across SoftSynth banks; NOTE_OFF returns to the same bank.
 * Control / program / pitch messages are broadcast so every bank stays in sync.
 */
internal class PolyphonyFanoutReceiver(
    private val banks: List<Receiver>,
) : Receiver {
    private val noteBanks = Array(16) { Array(128) { java.util.ArrayDeque<Int>() } }
    private val load = IntArray(banks.size)
    private var closed = false

    @Synchronized
    override fun send(
        message: MidiMessage,
        timeStamp: Long,
    ) {
        if (closed || banks.isEmpty()) return
        if (message is ShortMessage) {
            when (message.command) {
                ShortMessage.NOTE_ON -> {
                    val ch = message.channel
                    val note = message.data1
                    val vel = message.data2
                    if (vel > 0) {
                        var best = 0
                        var bestLoad = load[0]
                        for (i in 1 until load.size) {
                            if (load[i] < bestLoad) {
                                best = i
                                bestLoad = load[i]
                            }
                        }
                        noteBanks[ch][note].addLast(best)
                        load[best]++
                        banks[best].send(message, timeStamp)
                    } else {
                        release(ch, note, message, timeStamp)
                    }
                    return
                }
                ShortMessage.NOTE_OFF -> {
                    release(message.channel, message.data1, message, timeStamp)
                    return
                }
                ShortMessage.CONTROL_CHANGE -> {
                    if (message.data1 == 120 || message.data1 == 123) clearAssignments(message.channel)
                }
                else -> {
                    // Program / CC / pitch bend / pressure: keep banks aligned.
                }
            }
        }
        for (bank in banks) {
            try {
                bank.send(message, timeStamp)
            } catch (_: Exception) {
            }
        }
    }

    private fun release(
        channel: Int,
        note: Int,
        message: MidiMessage,
        timeStamp: Long,
    ) {
        val bank = noteBanks[channel][note].pollFirst() ?: -1
        if (bank in banks.indices) {
            load[bank] = (load[bank] - 1).coerceAtLeast(0)
            try {
                banks[bank].send(message, timeStamp)
            } catch (_: Exception) {
            }
        } else {
            // Unknown assignment (e.g. after reset) — fan out note-off safely.
            for (b in banks) {
                try {
                    b.send(message, timeStamp)
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun clearAssignments(channel: Int) {
        for (assignments in noteBanks[channel]) {
            for (bank in assignments) load[bank] = (load[bank] - 1).coerceAtLeast(0)
            assignments.clear()
        }
    }

    @Synchronized
    override fun close() {
        closed = true
        for (bank in banks) {
            try {
                bank.close()
            } catch (_: Exception) {
            }
        }
    }
}

internal fun warnPolyphonyFallback(actual: Int) {
    "MIDI SoftSynth polyphony still $actual after raise attempts; using bank fan-out if needed".warn()
}
