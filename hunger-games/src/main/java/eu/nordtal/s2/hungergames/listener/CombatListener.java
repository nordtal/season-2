package eu.nordtal.s2.hungergames.listener;

import static eu.nordtal.s2.hungergames.HungerGamesMessages.MESSAGES;

import eu.nordtal.s2.common.Glyphs;
import eu.nordtal.s2.common.feedback.Feedback;
import eu.nordtal.s2.hungergames.GameState;
import eu.nordtal.s2.hungergames.body.PlayerBodies;
import eu.nordtal.s2.hungergames.border.BorderController;
import eu.nordtal.s2.hungergames.db.HgMember;
import eu.nordtal.s2.hungergames.db.HungerGamesDao;
import eu.nordtal.s2.hungergames.db.RosterEntry;
import eu.nordtal.s2.hungergames.feedback.HungerGamesSounds;
import eu.nordtal.s2.hungergames.game.Ceremony;
import eu.nordtal.s2.hungergames.game.WinTracker;
import eu.nordtal.s2.hungergames.player.ArenaComposition;
import eu.nordtal.s2.papercommon.chat.SystemLines;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
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
import org.jspecify.annotations.Nullable;

/**
 * PvP protection and death handling, for real players and disconnected ones' armor-stand bodies.
 *
 * Friendly fire needs no code: vanilla allows it with no scoreboard team in the way. Protection is
 * everyone from everyone, not a team mechanic: a per-player "protected until" timestamp in
 * {@link GameState}, cancelling damage when either side is still protected.
 */
public final class CombatListener implements Listener {

    private final Plugin plugin;
    private final HungerGamesDao dao;
    private final GameState state;
    private final PlayerBodies bodies;
    private final BorderController border;
    private final WinTracker winTracker;
    private final HungerGamesSounds sounds;

    /**
     * What to run once the game is decided.
     *
     * The winner's Minecraft uuid is resolved here, on the async task, because {@code Outcome} names
     * the winner by {@code hg_member.id} and the ceremony runs on the main thread, where this
     * repository does not query. {@code null} when there is no winner or the winner never linked an
     * account.
     */
    private final Consumer<Ceremony.Decision> onGameDecided;

    /**
     * The kill feed, for the one death vanilla does not announce.
     *
     * A body's marker dying is an {@code EntityDeathEvent}, which carries no death message, so
     * {@link SystemLines#onDeath} never sees it.
     */
    private final SystemLines systemLines;

    private final ArenaComposition composition;

    public CombatListener(
            final Plugin plugin,
            final HungerGamesDao dao,
            final GameState state,
            final PlayerBodies bodies,
            final BorderController border,
            final WinTracker winTracker,
            final HungerGamesSounds sounds,
            final SystemLines systemLines,
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
        final UUID killerUuid =
                participantUuid(resolveAttacker(event.getDamageSource().getCausingEntity()));
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
        final UUID killerUuid =
                participantUuid(resolveAttacker(event.getDamageSource().getCausingEntity()));
        bodies.removeByMarker(event.getEntity().getUniqueId());
        announceBodyDeath(event.getEntity(), owner, killerUuid);
        handleDeath(owner, killerUuid);
    }

    /**
     * The kill feed line for a body's death.
     *
     * The victim's name comes off the marker, not a {@code Player}: its owner is offline, which is
     * why a body is standing there. Two keys rather than one with a sometimes-empty slot - "fell to
     * the border" and "was killed by" are different sentences.
     */
    private void announceBodyDeath(final Entity marker, final UUID owner, final @Nullable UUID killerUuid) {
        final Component victim = composition.ofName(marker.getName(), owner);
        final Player killer = killerUuid == null ? null : plugin.getServer().getPlayer(killerUuid);
        if (killer == null) {
            systemLines.announce(MESSAGES.hg().death().body(Glyphs.ICON_DEATH, victim));
            return;
        }
        systemLines.announce(MESSAGES.hg().death().bodySection().by(Glyphs.ICON_DEATH, victim, composition.of(killer)));
    }

    private void handleDeath(final UUID victimMcUuid, final @Nullable UUID killerMcUuid) {
        // Every caller checks state.isRunning() first, and release() never runs before reset() sets gameId.
        final UUID gameId = Objects.requireNonNull(state.gameId());
        state.clearProtection(victimMcUuid);

        // LOSS here, not in the async block: the victim may be an offline body's owner, so play() tolerates null.
        sounds.play(plugin.getServer().getPlayer(victimMcUuid), Feedback.LOSS);

        Bukkit.getScheduler()
                .runTaskAsynchronously(plugin, () -> resolveDeathAsync(gameId, victimMcUuid, killerMcUuid));
    }

    /** The database and outcome work for one death, off the main thread; scheduled by {@link #handleDeath}. */
    private void resolveDeathAsync(final UUID gameId, final UUID victimMcUuid, final @Nullable UUID killerMcUuid) {
        final Optional<RosterEntry> victimEntry = dao.rosterEntryByMcUuid(gameId, victimMcUuid);
        if (victimEntry.isEmpty()) {
            return;
        }
        final UUID killerMemberId = killerMcUuid == null
                ? null
                : dao.rosterEntryByMcUuid(gameId, killerMcUuid)
                        .map(entry -> entry.memberId())
                        .orElse(null);

        final Optional<WinTracker.Outcome> outcome =
                winTracker.recordDeath(gameId, victimEntry.get().memberId(), killerMemberId);

        // Everything the ceremony needs, read here: at most once per game, and only once there is a result.
        final Ceremony.Decision decision =
                outcome.map(decided -> decisionFor(gameId, decided)).orElse(null);

        Bukkit.getScheduler().runTask(plugin, () -> {
            border.onDeath(state);
            if (decision != null) {
                onGameDecided.accept(decision);
            } else {
                // SMALL_SUCCESS only here: a kill that decided the game gets BIG_SUCCESS in the same tick.
                if (killerMcUuid != null) {
                    sounds.play(plugin.getServer().getPlayer(killerMcUuid), Feedback.SMALL_SUCCESS);
                }
                final List<HgMember> activeMembers = dao.activeMembersOf(gameId);
                winTracker.announceIfSameTeamFinalTwo(
                        plugin.getServer().getWorlds().get(0), activeMembers);
            }
        });
    }

    /** Decides {@code gameId} in the database and builds the ceremony's {@link Ceremony.Decision}. */
    private Ceremony.Decision decisionFor(final UUID gameId, final WinTracker.Outcome decided) {
        final UUID winnerMcUuid = decided.winnerMemberId() == null
                ? null
                : dao.roster(gameId).stream()
                        .filter(entry -> decided.winnerMemberId().equals(entry.memberId()))
                        .map(RosterEntry::mcUuid)
                        // findFirst throws on a null element, so a never-linked member is filtered out first.
                        .filter(java.util.Objects::nonNull)
                        .findFirst()
                        .orElse(null);

        // Written ahead of the ceremony: a game left un-DECIDED is what the partial unique index refuses beside it.
        dao.decideGame(gameId, decided.winnerMemberId());

        return new Ceremony.Decision(decided, winnerMcUuid, dao.activeMembersOf(gameId), dao.killCounts(gameId));
    }

    /** Follows a projectile back to whoever fired it, so an arrow kill still counts as a kill. */
    private Entity resolveAttacker(final Entity damager) {
        if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Entity shooter) {
            return shooter;
        }
        return damager;
    }

    private @Nullable UUID participantUuid(final Entity entity) {
        if (entity instanceof Player player) {
            return player.getUniqueId();
        }
        if (entity instanceof ArmorStand) {
            return bodies.ownerOf(entity.getUniqueId());
        }
        return null;
    }
}
