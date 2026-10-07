package io.github.qwer854645.createresonance.mixin.content.record;

import io.github.qwer854645.createresonance.content.records.ResonanceDiscItem;
import io.github.qwer854645.createresonance.content.records.RecordUtilities;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.common.extensions.IItemExtension;
import org.jetbrains.annotations.NotNull;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(ResonanceDiscItem.class)
public abstract class ResonanceDiscItemMixin implements IItemExtension {

    @Override
    public int getMaxDamage(@NotNull ItemStack stack) {
        return 0;
    }

    /** Blank discs stack; URL-imprinted discs stay unique (max 1). */
    @Override
    public int getMaxStackSize(@NotNull ItemStack stack) {
        String url = RecordUtilities.INSTANCE.getAudioUrl(stack);
        return (url == null || url.isBlank()) ? 64 : 1;
    }
}
