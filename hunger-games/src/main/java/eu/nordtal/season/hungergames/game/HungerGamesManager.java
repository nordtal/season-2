package eu.nordtal.season.hungergames.game;

import static eu.nordtal.season.hungergames.HungerGamesMessages.MESSAGES;

import eu.nordtal.season.common.time.CountdownPlan;
import eu.nordtal.season.hungergames.GameState;
import eu.nordtal.season.hungergames.body.PlayerBodies;
import eu.nordtal.season.hungergames.border.BorderController;
import eu.nordtal.season.hungergames.border.BorderMath;
import eu.nordtal.season.hungergames.color.TeamColours;
import eu.nordtal.season.hungergames.config.HungerGamesSpec;
import eu.nordtal.season.hungergames.db.HungerGamesDao;
import eu.nordtal.season.hungergames.feedback.HungerGamesSounds;
import eu.nordtal.season.messagerendering.MessageRenderer;
import eu.nordtal.season.messages.MessageRef;
import eu.nordtal.season.messages.context.PlayerContext;
import eu.nordtal.season.messages.context.TeamContext;
import eu.nordtal.season.messages.feedback.Feedback;
import eu.nordtal.season.papercommon.player.Identities;
import eu.nordtal.season.papercommon.time.PaperScheduler;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Location;
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

    /** Sparse far out, dense at the end, the whole time first. */
    private static final CountdownPlan COUNTDOWN =
            CountdownPlan.at(60, 30, 20, 10, 5, 4, 3, 2, 1).fromTheStart();

    private final Plugin plugin;
    private final HungerGamesDao dao;
    private final HungerGamesSpec config;
    private final MessageRenderer renderer;
    private final Identities identities;
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
            final MessageRenderer renderer,
            final Identities identities,
            final PlayerBodies bodies,
            final GameState state,
            final BorderController border,
            final HungerGamesSounds sounds,
            final Clock clock) {
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
        this.plugin = plugin;
        this.dao = dao;
        this.config = config;
        this.renderer = renderer;
        this.identities = identities;
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
     * Runs the whole start sequence of a game just created; callers must already be off the main thread.
     *
     * @param gameId the game being started
     * @param participants who plays, at least one
     * @param names what the participants are called, by Minecraft account, for the bodies of those offline
     * @param world the event world
     * @param onReleased called on the main thread once the countdown finishes and protection begins
     */
    public void start(
            final UUID gameId,
            final List<Participant> participants,
            final Map<UUID, PlayerContext> names,
            final World world,
            final Runnable onReleased) {
        // Colours are written before the world is touched, so a restart before release repaints identically.
        assignColours(participants);

        final double step =
                BorderMath.deathStep(config.borderStartDiameter(), config.borderEndDiameter(), participants.size());

        PaperScheduler.of(plugin).onMain(() -> {
            state.reset(gameId, participants.size(), step, clock.instant());

            final Location centre = world.getSpawnLocation();
            final List<double[]> towerPositions =
                    SpawnTowers.positions(participants.size(), centre.getX(), centre.getZ(), config.spawnTowerRadius());
            final double towerY = centre.getY() + config.spawnTowerHeight();

            for (int index = 0; index < participants.size(); index++) {
                final Participant participant = participants.get(index);
                final double[] position = towerPositions.get(index);
                final Location tower = new Location(world, position[0], towerY, position[1]);
                placeOnTower(participant, tower, names);
            }

            frozen = true;
            announceDemotions(participants);
            scheduleCountdown(participants);
            PaperScheduler.of(plugin).onMainAfter(Duration.ofSeconds(config.countdownSeconds()), () -> {
                release(gameId, participants);
                onReleased.run();
            });
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
                online.sendMessage(renderer.format(
                        identities.languageOf(participant.mcUuid()),
                        MESSAGES.hg().team().demoted(new TeamContext(participant.teamName()))));
            }
        }
    }

    /** Schedules the countdown announcements, one task per beat, since the beats are uneven. */
    private void scheduleCountdown(final List<Participant> participants) {
        final List<CountdownPlan.Beat<MessageRef>> beats = COUNTDOWN.beats(
                Duration.ofSeconds(config.countdownSeconds()), MESSAGES.hg().start()::countdown, null);
        for (final CountdownPlan.Beat<MessageRef> beat : beats) {
            PaperScheduler.of(plugin).onMainAfter(beat.delay(), () -> {
                // The game can be over, or never have started, by the time a beat fires.
                if (!frozen) {
                    return;
                }
                for (final Participant participant : participants) {
                    final Player online = plugin.getServer().getPlayer(participant.mcUuid());
                    if (online != null) {
                        online.sendMessage(renderer.format(identities.languageOf(participant.mcUuid()), beat.said()));
                        sounds.play(online, Feedback.COUNTDOWN_TICK);
                    }
                }
            });
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

    private void placeOnTower(
            final Participant participant, final Location tower, final Map<UUID, PlayerContext> names) {
        final Player online = plugin.getServer().getPlayer(participant.mcUuid());
        if (online != null) {
            // Not in the teleport callback, which would reorder the sequence; a failed teleport is logged.
            final var _ = online.teleportAsync(tower).thenAccept(moved -> {
                if (!moved) {
                    plugin.getLogger()
                            .severe(online.getName() + " could not be placed on their "
                                    + "spawn tower and is invulnerable wherever they are standing. The end "
                                    + "of the countdown releases them with everybody else.");
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
                "Placing an unequipped body for offline participant {} on its "
                        + "spawn tower - see PlayerBodies for what this approximates",
                participant.mcUuid());
        final PlayerContext named = names.get(participant.mcUuid());
        bodies.spawnBareArmorStand(tower, named == null ? "" : named.name().name(), participant.mcUuid());
    }

    private void release(final UUID gameId, final List<Participant> participants) {
        frozen = false;
        PaperScheduler.of(plugin).execute(() -> dao.release(gameId));
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
                online.sendMessage(renderer.format(
                        identities.languageOf(participant.mcUuid()),
                        MESSAGES.hg().start().released(config.pvpProtectionSeconds())));
                sounds.play(online, Feedback.COUNTDOWN_TICK);
            }
        }
    }
}
