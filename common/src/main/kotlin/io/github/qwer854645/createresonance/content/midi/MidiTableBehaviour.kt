package io.github.qwer854645.createresonance.content.midi

import com.simibubi.create.content.kinetics.belt.behaviour.DirectBeltInputBehaviour
import com.simibubi.create.content.kinetics.belt.behaviour.TransportedItemStackHandlerBehaviour
import com.simibubi.create.content.kinetics.belt.transport.TransportedItemStack
import com.simibubi.create.foundation.blockEntity.behaviour.BehaviourType
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour
import io.github.qwer854645.createresonance.content.processing.DepotLikeBehaviour
import net.minecraft.core.Direction
import net.minecraft.core.HolderLookup
import net.minecraft.nbt.CompoundTag
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.util.Mth
import net.minecraft.world.item.ItemStack
import kotlin.math.abs

/**
 * Depot-like imprint behaviour: blank MIDI rolls on the pad are written from BE-stored MIDI
 * whenever the kinetic shaft has speed (no mechanical press required).
 */
class MidiTableBehaviour(
    private val be: MidiTableBlockEntity,
) : DepotLikeBehaviour(be) {
    companion object {
        @JvmStatic
        val BEHAVIOUR_TYPE = BehaviourType<MidiTableBehaviour>()

        /** Base duration at 32 RPM (~5s). Higher speed shortens; clamped. */
        private const val BASE_DURATION_TICKS = 100
        private const val BASE_RPM = 32f
    }

    var midiBytes: ByteArray = ByteArray(0)
    var midiName: String = ""
    /** Synced flag so the config screen can show the loaded track without pulling MIDI bytes. */
    var hasConfig: Boolean = false

    /** Progress toward finishing the current imprint (0 .. requiredTicks). Synced for stylus anim. */
    var processingTicks: Int = 0
        private set

    /** True while a blank roll is actively being written. Synced to clients. */
    var imprinting: Boolean = false
        private set

    init {
        onlyAccepts { MidiRollUtilities.isMidiRoll(it) && !MidiRollUtilities.hasMidi(it) }
    }

    fun hasConfiguredMidi(): Boolean =
        hasConfig && (be.level?.isClientSide == true || (midiBytes.isNotEmpty() && midiBytes.size <= MidiLimits.MAX_MIDI_BYTES))

    fun configureMidi(
        bytes: ByteArray,
        name: String,
    ): Boolean {
        if (bytes.isEmpty() || bytes.size > MidiLimits.MAX_MIDI_BYTES) return false
        midiBytes = bytes.copyOf()
        midiName = name.take(64).ifBlank { "MIDI" }
        hasConfig = true
        be.notifyUpdate()
        be.setChanged()
        return true
    }

    fun clearConfiguredMidi() {
        midiBytes = ByteArray(0)
        midiName = ""
        hasConfig = false
        resetProcessing()
        be.notifyUpdate()
        be.setChanged()
    }

    fun imprintDurationTicks(): Int {
        val rpm = abs(be.speed).coerceAtLeast(1f)
        return Mth.clamp((BASE_DURATION_TICKS * BASE_RPM / rpm).toInt(), 48, 200)
    }

    private fun canImprintNow(): Boolean {
        if (!hasConfiguredMidi()) return false
        if (abs(be.speed) < 1f) return false
        if (midiBytes.isEmpty() && be.level?.isClientSide != true) return false
        val held = heldItem ?: return false
        if (!processOnlyData(held)) return false
        return 0.5f - held.beltPosition <= 1 / 16f
    }

    private fun resetProcessing() {
        if (processingTicks == 0 && !imprinting) return
        processingTicks = 0
        imprinting = false
        be.sendData()
    }

    override fun addSubBehaviours(behaviours: MutableList<BlockEntityBehaviour>) {
        DirectBeltInputBehaviour(be)
            .allowingBeltFunnels()
            .setInsertionHandler(this::tryInsertingFromSide)
            .considerOccupiedWhen { side: Direction -> this.isOccupied(side) }
            .also { behaviours.add(it) }

        transportedHandler =
            TransportedItemStackHandlerBehaviour(be, this::applyRecipeProcessing)
                .withStackPlacement { this.getWorldPositionOf() }
                .also { behaviours.add(it) }
    }

    override fun tick() {
        super.tick()
        val world = blockEntity.level ?: return
        if (world.isClientSide) return

        if (!canImprintNow()) {
            resetProcessing()
            return
        }

        val wasImprinting = imprinting
        imprinting = true
        processingTicks++

        // Keep clients in sync for stylus swing; avoid every-tick spam.
        if (!wasImprinting || processingTicks % 5 == 0) {
            be.sendData()
        }

        if (processingTicks % 12 == 0) {
            world.playSound(
                null,
                blockEntity.blockPos,
                SoundEvents.UI_CARTOGRAPHY_TABLE_TAKE_RESULT,
                SoundSource.BLOCKS,
                0.12f,
                1.4f + (processingTicks % 24) * 0.01f,
            )
        }

        if (processingTicks >= imprintDurationTicks()) {
            applyRecipeProcessing(1 / 16f) {
                TransportedItemStackHandlerBehaviour.TransportedResult.doNothing()
            }
            processingTicks = 0
            imprinting = false
            be.sendData()
        }
    }

    override fun processOnlyData(input: TransportedItemStack): Boolean =
        MidiRollUtilities.isMidiRoll(input.stack) && !MidiRollUtilities.hasMidi(input.stack)

    override fun canExtractHeldByAutomation(): Boolean = false

    override fun processData(input: TransportedItemStack): ItemStack {
        if (!hasConfiguredMidi()) return input.stack
        // Imprint one roll from a blank stack; leftovers stay on the pad.
        val one = input.stack.split(1)
        MidiRollUtilities.writeMidi(one, midiBytes, midiName)
        val level = blockEntity.level
        if (level != null && !level.isClientSide) {
            level.playSound(
                null,
                blockEntity.blockPos,
                SoundEvents.UI_CARTOGRAPHY_TABLE_TAKE_RESULT,
                SoundSource.BLOCKS,
                0.55f,
                0.95f,
            )
        }
        return one
    }

    override fun write(
        compound: CompoundTag,
        registries: HolderLookup.Provider,
        clientPacket: Boolean,
    ) {
        super.write(compound, registries, clientPacket)
        compound.putString("MidiName", midiName)
        compound.putBoolean("HasMidi", hasConfig)
        compound.putBoolean("Imprinting", imprinting)
        compound.putInt("ProcessingTicks", processingTicks)
        // Full payload only for world save ??avoid syncing up to 512KiB every client packet.
        if (!clientPacket && midiBytes.isNotEmpty()) {
            compound.putByteArray("MidiBytes", midiBytes)
        }
    }

    override fun read(
        compound: CompoundTag,
        registries: HolderLookup.Provider,
        clientPacket: Boolean,
    ) {
        super.read(compound, registries, clientPacket)
        midiName = compound.getString("MidiName")
        hasConfig = compound.getBoolean("HasMidi")
        imprinting = compound.getBoolean("Imprinting")
        processingTicks = compound.getInt("ProcessingTicks")
        if (!clientPacket && compound.contains("MidiBytes")) {
            midiBytes = compound.getByteArray("MidiBytes")
            hasConfig = midiBytes.isNotEmpty()
        } else if (!hasConfig) {
            midiBytes = ByteArray(0)
        }
    }

    override fun getType(): BehaviourType<*> = BEHAVIOUR_TYPE
}
