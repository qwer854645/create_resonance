package io.github.qwer854645.createresonance.foundation.registry

import com.tterrag.registrate.util.entry.ItemEntry
import io.github.qwer854645.createresonance.ModRegistrate
import io.github.qwer854645.createresonance.content.midi.MidiRollItem
import io.github.qwer854645.createresonance.content.records.ResonanceDiscItem
import io.github.qwer854645.createresonance.foundation.info
import net.minecraft.core.Registry
import net.minecraft.world.item.Item

object ModItems : CommonRegistry {
    override val registrationOrder = 3

    val WEBDISC: ItemEntry<ResonanceDiscItem> =
        ModRegistrate
            .item("resonance_disc") { ResonanceDiscItem(Item.Properties().stacksTo(64)) }
            .lang("Resonance Disc")
            .model { ctx, prov ->
                prov.generated(ctx, prov.modLoc("item/resonance_disc_blank/base"))
            }.register()

    val MIDI_ROLL: ItemEntry<MidiRollItem> =
        ModRegistrate
            .item("midi_roll") { MidiRollItem(Item.Properties().stacksTo(64)) }
            .lang("Score")
            .model { ctx, prov ->
                // Reuse disc blank sheet look until a dedicated roll texture exists
                prov.generated(ctx, prov.modLoc("item/resonance_disc_blank/base"))
            }.register()

    override fun register(registry: Registry<*>?) {
        "Registering items".info()
    }
}
