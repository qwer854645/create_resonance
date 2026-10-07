package io.github.qwer854645.createresonance.foundation.network.packet

import kotlinx.serialization.Serializable
import io.github.qwer854645.createresonance.content.midi.PlayerMidiLibrary
import io.github.qwer854645.createresonance.foundation.registry.ModPackets

/** Asks the server for the combined MIDI library (own first, then others). */
@Serializable
class RequestPlayerMidiLibraryPacket : ModPacket, C2SPacket {
    override fun handle(context: ModPacket.Context): Boolean {
        val sender = context.sender ?: return false
        val entries =
            PlayerMidiLibrary.listForViewer(sender.server, sender.uuid).map { track ->
                MidiLibraryEntryDto(
                    ownerUuid = track.ownerId.toString(),
                    ownerName = track.ownerName,
                    name = track.name,
                    own = track.own,
                )
            }
        ModPackets.sendToPlayer(sender, PlayerMidiLibraryPacket(entries))
        return true
    }
}
