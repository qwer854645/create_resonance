package io.github.qwer854645.createresonance.content.midi

import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.storage.LevelResource
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.util.concurrent.ConcurrentHashMap
import java.security.MessageDigest
import java.util.UUID

/**
 * Per-player MIDI library stored under the world save:
 * `<world>/create_resonance/midi/<uuid>/<name>.mid`
 *
 * Refresh uploads the viewer's local files, then the UI lists every player's
 * library (own tracks first, then others).
 */
object PlayerMidiLibrary {
    const val MAX_ENTRIES = 64

    private data class Fingerprint(val size: Long, val modified: FileTime, val hash: String)
    private val fingerprints = ConcurrentHashMap<Path, Fingerprint>()

    data class Entry(
        val name: String,
        val size: Int,
        val hash: String,
    )

    /** One track in the combined library listing sent to a viewer. */
    data class ListedTrack(
        val ownerId: UUID,
        val ownerName: String,
        val name: String,
        val own: Boolean,
        val hash: String = "",
    )

    fun midiRoot(server: MinecraftServer): Path =
        server
            .getWorldPath(LevelResource.ROOT)
            .resolve("create_resonance")
            .resolve("midi")

    fun playerDir(
        server: MinecraftServer,
        playerId: UUID,
    ): Path = midiRoot(server).resolve(playerId.toString())

    fun sanitizeName(raw: String): String =
        raw
            .take(64)
            .replace(Regex("[\\\\/:*?\"<>|\\x00-\\x1f]"), "_")
            .trim()
            .ifBlank { "MIDI" }

    fun list(
        server: MinecraftServer,
        playerId: UUID,
        includeHashes: Boolean = true,
    ): List<Entry> {
        val dir = playerDir(server, playerId)
        if (!Files.isDirectory(dir)) return emptyList()
        return try {
            Files.list(dir).use { stream ->
                stream
                    .filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".mid", true) }
                    .map { path ->
                        val name = path.fileName.toString().dropLast(4)
                        val attrs = runCatching { Files.readAttributes(path, BasicFileAttributes::class.java) }.getOrNull()
                        if (attrs == null || attrs.size() <= 0 || attrs.size() > MidiLimits.MAX_MIDI_BYTES) {
                            null
                        } else {
                            val hash = if (!includeHashes) "" else {
                                val cached = fingerprints[path]
                                if (cached != null && cached.size == attrs.size() && cached.modified == attrs.lastModifiedTime()) {
                                    cached.hash
                                } else {
                                    val bytes = runCatching { Files.readAllBytes(path) }.getOrNull()
                                        ?: return@map null
                                    sha256Hex(bytes).also { fingerprints[path] = Fingerprint(attrs.size(), attrs.lastModifiedTime(), it) }
                                }
                            }
                            Entry(name, attrs.size().toInt(), hash)
                        }
                    }.toList()
                    .filterNotNull()
                    .sortedBy { it.name.lowercase() }
            }
        } catch (_: IOException) {
            emptyList()
        }
    }

    /**
     * Own tracks first (A–Z), then every other player's tracks (by owner name, then title).
     */
    fun listForViewer(
        server: MinecraftServer,
        viewerId: UUID,
    ): List<ListedTrack> {
        val root = midiRoot(server)
        if (!Files.isDirectory(root)) {
            return list(server, viewerId).map {
                ListedTrack(viewerId, "", it.name, true, it.hash)
            }
        }
        val ownerIds =
            try {
                Files.list(root).use { stream ->
                    stream
                        .filter { Files.isDirectory(it) }
                        .toList()
                        .mapNotNull { dir ->
                            runCatching { UUID.fromString(dir.fileName.toString()) }.getOrNull()
                        }
                }
            } catch (_: IOException) {
                listOf(viewerId)
            }.ifEmpty { listOf(viewerId) }

        val ownName = ""
        val own =
            list(server, viewerId).map { entry ->
                ListedTrack(viewerId, ownName, entry.name, true, entry.hash)
            }
        val others =
            ownerIds
                .filter { it != viewerId }
                .flatMap { ownerId ->
                    val ownerName = ""
                    list(server, ownerId, includeHashes = false).map { entry ->
                        ListedTrack(ownerId, ownerName, entry.name, false)
                    }
                }.sortedWith(
                    compareBy(
                        { it.ownerName.lowercase() },
                        { it.name.lowercase() },
                    ),
                )
        return own + others
    }

    fun resolvePlayerName(
        server: MinecraftServer,
        playerId: UUID,
    ): String {
        server.playerList.getPlayer(playerId)?.gameProfile?.name?.let { return it }
        val cached =
            server.profileCache
                ?.get(playerId)
                ?.map { it.name }
                ?.orElse(null)
        if (!cached.isNullOrBlank()) return cached
        return playerId.toString().take(8)
    }

    fun get(
        server: MinecraftServer,
        playerId: UUID,
        displayName: String,
    ): ByteArray? {
        val name = sanitizeName(displayName)
        val path = playerDir(server, playerId).resolve("$name.mid")
        if (!Files.isRegularFile(path)) return null
        val size = runCatching { Files.size(path) }.getOrNull() ?: return null
        if (size <= 0 || size > MidiLimits.MAX_MIDI_BYTES) return null
        val bytes = runCatching { Files.readAllBytes(path) }.getOrNull() ?: return null
        if (bytes.isEmpty() || bytes.size > MidiLimits.MAX_MIDI_BYTES) return null
        return bytes
    }

    /**
     * Stores [bytes] under [displayName]. Skips rewrite when content hash matches.
     * @return true if the entry exists after the call (new or unchanged).
     */
    fun put(
        server: MinecraftServer,
        playerId: UUID,
        displayName: String,
        bytes: ByteArray,
    ): Boolean {
        if (bytes.isEmpty() || bytes.size > MidiLimits.MAX_MIDI_BYTES) return false
        val name = sanitizeName(displayName)
        val dir = playerDir(server, playerId)
        try {
            Files.createDirectories(dir)
        } catch (_: IOException) {
            return false
        }
        val path = dir.resolve("$name.mid")
        val newHash = sha256Hex(bytes)
        if (Files.isRegularFile(path)) {
            val existing = runCatching { Files.readAllBytes(path) }.getOrNull()
            if (existing != null && sha256Hex(existing) == newHash) return true
        } else {
            val count =
                try {
                    Files.list(dir).use { stream ->
                        stream
                            .filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".mid", true) }
                            .count()
                    }
                } catch (_: IOException) {
                    return false
                }
            if (count >= MAX_ENTRIES) return false
        }
        var temporary: Path? = null
        return try {
            temporary = Files.createTempFile(dir, ".upload-", ".tmp")
            Files.write(temporary, bytes)
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING)
            }
            fingerprints.remove(path)
            true
        } catch (_: IOException) {
            false
        } finally {
            temporary?.let { runCatching { Files.deleteIfExists(it) } }
        }
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }
}
