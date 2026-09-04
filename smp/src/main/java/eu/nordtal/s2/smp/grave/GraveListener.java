package eu.nordtal.s2.smp.grave;

import eu.nordtal.s2.common.feedback.Feedback;
import eu.nordtal.s2.common.message.MessageRenderer;
import eu.nordtal.s2.common.message.Messages;
import eu.nordtal.s2.common.message.PlayerLocales;
import eu.nordtal.s2.smp.aura.AuraReason;
import eu.nordtal.s2.smp.aura.DeathPenalty;
import eu.nordtal.s2.smp.db.SmpDao;
import eu.nordtal.s2.smp.feedback.SmpSounds;
import eu.nordtal.s2.smp.player.Identities;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
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

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * What happens when somebody dies, and what happens when somebody opens what they left.
 *
 * <p>Two things at once, and they are separate rules that happen to share an event:
 *
 * <ul>
 *   <li><b>The inventory becomes a grave.</b> Keep-inventory is off and the drops are taken here
 *       instead, so nothing scatters and nothing burns.</li>
 *   <li><b>The death costs aura</b> - five ordinarily, twenty for a listed cause, and <b>nothing at
 *       all in the duel arena</b>, where the ±10 stake is the whole of what was at risk.</li>
 * </ul>
 *
 * <p>The arena exception is passed in as a predicate rather than looked up, so this listener does
 * not need to know that duels exist - which is also what lets the arena rule be tested by handing it
 * a lambda.
 */
public final class GraveListener implements Listener {

    private final Plugin plugin;
    private final SmpDao dao;
    private final Graves graves;
    private final Identities identities;
    private final DeathPenalty penalty;
    private final Predicate<Player> inArena;
    private final Messages messages;
    private final PlayerLocales locales;
    private final SmpSounds sounds;

    public GraveListener(final Plugin plugin, final SmpDao dao, final Graves graves,
                         final Identities identities, final DeathPenalty penalty,
                         final Predicate<Player> inArena, final Messages messages,
                         final PlayerLocales locales, final SmpSounds sounds) {
        this.plugin = plugin;
        this.dao = dao;
        this.graves = graves;
        this.identities = identities;
        this.penalty = penalty;
        this.inArena = inArena;
        this.messages = messages;
        this.locales = locales;
        this.sounds = sounds;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDeath(final PlayerDeathEvent event) {
        final Player player = event.getEntity();
        if (inArena.test(player)) {
            // The arena keeps its own inventory and its own consequences. The one place with no
            // grave, because nothing real was ever at stake.
            return;
        }

        final Optional<String> discordId = identities.discordIdOf(player.getUniqueId());
        final Location at = player.getLocation();
        final List<ItemStack> drops = List.copyOf(event.getDrops());
        final int experience = event.getDroppedExp();

        event.getDrops().clear();
        event.setDroppedExp(0);

        if (discordId.isEmpty()) {
            // No account to hang a grave off. Give the items straight back rather than destroying
            // them - somebody is already in a bad state and this must not make it worse.
            event.getDrops().addAll(drops);
            event.setDroppedExp(experience);
            return;
        }

        if (!drops.isEmpty() || experience > 0) {
            graves.create(discordId.get(), player.getUniqueId(), at,
                    drops.toArray(new ItemStack[0]), experience);
        }
        applyPenalty(player, discordId.get(), event);
    }

    private void applyPenalty(final Player player, final String discordId, final PlayerDeathEvent event) {
        final String cause = event.getDamageSource() == null ? null
                : event.getDamageSource().getDamageType().key().value();
        final int delta = penalty.deltaFor(cause, false);
        if (delta == 0) {
            return;
        }
        final AuraReason reason = penalty.reasonFor(cause);
        final Locale locale = locales.of(player.getUniqueId());

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            dao.addAura(discordId, delta, reason.stored(), cause);
            final Integer now = dao.auraOf(discordId).orElse(null);
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (now != null) {
                    identities.recordAura(player.getUniqueId(), now);
                }
                if (player.isOnline()) {
                    player.sendMessage(MessageRenderer.of(messages).format(locale, "smp.aura.death",
                            "aura", Math.abs(delta)));
                    sounds.play(player, Feedback.LOSS);
                }
            });
        });
    }

    /** Right-clicking a grave's invisible click surface opens it - for anybody, by design. */
    @EventHandler(ignoreCancelled = true)
    public void onInteract(final PlayerInteractEntityEvent event) {
        final Optional<UUID> graveId = graves.graveOfInteraction(event.getRightClicked().getUniqueId());
        if (graveId.isEmpty()) {
            return;
        }
        event.setCancelled(true);
        graves.open(event.getPlayer(), graveId.get());
    }

    @EventHandler
    public void onClose(final InventoryCloseEvent event) {
        if (event.getPlayer() instanceof Player player) {
            graves.onClosed(player, event.getInventory());
        }
    }
}
