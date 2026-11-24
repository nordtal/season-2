package eu.nordtal.season.smp;

import io.papermc.paper.plugin.bootstrap.BootstrapContext;
import io.papermc.paper.plugin.bootstrap.PluginBootstrap;
import io.papermc.paper.registry.RegistryKey;
import io.papermc.paper.registry.TypedKey;
import io.papermc.paper.registry.data.EnchantmentRegistryEntry;
import io.papermc.paper.registry.event.RegistryEvents;
import io.papermc.paper.registry.keys.tags.ItemTypeTagKeys;
import io.papermc.paper.registry.set.RegistrySet;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.EquipmentSlotGroup;

/** Registers Nordtal's custom data-driven enchantments during Paper bootstrap. */
public final class SmpBootstrap implements PluginBootstrap {

    /** The registry key of the Tunnel Miner enchantment. */
    public static final Key TUNNEL_MINER_KEY = Key.key("nordtal", "tunnel_miner");

    /** The typed registry key of the Tunnel Miner enchantment. */
    public static final TypedKey<Enchantment> TUNNEL_MINER = TypedKey.create(RegistryKey.ENCHANTMENT, TUNNEL_MINER_KEY);

    /** The resource-pack translation key for Tunnel Miner. */
    public static final String TUNNEL_MINER_TRANSLATION = "enchantment.nordtal.tunnel_miner";

    @Override
    public void bootstrap(final BootstrapContext context) {
        context.getLifecycleManager()
                .registerEventHandler(RegistryEvents.ENCHANTMENT
                        .compose()
                        .newHandler(event -> event.registry()
                                .register(
                                        TUNNEL_MINER,
                                        builder -> builder.description(Component.translatable(
                                                        TUNNEL_MINER_TRANSLATION, "Tunnel Miner"))
                                                .supportedItems(event.getOrCreateTag(ItemTypeTagKeys.PICKAXES))
                                                .anvilCost(4)
                                                .maxLevel(1)
                                                .weight(1)
                                                .minimumCost(EnchantmentRegistryEntry.EnchantmentCost.of(15, 0))
                                                .maximumCost(EnchantmentRegistryEntry.EnchantmentCost.of(65, 0))
                                                .activeSlots(EquipmentSlotGroup.MAINHAND)
                                                .exclusiveWith(RegistrySet.keySet(RegistryKey.ENCHANTMENT)))));
    }
}
