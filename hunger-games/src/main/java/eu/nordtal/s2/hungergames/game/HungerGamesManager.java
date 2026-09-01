package eu.nordtal.s2.hungergames.game;

import eu.nordtal.s2.common.message.Messages;
import eu.nordtal.s2.common.message.PlayerLocales;
import eu.nordtal.s2.hungergames.body.PlayerBodies;
import eu.nordtal.s2.hungergames.border.BorderController;
import eu.nordtal.s2.hungergames.border.BorderMath;
import eu.nordtal.s2.hungergames.color.TeamColours;
import eu.nordtal.s2.hungergames.config.HungerGamesSpec;
import eu.nordtal.s2.hungergames.db.HgMember;
import eu.nordtal.s2.hungergames.db.HungerGamesDao;
import eu.nordtal.s2.hungergames.db.RosterEntry;

import net.kyori.adventure.text.Component;

import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The start sequence, in one place: teleport to towers, freeze, countdown, release with PvP
 * protection - docs/hunger-games.md#start. Also owns the effective-participant/colour/demotion
 * work that must happen exactly once, at countdown time, before the border step is computed.
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

    /** Frozen players cannot move during the countdown - {@code FreezeListener} consults this. */
    private volatile boolean frozen;

    public HungerGamesManager(final Plugin plugin, final HungerGamesDao dao, final HungerGamesSpec config,
                              final Messages messages, final PlayerLocales locales, final PlayerBodies bodies,
                              final GameState state, final BorderController border) {
        this.plugin = plugin;
        this.dao = dao;
        this.config = config;
        this.messages = messages;
        this.locales = locales;
        this.bodies = bodies;
        this.state = state;
        this.border = border;
    }

    public boolean isFrozen() {
        return frozen;
    }

    public GameState state() {
        return state;
    }

    /**
     * Runs the whole start sequence: resolve the roster, demote incomplete duos, generate and
     * write colours, teleport everyone (or their body) onto a spawn tower, freeze, count down,
     * release with PvP protection.
     * <p>
     * Callers must already be off the main thread for the database reads/writes this does before
     * the world is touched; the actual teleports and the release callback are scheduled back onto
     * the main thread internally.
     * </p>
     *
     * @param gameId     the game being started
     * @param world      the event world
     * @param onReleased called once the countdown finishes and protection begins, on the main
     *                   thread - the caller flips {@code hg_game.state} to RUNNING's dependent
     *                   schedulers (loot, HUD) here, since starting those is this plugin's own
     *                   concern and not this class's
     */
    public void start(final UUID gameId, final World world, final Runnable onReleased) {
        final List<RosterEntry> roster = dao.roster(gameId);
        final List<Participant> participants = Demotion.resolve(roster);

        if (participants.isEmpty()) {
            LOGGER.warn("hunger-games start called with zero resolvable (linked) participants for game {}",
                    gameId);
            return;
        }

        // Colours are written before the world is touched: a restart between this point and
        // release must still repaint identically (docs/hunger-games.md#teams-colours-and-hearts).
        assignColours(participants);

        final double step = BorderMath.deathStep(
                config.borderStartDiameter(), config.borderEndDiameter(), participants.size());

        plugin.getServer().getScheduler().runTask(plugin, () -> {
            state.reset(gameId, participants.size(), step);
            dao.startGame(gameId, "COUNTDOWN");

            final Location centre = world.getSpawnLocation();
            final List<double[]> towerPositions = SpawnTowers.positions(
                    participants.size(), centre.getX(), centre.getZ(), config.spawnTowerRadius());
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
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                release(gameId, participants);
                onReleased.run();
            }, config.countdownSeconds() * 20L);
        });
    }

    /**
     * Tells every solo-by-demotion participant why they are standing on their tower alone. The
     * flag has been computed by {@link Demotion} and carried on {@link Participant} since the
     * module was built, and nothing ever read it - so a player whose partner never linked found
     * out by looking around.
     *
     * <p>Sent once, at the start of the countdown, rather than at release: it is the answer to a
     * question the player asks the moment they arrive, and by release they have stopped asking.
     * Only online participants are told; a body waiting for its owner has nobody to tell.</p>
     */
    private void announceDemotions(final List<Participant> participants) {
        for (final Participant participant : participants) {
            if (!participant.demotedToSolo()) {
                continue;
            }
            final Player online = plugin.getServer().getPlayer(participant.mcUuid());
            if (online != null) {
                online.sendMessage(Component.text(messages.format(locales.of(participant.mcUuid()),
                        "hg.team.demoted", "team", participant.teamName())));
            }
        }
    }

    /**
     * Schedules the countdown announcements at {@link Countdown#marks(int)}. One task per mark
     * rather than one repeating task: the marks are not evenly spaced, and a task that survives a
     * cancelled game is worse than eight that expire on their own. Bukkit cancels all of them when
     * the plugin disables.
     */
    private void scheduleCountdown(final List<Participant> participants) {
        final int total = config.countdownSeconds();
        for (final int remaining : Countdown.marks(total)) {
            final long delayTicks = (total - remaining) * 20L;
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                // The game can be over, or never have started, by the time a mark fires.
                if (!frozen) {
                    return;
                }
                for (final Participant participant : participants) {
                    final Player online = plugin.getServer().getPlayer(participant.mcUuid());
                    if (online != null) {
                        online.sendMessage(Component.text(messages.format(
                                locales.of(participant.mcUuid()), "hg.start.countdown",
                                "seconds", remaining)));
                    }
                }
            }, delayTicks);
        }
    }

    /**
     * Generates one palette entry per distinct team (not per player) and writes it, so a duo
     * shares its colour and a solo team gets one of its own - the palette size is
     * {@link Demotion#effectiveTeamCount(List)}, computed after demotion, exactly as
     * docs/hunger-games.md#the-border requires for the step arithmetic too.
     */
    private void assignColours(final List<Participant> participants) {
        final int teamCount = Demotion.effectiveTeamCount(participants);
        final List<Integer> palette = TeamColours.generatePalette(teamCount);

        // Deterministic walk: one palette entry per distinct team, in the order teams are first
        // seen in the (stable-ordered) participant list, so re-running this against the same
        // roster always produces the same assignment.
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
            online.teleportAsync(tower);
            online.setInvulnerable(true);
            return;
        }

        // "A player who was ready in the lobby and then disconnected is not dropped: their body is
        // teleported onto its tower at the start and waits there for its owner"
        // (docs/hunger-games.md#start) - the same shared mechanism as a mid-game disconnect; see
        // PlayerBodies' own documentation of exactly what is approximated and why. An offline
        // player has no live Player object to copy equipment from at this point (their gear is
        // only readable from stored NBT, which this plugin does not parse), so the body placed
        // here starts bare; a mid-session disconnect (handled by the quit listener, which still
        // has a live Player) copies gear correctly.
        LOGGER.info("Placing an unequipped body for offline participant on discord id {} on its "
                + "spawn tower - see PlayerBodies for what this approximates", participant.discordId());
        bodies.spawnBareArmorStand(tower, resolveDisplayName(participant), participant.mcUuid());
    }

    private String resolveDisplayName(final Participant participant) {
        final OfflinePlayer offline = plugin.getServer().getOfflinePlayer(participant.mcUuid());
        final String name = offline.getName();
        return name != null ? name : participant.discordId();
    }

    private void release(final UUID gameId, final List<Participant> participants) {
        frozen = false;
        dao.setGameState(gameId, "RUNNING");
        state.release();
        border.begin(gameId, state);

        final Instant protectedUntil = Instant.now().plusSeconds(config.pvpProtectionSeconds());
        for (final Participant participant : participants) {
            state.protect(participant.mcUuid(), protectedUntil);
            final Player online = plugin.getServer().getPlayer(participant.mcUuid());
            if (online != null) {
                online.setInvulnerable(false);
                online.sendMessage(Component.text(messages.format(locales.of(participant.mcUuid()),
                        "hg.start.released", "seconds", config.pvpProtectionSeconds())));
            }
        }
    }

    public Optional<HgMember> activeMemberByDiscordId(final UUID gameId, final String discordId) {
        return dao.activeMembersOf(gameId).stream().filter(member -> member.discordId().equals(discordId))
                .findFirst();
    }
}
