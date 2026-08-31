package eu.nordtal.s2.hungergames.listener;

import eu.nordtal.s2.hungergames.body.PlayerBodies;
import eu.nordtal.s2.hungergames.border.BorderController;
import eu.nordtal.s2.hungergames.db.HgMember;
import eu.nordtal.s2.hungergames.db.HungerGamesDao;
import eu.nordtal.s2.hungergames.game.GameState;
import eu.nordtal.s2.hungergames.game.WinTracker;

import org.bukkit.Bukkit;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.plugin.Plugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * PvP protection, friendly fire (always on - no code needed, vanilla already allows player-vs-player
 * damage without a scoreboard team blocking it) and death handling, for both real players and the
 * armor-stand bodies standing in for disconnected ones - docs/hunger-games.md#winning and
 * #disconnects.
 * <p>
 * "PvP protection here is everyone protected from everyone, not a team mechanic" - implemented via
 * {@link GameState#isProtected(UUID, Instant)}, a tracked per-player "protected until" timestamp,
 * cancelling {@link EntityDamageByEntityEvent} when either the attacker or the victim is still
 * protected.
 * </p>
 */
public final class CombatListener implements Listener {

    private static final Logger LOGGER = LoggerFactory.getLogger(CombatListener.class);

    private final Plugin plugin;
    private final HungerGamesDao dao;
    private final GameState state;
    private final PlayerBodies bodies;
    private final BorderController border;
    private final WinTracker winTracker;
    private final Consumer<WinTracker.Outcome> onGameDecided;

    public CombatListener(final Plugin plugin, final HungerGamesDao dao, final GameState state,
                          final PlayerBodies bodies, final BorderController border, final WinTracker winTracker,
                          final Consumer<WinTracker.Outcome> onGameDecided) {
        this.plugin = plugin;
        this.dao = dao;
        this.state = state;
        this.bodies = bodies;
        this.border = border;
        this.winTracker = winTracker;
        this.onGameDecided = onGameDecided;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDamage(final EntityDamageByEntityEvent event) {
        if (!state.isRunning()) {
            return;
        }

        final UUID victimUuid = participantUuid(event.getEntity());
        final UUID attackerUuid = participantUuid(resolveAttacker(event.getDamager()));
        if (victimUuid == null) {
            return;
        }

        final Instant now = Instant.now();
        if (state.isProtected(victimUuid, now) || (attackerUuid != null && state.isProtected(attackerUuid, now))) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerDeath(final PlayerDeathEvent event) {
        if (!state.isRunning()) {
            return;
        }
        final Player victim = event.getEntity();
        final UUID killerUuid = participantUuid(resolveAttacker(event.getDamageSource().getCausingEntity()));
        handleDeath(victim.getUniqueId(), killerUuid);
    }

    /** A body's marker dying counts as its owner dying - docs/hunger-games.md#disconnects. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onMarkerDeath(final EntityDeathEvent event) {
        if (!(event.getEntity() instanceof ArmorStand)) {
            return;
        }
        final UUID owner = bodies.ownerOf(event.getEntity().getUniqueId());
        if (owner == null) {
            return;
        }
        final UUID killerUuid = participantUuid(resolveAttacker(event.getDamageSource().getCausingEntity()));
        bodies.removeByMarker(event.getEntity().getUniqueId());
        handleDeath(owner, killerUuid);
    }

    private void handleDeath(final UUID victimMcUuid, final UUID killerMcUuid) {
        final UUID gameId = state.gameId();
        state.clearProtection(victimMcUuid);

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            final Optional<eu.nordtal.s2.hungergames.db.RosterEntry> victimEntry =
                    dao.rosterEntryByMcUuid(gameId, victimMcUuid);
            if (victimEntry.isEmpty()) {
                return;
            }
            final UUID killerMemberId = killerMcUuid == null ? null
                    : dao.rosterEntryByMcUuid(gameId, killerMcUuid).map(entry -> entry.memberId()).orElse(null);

            final Optional<WinTracker.Outcome> outcome =
                    winTracker.recordDeath(gameId, victimEntry.get().memberId(), killerMemberId);

            Bukkit.getScheduler().runTask(plugin, () -> {
                border.onDeath(state);
                if (outcome.isPresent()) {
                    onGameDecided.accept(outcome.get());
                } else {
                    final List<HgMember> activeMembers = dao.activeMembersOf(gameId);
                    winTracker.announceIfSameTeamFinalTwo(
                            plugin.getServer().getWorlds().get(0), activeMembers);
                }
            });
        });
    }

    /** Follows a projectile back to whoever fired it, so an arrow kill still counts as a kill. */
    private Entity resolveAttacker(final Entity damager) {
        if (damager instanceof Projectile projectile
                && projectile.getShooter() instanceof Entity shooter) {
            return shooter;
        }
        return damager;
    }

    private UUID participantUuid(final Entity entity) {
        if (entity instanceof Player player) {
            return player.getUniqueId();
        }
        if (entity instanceof ArmorStand) {
            return bodies.ownerOf(entity.getUniqueId());
        }
        return null;
    }
}
