package io.github.qwer854645.createresonance.foundation.network.packet

import com.simibubi.create.foundation.utility.AdventureUtil
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
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
        val midiBytes =
            PlayerMidiLibrary.get(sender.server, ownerId, name) ?: return false
        val world = sender.level() ?: return false
        if (!world.isLoaded(blockPos) || !sender.canInteractWithBlock(blockPos, 20.0)) return false

        val be = world.getBlockEntity(blockPos) as? MidiTableBlockEntity ?: return false
        if (!be.canPlayerUse(sender)) return false
        if (!be.configureMidi(midiBytes, name)) return false

        world.playSound(
            null,
            blockPos,
            SoundEvents.UI_CARTOGRAPHY_TABLE_TAKE_RESULT,
            SoundSource.BLOCKS,
            0.5f,
            0.95f,
        )
        return true
    }
}
