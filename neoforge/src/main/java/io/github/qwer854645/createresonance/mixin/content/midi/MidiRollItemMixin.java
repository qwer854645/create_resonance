package io.github.qwer854645.createresonance.mixin.content.midi;

import io.github.qwer854645.createresonance.content.midi.MidiRollItem;
import io.github.qwer854645.createresonance.content.midi.MidiRollUtilities;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.common.extensions.IItemExtension;
import org.jetbrains.annotations.NotNull;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(MidiRollItem.class)
public abstract class MidiRollItemMixin implements IItemExtension {

    /** Blank scores stack; imprinted scores stay unique (max 1). */
    @Override
    public int getMaxStackSize(@NotNull ItemStack stack) {
        return MidiRollUtilities.INSTANCE.hasMidi(stack) ? 1 : 64;
    }
}
