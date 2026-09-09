package eu.nordtal.s2.hungergames.listener;

import net.kyori.adventure.text.Component;
import eu.nordtal.s2.papercommon.chat.SystemLines;
import eu.nordtal.s2.common.Glyphs;
import eu.nordtal.s2.common.feedback.Feedback;
import eu.nordtal.s2.hungergames.player.ArenaComposition;
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
import java.util.Map;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * PvP protection and death handling, for both real players and the armor-stand bodies standing in
 * for disconnected ones. Friendly fire needs no code: vanilla allows it with no scoreboard team in
 * the way.
 *
 * <p>Protection is everyone from everyone, not a team mechanic: a per-player "protected until"
 * timestamp in {@link GameState}, cancelling damage when either side is still protected.</p>
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
     * What to run once the game is decided. The winner's Minecraft uuid is resolved here, on the
     * async task, because {@code Outcome} names the winner by {@code hg_member.id} and the ceremony
     * runs on the main thread, where this repository does not query. {@code null} when there is no
     * winner or the winner never linked an account.
     */
    private final Consumer<Ceremony.Decision> onGameDecided;

    /**
     * The kill feed, for the one death vanilla does not announce: a body's marker dying is an
     * {@code EntityDeathEvent}, which carries no death message, so {@link SystemLines#onDeath}
     * never sees it.
     */
    private final SystemLines systemLines;

    private final ArenaComposition composition;

    public CombatListener(final Plugin plugin, final HungerGamesDao dao, final GameState state,
                          final PlayerBodies bodies, final BorderController border, final WinTracker winTracker,
                          final HungerGamesSounds sounds, final SystemLines systemLines,
                          final ArenaComposition composition,
                          final Consumer<Ceremony.Decision> onGameDecided) {
        this.plugin = plugin;
        this.dao = dao;
        this.state = state;
        this.bodies = bodies;
        this.border = border;
        this.winTracker = winTracker;
        this.sounds = sounds;
        this.systemLines = systemLines;
        this.composition = composition;
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

    /** A body's marker dying counts as its owner dying. */
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
        announceBodyDeath(event.getEntity(), owner, killerUuid);
        handleDeath(owner, killerUuid);
    }

    /**
     * The kill feed line for a body's death. The victim's name comes off the marker, not a
     * {@code Player}: its owner is offline, which is why a body is standing there. Two keys rather
     * than one with a sometimes-empty slot - "fell to the border" and "was killed by" are different
     * sentences.
     */
    private void announceBodyDeath(final Entity marker, final UUID owner, final UUID killerUuid) {
        final Component victim = composition.ofName(marker.getName(), owner);
        final Player killer = killerUuid == null ? null : plugin.getServer().getPlayer(killerUuid);
        if (killer == null) {
            systemLines.announce("hg.death.body", Glyphs.ICON_DEATH, Map.of("_player", victim));
            return;
        }
        systemLines.announce("hg.death.body.by", Glyphs.ICON_DEATH,
                Map.of("_player", victim, "_killer", composition.of(killer)));
    }

    private void handleDeath(final UUID victimMcUuid, final UUID killerMcUuid) {
        final UUID gameId = state.gameId();
        state.clearProtection(victimMcUuid);

        // LOSS here rather than in the async block: both callers are main-thread event handlers,
        // so it lands on the death itself instead of a database round trip later. The victim may be
        // a body whose owner is offline, which is why play(...) tolerates a null player.
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

            // Everything the ceremony needs, read here rather than on the main thread. At most
            // once per game, and not at all until there is a result to announce.
            final Ceremony.Decision decision = outcome.map(decided -> {
                final UUID winnerMcUuid = decided.winnerMemberId() == null ? null
                        : dao.roster(gameId).stream()
                                .filter(entry -> decided.winnerMemberId().equals(entry.memberId()))
                                .map(RosterEntry::mcUuid)
                                // mcUuid is null for a member who never linked, and findFirst
                                // throws on a null element rather than answering empty. Such a
                                // member can still be the last one standing.
                                .filter(java.util.Objects::nonNull)
                                .findFirst().orElse(null);

                // Written ahead of the ceremony, not inside it: if the server dies between the
                // two the database is still right. A game left un-DECIDED is the one the partial
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
                    // SMALL_SUCCESS only in this branch: a kill that decided the game gets the
                    // ceremony's BIG_SUCCESS in the same tick, and two chimes are one noise.
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
