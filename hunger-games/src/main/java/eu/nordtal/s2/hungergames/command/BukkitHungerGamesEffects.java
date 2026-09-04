package eu.nordtal.s2.hungergames.command;

import eu.nordtal.s2.commands.NordtalUser;
import eu.nordtal.s2.commands.hungergames.HungerGamesEffects;
import eu.nordtal.s2.hungergames.config.HungerGamesSpec;
import eu.nordtal.s2.hungergames.db.HungerGamesDao;
import eu.nordtal.s2.hungergames.db.RosterEntry;
import eu.nordtal.s2.hungergames.game.Demotion;
import eu.nordtal.s2.hungergames.lobby.Lobby;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * {@link HungerGamesEffects} against this server.
 *
 * <h2>Nothing here hops to the main thread, and that is the interesting part</h2>
 * Everything {@code /hg start} does before the world is touched is database work, and
 * {@code HungerGamesManager#start} reads the roster and writes the team colours before it hops to
 * the main thread <em>itself</em>. Hopping here would put all of that on the server thread at the
 * exact moment every participant is about to be teleported onto a tower - the same mistake, in the
 * same shape, as the join-time language lookup that froze this module's login path.
 *
 * <h2>Two instances, as everywhere</h2>
 * The chat one runs its work on the plugin's async scheduler; the inbox's runs it inline, because
 * the inbox settles a request row when the command returns.
 */
public final class BukkitHungerGamesEffects implements HungerGamesEffects {

    private final Plugin plugin;
    private final Executor executor;
    private final HungerGamesDao dao;
    private final HungerGamesSpec config;
    private final Lobby lobby;
    private final Supplier<UUID> currentGameId;
    private final Consumer<UUID> onStart;
    private final BooleanSupplier reloadSounds;
    private final Runnable reloadMessages;

    public BukkitHungerGamesEffects(final Plugin plugin, final Executor executor,
                                    final HungerGamesDao dao, final HungerGamesSpec config,
                                    final Lobby lobby, final Supplier<UUID> currentGameId,
                                    final Consumer<UUID> onStart,
                                    final BooleanSupplier reloadSounds,
                                    final Runnable reloadMessages) {
        this.plugin = plugin;
        this.executor = executor;
        this.dao = dao;
        this.config = config;
        this.lobby = lobby;
        this.currentGameId = currentGameId;
        this.onStart = onStart;
        this.reloadSounds = reloadSounds;
        this.reloadMessages = reloadMessages;
    }

    /** Everything {@code /hg} does off the main thread, on the plugin's async scheduler. */
    public static Executor async(final Plugin plugin) {
        return task -> Bukkit.getScheduler().runTaskAsynchronously(plugin, task);
    }

    @Override
    public void async(final Runnable work) {
        executor.execute(work);
    }

    @Override
    public void warn(final String what, final Throwable failure) {
        plugin.getLogger().log(java.util.logging.Level.WARNING, what, failure);
    }

    @Override
    public Optional<Registration> registration() {
        final UUID gameId = currentGameId.get();
        if (gameId == null) {
            return Optional.empty();
        }
        return dao.game(gameId).map(game -> new Registration(gameId, game.state().name(),
                // The RESOLVED count: Demotion turns a duo whose partner never showed into the
                // full-hearted solo it will actually become, and that is the number the border
                // arithmetic will divide by.
                Demotion.resolve(dao.roster(gameId)).size()));
    }

    @Override
    public void start(final UUID gameId) {
        // Straight through on this thread. onStart leads to HungerGamesManager#start, which reads
        // the roster and writes the team colours before it hops to the main thread itself; a
        // runTask here would have undone exactly that.
        onStart.accept(gameId);
    }

    @Override
    public boolean reloadSounds() {
        return reloadSounds.getAsBoolean();
    }

    @Override
    public boolean reloadMessages() {
        try {
            reloadMessages.run();
            return true;
        } catch (final RuntimeException failure) {
            plugin.getLogger().severe("the messages could not be reloaded, the running ones are "
                    + "unchanged: " + failure.getMessage());
            return false;
        }
    }

    @Override
    public List<TeamReady> readyStatus(final UUID gameId) {
        // A team is ready when every one of its members is - the same merge the lobby already does
        // for the "{ready}/{total} teams ready" line everybody can see.
        final Map<String, Boolean> byTeam = new LinkedHashMap<>();
        for (final RosterEntry entry : lobby.readyStatus(gameId)) {
            byTeam.merge(entry.teamName(), entry.ready(), (a, b) -> a && b);
        }
        return byTeam.entrySet().stream()
                .map(entry -> new TeamReady(entry.getKey(), entry.getValue()))
                .toList();
    }

    @Override
    public int softMinimumParticipants() {
        return config.softMinimumParticipants();
    }

    @Override
    public void recordStart(final NordtalUser who, final Registration game,
                            final boolean confirmedBelowMinimum) {
        plugin.getLogger().info("hunger-games game " + game.gameId() + " started by " + who.name()
                + " (" + who.origin() + ") with " + game.participants() + " resolvable participants"
                + (confirmedBelowMinimum ? " (confirmed below the soft minimum)" : ""));
    }
}
