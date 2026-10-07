package io.github.qwer854645.createresonance.foundation.network.packet

import com.simibubi.create.foundation.utility.AdventureUtil
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import io.github.qwer854645.createresonance.content.midi.MidiLimits
import io.github.qwer854645.createresonance.content.midi.MidiTableBlockEntity
import io.github.qwer854645.createresonance.content.midi.PlayerMidiLibrary
import net.minecraft.core.BlockPos
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import java.io.ByteArrayInputStream
import javax.sound.midi.MidiSystem

/**
 * Uploads a MIDI file once onto the imprint table. Subsequent blank rolls are stamped
 * automatically while the table has kinetic speed. Also caches into the sender's
 * per-player server library.
 */
@Serializable
class ConfigureMidiTablePacket(
    @Contextual val blockPos: BlockPos,
    @Contextual val midiBytes: ByteArray,
    val displayName: String = "",
) : ModPacket,
    C2SPacket {
    override fun handle(context: ModPacket.Context): Boolean {
        val sender = context.sender ?: return false
        if (sender.isSpectator || AdventureUtil.isAdventure(sender)) return false
        if (midiBytes.isEmpty() || midiBytes.size > MidiLimits.MAX_MIDI_BYTES) return false
        runCatching { MidiSystem.getSequence(ByteArrayInputStream(midiBytes)) }.getOrNull()
            ?: return false
        val world = sender.level() ?: return false
        if (!world.isLoaded(blockPos) || !sender.canInteractWithBlock(blockPos, 20.0)) return false

        val be = world.getBlockEntity(blockPos) as? MidiTableBlockEntity ?: return false
        if (!be.canPlayerUse(sender)) return false
        val name = PlayerMidiLibrary.sanitizeName(displayName)
        PlayerMidiLibrary.put(sender.server, sender.uuid, name, midiBytes)
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
