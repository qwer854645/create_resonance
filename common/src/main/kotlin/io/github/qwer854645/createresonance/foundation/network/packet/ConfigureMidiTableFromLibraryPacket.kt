package io.github.qwer854645.createresonance.foundation.network.packet

import com.simibubi.create.foundation.utility.AdventureUtil
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import io.github.qwer854645.createresonance.content.midi.MidiLibraryIo
import io.github.qwer854645.createresonance.content.midi.MidiTableBlockEntity
import io.github.qwer854645.createresonance.content.midi.PlayerMidiLibrary
import net.minecraft.core.BlockPos
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import java.util.UUID

/**
 * Configures the imprint table from a track in any player's server library.
 */
@Serializable
class ConfigureMidiTableFromLibraryPacket(
    @Contextual val blockPos: BlockPos,
    val ownerUuid: String,
    val displayName: String,
) : ModPacket,
    C2SPacket {
    override fun handle(context: ModPacket.Context): Boolean {
        val sender = context.sender ?: return false
        if (sender.isSpectator || AdventureUtil.isAdventure(sender)) return false
        val ownerId = runCatching { UUID.fromString(ownerUuid) }.getOrNull() ?: return false
        val name = PlayerMidiLibrary.sanitizeName(displayName)
        val world = sender.level()
        if (!world.isLoaded(blockPos) || !sender.canInteractWithBlock(blockPos, 20.0)) return false
        val table = world.getBlockEntity(blockPos) as? MidiTableBlockEntity ?: return false
        if (!table.canPlayerUse(sender)) return false
        val server = sender.server
        return MidiLibraryIo.submit {
            if (server.isStopped) return@submit
            val bytes = PlayerMidiLibrary.get(server, ownerId, name) ?: return@submit
            server.execute {
                if (server.playerList.getPlayer(sender.uuid) !== sender || sender.level() !== world) return@execute
                if (!world.isLoaded(blockPos) || !sender.canInteractWithBlock(blockPos, 20.0)) return@execute
                val be = world.getBlockEntity(blockPos) as? MidiTableBlockEntity ?: return@execute
                if (!be.canPlayerUse(sender) || !be.configureMidi(bytes, name)) return@execute
                world.playSound(null, blockPos, SoundEvents.UI_CARTOGRAPHY_TABLE_TAKE_RESULT, SoundSource.BLOCKS, 0.5f, 0.95f)
            }
        }
    }
}
