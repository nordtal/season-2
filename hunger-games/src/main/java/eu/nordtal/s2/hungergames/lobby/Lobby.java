package eu.nordtal.s2.hungergames.lobby;

import eu.nordtal.s2.common.message.MessageRenderer;
import eu.nordtal.s2.common.message.Messages;
import eu.nordtal.s2.common.message.PlayerLocales;
import eu.nordtal.s2.hungergames.config.HungerGamesSpec;
import eu.nordtal.s2.hungergames.db.HungerGamesDao;
import eu.nordtal.s2.hungergames.db.RosterEntry;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The lobby's periodic ready-check broadcast - docs/hunger-games.md#the-lobby: "a periodic
 * broadcast - 'the game starts once everybody is ready' - carrying a clickable 'I have read the
 * rules and I am ready'. Ready state is visible to everyone; it informs the admin's decision and
 * does not start anything by itself."
 * <p>
 * <b>Ready-status visibility, documented here since the task brief leaves the choice open:</b> this
 * implementation does both - the periodic broadcast itself always includes a live "{ready}/{total}
 * teams ready" count (visible to everyone without asking), and {@code /hg ready-status} additionally
 * lists every team by name so a player can see exactly who is still missing, on demand rather than
 * flooding chat with it on every broadcast.
 * </p>
 */
public final class Lobby {

    private static final Logger LOGGER = LoggerFactory.getLogger(Lobby.class);

    private final Plugin plugin;
    private final HungerGamesDao dao;
    private final HungerGamesSpec config;
    private final Messages messages;
    private final PlayerLocales locales;

    private BukkitTask broadcastTask;

    public Lobby(final Plugin plugin, final HungerGamesDao dao, final HungerGamesSpec config,
                final Messages messages, final PlayerLocales locales) {
        this.plugin = plugin;
        this.dao = dao;
        this.config = config;
        this.messages = messages;
        this.locales = locales;
    }

    /** Starts the periodic ready-check broadcast. Call once, from {@code onEnable}. */
    public void startBroadcasting(final World world, final java.util.function.Supplier<UUID> currentGameId) {
        final long periodTicks = config.lobby().broadcastIntervalSeconds() * 20L;
        broadcastTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            final UUID gameId = currentGameId.get();
            if (gameId == null) {
                return;
            }
            broadcast(world, gameId);
        }, periodTicks, periodTicks);
    }

    public void stop() {
        if (broadcastTask != null) {
            broadcastTask.cancel();
            broadcastTask = null;
        }
    }

    /**
     * <b>Deliberately silent.</b> This is a standing reminder on a timer, not an event: it says the
     * same thing every {@code broadcast-interval-seconds} for as long as the lobby is open, and a
     * chime on a repeating message is the fastest way to make people turn the sound off - at which
     * point the countdown, the border and their own elimination go with it.
     */
    private void broadcast(final World world, final UUID gameId) {
        final List<RosterEntry> roster = dao.roster(gameId);
        final long totalTeams = roster.stream().map(RosterEntry::teamId).distinct().count();
        final long readyTeams = roster.stream()
                .collect(Collectors.groupingBy(RosterEntry::teamId))
                .values().stream()
                .filter(members -> members.stream().allMatch(RosterEntry::ready))
                .count();

        for (final Player player : world.getPlayers()) {
            final Locale locale = locales.of(player.getUniqueId());
            final Component link = MessageRenderer.of(messages).get(locale, "hg.lobby.ready-link")
                    .color(NamedTextColor.GREEN)
                    .clickEvent(ClickEvent.runCommand("/hg ready"));
            final Component message = MessageRenderer.of(messages).format(locale, "hg.lobby.broadcast",
                    "ready", readyTeams, "total", totalTeams).append(Component.text(" ")).append(link);
            player.sendMessage(message);
        }
    }

    /**
     * Marks the calling player's active membership as ready. See {@code /hg ready} in
     * {@code eu.nordtal.s2.hungergames.command.HungerGamesCommand}.
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
