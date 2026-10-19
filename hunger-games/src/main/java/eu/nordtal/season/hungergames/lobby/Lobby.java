package eu.nordtal.season.hungergames.lobby;

import eu.nordtal.season.common.id.DiscordId;
import eu.nordtal.season.common.time.Scheduler;
import eu.nordtal.season.hungergames.config.HungerGamesSpec;
import eu.nordtal.season.hungergames.game.GameDao;
import eu.nordtal.season.hungergames.roster.RosterDao;
import eu.nordtal.season.hungergames.roster.RosterEntry;
import eu.nordtal.season.messagerendering.MessageRenderer;
import eu.nordtal.season.messages.document.DocumentSpec;
import eu.nordtal.season.papercommon.player.Identities;
import eu.nordtal.season.papercommon.time.PaperScheduler;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.Nullable;

/**
 * Broadcasts the ready check to the lobby periodically. The broadcast has a ready button and the number of ready teams.
 */
public final class Lobby {

    record Broadcast(long ready, long total) {}

    /** The rules, the number of ready teams and the ready button. */
    static final DocumentSpec<Broadcast> BROADCAST =
            DocumentSpec.game("hunger-games", "lobby-broadcast", "Lobby", DocumentSpec.Shape.CHAT, Broadcast.class);

    private final Plugin plugin;
    private final GameDao dao;
    private final RosterDao rosters;
    private final HungerGamesSpec config;
    private final MessageRenderer renderer;
    private final Identities identities;

    private Scheduler.@Nullable Task broadcastTask;

    public Lobby(
            final Plugin plugin,
            final GameDao dao,
            final RosterDao rosters,
            final HungerGamesSpec config,
            final MessageRenderer renderer,
            final Identities identities) {
        this.plugin = plugin;
        this.dao = dao;
        this.rosters = rosters;
        this.config = config;
        this.renderer = renderer;
        this.identities = identities;
    }

    /** Starts the periodic broadcast. */
    public void startBroadcasting(final World world) {
        final Duration period = Duration.ofSeconds(config.lobby().broadcastIntervalSeconds());
        final PaperScheduler scheduler = PaperScheduler.of(plugin);
        broadcastTask = scheduler.every(
                period,
                period,
                () -> readyStatus().ifPresent(roster -> scheduler.onMain(() -> broadcast(world, roster))));
    }

    public void stop() {
        if (broadcastTask != null) {
            broadcastTask.cancel();
            broadcastTask = null;
        }
    }

    /** Sends the broadcast to the players in the world. It plays no sound. */
    private void broadcast(final World world, final List<RosterEntry> roster) {
        final long totalTeams =
                roster.stream().map(RosterEntry::teamId).distinct().count();
        final long readyTeams = roster.stream().collect(Collectors.groupingBy(RosterEntry::teamId)).values().stream()
                .filter(members -> members.stream().allMatch(RosterEntry::ready))
                .count();

        for (final Player player : world.getPlayers()) {
            final Locale locale = identities.languageOf(player.getUniqueId());
            player.sendMessage(renderer.format(locale, BROADCAST.with(new Broadcast(readyTeams, totalTeams))));
        }
    }

    /**
     * Marks the team of the player as ready in the open round. It blocks.
     *
     * @return whether the player is on a team of the open round
     */
    public boolean markReady(final DiscordId discordId) {
        return dao.openRound().map(round -> rosters.markReady(round, discordId)).orElse(false);
    }

    /** Returns the members of the open round and whether each is ready, or empty without an open round. It blocks. */
    public Optional<List<RosterEntry>> readyStatus() {
        return dao.openRound().map(rosters::roster);
    }
}
