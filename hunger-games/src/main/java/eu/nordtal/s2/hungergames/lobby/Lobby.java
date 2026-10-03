package eu.nordtal.s2.hungergames.lobby;

import static eu.nordtal.s2.hungergames.HungerGamesMessages.MESSAGES;

import eu.nordtal.s2.common.id.DiscordId;
import eu.nordtal.s2.hungergames.config.HungerGamesSpec;
import eu.nordtal.s2.hungergames.db.HungerGamesDao;
import eu.nordtal.s2.hungergames.db.RosterEntry;
import eu.nordtal.s2.messagerendering.MessageRenderer;
import eu.nordtal.s2.messages.Messages;
import eu.nordtal.s2.messages.value.Action;
import eu.nordtal.s2.papercommon.player.Identities;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Collectors;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
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
    private final Messages messages;
    private final Identities identities;

    private @Nullable BukkitTask broadcastTask;

    public Lobby(
            final Plugin plugin,
            final HungerGamesDao dao,
            final HungerGamesSpec config,
            final Messages messages,
            final Identities identities) {
        this.plugin = plugin;
        this.dao = dao;
        this.config = config;
        this.messages = messages;
        this.identities = identities;
    }

    /** Starts the periodic ready-check broadcast. Call once, from {@code onEnable}. */
    public void startBroadcasting(final World world, final java.util.function.Supplier<UUID> currentGameId) {
        final long periodTicks = config.lobby().broadcastIntervalSeconds() * 20L;
        broadcastTask = Bukkit.getScheduler()
                .runTaskTimer(
                        plugin,
                        () -> {
                            final UUID gameId = currentGameId.get();
                            if (gameId == null) {
                                return;
                            }
                            broadcast(world, gameId);
                        },
                        periodTicks,
                        periodTicks);
    }

    public void stop() {
        if (broadcastTask != null) {
            broadcastTask.cancel();
            broadcastTask = null;
        }
    }

    /** Deliberately silent: a chime on a repeating message makes people turn the sound off entirely. */
    private void broadcast(final World world, final UUID gameId) {
        final List<RosterEntry> roster = dao.roster(gameId);
        final long totalTeams =
                roster.stream().map(RosterEntry::teamId).distinct().count();
        final long readyTeams = roster.stream().collect(Collectors.groupingBy(RosterEntry::teamId)).values().stream()
                .filter(members -> members.stream().allMatch(RosterEntry::ready))
                .count();

        for (final Player player : world.getPlayers()) {
            final Locale locale = identities.languageOf(player.getUniqueId());
            final Component message = MessageRenderer.of(messages)
                    .format(locale, MESSAGES.hg().lobby().broadcast(readyTeams, totalTeams, READY));
            player.sendMessage(message);
        }
    }

    /**
     * Marks the calling player's active membership as ready, for {@code /hg ready}.
     *
     * @return whether a membership was found and updated
     */
    public boolean markReady(final UUID gameId, final DiscordId discordId) {
        return dao.setReady(gameId, discordId, true) > 0;
    }

    public List<RosterEntry> readyStatus(final UUID gameId) {
        return dao.roster(gameId);
    }
}
