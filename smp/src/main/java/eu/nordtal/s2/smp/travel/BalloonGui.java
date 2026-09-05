package eu.nordtal.s2.smp.travel;

import eu.nordtal.s2.common.feedback.Feedback;
import eu.nordtal.s2.common.message.MessageRenderer;
import eu.nordtal.s2.common.message.Messages;
import eu.nordtal.s2.common.message.PlayerLocales;
import eu.nordtal.s2.papercommon.menu.BlankItem;
import eu.nordtal.s2.smp.feedback.SmpSounds;
import eu.nordtal.s2.smp.feedback.Surface;
import eu.nordtal.s2.smp.feedback.WorldEffects;
import eu.nordtal.s2.smp.milestone.Milestone;
import eu.nordtal.s2.smp.milestone.MilestoneTrack;
import eu.nordtal.s2.smp.milestone.Unlock;
import eu.nordtal.s2.smp.state.SeasonState;
import eu.nordtal.s2.smp.world.WorldRole;
import eu.nordtal.s2.smp.world.Worlds;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
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
 * <p>The layout is {@link BalloonMenu}'s and is tested there without a server; the surface is
 * {@link TravelPanel}'s and is drawn into the inventory title; this class is the part that needs a
 * server - the tooltips, the click and the teleport.
 *
 * <p><b>Nothing visible sits in a slot.</b> The four cards are art in the title, and every slot a
 * card covers holds a {@link BlankItem}: an item that draws nothing and carries the card's name and
 * caption as its tooltip, so hovering anywhere on a card explains it and clicking anywhere on it
 * travels. A vanilla item there would draw its icon over the art.
 *
 * <p>A destination that is not unlocked yet <b>keeps its place, shaded</b>, naming the milestone
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
    private final WorldEffects effects;

    private final WorldRole here;
    private final List<BalloonMenu.Entry> entries;
    private final Inventory inventory;

    public BalloonGui(final Messages messages, final PlayerLocales locales, final Worlds worlds,
                      final SeasonState season, final MilestoneTrack track, final SmpSounds sounds,
                      final WorldEffects effects, final Player viewer, final WorldRole here) {
        this.messages = messages;
        this.locales = locales;
        this.worlds = worlds;
        this.season = season;
        this.track = track;
        this.sounds = sounds;
        this.effects = effects;
        this.here = here;
        this.entries = BalloonMenu.of(here, season.unlocked());

        final Locale locale = locales.of(viewer.getUniqueId());
        this.inventory = Bukkit.createInventory(this, BalloonMenu.ROWS * 9,
                TravelPanel.title(entries));
        draw(locale);
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    private void draw(final Locale locale) {
        for (final BalloonMenu.Entry entry : entries) {
            final ItemStack tooltip = tooltip(entry, locale);
            for (final int slot : entry.slots()) {
                inventory.setItem(slot, tooltip);
            }
        }
    }

    /** The invisible item under a card: the world's name, and one or two lines on its state. */
    private ItemStack tooltip(final BalloonMenu.Entry entry, final Locale locale) {
        final boolean locked = entry.state() == BalloonMenu.State.LOCKED;
        final Component name = MessageRenderer.of(messages).get(locale, nameKey(entry.destination()))
                .color(locked ? NamedTextColor.GRAY : NamedTextColor.WHITE);

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
        return BlankItem.of(name, lore);
    }

    private static Component line(final String text, final NamedTextColor colour) {
        return Component.text(text).color(colour);
    }

    private static String nameKey(final WorldRole role) {
        return "smp.world." + role.name().toLowerCase(Locale.ROOT);
    }

    /**
     * The name of the milestone that opens a destination, for the shaded card's first lore line.
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
     * @return true when the player was sent somewhere, false for the gap, "you are here" and locked
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
        // Both ends of the trip, and the departure has to be read off before the teleport: to
        // anybody left standing at the balloon this is the whole of what they see happen.
        final org.bukkit.Location from = player.getLocation();
        // Always the world spawn. The balloon never drops anyone anywhere else, which is what makes
        // a world spawn a landmark everybody knows; portals are the only exception in the design.
        // Unreachable from an open chest screen today - a player clicking one is by definition
        // alive, awake and connected - but the success path below is six unconditional statements
        // and one of them is a message saying they arrived. Reusing the branch six lines up rather
        // than inventing a second way to say the same thing.
        if (!player.teleport(destination.getSpawnLocation())) {
            player.sendMessage(MessageRenderer.of(messages).get(locale, "smp.balloon.unavailable"));
            sounds.play(player, Feedback.REFUSED);
            return false;
        }
        effects.travelled(from);
        effects.travelled(destination.getSpawnLocation());
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
