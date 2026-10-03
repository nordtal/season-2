package eu.nordtal.s2.smp.travel;

import static eu.nordtal.s2.smp.SmpMessages.MESSAGES;

import eu.nordtal.s2.messagerendering.MessageRenderer;
import eu.nordtal.s2.messages.Messages;
import eu.nordtal.s2.messages.context.MilestoneContext;
import eu.nordtal.s2.messages.feedback.Feedback;
import eu.nordtal.s2.papercommon.player.Identities;
import eu.nordtal.s2.smp.config.SpawnPointSpec;
import eu.nordtal.s2.smp.feedback.SmpSounds;
import eu.nordtal.s2.smp.feedback.Surface;
import eu.nordtal.s2.smp.feedback.WorldEffects;
import eu.nordtal.s2.smp.menu.BlankItem;
import eu.nordtal.s2.smp.milestone.Milestone;
import eu.nordtal.s2.smp.milestone.MilestoneNames;
import eu.nordtal.s2.smp.milestone.MilestoneTrack;
import eu.nordtal.s2.smp.milestone.Unlock;
import eu.nordtal.s2.smp.state.SeasonState;
import eu.nordtal.s2.smp.world.WorldRole;
import eu.nordtal.s2.smp.world.Worlds;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/**
 * The travel GUI a balloon opens: tooltips, clicks and the teleport around {@link BalloonMenu} and {@link TravelPanel}.
 *
 * Every card slot holds a {@link BlankItem} whose tooltip names its card; locked destinations stay in place, shaded.
 */
public final class BalloonGui implements Surface {

    private final Messages messages;
    private final Identities identities;
    private final Worlds worlds;
    private final SeasonState season;
    private final MilestoneTrack track;
    private final SmpSounds sounds;
    private final WorldEffects effects;

    private final WorldRole here;
    private final List<BalloonMenu.Entry> entries;
    private final Inventory inventory;

    public BalloonGui(
            final Messages messages,
            final Identities identities,
            final Worlds worlds,
            final SeasonState season,
            final MilestoneTrack track,
            final SmpSounds sounds,
            final WorldEffects effects,
            final Player viewer,
            final WorldRole here) {
        this.messages = messages;
        this.identities = identities;
        this.worlds = worlds;
        this.season = season;
        this.track = track;
        this.sounds = sounds;
        this.effects = effects;
        this.here = here;
        this.entries = BalloonMenu.of(here, season.unlocked());

        final Locale locale = identities.languageOf(viewer.getUniqueId());
        this.inventory = Bukkit.createInventory(this, BalloonMenu.ROWS * 9, TravelPanel.title(entries));
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
        // The world's name is a parameter and the colour is the bundle's.
        final MessageRenderer renderer = MessageRenderer.of(messages);
        final boolean locked = entry.state() == BalloonMenu.State.LOCKED;
        final String destination = messages.format(locale, MESSAGES.smp().world(entry.destination()));
        final Component name = renderer.format(
                locale,
                locked
                        ? MESSAGES.smp().balloon().cardLocked(destination)
                        : MESSAGES.smp().balloon().card(destination));

        final List<Component> lore = new ArrayList<>();
        switch (entry.state()) {
            case HERE ->
                lore.add(renderer.format(locale, MESSAGES.smp().balloon().here()));
            case OPEN ->
                lore.add(renderer.format(locale, MESSAGES.smp().balloon().open()));
            case LOCKED -> {
                lore.add(renderer.format(
                        locale,
                        MESSAGES.smp()
                                .balloon()
                                .locked(new MilestoneContext(milestoneName(entry.destination(), locale)))));
                lore.add(renderer.format(locale, MESSAGES.smp().balloon().lockedHint()));
            }
        }
        return BlankItem.of(name, lore);
    }

    /** The name of the milestone that opens a destination, or the raw key when the track has none. */
    private String milestoneName(final WorldRole role, final Locale locale) {
        final Unlock needed = role == WorldRole.NETHER ? Unlock.NETHER : Unlock.END;
        final Optional<Milestone> milestone = track.milestones().stream()
                .filter(candidate -> candidate.unlock() == needed)
                .findFirst();
        if (milestone.isEmpty()) {
            return messages.format(locale, MESSAGES.smp().balloon().lockedUnknown());
        }
        return MilestoneNames.of(messages, locale, milestone.get().key());
    }

    /** Handles a click on {@code slot}, returning whether the player was sent somewhere. */
    public boolean click(final Player player, final int slot) {
        final Locale locale = identities.languageOf(player.getUniqueId());
        final Optional<BalloonMenu.Entry> clicked = BalloonMenu.at(entries, slot);
        if (clicked.isEmpty()) {
            return false;
        }

        final BalloonMenu.Entry entry = clicked.get();
        if (!entry.travellable()) {
            if (entry.state() == BalloonMenu.State.LOCKED) {
                player.sendMessage(MessageRenderer.of(messages)
                        .format(
                                locale,
                                MESSAGES.smp()
                                        .balloon()
                                        .locked(new MilestoneContext(milestoneName(entry.destination(), locale)))));
                sounds.play(player, Feedback.REFUSED);
            }
            return false;
        }

        final World destination = worlds.world(entry.destination()).orElse(null);
        if (destination == null) {
            player.sendMessage(MessageRenderer.of(messages)
                    .format(locale, MESSAGES.smp().balloon().unavailable()));
            sounds.play(player, Feedback.REFUSED);
            return false;
        }

        player.closeInventory();
        return teleportToBalloon(player, locale, entry, destination);
    }

    /** The jump itself, via {@code LandingSite#findSafeAt}, which never falls back to an unsafe point. */
    private boolean teleportToBalloon(
            final Player player, final Locale locale, final BalloonMenu.Entry entry, final World destination) {
        // Read before the teleport: it is all anybody left at the balloon sees.
        final org.bukkit.Location from = Objects.requireNonNull(player.getLocation());
        final SpawnPointSpec point = worlds.balloonSpawnPoint(entry.destination());
        final org.bukkit.Location target =
                new org.bukkit.Location(destination, point.x(), point.y(), point.z(), point.yaw(), point.pitch());
        final org.bukkit.Location landing = eu.nordtal.s2.smp.world.LandingSite.findSafeAt(destination, target)
                .orElse(null);
        if (landing == null || !player.teleport(landing)) {
            player.sendMessage(MessageRenderer.of(messages)
                    .format(locale, MESSAGES.smp().balloon().unavailable()));
            sounds.play(player, Feedback.REFUSED);
            return false;
        }
        effects.travelled(from);
        effects.travelled(landing);
        player.sendMessage(MessageRenderer.of(messages)
                .format(
                        locale,
                        MESSAGES.smp()
                                .balloon()
                                .travelled(
                                        messages.format(locale, MESSAGES.smp().world(entry.destination())))));
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
