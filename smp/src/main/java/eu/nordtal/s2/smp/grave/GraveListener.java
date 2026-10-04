package eu.nordtal.s2.smp.grave;

import static eu.nordtal.s2.smp.SmpMessages.MESSAGES;

import eu.nordtal.s2.common.id.DiscordId;
import eu.nordtal.s2.messagerendering.MessageRenderer;
import eu.nordtal.s2.messages.feedback.Feedback;
import eu.nordtal.s2.papercommon.player.Identities;
import eu.nordtal.s2.papercommon.time.PaperScheduler;
import eu.nordtal.s2.smp.aura.AuraReason;
import eu.nordtal.s2.smp.aura.DeathPenalty;
import eu.nordtal.s2.smp.db.SmpDao;
import eu.nordtal.s2.smp.feedback.SmpSounds;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

/**
 * What happens when somebody dies, and when somebody opens what they left.
 *
 * The inventory becomes a grave, and the death costs aura except in the duel arena, which comes in as a predicate.
 */
public final class GraveListener implements Listener {

    private final Plugin plugin;
    private final SmpDao dao;
    private final Graves graves;
    private final Identities identities;
    private final DeathPenalty penalty;
    private final Predicate<Player> inArena;
    private final MessageRenderer renderer;
    private final SmpSounds sounds;

    public GraveListener(
            final Plugin plugin,
            final SmpDao dao,
            final Graves graves,
            final Identities identities,
            final DeathPenalty penalty,
            final Predicate<Player> inArena,
            final MessageRenderer renderer,
            final SmpSounds sounds) {
        this.plugin = plugin;
        this.dao = dao;
        this.graves = graves;
        this.identities = identities;
        this.penalty = penalty;
        this.inArena = inArena;
        this.renderer = renderer;
        this.sounds = sounds;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDeath(final PlayerDeathEvent event) {
        final Player player = event.getEntity();
        if (inArena.test(player)) {
            // The arena keeps its own inventory and consequences.
            return;
        }

        final Optional<DiscordId> discordId = identities.discordIdOf(player.getUniqueId());
        final Location at = java.util.Objects.requireNonNull(player.getLocation());
        final List<ItemStack> drops = List.copyOf(event.getDrops());
        final int experience = event.getDroppedExp();

        event.getDrops().clear();
        event.setDroppedExp(0);

        if (discordId.isEmpty()) {
            // No account to hang a grave off; give the items straight back rather than destroy them.
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
            dao.addAura(discordId, delta, reason.stored(), cause);
            PaperScheduler.of(plugin).onMain(() -> {
                if (player.isOnline()) {
                    player.sendMessage(
                            renderer.format(locale, MESSAGES.smp().aura().death(Math.abs(delta))));
                    sounds.play(player, Feedback.LOSS);
                }
            });
        });
    }

    /** Right-clicking a grave's invisible click surface opens it, for anybody. */
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

    /**
     * A click inside a grave window.
     *
     * The content rows are free; the footer is furniture and one button, and a click on it is cancelled.
     */
    @EventHandler
    public void onClick(final org.bukkit.event.inventory.InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (graves.click(player, event.getInventory(), event.getRawSlot())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onClose(final InventoryCloseEvent event) {
        if (event.getPlayer() instanceof Player player) {
            graves.onClosed(player, event.getInventory());
        }
    }
}
