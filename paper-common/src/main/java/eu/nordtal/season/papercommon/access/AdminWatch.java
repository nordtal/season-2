package eu.nordtal.season.papercommon.access;

import eu.nordtal.season.database.access.AccessReader;
import eu.nordtal.season.database.access.AdminOperators;
import eu.nordtal.season.database.notify.Channel;
import eu.nordtal.season.database.notify.SignalHub;
import eu.nordtal.season.papercommon.time.PaperScheduler;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.slf4j.Logger;

/**
 * Keeps a server's operators equal to {@code discord_user.admin}.
 * It reads on the hub's thread and applies on the main thread.
 */
public final class AdminWatch implements AutoCloseable {

    private final Plugin plugin;
    private final AccessReader access;
    private final AdminOperators operators;
    private final Logger logger;

    private volatile Set<UUID> known = Set.of();

    private volatile boolean running = true;

    public AdminWatch(
            final Plugin plugin, final AccessReader access, final AdminOperators operators, final Logger logger) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.access = Objects.requireNonNull(access, "access");
        this.operators = Objects.requireNonNull(operators, "operators");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    public void listen(final SignalHub signals) {
        signals.on(Channel.ADMIN, "the admin roster", this::refresh);
    }

    /** Returns whether the account was an admin at the last refresh. It is false until the first read. */
    public boolean isAdmin(final UUID mcUuid) {
        return known.contains(mcUuid);
    }

    /** Reads the admin set and applies it. Never call it on the main thread. */
    public void refresh() {
        if (!running) {
            return;
        }
        final Set<UUID> admins;
        try {
            admins = access.adminMinecraftAccounts();
        } catch (final RuntimeException failure) {
            // The operators who already hold the flag stay.
            logger.warn("Could not read the admin roster; operators are unchanged until the next signal", failure);
            return;
        }

        try {
            PaperScheduler.of(plugin).onMain(() -> apply(admins));
        } catch (final RejectedExecutionException shuttingDown) {
            // The next enable sweeps it.
            logger.debug("Dropped an admin refresh because the plugin is no longer enabled");
        }
    }

    private void apply(final Set<UUID> admins) {
        final Set<UUID> snapshot = Set.copyOf(admins);
        known = snapshot;
        final Set<UUID> online = new HashSet<>();
        for (final Player player : Bukkit.getOnlinePlayers()) {
            online.add(player.getUniqueId());
        }

        final Set<UUID> before = operators.held();
        operators.refresh(snapshot, online);
        final Set<UUID> after = operators.held();

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
