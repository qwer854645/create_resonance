package io.github.qwer854645.createresonance.content.midi

import io.github.qwer854645.createresonance.audio.midi.MidiEngine
import io.github.qwer854645.createresonance.foundation.network.packet.MidiLibraryEntryDto
import io.github.qwer854645.createresonance.foundation.network.packet.RequestPlayerMidiLibraryPacket
import io.github.qwer854645.createresonance.foundation.network.packet.UploadPlayerMidiPacket
import io.github.qwer854645.createresonance.foundation.registry.ModPackets
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Client cache of the combined server MIDI library (own tracks first).
 * Sync runs on screen open / refresh via [syncLocalToServer] — never continuously.
 */
object MidiLibraryClient {
    data class Track(
        val ownerId: UUID,
        val ownerName: String,
        val name: String,
        val own: Boolean,
    ) {
        fun label(): String =
            if (own) name else "$name ($ownerName)"
    }

    @Volatile
    var tracks: List<Track> = emptyList()
        private set

    private val listeners = CopyOnWriteArrayList<(List<Track>) -> Unit>()

    fun onListReceived(entries: List<MidiLibraryEntryDto>) {
        tracks =
            entries.mapNotNull { dto ->
                val id = runCatching { UUID.fromString(dto.ownerUuid) }.getOrNull() ?: return@mapNotNull null
                Track(id, dto.ownerName, dto.name, dto.own)
            }
        listeners.forEach { it(tracks) }
    }

    fun addListener(listener: (List<Track>) -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: (List<Track>) -> Unit) {
        listeners.remove(listener)
    }

    /**
     * Upload local `midi/` into this player's server library, then pull the
     * combined list (own + others). Called on screen open and refresh.
     */
    fun syncLocalToServer() {
        for (file in MidiEngine.listImportFiles()) {
            val bytes =
                runCatching { file.readBytes() }.getOrNull() ?: continue
            if (bytes.isEmpty() || bytes.size > MidiEngine.MAX_MIDI_BYTES) continue
            ModPackets.sendToServer(
                UploadPlayerMidiPacket(
                    displayName = file.nameWithoutExtension,
                    midiBytes = bytes,
                ),
            )
        }
        ModPackets.sendToServer(RequestPlayerMidiLibraryPacket())
    }
}
