package eu.nordtal.season.hungergames.game;

import eu.nordtal.season.common.time.CountdownPlan;
import eu.nordtal.season.hungergames.GameState;
import eu.nordtal.season.hungergames.body.PlayerBodies;
import eu.nordtal.season.hungergames.border.BorderController;
import eu.nordtal.season.hungergames.border.BorderMath;
import eu.nordtal.season.hungergames.color.TeamColours;
import eu.nordtal.season.hungergames.config.HungerGamesSpec;
import eu.nordtal.season.hungergames.feedback.HungerGamesSounds;
import eu.nordtal.season.hungergames.roster.RosterDao;
import eu.nordtal.season.messagerendering.MessageRenderer;
import eu.nordtal.season.messages.context.PlayerContext;
import eu.nordtal.season.messages.context.TeamContext;
import eu.nordtal.season.messages.document.DocumentSpec;
import eu.nordtal.season.messages.document.Sent;
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
 * Starts a game. It assigns the colours, builds the towers, freezes the players, runs the countdown and releases them.
 */
public final class HungerGamesManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(HungerGamesManager.class);

    /** The announcements of the countdown. */
    private static final CountdownPlan COUNTDOWN =
            CountdownPlan.at(60, 30, 20, 10, 5, 4, 3, 2, 1).fromTheStart();

    record Seconds(long seconds) {}

    record Demoted(TeamContext team) {}

    static final DocumentSpec<Seconds> COUNTDOWN_LINE = start("start-countdown", Seconds.class);
    static final DocumentSpec<Seconds> RELEASED = start("start-released", Seconds.class);
    static final DocumentSpec<Demoted> DEMOTED = start("team-demoted", Demoted.class);

    private static <V extends Record> DocumentSpec<V> start(final String name, final Class<V> values) {
        return DocumentSpec.game("hunger-games", name, "Start", DocumentSpec.Shape.CHAT, values);
    }

    private final Plugin plugin;
    private final GameDao dao;
    private final RosterDao rosters;
    private final HungerGamesSpec config;
    private final MessageRenderer renderer;
    private final Identities identities;
    private final PlayerBodies bodies;
    private final GameState state;
    private final BorderController border;
    private final HungerGamesSounds sounds;

    /** Whether the players are frozen. {@code FreezeListener} reads it. */
    private volatile boolean frozen;

    private final Clock clock;

    public HungerGamesManager(
            final Plugin plugin,
            final GameDao dao,
            final RosterDao rosters,
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
        this.rosters = rosters;
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
     * Starts the game. The caller runs it off the main thread.
     *
     * @param names the name of each participant by Minecraft UUID
     * @param onReleased runs on the main thread when the countdown ends and the PvP protection starts
     */
    public void start(
            final UUID gameId,
            final List<Participant> participants,
            final Map<UUID, PlayerContext> names,
            final World world,
            final Runnable onReleased) {
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

    /** Sends every online participant of a demoted team the demotion message. */
    private void announceDemotions(final List<Participant> participants) {
        for (final Participant participant : participants) {
            if (!participant.demotedToSolo()) {
                continue;
            }
            final Player online = plugin.getServer().getPlayer(participant.mcUuid());
            if (online != null) {
                online.sendMessage(renderer.format(
                        identities.languageOf(participant.mcUuid()),
                        DEMOTED.with(new Demoted(new TeamContext(participant.teamName())))));
            }
        }
    }

    /** Schedules one task for each countdown announcement. */
    private void scheduleCountdown(final List<Participant> participants) {
        final List<CountdownPlan.Beat<Sent<Seconds>>> beats = COUNTDOWN.beats(
                Duration.ofSeconds(config.countdownSeconds()),
                seconds -> COUNTDOWN_LINE.with(new Seconds(seconds)),
                null);
        for (final CountdownPlan.Beat<Sent<Seconds>> beat : beats) {
            PaperScheduler.of(plugin).onMainAfter(beat.delay(), () -> {
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

    /** Assigns each team one colour of the palette. */
    private void assignColours(final List<Participant> participants) {
        final int teamCount = Demotion.effectiveTeamCount(participants);
        final List<Integer> palette = TeamColours.generatePalette(teamCount);

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
            rosters.setTeamColour(entry.getKey(), rgb, named);
        }
    }

    private void placeOnTower(
            final Participant participant, final Location tower, final Map<UUID, PlayerContext> names) {
        final Player online = plugin.getServer().getPlayer(participant.mcUuid());
        if (online != null) {
            final var _ = online.teleportAsync(tower).thenAccept(moved -> {
                if (!moved) {
                    plugin.getLogger()
                            .severe(online.getName() + " could not be placed on their "
                                    + "spawn tower and is invulnerable wherever they are standing. The end "
                                    + "of the countdown releases them with everybody else.");
                }
            });
            online.setInvulnerable(true);
            online.setAllowFlight(true);
            sounds.play(online, Feedback.TRAVEL);
            return;
        }

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
                online.setFlying(false);
                online.setAllowFlight(false);
                online.sendMessage(renderer.format(
                        identities.languageOf(participant.mcUuid()),
                        RELEASED.with(new Seconds(config.pvpProtectionSeconds()))));
                sounds.play(online, Feedback.COUNTDOWN_TICK);
            }
        }
    }
}
