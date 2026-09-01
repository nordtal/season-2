package eu.nordtal.s2.smp.navigate;

import eu.nordtal.s2.common.message.Messages;
import eu.nordtal.s2.common.message.PlayerLocales;
import eu.nordtal.s2.smp.db.PoiRow;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The list {@code /navigate} opens: the current world's spawn, the player's last death, and every
 * public POI.
 *
 * <p>POIs are public and unlimited - anyone may create one and everyone sees all of them - so this
 * list is the same for every player except for the last-death entry, which is their own. There is
 * deliberately no entry for another player: with PvP on everywhere, an arrow pointing at a person
 * is a hunting tool.
 *
 * <p>The first slot always turns navigation <em>off</em>. It is off by default, a player switched it
 * on, and the way back has to be as easy as the way in - and as visible, because an arrow nobody
 * asked for any more is the kind of thing that quietly annoys somebody for a week.
 */
public final class NavigateGui implements InventoryHolder {

    private static final int STOP_SLOT = 0;
    private static final int FIRST_TARGET_SLOT = 9;

    private final Messages messages;
    private final Navigation navigation;
    private final List<NavigationTarget> targets;
    private final Inventory inventory;

    public NavigateGui(final Messages messages, final PlayerLocales locales,
                       final Navigation navigation, final Player viewer,
                       final Optional<NavigationTarget> lastDeath, final List<PoiRow> pois) {
        this.messages = messages;
        this.navigation = navigation;

        final Locale locale = locales.of(viewer.getUniqueId());
        this.targets = build(viewer, lastDeath, pois);

        final int rows = Math.min(6, 2 + (targets.size() + 8) / 9);
        this.inventory = Bukkit.createInventory(this, rows * 9,
                Component.text(messages.get(locale, "smp.navigate.title")));
        draw(locale);
    }

    private static List<NavigationTarget> build(final Player viewer,
                                                final Optional<NavigationTarget> lastDeath,
                                                final List<PoiRow> pois) {
        final List<NavigationTarget> out = new ArrayList<>();
        out.add(NavigationTarget.worldSpawn(viewer.getWorld().getName(),
                viewer.getWorld().getSpawnLocation().getBlockX(),
                viewer.getWorld().getSpawnLocation().getBlockY(),
                viewer.getWorld().getSpawnLocation().getBlockZ()));
        lastDeath.ifPresent(out::add);
        for (final PoiRow poi : pois) {
            out.add(NavigationTarget.poi(poi.id(), poi.name(), poi.world(), poi.x(), poi.y(), poi.z()));
        }
        return List.copyOf(out);
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    private void draw(final Locale locale) {
        final ItemStack stop = new ItemStack(Material.BARRIER);
        stop.editMeta(meta -> meta.displayName(
                Component.text(messages.get(locale, "smp.navigate.stop"))
                        .color(NamedTextColor.RED).decoration(TextDecoration.ITALIC, false)));
        inventory.setItem(STOP_SLOT, stop);

        for (int index = 0; index < targets.size(); index++) {
            final int slot = FIRST_TARGET_SLOT + index;
            if (slot >= inventory.getSize()) {
                break;
            }
            inventory.setItem(slot, icon(targets.get(index), locale));
        }
    }

    private ItemStack icon(final NavigationTarget target, final Locale locale) {
        final Material material = switch (target.kind()) {
            case WORLD_SPAWN -> Material.LODESTONE;
            case LAST_DEATH -> Material.SKELETON_SKULL;
            case POI -> Material.FILLED_MAP;
        };
        final String label = target.kind() == NavigationTarget.Kind.POI
                ? target.label()
                : messages.get(locale, target.label());

        final ItemStack stack = new ItemStack(material);
        stack.editMeta(meta -> {
            meta.displayName(Component.text(label).color(NamedTextColor.WHITE)
                    .decoration(TextDecoration.ITALIC, false));
            meta.lore(List.of(Component.text(messages.format(locale, "smp.navigate.at",
                            "world", target.world(), "x", target.x(), "y", target.y(), "z", target.z()))
                    .color(NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false)));
        });
        return stack;
    }

    /** Handles a click; returns true when the inventory should close. */
    public boolean click(final Player player, final int slot, final Locale locale) {
        if (slot == STOP_SLOT) {
            navigation.clear(player.getUniqueId());
            player.sendMessage(Component.text(messages.get(locale, "smp.navigate.stopped")));
            return true;
        }
        final int index = slot - FIRST_TARGET_SLOT;
        if (index < 0 || index >= targets.size()) {
            return false;
        }
        final NavigationTarget target = targets.get(index);
        navigation.set(player.getUniqueId(), target);

        final String label = target.kind() == NavigationTarget.Kind.POI
                ? target.label()
                : messages.get(locale, target.label());
        player.sendMessage(Component.text(messages.format(locale, "smp.navigate.started", "target", label)));
        return true;
    }
}
