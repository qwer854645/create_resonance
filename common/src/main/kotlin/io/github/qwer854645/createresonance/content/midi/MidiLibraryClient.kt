package io.github.qwer854645.createresonance.content.midi

import io.github.qwer854645.createresonance.audio.midi.MidiEngine
import io.github.qwer854645.createresonance.foundation.network.packet.MidiLibraryEntryDto
import io.github.qwer854645.createresonance.foundation.network.packet.RequestPlayerMidiLibraryPacket
import io.github.qwer854645.createresonance.foundation.network.packet.UploadPlayerMidiPacket
import io.github.qwer854645.createresonance.foundation.registry.ModPackets
import net.minecraft.client.Minecraft
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Executors
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

    private data class LocalTrack(val name: String, val bytes: ByteArray, val hash: String)
    private val io = Executors.newSingleThreadExecutor { task ->
        Thread(task, "Resonance local MIDI scan").apply { isDaemon = true }
    }
    private var connection: Any? = null
    private var generation = 0
    private var pendingTracks: List<LocalTrack> = emptyList()

    private val listeners = CopyOnWriteArrayList<(List<Track>) -> Unit>()

    fun onListReceived(entries: List<MidiLibraryEntryDto>, syncId: Int = 0) {
        if (connection !== Minecraft.getInstance().connection) return
        if (syncId > 0) {
            if (syncId != generation) return
            val stored = entries.filter { it.own }.associate { it.name to it.hash }
            pendingTracks.filter { stored[it.name] != it.hash }.forEach { track ->
                ModPackets.sendToServer(UploadPlayerMidiPacket(track.name, track.bytes))
            }
            pendingTracks = emptyList()
            // The server FIFO completes every upload before producing this final snapshot.
            ModPackets.sendToServer(RequestPlayerMidiLibraryPacket(-syncId))
            return
        }
        if (syncId < 0 && -syncId != generation) return
        tracks =
            entries.mapNotNull { dto ->
                val id = runCatching { UUID.fromString(dto.ownerUuid) }.getOrNull() ?: return@mapNotNull null
                Track(id, dto.ownerName, dto.name, dto.own)
            }
        listeners.forEach { it(tracks) }
    }

    fun tracksForCurrentConnection(): List<Track> =
        if (connection === Minecraft.getInstance().connection) tracks else emptyList()

    fun addListener(listener: (List<Track>) -> Unit) {
        listeners.addIfAbsent(listener)
    }

    fun removeListener(listener: (List<Track>) -> Unit) {
        listeners.remove(listener)
    }

    /** Scan off-thread, compare with this server's manifest, upload only changed files. */
    fun syncLocalToServer() {
        val minecraft = Minecraft.getInstance()
        val currentConnection = minecraft.connection ?: return
        if (connection !== currentConnection) {
            connection = currentConnection
            tracks = emptyList()
        }
        val syncId = ++generation
        val directory = MidiEngine.importDir()
        io.execute {
            val local = directory.listFiles().orEmpty()
                .filter { it.isFile && (it.extension.equals("mid", true) || it.extension.equals("midi", true)) }
                .sortedBy { it.name.lowercase() }
                .distinctBy { PlayerMidiLibrary.sanitizeName(it.nameWithoutExtension) }
                .take(PlayerMidiLibrary.MAX_ENTRIES)
                .mapNotNull { file ->
                    if (file.length() !in 1..MidiLimits.MAX_MIDI_BYTES.toLong()) return@mapNotNull null
                    val bytes = runCatching { file.readBytes() }.getOrNull() ?: return@mapNotNull null
                    if (bytes.isEmpty() || bytes.size > MidiLimits.MAX_MIDI_BYTES) return@mapNotNull null
                    val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                    LocalTrack(PlayerMidiLibrary.sanitizeName(file.nameWithoutExtension), bytes, hash)
                }
            minecraft.execute {
                if (minecraft.connection !== currentConnection || generation != syncId) return@execute
                pendingTracks = local
                ModPackets.sendToServer(RequestPlayerMidiLibraryPacket(syncId))
            }
        }
    }
}
