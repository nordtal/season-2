package eu.nordtal.season.smp.grave;

import static eu.nordtal.season.smp.SmpMessages.MESSAGES;

import eu.nordtal.season.common.id.DiscordId;
import eu.nordtal.season.messagerendering.MessageRenderer;
import eu.nordtal.season.messages.feedback.Feedback;
import eu.nordtal.season.papercommon.player.Identities;
import eu.nordtal.season.papercommon.time.PaperScheduler;
import eu.nordtal.season.smp.aura.AuraCause;
import eu.nordtal.season.smp.aura.AuraDao;
import eu.nordtal.season.smp.aura.AuraReason;
import eu.nordtal.season.smp.aura.DeathPenalty;
import eu.nordtal.season.smp.feedback.SmpSounds;
import eu.nordtal.season.smp.port.Arenas;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

/** Turns the drops of a dead player into a grave, applies the death penalty and opens a grave on a click. */
public final class GraveListener implements Listener {

    private final Plugin plugin;
    private final AuraDao aura;
    private final Graves graves;
    private final Identities identities;
    private final DeathPenalty penalty;
    private final Arenas arenas;
    private final MessageRenderer renderer;
    private final SmpSounds sounds;

    public GraveListener(
            final Plugin plugin,
            final AuraDao aura,
            final Graves graves,
            final Identities identities,
            final DeathPenalty penalty,
            final Arenas arenas,
            final MessageRenderer renderer,
            final SmpSounds sounds) {
        this.plugin = plugin;
        this.aura = aura;
        this.graves = graves;
        this.identities = identities;
        this.penalty = penalty;
        this.arenas = arenas;
        this.renderer = renderer;
        this.sounds = sounds;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDeath(final PlayerDeathEvent event) {
        final Player player = event.getEntity();
        if (arenas.isInArena(player)) {
            return;
        }

        final Optional<DiscordId> discordId = identities.discordIdOf(player.getUniqueId());
        final Location at = java.util.Objects.requireNonNull(player.getLocation());
        final List<ItemStack> drops = List.copyOf(event.getDrops());
        final int experience = event.getDroppedExp();

        event.getDrops().clear();
        event.setDroppedExp(0);

        if (discordId.isEmpty()) {
            // A player without a linked account gets no grave and drops the items as usual.
            event.getDrops().addAll(drops);
            event.setDroppedExp(experience);
            return;
        }

        if (!drops.isEmpty() || experience > 0) {
            graves.create(
                    discordId.get().value(), player.getUniqueId(), at, drops.toArray(new ItemStack[0]), experience);
        }
        applyPenalty(player, discordId.get(), event);
    }

    private void applyPenalty(final Player player, final DiscordId discordId, final PlayerDeathEvent event) {
        final String cause = event.getDamageSource() == null
                ? null
                : event.getDamageSource().getDamageType().key().asString();
        final int delta = penalty.deltaFor(cause, false);
        if (delta == 0) {
            return;
        }
        final AuraReason reason = penalty.reasonFor(cause);
        final Locale locale = identities.languageOf(player.getUniqueId());

        PaperScheduler.of(plugin).execute(() -> {
            aura.addAura(discordId, delta, new AuraCause.Death(reason, cause));
            PaperScheduler.of(plugin).onMain(() -> {
                if (player.isOnline()) {
                    player.sendMessage(
                            renderer.format(locale, MESSAGES.smp().aura().death(Math.abs(delta))));
                    sounds.play(player, Feedback.LOSS);
                }
            });
        });
    }

    /** Opens the grave whose {@link org.bukkit.entity.Interaction} the player right-clicks. */
    @EventHandler(ignoreCancelled = true)
    public void onInteract(final PlayerInteractEntityEvent event) {
        final Optional<UUID> graveId =
                graves.graveOfInteraction(event.getRightClicked().getUniqueId());
        if (graveId.isEmpty()) {
            return;
        }
        event.setCancelled(true);
        graves.open(event.getPlayer(), graveId.get());
    }
}
