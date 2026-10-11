package io.github.qwer854645.createresonance.foundation.network.packet

import com.simibubi.create.foundation.utility.AdventureUtil
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import io.github.qwer854645.createresonance.audio.midi.GeneralMidiInstruments
import io.github.qwer854645.createresonance.content.kinetics.musicBox.MusicBoxBlockEntity
import io.github.qwer854645.createresonance.content.kinetics.musicBox.MusicBoxRole
import net.minecraft.core.BlockPos

@Serializable
class MusicBoxActionPacket(
    @Contextual val blockPos: BlockPos,
    val action: String,
    val midiBase64: String = "",
    val displayName: String = "",
    val seconds: Double = 0.0,
    val sectionChannels: List<Int> = emptyList(),
    val conductOnly: Boolean = false,
    val instrumentProgram: Int = -1,
) : ModPacket,
    C2SPacket {
    override fun handle(context: ModPacket.Context): Boolean {
        val sender = context.sender ?: return false
        if (sender.isSpectator || AdventureUtil.isAdventure(sender)) return false
        val world = sender.level() ?: return false
        if (!world.isLoaded(blockPos) || !sender.canInteractWithBlock(blockPos, 20.0)) return false
        val be = world.getBlockEntity(blockPos) as? MusicBoxBlockEntity ?: return false
        val behaviour = be.behaviour
        behaviour.enforceRoleFromBlock()
        behaviour.syncRoleFromModeSlot()

        when (action) {
            "import" -> return false
            "clear" -> {
                if (!be.extractScoreTo(sender)) {
                    behaviour.clearMidi()
                }
            }
            "play" -> {
                if (behaviour.role == MusicBoxRole.SECTION) return false
                behaviour.setPlaying(true)
            }
            "pause" -> {
                if (behaviour.role == MusicBoxRole.SECTION) return false
                behaviour.setPlaying(false)
            }
            "seek" -> {
                if (behaviour.role == MusicBoxRole.SECTION) return false
                if (!seconds.isFinite() || seconds < 0.0) return false
                behaviour.seekTo(seconds)
            }
            "restart" -> {
                if (behaviour.role == MusicBoxRole.SECTION) return false
                behaviour.restart()
            }
            "finished" -> {
                // Client completion is only a hint; the server clock decides when arms may extract.
                if (behaviour.role == MusicBoxRole.SECTION) return false
                behaviour.checkNaturalEnd()
            }
            "resync_midi" -> {
                // Client lost its MidiBytes cache while the server still has HasMidi=true.
                behaviour.forceResyncMidiToClient()
            }
            "config" -> {
                if (behaviour.isConductorBlock) {
                    behaviour.role = MusicBoxRole.CONDUCTOR
                    behaviour.conductOnly = true
                } else {
                    behaviour.conductOnly = false
                    if (sectionChannels.isNotEmpty()) {
                        behaviour.sectionChannels.clear()
                        if (!sectionChannels.any { it < 0 }) {
                            behaviour.sectionChannels.addAll(sectionChannels.map { it.coerceIn(0, 15) })
                        }
                        // list containing a negative value means "all parts"
                    }
                    // -1 = score-native instruments; 0–127 = forced GM program.
                    behaviour.instrumentProgram = GeneralMidiInstruments.normalize(instrumentProgram)
                }
                be.notifyUpdate()
                be.sendData()
                be.setChanged()
            }
            else -> return false
        }
        return true
    }
}
