package io.github.qwer854645.createresonance.foundation.network.packet

import com.simibubi.create.foundation.utility.AdventureUtil
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import io.github.qwer854645.createresonance.content.midi.MidiLimits
import io.github.qwer854645.createresonance.content.midi.PlayerMidiLibrary
import java.io.ByteArrayInputStream
import javax.sound.midi.MidiSystem

/**
 * Uploads one MIDI file into the sender's per-player server library.
 */
@Serializable
class UploadPlayerMidiPacket(
    val displayName: String,
    @Contextual val midiBytes: ByteArray,
) : ModPacket,
    C2SPacket {
    override fun handle(context: ModPacket.Context): Boolean {
        val sender = context.sender ?: return false
        if (sender.isSpectator || AdventureUtil.isAdventure(sender)) return false
        if (midiBytes.isEmpty() || midiBytes.size > MidiLimits.MAX_MIDI_BYTES) return false
        runCatching { MidiSystem.getSequence(ByteArrayInputStream(midiBytes)) }.getOrNull()
            ?: return false
        val name = PlayerMidiLibrary.sanitizeName(displayName)
        return PlayerMidiLibrary.put(sender.server, sender.uuid, name, midiBytes)
    }
}
