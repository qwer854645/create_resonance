package io.github.qwer854645.createresonance.content.kinetics.musicBox

import com.google.common.collect.ImmutableList
import com.simibubi.create.foundation.gui.AllGuiTextures
import com.simibubi.create.foundation.gui.AllIcons
import com.simibubi.create.foundation.gui.menu.AbstractSimiContainerScreen
import com.simibubi.create.foundation.gui.widget.IconButton
import io.github.qwer854645.createresonance.audio.midi.GeneralMidiInstruments
import io.github.qwer854645.createresonance.audio.midi.MidiEngine
import io.github.qwer854645.createresonance.foundation.network.packet.MusicBoxActionPacket
import io.github.qwer854645.createresonance.foundation.registry.ModPackets
import io.github.qwer854645.createresonance.gui.ResonanceGuiStyle
import net.createmod.catnip.gui.element.GuiGameElement
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.renderer.Rect2i
import net.minecraft.network.chat.Component
import net.minecraft.util.Mth
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.item.ItemStack

/**
 * Music box / conductor GUI.
 *
 * - Solo music box: transport + seek + instrument + part
 * - Section music box: instrument + part only (score unused, grayed out)
 * - Conductor: transport + seek only (always silent, drives the ensemble)
 */
class MusicBoxScreen(
    menu: MusicBoxMenu,
    inv: Inventory,
    title: Component,
) : AbstractSimiContainerScreen<MusicBoxMenu>(menu, inv, title) {
    companion object {
        private const val COL_TITLE = ResonanceGuiStyle.COL_TITLE
        private const val COL_BODY = ResonanceGuiStyle.COL_BODY
        private const val COL_HINT = ResonanceGuiStyle.COL_HINT
        private const val COL_WARN = ResonanceGuiStyle.COL_WARN
    }

    private val renderedItem = ItemStack(menu.contentHolder.blockState.block.asItem())
    private var minuteInput: EditBox? = null
    private var secondInput: EditBox? = null
    private var seekButton: Button? = null
    private var extraAreas: List<Rect2i> = emptyList()

    private val be get() = menu.contentHolder
    private val behaviour get() = be.behaviour
    private val conductor get() = behaviour.isConductorBlock
    private val sectionMode get() = !conductor && behaviour.role == MusicBoxRole.SECTION
    private val showTransport get() = conductor || !sectionMode
    private val showSeek get() = showTransport && behaviour.hasAdvancedControls
    private val showInstrument get() = !conductor
    private val showPart get() = !conductor
    private val panelH get() = MusicBoxMenu.panelHeight(be)

    override fun init() {
        val w = MusicBoxMenu.GUI_WIDTH
        setWindowSize(w, panelH + 4 + AllGuiTextures.PLAYER_INVENTORY.height)
        setWindowOffset(0, 0)
        super.init()

        behaviour.enforceRoleFromBlock()
        behaviour.syncRoleFromModeSlot()
        val x = leftPos
        val y = topPos

        var rowY = y + 58

        if (showTransport) {
            addRenderableWidget(
                IconButton(x + 58, rowY, AllIcons.I_PLAY).also {
                    it.withCallback<IconButton> {
                        ModPackets.sendToServer(MusicBoxActionPacket(be.blockPos, "play"))
                    }
                    it.setToolTip(Component.translatable("create_resonance.gui.music_box.play"))
                },
            )
            addRenderableWidget(
                IconButton(x + 80, rowY, AllIcons.I_PAUSE).also {
                    it.withCallback<IconButton> {
                        ModPackets.sendToServer(MusicBoxActionPacket(be.blockPos, "pause"))
                    }
                    it.setToolTip(Component.translatable("create_resonance.gui.music_box.pause"))
                },
            )
            addRenderableWidget(
                IconButton(x + 102, rowY, AllIcons.I_STOP).also {
                    it.withCallback<IconButton> {
                        ModPackets.sendToServer(MusicBoxActionPacket(be.blockPos, "restart"))
                    }
                    it.setToolTip(Component.translatable("create_resonance.gui.music_box.restart"))
                },
            )
            rowY += 22
        }

        if (showSeek) {
            val minutes =
                ResonanceGuiStyle
                    .parchmentEditBox(
                        font,
                        x + 8,
                        rowY,
                        Component.translatable("create_resonance.gui.music_box.minutes"),
                    ).also {
                        it.value = "0"
                        it.setMaxLength(4)
                        it.setFilter { text -> text.isEmpty() || text.all(Char::isDigit) }
                    }
            val seconds =
                ResonanceGuiStyle
                    .parchmentEditBox(
                        font,
                        x + 48,
                        rowY,
                        Component.translatable("create_resonance.gui.music_box.seconds"),
                    ).also {
                        it.value = "00"
                        it.setMaxLength(2)
                        it.setFilter { text -> text.isEmpty() || text.all(Char::isDigit) }
                    }
            minuteInput = minutes
            secondInput = seconds
            addRenderableWidget(minutes)
            addRenderableWidget(seconds)
            val seek =
                Button
                    .builder(Component.translatable("create_resonance.gui.music_box.seek")) {
                        parseSeekSeconds()?.let { sec ->
                            ModPackets.sendToServer(MusicBoxActionPacket(be.blockPos, "seek", seconds = sec))
                        }
                    }.bounds(x + 84, rowY, 48, 16)
                    .build()
            seekButton = seek
            seek.active = parseSeekSeconds() != null
            addRenderableWidget(seek)
            rowY += 20
        }

        if (showInstrument) {
            addRenderableWidget(
                IconButton(x + 8, rowY, AllIcons.I_CONFIG_PREV).also {
                    it.withCallback<IconButton> { cycleInstrument(-1) }
                    it.setToolTip(Component.translatable("create_resonance.gui.music_box.instrument_prev"))
                },
            )
            val name =
                truncate(GeneralMidiInstruments.displayName(behaviour.instrumentProgram).string, 14)
            addRenderableWidget(
                Button
                    .builder(
                        Component.translatable("create_resonance.gui.music_box.instrument", name),
                    ) { cycleInstrument(1) }
                    .bounds(x + 30, rowY, 116, 18)
                    .build(),
            )
            addRenderableWidget(
                IconButton(x + 150, rowY, AllIcons.I_CONFIG_NEXT).also {
                    it.withCallback<IconButton> { cycleInstrument(1) }
                    it.setToolTip(Component.translatable("create_resonance.gui.music_box.instrument_next"))
                },
            )
            rowY += 20
        }

        if (showPart) {
            addRenderableWidget(
                IconButton(x + 8, rowY, AllIcons.I_CONFIG_PREV).also {
                    it.withCallback<IconButton> { cyclePart(-1) }
                    it.setToolTip(Component.translatable("create_resonance.gui.music_box.part_prev"))
                },
            )
            addRenderableWidget(
                Button
                    .builder(partLabel()) {
                        cyclePart(1)
                    }.bounds(x + 30, rowY, 116, 18)
                    .build(),
            )
            addRenderableWidget(
                IconButton(x + 150, rowY, AllIcons.I_CONFIG_NEXT).also {
                    it.withCallback<IconButton> { cyclePart(1) }
                    it.setToolTip(Component.translatable("create_resonance.gui.music_box.part_next"))
                },
            )
        }

        extraAreas =
            ImmutableList.of(
                Rect2i(x + w + 4, y + panelH - 48, 48, 48),
            )
    }

    /** -1 = all parts; otherwise a single MIDI channel 0–15. */
    private fun currentPart(): Int =
        if (behaviour.sectionChannels.isEmpty()) -1 else behaviour.sectionChannels.first()

    private fun partLabel(): Component {
        val part = currentPart()
        return if (part < 0) {
            Component.translatable("create_resonance.gui.music_box.part_all")
        } else {
            Component.translatable("create_resonance.gui.music_box.part", part + 1)
        }
    }

    private fun cyclePart(delta: Int) {
        // Cycle: all (-1) ↔ parts 0..15
        val cur = currentPart()
        val next = ((cur + 1 + delta).mod(17)) - 1
        behaviour.sectionChannels.clear()
        if (next >= 0) {
            behaviour.sectionChannels.add(next)
        }
        sendConfig()
        rebuildWidgets()
    }

    private fun cycleInstrument(delta: Int) {
        behaviour.instrumentProgram =
            GeneralMidiInstruments.cycle(behaviour.instrumentProgram, delta)
        sendConfig()
        rebuildWidgets()
    }

    private fun sendConfig() {
        val parts =
            if (behaviour.sectionChannels.isEmpty()) {
                listOf(-1) // sentinel: all parts
            } else {
                behaviour.sectionChannels.toList()
            }
        ModPackets.sendToServer(
            MusicBoxActionPacket(
                be.blockPos,
                action = "config",
                sectionChannels = parts,
                conductOnly = true,
                instrumentProgram = behaviour.instrumentProgram,
            ),
        )
    }

    override fun render(
        graphics: GuiGraphics,
        mouseX: Int,
        mouseY: Int,
        partialTicks: Float,
    ) {
        super.render(graphics, mouseX, mouseY, partialTicks)
        if (sectionMode) {
            val sx = leftPos + MusicBoxMenu.SCORE_SLOT_X - 1
            val sy = topPos + MusicBoxMenu.SCORE_SLOT_Y - 1
            graphics.fill(sx, sy, sx + 18, sy + 18, 0x66000000)
        }
    }

    override fun renderBg(
        graphics: GuiGraphics,
        partialTick: Float,
        mouseX: Int,
        mouseY: Int,
    ) {
        val x = leftPos
        val y = topPos
        val w = MusicBoxMenu.GUI_WIDTH
        val h = panelH

        // Soft parchment panel (closer to Create schematic tones).
        ResonanceGuiStyle.drawPanel(graphics, x, y, w, h)

        // Title — left-aligned like Create tables, no drop shadow.
        graphics.drawString(font, title, x + 8, y + 6, ResonanceGuiStyle.COL_TITLE, false)

        val track =
            behaviour.displayName.ifBlank {
                Component.translatable("create_resonance.gui.music_box.no_track").string
            }
        // Prefer the block-entity timeline so the conductor / section progress stays visible.
        // Sections resolve through the linked conductor clock (see ensemblePositionSeconds).
        val player = MidiEngine.getOrCreate(behaviour.playerId())
        val current =
            if (conductor || sectionMode || behaviour.durationSeconds > 0.0) {
                behaviour.ensemblePositionSeconds()
            } else {
                player.currentSeconds()
            }
        val duration =
            behaviour.durationSeconds.takeIf { it > 0.0 } ?: player.durationSeconds
        val time =
            Component
                .translatable(
                    "create_resonance.gui.music_box.progress",
                    formatTime(current),
                    formatTime(duration),
                ).string

        graphics.drawString(font, truncate(track, 22), x + 8, y + 18, COL_BODY, false)
        graphics.drawString(font, time, x + 8, y + 28, COL_HINT, false)

        // Short caption to the left of the score slot (avoids stacking over the slot).
        hintLine()?.let { (text, color) ->
            graphics.drawString(font, truncate(text, 14), x + 8, y + 40, color, false)
        }

        if (showSeek) {
            val seekY = y + 58 + 22
            // Cream plates so dark ink stays readable (vanilla EditBox chrome is too dark).
            ResonanceGuiStyle.drawFieldPlate(graphics, x + 8, seekY, 28, 16)
            ResonanceGuiStyle.drawFieldPlate(graphics, x + 48, seekY, 28, 16)
            graphics.drawString(font, ":", x + 40, seekY + 4, COL_HINT, false)
        }

        AllGuiTextures.SCHEMATIC_SLOT.render(
            graphics,
            x + MusicBoxMenu.SCORE_SLOT_X - 1,
            y + MusicBoxMenu.SCORE_SLOT_Y - 1,
        )

        val invY = y + h + 4
        AllGuiTextures.PLAYER_INVENTORY.render(graphics, x, invY)

        GuiGameElement
            .of(renderedItem)
            .at<GuiGameElement.GuiRenderBuilder>(x + w + 8f, y + h - 40f, -200f)
            .scale(3.0)
            .render(graphics)
    }

    /** Optional quiet caption under the progress line. */
    private fun hintLine(): Pair<String, Int>? {
        val emptyScore = be.inventory.getStackInSlot(0).isEmpty
        return when {
            conductor ->
                Component.translatable("create_resonance.gui.music_conductor.silent_hint").string to COL_HINT
            kotlin.math.abs(be.speed) <= 0f ->
                Component.translatable("create_resonance.gui.music_box.need_rotation").string to COL_WARN
            sectionMode ->
                Component.translatable("create_resonance.gui.music_box.section_score_unused").string to COL_HINT
            emptyScore ->
                Component.translatable("create_resonance.gui.music_box.score_slot").string to COL_HINT
            else -> null
        }
    }

    override fun getExtraAreas(): List<Rect2i> = extraAreas

    override fun containerTick() {
        super.containerTick()
        seekButton?.active = parseSeekSeconds() != null
    }

    private fun truncate(
        text: String,
        maxChars: Int,
    ): String = if (text.length <= maxChars) text else text.take(maxChars - 1) + "…"

    private fun formatTime(seconds: Double): String {
        val total = Mth.floor(seconds).coerceAtLeast(0)
        return "%d:%02d".format(total / 60, total % 60)
    }

    /** Valid seek: integer minutes ≥ 0 and integer seconds in 0–59. */
    private fun parseSeekSeconds(): Double? {
        val minutesText = minuteInput?.value?.trim() ?: return null
        val secondsText = secondInput?.value?.trim() ?: return null
        if (minutesText.isEmpty() || secondsText.isEmpty()) return null
        if (!minutesText.all(Char::isDigit) || !secondsText.all(Char::isDigit)) return null
        val minutes = minutesText.toIntOrNull() ?: return null
        val seconds = secondsText.toIntOrNull() ?: return null
        if (minutes < 0 || seconds !in 0..59) return null
        return minutes * 60.0 + seconds
    }
}

