package eu.nordtal.s2.hungergames.listener;

import eu.nordtal.s2.common.feedback.Feedback;
import eu.nordtal.s2.hungergames.body.PlayerBodies;
import eu.nordtal.s2.hungergames.border.BorderController;
import eu.nordtal.s2.hungergames.db.HgMember;
import eu.nordtal.s2.hungergames.db.HungerGamesDao;
import eu.nordtal.s2.hungergames.db.RosterEntry;
import eu.nordtal.s2.hungergames.feedback.HungerGamesSounds;
import eu.nordtal.s2.hungergames.game.Ceremony;
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
    private final HungerGamesSounds sounds;

    /**
     * What to run once the game is decided: the outcome, and the winner's <b>Minecraft</b> uuid.
     *
     * <p>The second argument exists because {@code WinTracker.Outcome} names the winner by
     * {@code hg_member.id}, and the ceremony has to congratulate a {@code Player}. Resolving one to
     * the other is a query, the ceremony runs on the main thread, and this repository does not query
     * the database from there - so it is resolved here instead, on the async task that has just
     * finished doing exactly that kind of work, and travels with the outcome. {@code null} when the
     * game ended with no winner, or when the winner has no linked Minecraft account.
     */
    private final Consumer<Ceremony.Decision> onGameDecided;

    public CombatListener(final Plugin plugin, final HungerGamesDao dao, final GameState state,
                          final PlayerBodies bodies, final BorderController border, final WinTracker winTracker,
                          final HungerGamesSounds sounds,
                          final Consumer<Ceremony.Decision> onGameDecided) {
        this.plugin = plugin;
        this.dao = dao;
        this.state = state;
        this.bodies = bodies;
        this.border = border;
        this.winTracker = winTracker;
        this.sounds = sounds;
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

        // LOSS, here rather than in the async block, and that is the whole reason it is here: both
        // callers are main-thread event handlers, so this lands on the death itself instead of one
        // database round trip later. The player it belongs to may be an armor-stand body whose owner
        // is offline, which is why play(...) takes a null player - see HungerGamesSounds.
        sounds.play(plugin.getServer().getPlayer(victimMcUuid), Feedback.LOSS);

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            final Optional<RosterEntry> victimEntry = dao.rosterEntryByMcUuid(gameId, victimMcUuid);
            if (victimEntry.isEmpty()) {
                return;
            }
            final UUID killerMemberId = killerMcUuid == null ? null
                    : dao.rosterEntryByMcUuid(gameId, killerMcUuid).map(entry -> entry.memberId()).orElse(null);

            final Optional<WinTracker.Outcome> outcome =
                    winTracker.recordDeath(gameId, victimEntry.get().memberId(), killerMemberId);

            // Everything the ceremony needs, read here rather than there - see Ceremony.Decision
            // for what that used to cost. All of it happens at most once per game, and none of it
            // happens at all until there is a winner to announce.
            final Ceremony.Decision decision = outcome.map(decided -> {
                final UUID winnerMcUuid = decided.winnerMemberId() == null ? null
                        : dao.roster(gameId).stream()
                                .filter(entry -> decided.winnerMemberId().equals(entry.memberId()))
                                .map(RosterEntry::mcUuid)
                                // RosterEntry#mcUuid is null for a member who never linked, and
                                // Stream#findFirst throws on a null element rather than answering
                                // empty. Such a member can still be the last one standing, because
                                // WinTracker is reset from activeMembersOf and not from the
                                // resolved participants.
                                .filter(java.util.Objects::nonNull)
                                .findFirst().orElse(null);

                // The write goes here too, ahead of the ceremony rather than inside it. A game is
                // decided the moment WinTracker says so; the ceremony is what players see of that,
                // and if the server dies between the two the database is still right - which is the
                // direction that matters, because a game left un-DECIDED is the one the partial
                // unique index refuses to let a second game start beside.
                dao.decideGame(gameId, decided.winnerMemberId());

                return new Ceremony.Decision(decided, winnerMcUuid,
                        dao.activeMembersOf(gameId), dao.killCounts(gameId));
            }).orElse(null);

            Bukkit.getScheduler().runTask(plugin, () -> {
                border.onDeath(state);
                if (decision != null) {
                    onGameDecided.accept(decision);
                } else {
                    // SMALL_SUCCESS for the kill, and only in this branch. When the kill decided the
                    // game the ceremony's BIG_SUCCESS lands in the same tick, and two chimes on top
                    // of each other are one noise - the bigger of the two is the one to keep.
                    if (killerMcUuid != null) {
                        sounds.play(plugin.getServer().getPlayer(killerMcUuid), Feedback.SMALL_SUCCESS);
                    }
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
