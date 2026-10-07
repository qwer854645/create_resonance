package io.github.qwer854645.createresonance.content.records

import com.simibubi.create.AllKeys
import com.simibubi.create.foundation.item.TooltipHelper
import io.github.qwer854645.createresonance.handler.RecordCraftingHandler
import net.createmod.catnip.lang.FontHelper
import net.minecraft.ChatFormatting
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.TooltipFlag

class ResonanceDiscItem(
    props: Properties,
) : Item(props) {
    companion object {
        val JUKEBOX_DISCS: List<Item> by lazy {
            BuiltInRegistries.ITEM
                .stream()
                .filter { item ->
                    val stack = ItemStack(item)
                    stack.has(DataComponents.JUKEBOX_PLAYABLE)
                }.toList()
        }
    }

    override fun getDefaultInstance(): ItemStack {
        val default = super.getDefaultInstance()

        // Stable vanity disc so creative / default blanks can stack together.
        if (JUKEBOX_DISCS.isNotEmpty()) {
            RecordCraftingHandler.setCraftedWithDisc(
                default,
                ItemStack(JUKEBOX_DISCS.first()),
            )
        }

        return default
    }

    /** Enchantment glint when a URL is stored — visible at a glance in inventory. */
    override fun isFoil(stack: ItemStack): Boolean = stack.hasAssignedUrl()

    override fun appendHoverText(
        stack: ItemStack,
        context: TooltipContext,
        tooltipComponents: MutableList<Component>,
        isAdvanced: TooltipFlag,
    ) {
        val url = RecordUtilities.getAudioUrl(stack)
        if (!url.isNullOrBlank()) {
            tooltipComponents.add(
                Component
                    .translatable("create_resonance.tooltips.item.resonance_disc.url_bound")
                    .withStyle(ChatFormatting.AQUA),
            )
            // Same pattern as Create's FilterItem: Shift expands details.
            if (AllKeys.shiftDown()) {
                tooltipComponents.addAll(
                    TooltipHelper.cutStringTextComponent(url, FontHelper.Palette.GRAY_AND_WHITE),
                )
            } else {
                tooltipComponents.add(
                    TooltipHelper.holdShift(FontHelper.Palette.STANDARD_CREATE, false),
                )
            }
        } else {
            tooltipComponents.add(
                Component
                    .translatable("create_resonance.tooltips.item.resonance_disc.url_empty")
                    .withStyle(ChatFormatting.DARK_GRAY),
            )
        }

        super.appendHoverText(stack, context, tooltipComponents, isAdvanced)
    }
}
