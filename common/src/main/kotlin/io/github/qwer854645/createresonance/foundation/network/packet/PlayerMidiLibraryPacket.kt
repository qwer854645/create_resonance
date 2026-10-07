package io.github.qwer854645.createresonance.foundation.network.packet

import kotlinx.serialization.Serializable
import io.github.qwer854645.createresonance.content.midi.MidiLibraryClient

@Serializable
data class MidiLibraryEntryDto(
    val ownerUuid: String,
    val ownerName: String,
    val name: String,
    val own: Boolean,
)

/** Server → client: combined MIDI library (own tracks first, then others). */
@Serializable
class PlayerMidiLibraryPacket(
    val entries: List<MidiLibraryEntryDto>,
) : ModPacket,
    S2CPacket {
    override fun handle(context: ModPacket.Context): Boolean {
        MidiLibraryClient.onListReceived(entries)
        return true
    }
}
