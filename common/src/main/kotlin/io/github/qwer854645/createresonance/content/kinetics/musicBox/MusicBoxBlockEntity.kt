package io.github.qwer854645.createresonance.content.kinetics.musicBox

import com.simibubi.create.content.kinetics.base.KineticBlockEntity
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour
import com.simibubi.create.foundation.blockEntity.behaviour.scrollValue.ScrollOptionBehaviour
import com.simibubi.create.foundation.utility.IInteractionChecker
import io.github.qwer854645.createresonance.audio.midi.GeneralMidiInstruments
import io.github.qwer854645.createresonance.audio.midi.MidiEngine
import io.github.qwer854645.createresonance.foundation.network.packet.MusicBoxActionPacket
import io.github.qwer854645.createresonance.foundation.registry.ModPackets
import io.github.qwer854645.createresonance.audio.midi.MidiPlayerInstance
import io.github.qwer854645.createresonance.audio.midi.rpmToTempoFactor
import io.github.qwer854645.createresonance.content.midi.MidiLimits
import io.github.qwer854645.createresonance.content.midi.MidiRollUtilities
import io.github.qwer854645.createresonance.foundation.extension.onClient
import io.github.qwer854645.createresonance.foundation.goggles.ResonanceGoggles
import net.minecraft.ChatFormatting
import net.minecraft.core.BlockPos
import net.minecraft.core.HolderLookup
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.network.chat.Component
import net.minecraft.world.Clearable
import net.minecraft.world.Containers
import net.minecraft.world.MenuProvider
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockState
import net.neoforged.neoforge.items.ItemStackHandler
import java.util.Base64
import java.util.UUID
import kotlin.math.abs

enum class MusicBoxRole {
    SOLO,
    SECTION,
    CONDUCTOR,
}

class MusicBoxBlockEntity(
    type: BlockEntityType<*>,
    pos: net.minecraft.core.BlockPos,
    state: BlockState,
) : KineticBlockEntity(type, pos, state),
    MenuProvider,
    IInteractionChecker,
    Clearable {
    lateinit var behaviour: MusicBoxBehaviour
        private set

    lateinit var frequency: MusicBoxFrequencyBehaviour
        private set

    /** Present on andesite/brass music boxes — Solo ↔ Section via side value box. */
    var ensembleMode: ScrollOptionBehaviour<MusicBoxEnsembleMode>? = null
        private set

    /** Schematic-table style: one score slot (solo / conductor). */
    val inventory = ScoreInventory()

    override fun addBehaviours(behaviours: MutableList<BlockEntityBehaviour>) {
        behaviour = MusicBoxBehaviour(this)
        behaviours.add(behaviour)

        frequency = MusicBoxFrequencyBehaviour.create(this)
        behaviours.add(frequency)

        if ((blockState.block as? MusicBoxBlock)?.isConductor != true) {
            val mode =
                ScrollOptionBehaviour(
                    MusicBoxEnsembleMode::class.java,
                    Component.translatable("create_resonance.music_box.ensemble_mode"),
                    this,
                    MusicBoxEnsembleMode.sideSlot(),
                )
            mode.withCallback { onEnsembleModeChanged() }
            ensembleMode = mode
            behaviours.add(mode)
        }
    }

    private fun onEnsembleModeChanged() {
        val mode = ensembleMode ?: return
        val newRole = mode.get().toRole()
        if (behaviour.role == newRole) return
        behaviour.role = newRole
        if (newRole == MusicBoxRole.SECTION) {
            // Keep the score item in the slot, but do not use it while sectioned.
            behaviour.onSwitchedToSection()
        } else if (newRole == MusicBoxRole.SOLO) {
            // Resume from local score; empty slot clears cached MIDI.
            behaviour.syncFromScoreSlot(notify = false)
            behaviour.onReturnedToSolo()
        }
        notifyUpdate()
        sendData()
        setChanged()
    }

    fun ejectScoreIntoWorld() {
        val stack = inventory.getStackInSlot(0)
        if (stack.isEmpty) return
        inventory.setStackInSlot(0, ItemStack.EMPTY)
        val level = level ?: return
        if (!level.isClientSide) {
            Containers.dropItemStack(
                level,
                blockPos.x + 0.5,
                blockPos.y + 0.5,
                blockPos.z + 0.5,
                stack,
            )
        }
    }

    override fun addToGoggleTooltip(
        tooltip: MutableList<Component>,
        isPlayerSneaking: Boolean,
    ): Boolean {
        super.addToGoggleTooltip(tooltip, isPlayerSneaking)
        behaviour.enforceRoleFromBlock()
        ResonanceGoggles.header(tooltip, "gui.goggles.music_box.header")

        val roleKey =
            when {
                behaviour.isConductorBlock -> "gui.goggles.music_box.role.conductor"
                behaviour.role == MusicBoxRole.SECTION -> "gui.goggles.music_box.role.section"
                else -> "gui.goggles.music_box.role.solo"
            }
        ResonanceGoggles.line(tooltip, roleKey)

        val track = behaviour.displayName
        if (track.isNotBlank()) {
            ResonanceGoggles.line(
                tooltip,
                "gui.goggles.music_box.track",
                ResonanceGoggles.truncate(track),
            )
        } else {
            ResonanceGoggles.line(tooltip, "gui.goggles.music_box.no_track", style = ChatFormatting.DARK_GRAY)
        }

        if (behaviour.playing) {
            ResonanceGoggles.line(tooltip, "gui.goggles.music_box.state.playing", style = ChatFormatting.GREEN)
        } else {
            ResonanceGoggles.line(tooltip, "gui.goggles.music_box.state.stopped", style = ChatFormatting.DARK_GRAY)
        }

        if (behaviour.durationSeconds > 0.0 || behaviour.playing) {
            ResonanceGoggles.line(
                tooltip,
                "gui.goggles.music_box.progress",
                ResonanceGoggles.formatTime(behaviour.ensemblePositionSeconds()),
                ResonanceGoggles.formatTime(behaviour.durationSeconds),
            )
        }

        if (!behaviour.isConductorBlock) {
            ResonanceGoggles.line(
                tooltip,
                "gui.goggles.music_box.instrument",
                GeneralMidiInstruments.displayName(behaviour.instrumentProgram).string,
            )
            if (behaviour.sectionChannels.isEmpty()) {
                ResonanceGoggles.line(tooltip, "gui.goggles.music_box.part_all")
            } else {
                val parts = behaviour.sectionChannels.sorted().joinToString(", ") { "${it + 1}" }
                ResonanceGoggles.line(tooltip, "gui.goggles.music_box.part", parts)
            }
            behaviour.linkedConductorPos?.let { pos ->
                ResonanceGoggles.line(
                    tooltip,
                    "gui.goggles.music_box.linked_conductor",
                    "${pos.x}, ${pos.y}, ${pos.z}",
                )
            }
        }
        return true
    }

    override fun write(
        compound: CompoundTag,
        registries: HolderLookup.Provider,
        clientPacket: Boolean,
    ) {
        compound.put("Score", inventory.serializeNBT(registries))
        super.write(compound, registries, clientPacket)
    }

    override fun read(
        compound: CompoundTag,
        registries: HolderLookup.Provider,
        clientPacket: Boolean,
    ) {
        super.read(compound, registries, clientPacket)
        // deserializeNBT → setStackInSlot → onContentsChanged; suppress so we don't wipe
        // MidiBytes/Playing that were just applied from the packet on the client.
        inventory.suppressChangeCallbacks = true
        try {
            if (compound.contains("Score")) {
                inventory.deserializeNBT(registries, compound.getCompound("Score"))
            }
        } finally {
            inventory.suppressChangeCallbacks = false
        }
        if (clientPacket) {
            // Client playback uses MidiBytes from the packet; do not re-derive from the
            // score item (component decode can briefly fail and clear playback state).
            behaviour.applyLoadedRoleToModeSlot()
            // GUI SetSlot can empty the score before the BE sync packet arrives — stop audio now.
            behaviour.forceStopIfLocalScoreMissing()
        } else {
            // Mode scroll is authoritative for solo/section after load.
            behaviour.syncRoleFromModeSlot()
            behaviour.migrateLegacyMidiIntoSlot()
            behaviour.syncFromScoreSlot(notify = false)
            behaviour.applyLoadedRoleToModeSlot()
        }
    }

    override fun clearContent() {
        for (i in 0 until inventory.slots) {
            inventory.setStackInSlot(i, ItemStack.EMPTY)
        }
    }

    /** Take score from slot and give to player (Create-style remove from machine). */
    fun extractScoreTo(player: Player): Boolean {
        val stack = inventory.getStackInSlot(0)
        if (stack.isEmpty) return false
        inventory.setStackInSlot(0, ItemStack.EMPTY)
        if (!player.addItem(stack)) player.drop(stack, false)
        return true
    }

    fun hasScore(): Boolean = !inventory.getStackInSlot(0).isEmpty

    /** Mechanical arms may swap scores on the conductor or solo music boxes only. */
    fun allowsArmScoreAccess(): Boolean {
        if (!::behaviour.isInitialized) return false
        behaviour.enforceRoleFromBlock()
        behaviour.syncRoleFromModeSlot()
        if (behaviour.isConductorBlock) return true
        return behaviour.role == MusicBoxRole.SOLO
    }

    fun getScore(): ItemStack = inventory.getStackInSlot(0).copy()

    fun insertScore(stack: ItemStack): Boolean {
        if (!allowsArmScoreAccess()) return false
        if (hasScore()) return false
        if (!MidiRollUtilities.isMidiRoll(stack) || !MidiRollUtilities.hasMidi(stack)) return false
        behaviour.markScoreChanged()
        inventory.setStackInSlot(0, stack.copyWithCount(1))
        return true
    }

    /** Arms may pull the score only after a natural end (not on manual pause). */
    fun canArmExtractScore(): Boolean {
        if (!allowsArmScoreAccess()) return false
        if (!hasScore()) return false
        if (behaviour.playing) return false
        return behaviour.playbackEndedNaturally
    }

    fun popScore(): ItemStack? {
        if (!canArmExtractScore()) return null
        val item = inventory.getStackInSlot(0).copy()
        inventory.setStackInSlot(0, ItemStack.EMPTY)
        behaviour.markScoreChanged()
        return item
    }

    override fun createMenu(
        id: Int,
        playerInventory: Inventory,
        player: Player,
    ): AbstractContainerMenu = MusicBoxMenu.create(id, playerInventory, this)

    override fun getDisplayName(): Component =
        Component.translatable(
            if (behaviour.isConductorBlock) {
                "create_resonance.gui.music_conductor.title"
            } else {
                "create_resonance.gui.music_box.title"
            },
        )

    override fun canPlayerUse(player: Player): Boolean {
        val level = level ?: return false
        if (level.getBlockEntity(worldPosition) !== this) return false
        return player.distanceToSqr(
            worldPosition.x + 0.5,
            worldPosition.y + 0.5,
            worldPosition.z + 0.5,
        ) <= 64.0
    }

    inner class ScoreInventory : ItemStackHandler(1) {
        /** When true, skip sync/notify — used while applying network/world NBT. */
        var suppressChangeCallbacks: Boolean = false

        override fun onContentsChanged(slot: Int) {
            if (suppressChangeCallbacks) return
            // Score → MIDI is server-authoritative; client gets MidiBytes via BE packets.
            if (level?.isClientSide == true) return
            if (::behaviour.isInitialized) {
                behaviour.syncRoleFromModeSlot()
                behaviour.syncFromScoreSlot(notify = true)
            }
            setChanged()
            notifyUpdate()
        }

        override fun isItemValid(
            slot: Int,
            stack: ItemStack,
        ): Boolean = MidiRollUtilities.isMidiRoll(stack) && MidiRollUtilities.hasMidi(stack)
    }
}

class MusicBoxBehaviour(
    val be: MusicBoxBlockEntity,
) : BlockEntityBehaviour(be) {
    companion object {
        val TYPE = com.simibubi.create.foundation.blockEntity.behaviour.BehaviourType<MusicBoxBehaviour>()
        /** Max search / accept radius (blocks). 64 RPM maps to 64 blocks. */
        const val MAX_ENSEMBLE_RADIUS = 128

        /** Section RPM decides how far it can hear the conductor (64 RPM = 64 blocks). */
        fun ensembleAcceptRadius(rpm: Float): Double =
            abs(rpm).toDouble().coerceIn(0.0, MAX_ENSEMBLE_RADIUS.toDouble())
    }

    private var playerUuid: UUID? = null

    /** Last conductor that pushed ensemble state (section boxes). */
    var linkedConductorPos: BlockPos? = null
        private set

    var midiBytes: ByteArray? = null
        private set
    var displayName: String = ""
        private set

    /** Avoid re-sending up to 512 KiB MIDI on every ensemble / GUI sync packet. */
    private var lastClientSyncedMidiHash: Int = Int.MIN_VALUE
    /** Song length in seconds (from MIDI); used for natural-end / arm extract. */
    var durationSeconds: Double = 0.0
        private set
    var role: MusicBoxRole = MusicBoxRole.SOLO
    var sectionChannels: MutableSet<Int> = mutableSetOf() // empty = all parts
    /**
     * GM program 0–127 forces a single instrument; [GeneralMidiInstruments.SCORE_PROGRAM]
     * keeps each MIDI channel's instruments from the score.
     */
    var instrumentProgram: Int = GeneralMidiInstruments.DEFAULT_PROGRAM

    val isConductorBlock: Boolean
        get() = (be.blockState.block as? MusicBoxBlock)?.isConductor == true

    val hasAdvancedControls: Boolean
        get() = (be.blockState.block as? MusicBoxBlock)?.hasAdvancedControls == true

    @Volatile
    var playing: Boolean = false
        private set

    /** True only after the track reaches its end — mechanical arms may then extract the score. */
    var playbackEndedNaturally: Boolean = false
        private set

    var conductOnly: Boolean = false

    private var pendingSeek: Double? = null
    private var pendingRestart: Boolean = false
    private var clientLoadedHash: Int = 0

    /**
     * SoftSynth instance key last used on this client. When [playerId] flips from block-pos
     * fallback to the server UUID, we must release the orphan and force a reload — otherwise
     * [clientLoadedHash] skips [MidiPlayerInstance.load] and playback stays silent until a
     * Solo↔Section toggle resets the hash.
     */
    private var boundAudioPlayerId: String? = null

    /** Client-only: avoid spamming resync while waiting for MidiBytes after a cold HasMidi packet. */
    private var midiResyncRequested: Boolean = false

    // Conductor-authoritative ensemble timeline (server). Prevents drift on rapid ops.
    private var ensembleClockSeconds: Double = 0.0
    private var ensembleClockGameTime: Long = 0L
    private var ensembleEpoch: Int = 0
    private var lastAppliedEnsembleEpoch: Int = 0
    private var ensembleSyncDirty: Boolean = false
    private var pendingEnsembleSeek: Double? = null
    private var pendingEnsembleRestart: Boolean = false
    /** When false, clock snapshot only — sections must not jump their sequencers. */
    private var pendingEnsembleForceSeek: Boolean = true
    private var forceAlignTicks: Int = 0

    override fun getType() = TYPE

    fun markScoreChanged() {
        playbackEndedNaturally = false
    }

    fun ensureUuid() {
        if (playerUuid == null && be.level?.isClientSide == false) {
            playerUuid = UUID.randomUUID()
            be.notifyUpdate()
        }
    }

    fun playerId(): String = (playerUuid ?: be.blockPos.asLong().toString()).toString()

    /** Release every id this box may have used (UUID can arrive after playback started under pos id). */
    private fun releaseClientAudio() {
        val ids = linkedSetOf(playerId(), be.blockPos.asLong().toString())
        playerUuid?.let { ids.add(it.toString()) }
        boundAudioPlayerId?.let { ids.add(it) }
        ids.forEach { MidiEngine.release(it) }
        clientLoadedHash = 0
        boundAudioPlayerId = null
    }

    /** Next client packet must include full MidiBytes (cold clients / after a clear). */
    fun invalidateClientMidiSync() {
        lastClientSyncedMidiHash = Int.MIN_VALUE
    }

    /** Server: re-push full MIDI payload to clients that lost their cache. */
    fun forceResyncMidiToClient() {
        invalidateClientMidiSync()
        be.sendData()
    }

    /** Bind SoftSynth to the current [playerId], migrating away from any prior id. */
    private fun clientMidiPlayer(): MidiPlayerInstance {
        val id = playerId()
        val previous = boundAudioPlayerId
        if (previous != null && previous != id) {
            MidiEngine.release(previous)
            // Also drop the pos-id fallback in case we bounced UUID → something else.
            val posId = be.blockPos.asLong().toString()
            if (previous != posId) MidiEngine.release(posId)
            clientLoadedHash = 0
        }
        boundAudioPlayerId = id
        return MidiEngine.getOrCreate(id)
    }

    /**
     * Solo / conductor own a local score. When the slot is empty (GUI sync can beat the BE
     * packet), clear stale MIDI state so SoftSynth cannot keep playing the removed roll.
     */
    fun forceStopIfLocalScoreMissing() {
        if (role == MusicBoxRole.SECTION && !isConductorBlock) return
        if (!be.inventory.getStackInSlot(0).isEmpty) return
        if (midiBytes == null && !playing && clientLoadedHash == 0) return
        midiBytes = null
        displayName = ""
        durationSeconds = 0.0
        playing = false
        playbackEndedNaturally = false
        pendingSeek = null
        pendingRestart = false
        be.level?.onClient { _, _ -> releaseClientAudio() }
    }

    fun enforceRoleFromBlock() {
        if (isConductorBlock) {
            role = MusicBoxRole.CONDUCTOR
            // Conductor is always silent — it only drives the ensemble.
            conductOnly = true
        } else if (role == MusicBoxRole.CONDUCTOR) {
            role = MusicBoxRole.SOLO
            conductOnly = false
        }
    }

    fun syncRoleFromModeSlot() {
        if (isConductorBlock) {
            role = MusicBoxRole.CONDUCTOR
            return
        }
        val mode = be.ensembleMode ?: return
        role = mode.get().toRole()
    }

    /** After NBT load: keep side slot in sync with stored Role without ejecting scores. */
    fun applyLoadedRoleToModeSlot() {
        if (isConductorBlock) {
            role = MusicBoxRole.CONDUCTOR
            return
        }
        val mode = be.ensembleMode ?: return
        val desired = MusicBoxEnsembleMode.fromRole(role).ordinal
        if (mode.value != desired) {
            mode.value = desired
        }
    }

    /** Old worlds stored MIDI on the BE — turn it into a score item in the slot. */
    fun migrateLegacyMidiIntoSlot() {
        if (be.level?.isClientSide == true) return
        // Only solo/conductor own a local score; section MIDI comes from the conductor.
        if (role == MusicBoxRole.SECTION && !isConductorBlock) return
        if (!be.inventory.getStackInSlot(0).isEmpty) return
        val bytes = midiBytes ?: return
        val roll = MidiRollUtilities.createFilledRoll(bytes, displayName.ifBlank { "MIDI" }) ?: return
        be.inventory.setStackInSlot(0, roll)
    }

    /**
     * Solo / conductor: score item is the source of truth for MIDI.
     * Section: local score is storage only - MIDI is driven by the conductor.
     *
     * Only an empty slot clears the cache. A present roll that fails to decode
     * must not wipe already-loaded MIDI (component sync can briefly fail).
     */

    private fun refreshDurationFromMidi() {
        val bytes = midiBytes
        if (bytes == null || bytes.isEmpty()) {
            durationSeconds = 0.0
            return
        }
        durationSeconds =
            try {
                val seq = javax.sound.midi.MidiSystem.getSequence(java.io.ByteArrayInputStream(bytes))
                seq.microsecondLength / 1_000_000.0
            } catch (_: Exception) {
                0.0
            }
    }
    fun syncFromScoreSlot(notify: Boolean) {
        enforceRoleFromBlock()
        if (role == MusicBoxRole.SECTION && !isConductorBlock) return

        val stack = be.inventory.getStackInSlot(0)
        if (stack.isEmpty) {
            if (midiBytes != null || displayName.isNotEmpty() || playing || durationSeconds != 0.0) {
                midiBytes = null
                displayName = ""
                durationSeconds = 0.0
                playing = false
                playbackEndedNaturally = false
                clientLoadedHash = 0
                invalidateClientMidiSync()
                pendingSeek = null
                pendingRestart = false
                // Stop mid-note immediately; otherwise SoftSynth leaves hanging voices.
                if (isConductorBlock || role == MusicBoxRole.SOLO) {
                    armEnsembleClock(0.0, play = false)
                }
                if (isConductorBlock) {
                    // Sections must pause too — otherwise they keep sounding after the score leaves.
                    queueEnsembleSync(0.0, restart = false)
                }
                be.level?.onClient { _, _ -> releaseClientAudio() }
            }
        } else {
            val bytes = MidiRollUtilities.getMidiBytes(stack)
            if (bytes != null) {
                val name = MidiRollUtilities.getDisplayName(stack).ifBlank { "MIDI" }
                val changed = midiBytes == null || !midiBytes!!.contentEquals(bytes) || displayName != name
                if (midiBytes == null) invalidateClientMidiSync()
                midiBytes = bytes
                displayName = name.take(64)
                if (changed) {
                    playbackEndedNaturally = false
                    clientLoadedHash = 0
                    invalidateClientMidiSync()
                    refreshDurationFromMidi()
                    // Inventory insert (GUI / arm): auto-start from the beginning.
                    // notify=false paths (world load, mode switch) must not force play.
                    val autoPlay =
                        notify &&
                            be.level?.isClientSide != true &&
                            (isConductorBlock || role == MusicBoxRole.SOLO)
                    if (autoPlay) {
                        pendingSeek = 0.0
                        pendingRestart = true
                        playing = true
                        armEnsembleClock(0.0, play = true)
                        if (isConductorBlock) {
                            queueEnsembleSync(0.0, restart = true)
                        }
                    } else {
                        playing = false
                        if (isConductorBlock || role == MusicBoxRole.SOLO) {
                            armEnsembleClock(0.0, play = false)
                        }
                    }
                } else if (durationSeconds <= 0.0) {
                    refreshDurationFromMidi()
                }
            }
        }
        if (notify) {
            be.sendData()
            be.setChanged()
        }
    }
    

    /** Section mode: keep local score item, ignore it for playback (conductor supplies MIDI). */
    fun onSwitchedToSection() {
        playing = false
        linkedConductorPos = null
        // Drop solo-driven payload so we do not keep sounding until the conductor sync arrives.
        midiBytes = null
        displayName = ""
        durationSeconds = 0.0
        clientLoadedHash = 0
        invalidateClientMidiSync()
        pendingSeek = null
        pendingRestart = false
        be.level?.onClient { _, _ -> releaseClientAudio() }
        be.notifyUpdate()
        be.sendData()
    }

    /** Clear section channel filter / reload from retained score after returning to Solo. */
    fun onReturnedToSolo() {
        linkedConductorPos = null
        playing = false
        clientLoadedHash = 0
        invalidateClientMidiSync()
        be.level?.onClient { _, _ ->
            // Hard-release — pause alone can leave an orphan SoftSynth under a stale player id.
            releaseClientAudio()
        }
    }

    fun setMidi(
        bytes: ByteArray,
        name: String,
    ) {
        if (bytes.size > MidiEngine.MAX_MIDI_BYTES) return
        midiBytes = bytes
        displayName = name.take(64)
        refreshDurationFromMidi()
        playing = false
        be.notifyUpdate()
        be.sendData()
        be.setChanged()
    }

    fun clearMidi() {
        val hadScore = !be.inventory.getStackInSlot(0).isEmpty
        if (hadScore) {
            be.inventory.setStackInSlot(0, ItemStack.EMPTY)
            // Solo/conductor: onContentsChanged already cleared MIDI via syncFromScoreSlot.
            // Section: sync is a no-op; ensemble MIDI from the conductor is kept.
            if (role == MusicBoxRole.SECTION && !isConductorBlock) {
                be.notifyUpdate()
                be.sendData()
                be.setChanged()
            }
            return
        }
        midiBytes = null
        displayName = ""
        durationSeconds = 0.0
        playing = false
        playbackEndedNaturally = false
        ensembleClockSeconds = 0.0
        be.level?.onClient { _, _ -> releaseClientAudio() }
        be.notifyUpdate()
        be.sendData()
        be.setChanged()
    }

    fun setPlaying(value: Boolean, naturalEnd: Boolean = false) {
        enforceRoleFromBlock()
        syncRoleFromModeSlot()
        if (value) {
            playbackEndedNaturally = false
            syncFromScoreSlot(notify = false)
            if (midiBytes == null) {
                playing = false
                playbackEndedNaturally = false
                if (isConductorBlock) {
                    freezeEnsembleClock()
                    queueEnsembleSync(ensemblePositionSeconds(), restart = false)
                }
                be.notifyUpdate()
                be.sendData()
                be.setChanged()
                return
            }
        }
        if (!value) {
            playbackEndedNaturally = naturalEnd
        }

        if (isConductorBlock) {
            if (!value) {
                freezeEnsembleClock()
                playing = false
            } else {
                // Resume from the frozen / current timeline position.
                playing = true
                armEnsembleClock(playHeadForResume(), play = true)
            }
            val pos = ensemblePositionSeconds()
            pendingSeek = pos
            pendingRestart = false
            queueEnsembleSync(pos, restart = false)
        } else if (role != MusicBoxRole.SECTION) {
            // Solo: keep a server timeline so natural end can unlock arm extract.
            if (!value) {
                freezeEnsembleClock()
                playing = false
            } else {
                playing = true
                armEnsembleClock(playHeadForResume(), play = true)
            }
            pendingSeek = ensemblePositionSeconds()
            pendingRestart = false
        } else {
            playing = value
        }
        be.notifyUpdate()
        be.sendData()
        be.setChanged()
    }

    fun seekTo(seconds: Double) {
        enforceRoleFromBlock()
        playbackEndedNaturally = false
        val pos = seconds.coerceAtLeast(0.0)
        pendingSeek = pos
        pendingRestart = false
        if (isConductorBlock) {
            armEnsembleClock(pos, play = playing)
            queueEnsembleSync(pos, restart = false)
        } else if (role != MusicBoxRole.SECTION) {
            armEnsembleClock(pos, play = playing)
        }
        be.notifyUpdate()
        be.sendData()
        be.setChanged()
    }

    fun restart() {
        enforceRoleFromBlock()
        syncRoleFromModeSlot()
        playbackEndedNaturally = false
        syncFromScoreSlot(notify = false)
        if (midiBytes == null) {
            playing = false
            be.notifyUpdate()
            be.sendData()
            be.setChanged()
            return
        }
        pendingSeek = 0.0
        pendingRestart = true
        playing = true
        if (isConductorBlock) {
            armEnsembleClock(0.0, play = true)
            queueEnsembleSync(0.0, restart = true)
        } else if (role != MusicBoxRole.SECTION) {
            armEnsembleClock(0.0, play = true)
        }
        be.notifyUpdate()
        be.sendData()
        be.setChanged()
    }

    /** Current conductor timeline position (advances with game time while playing). */
    /** Where to resume: restart from 0 if the previous head was already at/past the end. */
    private fun playHeadForResume(): Double {
        val pos = ensembleClockSeconds
        if (durationSeconds >= 0.5 && pos >= durationSeconds - 0.1) return 0.0
        return pos.coerceAtLeast(0.0)
    }

    fun ensemblePositionSeconds(): Double {
        val level = be.level ?: return ensembleClockSeconds
        // Sections: follow the live conductor clock so GUI progress keeps moving after seeks
        // (local mirrored clock alone only updates on soft sync snapshots).
        if (role == MusicBoxRole.SECTION && !isConductorBlock) {
            val conductor = resolveLinkedConductor()
            if (conductor != null) {
                return conductor.ensemblePositionSeconds()
            }
            if (!playing) return ensembleClockSeconds
            val dt = (level.gameTime - ensembleClockGameTime).coerceAtLeast(0L) / 20.0
            return (ensembleClockSeconds + dt).coerceAtLeast(0.0)
        }
        // Solo + conductor share this timeline so natural end / arms stay authoritative.
        if (!playing) return ensembleClockSeconds
        val dt = (level.gameTime - ensembleClockGameTime).coerceAtLeast(0L) / 20.0
        val tempo = rpmToTempoFactor(be.speed).toDouble()
        return (ensembleClockSeconds + dt * tempo).coerceAtLeast(0.0)
    }

    private fun freezeEnsembleClock() {
        ensembleClockSeconds = ensemblePositionSeconds()
        ensembleClockGameTime = be.level?.gameTime ?: ensembleClockGameTime
    }

    private fun armEnsembleClock(seconds: Double, play: Boolean) {
        ensembleClockSeconds = seconds.coerceAtLeast(0.0)
        ensembleClockGameTime = be.level?.gameTime ?: 0L
        playing = play
    }

    /** Coalesce rapid conductor ops into one spatial sync on the next server tick. */
    private fun queueEnsembleSync(
        seekSeconds: Double,
        restart: Boolean,
        forceClientSeek: Boolean = true,
    ) {
        if (!isConductorBlock) return
        ensembleEpoch++
        if (!ensembleSyncDirty) {
            pendingEnsembleForceSeek = forceClientSeek || restart
        } else {
            pendingEnsembleForceSeek = pendingEnsembleForceSeek || forceClientSeek || restart
        }
        ensembleSyncDirty = true
        pendingEnsembleSeek = seekSeconds
        pendingEnsembleRestart = restart
    }

    private fun flushEnsembleSync() {
        if (!ensembleSyncDirty) return
        ensembleSyncDirty = false
        val seek = pendingEnsembleSeek ?: ensemblePositionSeconds()
        val restart = pendingEnsembleRestart
        val forceSeek = pendingEnsembleForceSeek
        pendingEnsembleSeek = null
        pendingEnsembleRestart = false
        pendingEnsembleForceSeek = false
        syncEnsembleClock(
            seekSeconds = seek,
            restart = restart,
            epoch = ensembleEpoch,
            forceClientSeek = forceSeek,
        )
    }

    private fun syncEnsembleClock(
        seekSeconds: Double? = null,
        restart: Boolean = false,
        epoch: Int = ensembleEpoch,
        forceClientSeek: Boolean = true,
    ) {
        val level = be.level ?: return
        if (level.isClientSide) return
        enforceRoleFromBlock()
        if (!isConductorBlock) return
        val midi = midiBytes
        val name = displayName
        // Always push an absolute position so sections cannot drift apart.
        val pos = seekSeconds ?: ensemblePositionSeconds()
        val radius = MAX_ENSEMBLE_RADIUS
        val radiusSq = radius * radius
        val origin = be.blockPos
        val myFreq = be.frequency
        // Iterate loaded chunk block-entities only — O(loaded BEs in AABB), not O(radius³) getBlockEntity.
        val minCx = (origin.x - radius) shr 4
        val maxCx = (origin.x + radius) shr 4
        val minCz = (origin.z - radius) shr 4
        val maxCz = (origin.z + radius) shr 4
        for (cx in minCx..maxCx) {
            for (cz in minCz..maxCz) {
                if (!level.hasChunk(cx, cz)) continue
                val chunk = level.getChunk(cx, cz)
                for ((posBlock, blockEntity) in chunk.blockEntities) {
                    if (blockEntity !is MusicBoxBlockEntity) continue
                    if (posBlock == origin) continue
                    val distSq = posBlock.distSqr(origin)
                    if (distSq > radiusSq) continue
                    val ob = blockEntity.behaviour
                    if (ob.isConductorBlock) continue
                    if (ob.role != MusicBoxRole.SECTION) continue
                    if (!myFreq.matches(blockEntity.frequency)) continue
                    val accept = ensembleAcceptRadius(blockEntity.speed)
                    if (distSq > accept * accept) continue
                    ob.applyEnsembleSync(
                        midi,
                        name,
                        playing,
                        pos,
                        restart,
                        origin,
                        epoch,
                        forceClientSeek = forceClientSeek || restart,
                    )
                }
            }
        }
    }

    fun applyEnsembleSync(
        midi: ByteArray?,
        name: String,
        play: Boolean,
        seekSeconds: Double?,
        restart: Boolean,
        conductorPos: BlockPos,
        epoch: Int,
        forceClientSeek: Boolean = true,
    ) {
        // Ignore stale packets from rapid conductor ops that arrived out of order.
        if (epoch > 0 && epoch < lastAppliedEnsembleEpoch) return
        if (epoch > 0) lastAppliedEnsembleEpoch = epoch
        linkedConductorPos = conductorPos
        if (midi != null) {
            if (midiBytes == null || !midiBytes!!.contentEquals(midi)) {
                midiBytes = midi
                displayName = name
                refreshDurationFromMidi()
                clientLoadedHash = 0
                invalidateClientMidiSync()
            }
        } else if (midiBytes != null || playing || displayName.isNotEmpty()) {
            // Conductor cleared its score — drop the section payload and kill SoftSynth orphans.
            midiBytes = null
            displayName = ""
            durationSeconds = 0.0
            clientLoadedHash = 0
            invalidateClientMidiSync()
            pendingSeek = null
            pendingRestart = false
            be.level?.onClient { _, _ -> releaseClientAudio() }
        }
        playing = play
        if (seekSeconds != null) {
            // Always mirror the conductor clock locally for GUI / out-of-range hold.
            ensembleClockSeconds = seekSeconds
            ensembleClockGameTime = be.level?.gameTime ?: 0L
            if (restart) {
                pendingSeek = seekSeconds
                pendingRestart = true
                forceAlignTicks = 2
            } else if (forceClientSeek) {
                // Play / pause / user seek — one-shot align, not a multi-tick hammer.
                pendingSeek = seekSeconds
                pendingRestart = false
                forceAlignTicks = 1
            } else {
                // Soft periodic snapshot only — sections follow conductor.ensemblePositionSeconds().
                pendingRestart = false
            }
        }
        be.notifyUpdate()
        be.sendData()
        be.setChanged()
    }

    override fun tick() {
        super.tick()
        val level = be.level ?: return
        syncRoleFromModeSlot()
        enforceRoleFromBlock()
        if (!level.isClientSide) {
            ensureUuid()
            if (isConductorBlock) {
                flushEnsembleSync()
                // Periodic clock snapshot for BE sync / GUI — sections soft-follow the conductor
                // clock; avoid forcing sequencer seeks (that causes note swallowing on dense MIDI).
                if (playing && level.gameTime % 20L == 0L) {
                    val pos = ensemblePositionSeconds()
                    ensembleClockSeconds = pos
                    ensembleClockGameTime = level.gameTime
                    ensembleEpoch++
                    syncEnsembleClock(
                        seekSeconds = pos,
                        restart = false,
                        epoch = ensembleEpoch,
                        forceClientSeek = false,
                    )
                    be.sendData()
                }
            }
            // Natural end → stop so mechanical arms can extract the score (jukebox parity).
            checkNaturalEnd()
            return
        }

        // Score slot can empty via container sync before MidiBytes packet — cut audio immediately.
        forceStopIfLocalScoreMissing()

        val silentConductor = isConductorBlock && conductOnly

        val bytes =
            midiBytes ?: run {
                releaseClientAudio()
                return
            }
        val player = clientMidiPlayer()
        val hash = bytes.contentHashCode()
        if (hash != clientLoadedHash) {
            if (player.load(bytes)) {
                clientLoadedHash = hash
            }
        }

        // Empty sectionChannels = all parts; otherwise filter to selected MIDI channel(s).
        if (sectionChannels.isEmpty()) {
            player.setChannelFilter(null)
        } else {
            player.setChannelFilter(sectionChannels.toSet())
        }
        player.setForcedProgram(
            if (GeneralMidiInstruments.isScoreNative(instrumentProgram)) {
                null
            } else {
                instrumentProgram
            },
        )
        // Conductor stays silent but still runs the sequencer so the GUI can show progress.
        player.setMuted(silentConductor)

        val speed = abs(be.speed)

        // Ensemble section: own RPM = accept range; conductor RPM = tempo.
        if (role == MusicBoxRole.SECTION) {
            val conductor = resolveLinkedConductor()
            val accept = ensembleAcceptRadius(speed)
            val inRange =
                conductor != null &&
                    accept > 0.0 &&
                    be.blockPos.distSqr(conductor.be.blockPos) <= accept * accept &&
                    be.frequency.matches(conductor.be.frequency)

            if (!inRange) {
                if (player.playing) player.pause()
                forceAlignTicks = 0
                return
            }

            val tempo = rpmToTempoFactor(conductor!!.be.speed)
            player.setTempoFactor(tempo)

            pendingSeek?.let { pos ->
                applyPendingSeek(player, pos, pendingRestart)
                // Keep local mirror of the conductor timeline.
                ensembleClockSeconds = pos
                ensembleClockGameTime = level.gameTime
                if (pendingRestart) {
                    forceAlignTicks = 2
                }
                pendingSeek = null
                pendingRestart = false
            }

            if (playing && speed > 0f) {
                if (!player.playing) {
                    player.play(conductor.ensemblePositionSeconds())
                }
                // Occasional soft-align only — frequent syncPosition swallows notes on dense MIDI.
                alignToEnsembleClock(player, conductor.ensemblePositionSeconds())
            } else if (!playing || speed <= 0f) {
                if (player.playing) player.pause()
                // Stay parked on the shared pause head (at most once per tick is fine when paused).
                player.syncPosition(conductor.ensemblePositionSeconds())
            }
            return
        }

        // Solo / conductor: own RPM controls playback speed (unchanged).
        player.setTempoFactor(rpmToTempoFactor(be.speed))

        pendingSeek?.let { pos ->
            applyPendingSeek(player, pos, pendingRestart)
            if (isConductorBlock) {
                ensembleClockSeconds = pos
                ensembleClockGameTime = level.gameTime
                if (pendingRestart) {
                    forceAlignTicks = 2
                }
            }
            pendingSeek = null
            pendingRestart = false
        }

        if (playing && speed > 0f) {
            if (!player.playing) {
                val start =
                    if (isConductorBlock) ensemblePositionSeconds() else player.currentSeconds()
                player.play(start)
            }
            if (isConductorBlock) {
                // Keep the silent conductor head on the authoritative timeline.
                alignToEnsembleClock(player, ensemblePositionSeconds())
            }
            player.setTempoFactor(rpmToTempoFactor(be.speed))
            if (player.pollNaturalEnd()) {
                ModPackets.sendToServer(MusicBoxActionPacket(be.blockPos, "finished"))
            }
        } else if (!playing || speed <= 0f) {
            if (player.playing) player.pause()
            if (isConductorBlock) {
                player.syncPosition(ensemblePositionSeconds())
            }
        }
    }

    private fun checkNaturalEnd() {
        if (!playing) return
        if (role == MusicBoxRole.SECTION && !isConductorBlock) return
        if (durationSeconds <= 0.0) {
            refreshDurationFromMidi()
        }
        // Require a real length and that playback has actually progressed.
        if (durationSeconds < 0.5) return
        val pos = ensemblePositionSeconds()
        if (pos < 0.25) return
        if (pos >= durationSeconds - 0.05) {
            setPlaying(false, naturalEnd = true)
        }
    }

    private fun applyPendingSeek(
        player: MidiPlayerInstance,
        pos: Double,
        restart: Boolean,
    ) {
        if (restart) {
            player.stop()
            player.play(0.0)
            return
        }
        val drift = abs(player.currentSeconds() - pos)
        // Ignore tiny drift — sequencer seeks skip events and drop notes on dense MIDI.
        if (drift < 0.08) return
        // Large jumps (user seek) need a full seek; medium corrections stay soft.
        if (drift > 0.50 || !player.playing) {
            player.seek(pos)
        } else {
            player.syncPosition(pos)
        }
    }

    private fun alignToEnsembleClock(
        player: MidiPlayerInstance,
        targetSeconds: Double,
    ) {
        val drift = abs(player.currentSeconds() - targetSeconds)
        if (forceAlignTicks > 0) {
            // One correction after restart, then trust tempo factor + conductor RPM.
            if (drift >= 0.04) {
                player.syncPosition(targetSeconds)
            }
            forceAlignTicks = 0
            return
        }
        // Prefer slight desync over seeking: SoftSynth note-offs are lost when the sequencer jumps.
        val gameTime = be.level?.gameTime ?: 0L
        when {
            drift > 0.55 -> player.syncPosition(targetSeconds)
            drift > 0.18 && gameTime % 40L == 0L -> player.syncPosition(targetSeconds)
        }
    }

    /** Section: conductor that last synced us, if still a conductor block. */
    private fun resolveLinkedConductor(): MusicBoxBehaviour? {
        val pos = linkedConductorPos ?: return null
        val level = be.level ?: return null
        val other = level.getBlockEntity(pos) as? MusicBoxBlockEntity ?: return null
        val ob = other.behaviour
        return if (ob.isConductorBlock) ob else null
    }

    override fun unload() {
        be.level?.onClient { _, _ -> releaseClientAudio() }
        super.unload()
    }

    override fun write(
        compound: CompoundTag,
        registries: HolderLookup.Provider,
        clientPacket: Boolean,
    ) {
        enforceRoleFromBlock()
        playerUuid?.let { compound.putUUID("MusicBoxUuid", it) }
        compound.putString("DisplayName", displayName)
        compound.putDouble("DurationSeconds", durationSeconds)
        compound.putString("Role", role.name)
        compound.putBoolean("Playing", playing)
        compound.putBoolean("PlaybackEndedNaturally", playbackEndedNaturally)
        compound.putBoolean("ConductOnly", conductOnly)
        compound.putInt("InstrumentProgram", instrumentProgram)
        val chList = ListTag()
        sectionChannels.forEach { chList.add(net.minecraft.nbt.IntTag.valueOf(it)) }
        compound.put("SectionChannels", chList)
        // Score item remains the server-side source of truth for solo/conductor.
        // Client packets: send full MIDI only when payload changes (NeoForge can split large
        // custom payloads, but rebroadcasting 512 KiB every ensemble tick is still costly).
        compound.putBoolean("HasMidi", midiBytes != null)
        val bytes = midiBytes
        if (!clientPacket) {
            bytes?.let { compound.putByteArray("MidiBytes", it) }
        } else {
            val hash = bytes?.contentHashCode() ?: 0
            if (hash != lastClientSyncedMidiHash) {
                lastClientSyncedMidiHash = hash
                bytes?.let { compound.putByteArray("MidiBytes", it) }
            }
        }
        linkedConductorPos?.let { compound.putLong("LinkedConductor", it.asLong()) }
        // Solo + conductor: sync timeline so the GUI can show progress.
        if (isConductorBlock || role != MusicBoxRole.SECTION) {
            compound.putDouble("EnsembleClock", ensemblePositionSeconds())
            compound.putLong("EnsembleClockTime", be.level?.gameTime ?: ensembleClockGameTime)
            compound.putInt("EnsembleEpoch", ensembleEpoch)
        }
        pendingSeek?.let {
            compound.putDouble("PendingSeek", it)
            if (pendingRestart) compound.putBoolean("PendingRestart", true)
            if (clientPacket) {
                pendingSeek = null
                pendingRestart = false
            }
        }
    }

    override fun read(
        compound: CompoundTag,
        registries: HolderLookup.Provider,
        clientPacket: Boolean,
    ) {
        if (compound.hasUUID("MusicBoxUuid")) {
            val incoming = compound.getUUID("MusicBoxUuid")
            if (playerUuid != incoming) {
                val stalePosId = be.blockPos.asLong().toString()
                val staleUuid = playerUuid
                playerUuid = incoming
                if (clientPacket) {
                    // UUID often arrives after the client already started SoftSynth under pos id.
                    clientLoadedHash = 0
                    MidiEngine.release(stalePosId)
                    staleUuid?.let { MidiEngine.release(it.toString()) }
                    if (boundAudioPlayerId != null && boundAudioPlayerId != incoming.toString()) {
                        MidiEngine.release(boundAudioPlayerId!!)
                        boundAudioPlayerId = null
                    }
                }
            }
        }
        displayName = compound.getString("DisplayName")
        durationSeconds = if (compound.contains("DurationSeconds")) compound.getDouble("DurationSeconds") else 0.0
        role = runCatching { MusicBoxRole.valueOf(compound.getString("Role")) }.getOrDefault(MusicBoxRole.SOLO)
        playing = compound.getBoolean("Playing")
        playbackEndedNaturally = compound.getBoolean("PlaybackEndedNaturally")
        conductOnly = compound.getBoolean("ConductOnly")
        instrumentProgram =
            if (compound.contains("InstrumentProgram")) {
                GeneralMidiInstruments.normalize(compound.getInt("InstrumentProgram"))
            } else {
                GeneralMidiInstruments.DEFAULT_PROGRAM
            }
        sectionChannels.clear()
        compound.getList("SectionChannels", net.minecraft.nbt.Tag.TAG_INT.toInt()).forEach {
            sectionChannels.add((it as net.minecraft.nbt.NumericTag).asInt)
        }
        // Empty SectionChannels = all parts (do not default to channel 0).
        if (compound.contains("HasMidi") && !compound.getBoolean("HasMidi")) {
            midiBytes = null
        } else if (compound.contains("MidiBytes")) {
            midiBytes =
                compound.getByteArray("MidiBytes").takeIf {
                    it.isNotEmpty() && it.size <= MidiLimits.MAX_MIDI_BYTES
                }
        } else if (compound.contains("MidiBase64")) {
            try {
                midiBytes =
                    Base64
                        .getDecoder()
                        .decode(compound.getString("MidiBase64"))
                        .takeIf { it.isNotEmpty() && it.size <= MidiLimits.MAX_MIDI_BYTES }
            } catch (_: Exception) {
                midiBytes = null
            }
        } else if (clientPacket && compound.contains("HasMidi") && compound.getBoolean("HasMidi")) {
            // Omitted MidiBytes with HasMidi=true — keep a warm cache. If cold, ask the server
            // to re-send (otherwise ensemble stays silent until a Solo↔Section toggle).
            if (midiBytes == null && !midiResyncRequested) {
                midiResyncRequested = true
                ModPackets.sendToServer(MusicBoxActionPacket(be.blockPos, "resync_midi"))
            }
        } else {
            midiBytes = null
        }
        if (midiBytes != null) {
            midiResyncRequested = false
        }
        if (compound.contains("PendingSeek")) {
            pendingSeek = compound.getDouble("PendingSeek")
            pendingRestart = compound.getBoolean("PendingRestart")
        }
        linkedConductorPos =
            if (compound.contains("LinkedConductor")) {
                BlockPos.of(compound.getLong("LinkedConductor"))
            } else {
                null
            }
        if (compound.contains("EnsembleClock")) {
            ensembleClockSeconds = compound.getDouble("EnsembleClock")
            ensembleClockGameTime = compound.getLong("EnsembleClockTime")
            ensembleEpoch = compound.getInt("EnsembleEpoch")
        }
        enforceRoleFromBlock()
        // Client audio must cut the moment the score leaves — waiting for the next tick
        // leaves hanging SoftSynth voices (same class of glitch as mid-play instrument swaps).
        if (clientPacket) {
            if (midiBytes == null) {
                releaseClientAudio()
            } else if (!playing) {
                MidiEngine.pauseIfPresent(playerId())
                MidiEngine.pauseIfPresent(be.blockPos.asLong().toString())
                boundAudioPlayerId?.let { MidiEngine.pauseIfPresent(it) }
            }
        }
    }
}
