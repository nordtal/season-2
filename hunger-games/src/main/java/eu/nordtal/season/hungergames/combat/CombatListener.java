package eu.nordtal.season.hungergames.combat;

import eu.nordtal.season.common.id.PlayerId;
import eu.nordtal.season.hungergames.GameState;
import eu.nordtal.season.hungergames.body.PlayerBodies;
import eu.nordtal.season.hungergames.border.BorderController;
import eu.nordtal.season.hungergames.feedback.HungerGamesSounds;
import eu.nordtal.season.hungergames.game.Ceremony;
import eu.nordtal.season.hungergames.game.GameDao;
import eu.nordtal.season.hungergames.game.Names;
import eu.nordtal.season.hungergames.game.WinTracker;
import eu.nordtal.season.hungergames.roster.RosterDao;
import eu.nordtal.season.hungergames.roster.RosterEntry;
import eu.nordtal.season.messages.context.PlayerContext;
import eu.nordtal.season.messages.document.DocumentSpec;
import eu.nordtal.season.messages.feedback.Feedback;
import eu.nordtal.season.papercommon.chat.SystemLines;
import eu.nordtal.season.papercommon.time.PaperScheduler;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
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

/** Handles damage and deaths of players and of the armour stands of disconnected players. */
public final class CombatListener implements Listener {

    record BodyDeath(PlayerContext player, @Nullable PlayerContext killer, boolean killed) {}

    /** A stand's death; a player's own death is paper-common's line. */
    static final DocumentSpec<BodyDeath> BODY_DEATH =
            DocumentSpec.game("hunger-games", "death-body", "Game", DocumentSpec.Shape.CHAT, BodyDeath.class);

    private final Plugin plugin;
    private final GameDao dao;
    private final RosterDao rosters;
    private final GameState state;
    private final PlayerBodies bodies;
    private final BorderController border;
    private final WinTracker winTracker;
    private final HungerGamesSounds sounds;
    private final Names names;

    /** Runs when a death decides the game. */
    private final Consumer<Ceremony.Decision> onGameDecided;

    private final SystemLines systemLines;

    private final Clock clock;

    public CombatListener(
            final Plugin plugin,
            final GameDao dao,
            final RosterDao rosters,
            final GameState state,
            final PlayerBodies bodies,
            final BorderController border,
            final WinTracker winTracker,
            final HungerGamesSounds sounds,
            final Names names,
            final SystemLines systemLines,
            final Consumer<Ceremony.Decision> onGameDecided,
            final Clock clock) {
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
        this.plugin = plugin;
        this.dao = dao;
        this.rosters = rosters;
        this.state = state;
        this.bodies = bodies;
        this.border = border;
        this.winTracker = winTracker;
        this.sounds = sounds;
        this.names = names;
        this.systemLines = systemLines;
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

        final Instant now = clock.instant();
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

    /** Announces the death of a stand. */
    private void announceBodyDeath(final Entity marker, final UUID owner, final @Nullable UUID killerUuid) {
        final PlayerContext victim = PlayerContext.of(PlayerId.of(owner), marker.getName());
        final Player killer = killerUuid == null ? null : plugin.getServer().getPlayer(killerUuid);
        systemLines.announce(BODY_DEATH.with(new BodyDeath(
                victim,
                killer == null ? null : PlayerContext.of(PlayerId.of(killer.getUniqueId()), killer.getName()),
                killer != null)));
    }

    private void handleDeath(final UUID victimMcUuid, final @Nullable UUID killerMcUuid) {
        final UUID gameId = Objects.requireNonNull(state.gameId());
        state.clearProtection(victimMcUuid);

        sounds.play(plugin.getServer().getPlayer(victimMcUuid), Feedback.LOSS);

        PaperScheduler.of(plugin).execute(() -> resolveDeathAsync(gameId, victimMcUuid, killerMcUuid));
    }

    /** Stores the death and decides the game. It runs off the main thread. */
    private void resolveDeathAsync(final UUID gameId, final UUID victimMcUuid, final @Nullable UUID killerMcUuid) {
        final Optional<RosterEntry> victimEntry = rosters.rosterEntryByMcUuid(gameId, victimMcUuid);
        if (victimEntry.isEmpty()) {
            return;
        }
        final UUID killerMemberId = killerMcUuid == null
                ? null
                : rosters.rosterEntryByMcUuid(gameId, killerMcUuid)
                        .map(entry -> entry.memberId())
                        .orElse(null);

        final Optional<WinTracker.Outcome> outcome =
                winTracker.recordDeath(gameId, victimEntry.get().memberId(), killerMemberId);

        final Ceremony.Decision decision =
                outcome.map(decided -> decisionFor(gameId, decided)).orElse(null);

        PaperScheduler.of(plugin).onMain(() -> {
            border.onDeath(state);
            if (decision != null) {
                onGameDecided.accept(decision);
            } else {
                if (killerMcUuid != null) {
                    sounds.play(plugin.getServer().getPlayer(killerMcUuid), Feedback.SMALL_SUCCESS);
                }
                winTracker.announceIfSameTeamFinalTwo(
                        plugin.getServer().getWorlds().get(0));
            }
        });
    }

    private Ceremony.Decision decisionFor(final UUID gameId, final WinTracker.Outcome decided) {
        final List<RosterEntry> roster = rosters.gameRoster(gameId);
        final UUID winnerMcUuid = decided.winnerMemberId() == null
                ? null
                : roster.stream()
                        .filter(entry -> decided.winnerMemberId().equals(entry.memberId()))
                        .map(RosterEntry::mcUuid)
                        .filter(java.util.Objects::nonNull)
                        .findFirst()
                        .orElse(null);

        dao.decideGame(gameId, decided.winnerMemberId());

        final Map<UUID, PlayerContext> byAccount = names.of(roster.stream()
                .map(RosterEntry::mcUuid)
                .filter(java.util.Objects::nonNull)
                .toList());
        final Map<UUID, PlayerContext> byMember = new LinkedHashMap<>();
        for (final RosterEntry entry : roster) {
            final PlayerContext named = entry.mcUuid() == null ? null : byAccount.get(entry.mcUuid());
            if (named != null) {
                byMember.put(entry.memberId(), named);
            }
        }
        return new Ceremony.Decision(decided, winnerMcUuid, dao.killCounts(gameId), byMember);
    }

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
