package eu.nordtal.s2.smp.travel;

import eu.nordtal.s2.common.feedback.Feedback;
import eu.nordtal.s2.common.message.MessageRenderer;
import eu.nordtal.s2.common.message.Messages;
import eu.nordtal.s2.common.message.PlayerLocales;
import eu.nordtal.s2.smp.feedback.SmpSounds;
import eu.nordtal.s2.smp.feedback.Surface;
import eu.nordtal.s2.smp.milestone.Milestone;
import eu.nordtal.s2.smp.milestone.MilestoneTrack;
import eu.nordtal.s2.smp.milestone.Unlock;
import eu.nordtal.s2.smp.state.SeasonState;
import eu.nordtal.s2.smp.world.WorldRole;
import eu.nordtal.s2.smp.world.Worlds;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The travel GUI a balloon opens.
 *
 * <p>The layout is {@link BalloonMenu}'s and is tested there without a server; this class is the
 * part that needs one - items, names, colours and the teleport.
 *
 * <p>A destination that is not unlocked yet <b>keeps its place, greyed</b>, naming the milestone
 * that will open it and pointing at the objective board. Standing at the balloon is exactly when
 * somebody wants to know why the Nether is not available, and an entry that has simply vanished
 * answers nothing.
 */
public final class BalloonGui implements Surface {

    private final Messages messages;
    private final PlayerLocales locales;
    private final Worlds worlds;
    private final SeasonState season;
    private final MilestoneTrack track;
    private final SmpSounds sounds;

    private final WorldRole here;
    private final List<BalloonMenu.Entry> entries;
    private final Inventory inventory;

    public BalloonGui(final Messages messages, final PlayerLocales locales, final Worlds worlds,
                      final SeasonState season, final MilestoneTrack track, final SmpSounds sounds,
                      final Player viewer, final WorldRole here) {
        this.messages = messages;
        this.locales = locales;
        this.worlds = worlds;
        this.season = season;
        this.track = track;
        this.sounds = sounds;
        this.here = here;
        this.entries = BalloonMenu.of(here, season.unlocked());

        final Locale locale = locales.of(viewer.getUniqueId());
        this.inventory = Bukkit.createInventory(this, BalloonMenu.ROWS * 9,
                MessageRenderer.of(messages).get(locale, "smp.balloon.title"));
        draw(locale);
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    private void draw(final Locale locale) {
        for (final BalloonMenu.Entry entry : entries) {
            final ItemStack icon = icon(entry, locale);
            for (final int slot : entry.slots()) {
                inventory.setItem(slot, icon);
            }
        }
    }

    private ItemStack icon(final BalloonMenu.Entry entry, final Locale locale) {
        final boolean locked = entry.state() == BalloonMenu.State.LOCKED;
        final ItemStack stack = new ItemStack(locked ? Material.GRAY_STAINED_GLASS_PANE
                : material(entry.destination()));

        stack.editMeta(meta -> {
            meta.displayName(MessageRenderer.of(messages).get(locale, nameKey(entry.destination()))
                    .color(locked ? NamedTextColor.GRAY : NamedTextColor.WHITE)
                    .decoration(TextDecoration.ITALIC, false));

            final List<Component> lore = new ArrayList<>();
            switch (entry.state()) {
                case HERE -> lore.add(line(messages.get(locale, "smp.balloon.here"), NamedTextColor.DARK_GRAY));
                case OPEN -> lore.add(line(messages.get(locale, "smp.balloon.open"), NamedTextColor.GREEN));
                case LOCKED -> {
                    lore.add(line(messages.format(locale, "smp.balloon.locked",
                            "milestone", milestoneName(entry.destination(), locale)), NamedTextColor.RED));
                    lore.add(line(messages.get(locale, "smp.balloon.locked-hint"), NamedTextColor.DARK_GRAY));
                }
            }
            meta.lore(lore);
        });
        return stack;
    }

    private static Component line(final String text, final NamedTextColor colour) {
        return Component.text(text).color(colour).decoration(TextDecoration.ITALIC, false);
    }

    private static Material material(final WorldRole role) {
        return switch (role) {
            case NORDTAL -> Material.GRASS_BLOCK;
            case FARM -> Material.WHEAT;
            case NETHER -> Material.NETHERRACK;
            case END -> Material.END_STONE;
        };
    }

    private static String nameKey(final WorldRole role) {
        return "smp.world." + role.name().toLowerCase(Locale.ROOT);
    }

    /**
     * The name of the milestone that opens a destination, for the greyed entry's first lore line.
     *
     * <p>Falls back to the raw key when the track has no name for it, which is what a milestone
     * whose translation is missing should look like: unhelpful, but not blank.
     */
    private String milestoneName(final WorldRole role, final Locale locale) {
        final Unlock needed = role == WorldRole.NETHER ? Unlock.NETHER : Unlock.END;
        final Optional<Milestone> milestone = track.milestones().stream()
                .filter(candidate -> candidate.unlock() == needed)
                .findFirst();
        if (milestone.isEmpty()) {
            return messages.get(locale, "smp.balloon.locked-unknown");
        }
        final String key = "smp.milestone." + milestone.get().key();
        return messages.hasTranslation(locale, key) ? messages.get(locale, key) : milestone.get().key();
    }

    /**
     * Handles a click on {@code slot}.
     *
     * @return true when the player was sent somewhere, false for filler, "you are here" and locked
     */
    public boolean click(final Player player, final int slot) {
        final Locale locale = locales.of(player.getUniqueId());
        final Optional<BalloonMenu.Entry> clicked = BalloonMenu.at(entries, slot);
        if (clicked.isEmpty()) {
            return false;
        }

        final BalloonMenu.Entry entry = clicked.get();
        if (!entry.travellable()) {
            if (entry.state() == BalloonMenu.State.LOCKED) {
                player.sendMessage(MessageRenderer.of(messages).format(locale, "smp.balloon.locked",
                        "milestone", milestoneName(entry.destination(), locale)));
                sounds.play(player, Feedback.REFUSED);
            }
            return false;
        }

        final World destination = worlds.world(entry.destination()).orElse(null);
        if (destination == null) {
            player.sendMessage(MessageRenderer.of(messages).get(locale, "smp.balloon.unavailable"));
            sounds.play(player, Feedback.REFUSED);
            return false;
        }

        player.closeInventory();
        // Always the world spawn. The balloon never drops anyone anywhere else, which is what makes
        // a world spawn a landmark everybody knows; portals are the only exception in the design.
        player.teleport(destination.getSpawnLocation());
        player.sendMessage(MessageRenderer.of(messages).format(locale, "smp.balloon.travelled",
                "world", messages.get(locale, nameKey(entry.destination()))));
        sounds.play(player, Feedback.TRAVEL);
        return true;
    }

    public WorldRole here() {
        return here;
    }

    SeasonState season() {
        return season;
    }
}
