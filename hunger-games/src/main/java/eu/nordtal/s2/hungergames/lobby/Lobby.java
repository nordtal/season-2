package eu.nordtal.s2.hungergames.lobby;

import static eu.nordtal.s2.hungergames.HungerGamesMessages.MESSAGES;

import eu.nordtal.s2.common.message.MessageRenderer;
import eu.nordtal.s2.common.message.Messages;
import eu.nordtal.s2.common.message.PlayerLocales;
import eu.nordtal.s2.hungergames.config.HungerGamesSpec;
import eu.nordtal.s2.hungergames.db.HungerGamesDao;
import eu.nordtal.s2.hungergames.db.RosterEntry;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Collectors;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.jspecify.annotations.Nullable;

/**
 * The lobby's periodic ready-check broadcast, carrying a clickable "I am ready".
 *
 * Ready state is visible to everyone and starts nothing by itself - it informs the admin's
 * decision. The broadcast carries a live ready/total team count; {@code /hg ready-status} lists
 * the teams by name on demand, rather than flooding chat with them on every broadcast.
 */
public final class Lobby {

    private final Plugin plugin;
    private final HungerGamesDao dao;
    private final HungerGamesSpec config;
    private final Messages messages;
    private final PlayerLocales locales;

    private @Nullable BukkitTask broadcastTask;

    public Lobby(
            final Plugin plugin,
            final HungerGamesDao dao,
            final HungerGamesSpec config,
            final Messages messages,
            final PlayerLocales locales) {
        this.plugin = plugin;
        this.dao = dao;
        this.config = config;
        this.messages = messages;
        this.locales = locales;
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

    /**
     * Deliberately silent: a standing reminder on a timer, not an event.
     *
     * A chime on a repeating message makes people turn the sound off, taking the countdown and the
     * border with it.
     */
    private void broadcast(final World world, final UUID gameId) {
        final List<RosterEntry> roster = dao.roster(gameId);
        final long totalTeams =
                roster.stream().map(RosterEntry::teamId).distinct().count();
        final long readyTeams = roster.stream().collect(Collectors.groupingBy(RosterEntry::teamId)).values().stream()
                .filter(members -> members.stream().allMatch(RosterEntry::ready))
                .count();

        for (final Player player : world.getPlayers()) {
            final Locale locale = locales.of(player.getUniqueId());
            // No .color(): the colour is in the bundle, and a set one would win over it and never change.
            final Component link = MessageRenderer.of(messages)
                    .format(locale, MESSAGES.hg().lobby().readyLink())
                    .clickEvent(ClickEvent.runCommand("/hg ready"));
            // A slot, not an append: hg.lobby.broadcast ends in "{link}", and an append would print it literally.
            final Component message = MessageRenderer.of(messages)
                    .format(locale, MESSAGES.hg().lobby().broadcast(readyTeams, totalTeams, link));
            player.sendMessage(message);
        }
    }

    /**
     * Marks the calling player's active membership as ready.
     *
     * See {@code /hg ready} in {@code eu.nordtal.s2.hungergames.command.HungerGamesCommand}.
     *
     * @return whether a membership was found and updated
     */
    public boolean markReady(final UUID gameId, final String discordId) {
        return dao.setReady(gameId, discordId, true) > 0;
    }

    public List<RosterEntry> readyStatus(final UUID gameId) {
        return dao.roster(gameId);
    }
}
