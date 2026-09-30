package eu.nordtal.s2.papercommon.access;

import eu.nordtal.s2.database.access.AccessReader;
import eu.nordtal.s2.database.access.AdminOperators;
import eu.nordtal.s2.database.notify.Channel;
import eu.nordtal.s2.database.notify.SignalHub;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.plugin.Plugin;
import org.slf4j.Logger;

/**
 * Keeps a Paper server's operators in step with {@code discord_user.admin} while people are online.
 *
 * It re-reads on the process's {@link SignalHub}; reads run on the hub's thread, the apply on the main thread.
 */
public final class AdminWatch implements AutoCloseable {

    private final Plugin plugin;
    private final AccessReader access;
    private final AdminOperators operators;
    private final Consumer<Set<UUID>> also;
    private final Logger logger;

    /** The admin set as of the last refresh, read by Brigadier's {@code requires} on the main thread. */
    private volatile Set<UUID> known = Set.of();

    private volatile boolean running = true;

    /**
     * @param plugin    the owning plugin, for the scheduler
     * @param access    where the admin set is read from
     * @param operators what grants and removes operator
     * @param also      anything else this plugin caches about admins, applied on the main thread; {@code set -> { }}
     *     when none
     * @param logger    the plugin logger
     */
    public AdminWatch(
            final Plugin plugin,
            final AccessReader access,
            final AdminOperators operators,
            final Consumer<Set<UUID>> also,
            final Logger logger) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.access = Objects.requireNonNull(access, "access");
        this.operators = Objects.requireNonNull(operators, "operators");
        this.also = Objects.requireNonNull(also, "also");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    /** Re-reads the admin set on every signal of {@code signals}; the hub's connect is the first read. */
    public void listen(final SignalHub signals) {
        signals.on(Channel.ADMIN, "the admin roster", this::refresh);
    }

    /**
     * Returns whether this account was an admin as of the last refresh.
     *
     * A set lookup; it answers {@code false} until the first read, so an unreachable database hands out no admin.
     */
    public boolean isAdmin(final UUID mcUuid) {
        return known.contains(mcUuid);
    }

    /** Reads the admin set and applies it; never call this on the main thread. */
    public void refresh() {
        if (!running) {
            return;
        }
        final Set<UUID> admins;
        try {
            admins = access.adminMinecraftAccounts();
        } catch (final RuntimeException failure) {
            // Not fatal: an unreachable database must not cost operators who already hold their flag.
            logger.warn("Could not read the admin roster; operators are unchanged until the next signal", failure);
            return;
        }

        try {
            Bukkit.getScheduler().runTask(plugin, () -> apply(admins));
        } catch (final IllegalPluginAccessException shuttingDown) {
            // The plugin was disabled between the read and the hop; the next enable sweep fixes it.
            logger.debug("Dropped an admin refresh because the plugin is no longer enabled");
        }
    }

    /** Applies the set on the main thread: who is online, who of them is an admin, and what changes. */
    private void apply(final Set<UUID> admins) {
        // Copied once: `known` is what isAdmin, and therefore Brigadier's requires, answers from.
        final Set<UUID> snapshot = Set.copyOf(admins);
        known = snapshot;
        final Set<UUID> online = new HashSet<>();
        for (final Player player : Bukkit.getOnlinePlayers()) {
            online.add(player.getUniqueId());
        }

        final Set<UUID> before = operators.held();
        operators.refresh(snapshot, online);
        final Set<UUID> after = operators.held();

        also.accept(snapshot);

        if (!before.equals(after)) {
            final Set<UUID> gained = new HashSet<>(after);
            gained.removeAll(before);
            final Set<UUID> lost = new HashSet<>(before);
            lost.removeAll(after);
            logger.info("The admin roster changed: {} gained operator, {} lost it", gained.size(), lost.size());
        }
    }

    @Override
    public void close() {
        running = false;
    }
}
