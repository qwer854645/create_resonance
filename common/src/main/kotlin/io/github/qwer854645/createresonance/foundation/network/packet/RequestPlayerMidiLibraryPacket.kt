package io.github.qwer854645.createresonance.foundation.network.packet

import kotlinx.serialization.Serializable
import io.github.qwer854645.createresonance.content.midi.MidiLibraryIo
import io.github.qwer854645.createresonance.content.midi.PlayerMidiLibrary
import io.github.qwer854645.createresonance.foundation.registry.ModPackets

/** Snapshot only on open/refresh. A sync ID lets the client compare hashes before uploading. */
@Serializable
class RequestPlayerMidiLibraryPacket(val syncId: Int = 0) : ModPacket, C2SPacket {
    override fun handle(context: ModPacket.Context): Boolean {
        val sender = context.sender ?: return false
        val server = sender.server
        return MidiLibraryIo.submit {
            if (server.isStopped) return@submit
            val tracks = PlayerMidiLibrary.listForViewer(server, sender.uuid)
            server.execute {
                if (server.playerList.getPlayer(sender.uuid) !== sender) return@execute
                val entries = tracks.map { track ->
                    MidiLibraryEntryDto(
                        track.ownerId.toString(), PlayerMidiLibrary.resolvePlayerName(server, track.ownerId),
                        track.name, track.own, track.hash,
                    )
                }.sortedWith(compareBy({ !it.own }, { if (it.own) "" else it.ownerName.lowercase() }, { it.name.lowercase() }))
                ModPackets.sendToPlayer(sender, PlayerMidiLibraryPacket(entries, syncId))
            }
        }
    }
}
