package eu.nordtal.s2.hungergames.game;

import static eu.nordtal.s2.hungergames.HungerGamesMessages.MESSAGES;

import eu.nordtal.s2.common.id.DiscordId;
import eu.nordtal.s2.hungergames.GameState;
import eu.nordtal.s2.hungergames.body.PlayerBodies;
import eu.nordtal.s2.hungergames.border.BorderController;
import eu.nordtal.s2.hungergames.border.BorderMath;
import eu.nordtal.s2.hungergames.color.TeamColours;
import eu.nordtal.s2.hungergames.config.HungerGamesSpec;
import eu.nordtal.s2.hungergames.db.HgMember;
import eu.nordtal.s2.hungergames.db.HungerGamesDao;
import eu.nordtal.s2.hungergames.db.RosterEntry;
import eu.nordtal.s2.hungergames.feedback.HungerGamesSounds;
import eu.nordtal.s2.messagerendering.MessageRenderer;
import eu.nordtal.s2.messages.Messages;
import eu.nordtal.s2.messages.PlayerLocales;
import eu.nordtal.s2.messages.context.TeamContext;
import eu.nordtal.s2.messages.feedback.Feedback;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The start sequence: towers, freeze, countdown, release with PvP protection.
 *
 * It also does the demotion and colour work that must happen once, before the border step is computed.
 */
public final class HungerGamesManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(HungerGamesManager.class);

    private final Plugin plugin;
    private final HungerGamesDao dao;
    private final HungerGamesSpec config;
    private final Messages messages;
    private final PlayerLocales locales;
    private final PlayerBodies bodies;
    private final GameState state;
    private final BorderController border;
    private final HungerGamesSounds sounds;

    /** Whether players are frozen for the countdown, which {@code FreezeListener} consults. */
    private volatile boolean frozen;

    private final Clock clock;

    public HungerGamesManager(
            final Plugin plugin,
            final HungerGamesDao dao,
            final HungerGamesSpec config,
            final Messages messages,
            final PlayerLocales locales,
            final PlayerBodies bodies,
            final GameState state,
            final BorderController border,
            final HungerGamesSounds sounds,
            final Clock clock) {
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
        this.plugin = plugin;
        this.dao = dao;
        this.config = config;
        this.messages = messages;
        this.locales = locales;
        this.bodies = bodies;
        this.state = state;
        this.border = border;
        this.sounds = sounds;
    }

    public boolean isFrozen() {
        return frozen;
    }

    public GameState state() {
        return state;
    }

    /**
     * Runs the whole start sequence; callers must already be off the main thread.
     *
     * @param gameId the game being started
     * @param world the event world
     * @param onReleased called on the main thread once the countdown finishes and protection begins
     */
    public void start(final UUID gameId, final World world, final Runnable onReleased) {
        final List<RosterEntry> roster = dao.roster(gameId);
        final List<Participant> participants = Demotion.resolve(roster);

        if (participants.isEmpty()) {
            LOGGER.warn("hunger-games start called with zero resolvable (linked) participants for game {}", gameId);
            return;
        }

        // Colours are written before the world is touched, so a restart before release repaints identically.
        assignColours(participants);

        final double step =
                BorderMath.deathStep(config.borderStartDiameter(), config.borderEndDiameter(), participants.size());

        plugin.getServer().getScheduler().runTask(plugin, () -> {
            state.reset(gameId, participants.size(), step, clock.instant());
            dao.startGame(gameId, "COUNTDOWN");

            final Location centre = world.getSpawnLocation();
            final List<double[]> towerPositions =
                    SpawnTowers.positions(participants.size(), centre.getX(), centre.getZ(), config.spawnTowerRadius());
            final double towerY = centre.getY() + config.spawnTowerHeight();

            for (int index = 0; index < participants.size(); index++) {
                final Participant participant = participants.get(index);
                final double[] position = towerPositions.get(index);
                final Location tower = new Location(world, position[0], towerY, position[1]);
                placeOnTower(participant, tower);
            }

            frozen = true;
            announceDemotions(participants);
            scheduleCountdown(participants);
            plugin.getServer()
                    .getScheduler()
                    .runTaskLater(
                            plugin,
                            () -> {
                                release(gameId, participants);
                                onReleased.run();
                            },
                            config.countdownSeconds() * 20L);
        });
    }

    /** Tells every online solo-by-demotion participant, at the start of the countdown, why they stand alone. */
    private void announceDemotions(final List<Participant> participants) {
        for (final Participant participant : participants) {
            if (!participant.demotedToSolo()) {
                continue;
            }
            final Player online = plugin.getServer().getPlayer(participant.mcUuid());
            if (online != null) {
                // Deliberately silent: the tower teleport in the same tick already played TRAVEL.
                online.sendMessage(MessageRenderer.of(messages)
                        .format(
                                locales.of(participant.mcUuid()),
                                MESSAGES.hg().team().demoted(new TeamContext(participant.teamName()))));
            }
        }
    }

    /** Schedules the countdown announcements, one task per mark, since the marks are uneven. */
    private void scheduleCountdown(final List<Participant> participants) {
        final int total = config.countdownSeconds();
        for (final int remaining : Countdown.marks(total)) {
            final long delayTicks = (total - remaining) * 20L;
            plugin.getServer()
                    .getScheduler()
                    .runTaskLater(
                            plugin,
                            () -> {
                                // The game can be over, or never have started, by the time a mark fires.
                                if (!frozen) {
                                    return;
                                }
                                for (final Participant participant : participants) {
                                    final Player online = plugin.getServer().getPlayer(participant.mcUuid());
                                    if (online != null) {
                                        online.sendMessage(MessageRenderer.of(messages)
                                                .format(
                                                        locales.of(participant.mcUuid()),
                                                        MESSAGES.hg().start().countdown(remaining)));
                                        sounds.play(online, Feedback.COUNTDOWN_TICK);
                                    }
                                }
                            },
                            delayTicks);
        }
    }

    /** One palette entry per distinct team, so a duo shares its colour, sized after demotion. */
    private void assignColours(final List<Participant> participants) {
        final int teamCount = Demotion.effectiveTeamCount(participants);
        final List<Integer> palette = TeamColours.generatePalette(teamCount);

        // Deterministic walk over the stable-ordered list: re-running against the same roster repeats it.
        final Map<UUID, Integer> assigned = new LinkedHashMap<>();
        int paletteIndex = 0;
        for (final Participant participant : participants) {
            if (!assigned.containsKey(participant.teamId())) {
                assigned.put(participant.teamId(), palette.get(paletteIndex));
                paletteIndex++;
            }
        }

        for (final Map.Entry<UUID, Integer> entry : assigned.entrySet()) {
            final int rgb = entry.getValue();
            final String named = TeamColours.nearestNamedColour(rgb);
            dao.setTeamColour(entry.getKey(), rgb, named);
        }
    }

    private void placeOnTower(final Participant participant, final Location tower) {
        final Player online = plugin.getServer().getPlayer(participant.mcUuid());
        if (online != null) {
            // Not in the teleport callback, which would reorder the sequence; a failed teleport is logged.
            final var _ = online.teleportAsync(tower).thenAccept(moved -> {
                if (!moved) {
                    plugin.getLogger()
                            .severe(online.getName() + " could not be placed on their "
                                    + "spawn tower and is invulnerable wherever they are standing. The "
                                    + "head start releases them with everybody else.");
                }
            });
            online.setInvulnerable(true);
            // mayfly only stops vanilla's floating kick while FreezeListener pins everyone; release() takes it away.
            online.setAllowFlight(true);
            sounds.play(online, Feedback.TRAVEL);
            return;
        }

        // Not dropped: a body with no live Player to copy equipment from waits bare on its tower.
        LOGGER.info(
                "Placing an unequipped body for offline participant on discord id {} on its "
                        + "spawn tower - see PlayerBodies for what this approximates",
                participant.discordId());
        bodies.spawnBareArmorStand(tower, resolveDisplayName(participant), participant.mcUuid());
    }

    private String resolveDisplayName(final Participant participant) {
        final OfflinePlayer offline = plugin.getServer().getOfflinePlayer(participant.mcUuid());
        final String name = offline.getName();
        return name != null ? name : participant.discordId().value();
    }

    private void release(final UUID gameId, final List<Participant> participants) {
        frozen = false;
        dao.setGameState(gameId, "RUNNING");
        state.release();
        border.begin(gameId, state);

        final Instant protectedUntil = clock.instant().plusSeconds(config.pvpProtectionSeconds());
        for (final Participant participant : participants) {
            state.protect(participant.mcUuid(), protectedUntil);
            final Player online = plugin.getServer().getPlayer(participant.mcUuid());
            if (online != null) {
                online.setInvulnerable(false);
                // setFlying(false) first: setAllowFlight(false) on someone actually flying drops them.
                online.setFlying(false);
                online.setAllowFlight(false);
                online.sendMessage(MessageRenderer.of(messages)
                        .format(
                                locales.of(participant.mcUuid()),
                                MESSAGES.hg().start().released(config.pvpProtectionSeconds())));
                sounds.play(online, Feedback.COUNTDOWN_TICK);
            }
        }
    }

    public Optional<HgMember> activeMemberByDiscordId(final UUID gameId, final DiscordId discordId) {
        return dao.activeMembersOf(gameId).stream()
                .filter(member -> member.discordId().equals(discordId))
                .findFirst();
    }
}
