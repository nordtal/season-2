package eu.nordtal.s2.hungergames.lobby;

import static eu.nordtal.s2.hungergames.HungerGamesMessages.MESSAGES;

import eu.nordtal.s2.common.id.DiscordId;
import eu.nordtal.s2.common.time.Scheduler;
import eu.nordtal.s2.hungergames.config.HungerGamesSpec;
import eu.nordtal.s2.hungergames.db.HungerGamesDao;
import eu.nordtal.s2.hungergames.db.RosterEntry;
import eu.nordtal.s2.messagerendering.MessageRenderer;
import eu.nordtal.s2.messages.value.Action;
import eu.nordtal.s2.papercommon.player.Identities;
import eu.nordtal.s2.papercommon.time.PaperScheduler;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;
import net.kyori.adventure.text.Component;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.Nullable;

/**
 * The lobby's periodic ready-check broadcast, carrying a clickable "I am ready" and a live team count.
 *
 * Ready state starts nothing by itself; it informs the admin's decision.
 */
public final class Lobby {

    /** What the lobby broadcast's button runs. */
    private static final Action READY = new Action("/hg ready");

    private final Plugin plugin;
    private final HungerGamesDao dao;
    private final HungerGamesSpec config;
    private final MessageRenderer renderer;
    private final Identities identities;

    private Scheduler.@Nullable Task broadcastTask;

    public Lobby(
            final Plugin plugin,
            final HungerGamesDao dao,
            final HungerGamesSpec config,
            final MessageRenderer renderer,
            final Identities identities) {
        this.plugin = plugin;
        this.dao = dao;
        this.config = config;
        this.renderer = renderer;
        this.identities = identities;
    }

    /** Starts the periodic ready-check broadcast. Call once, from {@code onEnable}. */
    public void startBroadcasting(final World world) {
        final Duration period = Duration.ofSeconds(config.lobby().broadcastIntervalSeconds());
        // The roster is read off the main thread, and only the lines are sent from it.
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

    /** Deliberately silent: a chime on a repeating message makes people turn the sound off entirely. */
    private void broadcast(final World world, final List<RosterEntry> roster) {
        final long totalTeams =
                roster.stream().map(RosterEntry::teamId).distinct().count();
        final long readyTeams = roster.stream().collect(Collectors.groupingBy(RosterEntry::teamId)).values().stream()
                .filter(members -> members.stream().allMatch(RosterEntry::ready))
                .count();

        for (final Player player : world.getPlayers()) {
            final Locale locale = identities.languageOf(player.getUniqueId());
            final Component message =
                    renderer.format(locale, MESSAGES.hg().lobby().broadcast(readyTeams, totalTeams, READY));
            player.sendMessage(message);
        }
    }

    /**
     * Marks the calling player ready in the open round, for {@code /hg ready}; blocking.
     *
     * @return whether the player is on a team of the open round
     */
    public boolean markReady(final DiscordId discordId) {
        return dao.openRound().map(round -> dao.markReady(round, discordId)).orElse(false);
    }

    /** Returns every member of the open round and whether they are ready, or empty while no round is open; blocking. */
    public Optional<List<RosterEntry>> readyStatus() {
        return dao.openRound().map(dao::roster);
    }
}
