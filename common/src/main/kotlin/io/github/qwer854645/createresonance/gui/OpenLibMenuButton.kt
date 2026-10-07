package io.github.qwer854645.createresonance.gui

import io.github.qwer854645.createresonance.config.ModConfigs
import io.github.qwer854645.createresonance.foundation.eventbus.ClientEvents
import io.github.qwer854645.createresonance.foundation.eventbus.EventBus
import io.github.qwer854645.createresonance.foundation.registry.ModItems
import io.github.qwer854645.createresonance.gui.OpenLibMenuButton.MenuRows
import io.github.qwer854645.createresonance.gui.OpenLibMenuButton.MenuRows.leftTextKeys
import io.github.qwer854645.createresonance.gui.OpenLibMenuButton.MenuRows.rightTextKeys
import net.createmod.catnip.gui.ScreenOpener
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.Tooltip
import net.minecraft.client.gui.components.events.GuiEventListener
import net.minecraft.client.gui.screens.PauseScreen
import net.minecraft.client.gui.screens.TitleScreen
import net.minecraft.client.resources.language.I18n
import net.minecraft.network.chat.Component
import org.apache.commons.lang3.mutable.MutableObject
import java.util.function.Consumer

/**
 * Title / pause menu entry for Create: Resonance (same injection pattern as Create's goggles button).
 *
 * FancyMenu:
 * - Must be added **synchronously** in [ScreenEvent.Init.Post] (not via async EventBus flows),
 *   otherwise FancyMenu finishes widget discovery before this button exists.
 * - Stable FancyMenu id via [UniqueWidget.setWidgetIdentifierFancyMenu]: [WIDGET_ID]
 * - Opens: `io.github.qwer854645.createresonance.gui.ResonanceMenuScreen`
 * - Disable injection: `mainMenuLibButtonRow` / `ingameMenuLibButtonRow` = 0
 */
class OpenLibMenuButton(
    x: Int,
    y: Int,
) : Button(
        x,
        y,
        20,
        20,
        // Language-independent message (also used as fallback locator).
        Component.literal(WIDGET_ID),
        OnPress { click(it) },
        DEFAULT_NARRATION,
    ) {
    init {
        tooltip = Tooltip.create(Component.translatable("create_resonance.gui.main_menu.open_btn"))
        // FancyMenu mixes UniqueWidget into AbstractWidget — set a stable universal id.
        FancyMenuWidgetIds.apply(this, WIDGET_ID)
    }

    override fun renderString(
        graphics: GuiGraphics,
        pFont: Font,
        pColor: Int,
    ) {
        graphics.renderItem(ModItems.WEBDISC.asStack(), x + 2, y + 2)
    }

    data object MenuRows {
        data class SingleMenuRow(
            val leftTextKey: String,
            val rightTextKey: String = "",
        )

        val MAIN_MENU =
            listOf(
                SingleMenuRow("menu.singleplayer"),
                SingleMenuRow("menu.multiplayer"),
                SingleMenuRow("fml.menu.mods", "menu.online"),
                SingleMenuRow("narrator.button.language", "narrator.button.accessibility"),
            )

        val INGAME_MENU =
            listOf(
                SingleMenuRow("menu.returnToGame"),
                SingleMenuRow("gui.advancements", "gui.stats"),
                SingleMenuRow("menu.sendFeedback", "menu.reportBugs"),
                SingleMenuRow("menu.options", "menu.shareToLan"),
                SingleMenuRow("menu.returnToMenu"),
            )

        fun List<SingleMenuRow>.leftTextKeys(): List<String> = map { it.leftTextKey }

        fun List<SingleMenuRow>.rightTextKeys(): List<String> = map { it.rightTextKey }
    }

    companion object {
        const val WIDGET_ID = "create_resonance:menu_open"

        fun click(b: Button?) {
            ScreenOpener.open(ResonanceMenuScreen(Minecraft.getInstance().screen))
        }
    }
}

/**
 * Soft-call FancyMenu's UniqueWidget API without a hard dependency.
 * Create/Quark buttons are discoverable because they are added during Init.Post;
 * a stable id additionally keeps layouts working when position anchors move.
 */
internal object FancyMenuWidgetIds {
    fun apply(
        widget: AbstractWidget,
        id: String,
    ) {
        try {
            val method =
                widget.javaClass.methods.firstOrNull {
                    it.name == "setWidgetIdentifierFancyMenu" && it.parameterCount == 1
                } ?: return
            method.invoke(widget, id)
        } catch (_: Throwable) {
        }
    }
}

object MainMenuHandler : CommonGuiEventHandler {
    override fun setupEvents() {
        // CRITICAL: use onSync, not onMcMain.
        // onMcMain posts through a SharedFlow + coroutine and adds the button *after*
        // ScreenEvent.Init.Post returns — FancyMenu has already finished discovering widgets.
        // Create's OpenCreateMenuButton / Quark's QButton add synchronously in Init.Post.
        EventBus.onSync<ClientEvents.ScreenEvent.Init> { event ->
            val screen = event.screen

            val menu: List<MenuRows.SingleMenuRow>
            val rowIdx: Int
            val offsetX: Int
            when (screen) {
                is TitleScreen -> {
                    menu = MenuRows.MAIN_MENU
                    rowIdx = ModConfigs.client.mainMenuLibButtonRow.get()
                    offsetX = ModConfigs.client.mainMenuLibButtonOffsetX.get()
                }

                is PauseScreen -> {
                    menu = MenuRows.INGAME_MENU
                    rowIdx = ModConfigs.client.ingameMenuLibButtonRow.get()
                    offsetX = ModConfigs.client.ingameMenuLibButtonOffsetX.get()
                }

                else -> return@onSync
            }

            // Row 0 = disabled so FancyMenu can own the entire title/pause layout.
            if (rowIdx == 0) return@onSync

            val onLeft = offsetX < 0
            val keys = if (onLeft) menu.leftTextKeys() else menu.rightTextKeys()
            if (rowIdx < 1 || rowIdx > keys.size) return@onSync
            val targetMessage = I18n.get(keys[rowIdx - 1])
            if (targetMessage.isBlank()) return@onSync

            val toAdd = MutableObject<GuiEventListener>(null)
            event.listenerList
                .stream()
                .filter { w -> w is AbstractWidget }
                .map { w -> w as AbstractWidget }
                .filter { w -> w.message.string == targetMessage }
                .findFirst()
                .ifPresent(
                    Consumer { w ->
                        toAdd.value =
                            OpenLibMenuButton(
                                w.x + offsetX + (if (onLeft) -20 else w.width),
                                w.y,
                            )
                    },
                )
            toAdd.value?.let { event.addListener(it) }
        }
    }
}
