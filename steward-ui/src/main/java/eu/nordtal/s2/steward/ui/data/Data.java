package eu.nordtal.s2.steward.ui.data;

import eu.nordtal.jcore.persistence.sql.Database;
import eu.nordtal.jcore.persistence.sql.DatabaseConfig;
import eu.nordtal.s2.common.access.AccessDirectory;
import eu.nordtal.s2.common.audit.AuditDirectory;
import eu.nordtal.s2.common.metric.MetricDirectory;
import eu.nordtal.s2.common.roster.RosterDirectory;
import eu.nordtal.s2.common.phase.PhaseDirectory;
import eu.nordtal.s2.common.update.UpdateDirectory;
import eu.nordtal.s2.steward.ui.config.DatabaseSpec;
import org.jetbrains.annotations.NotNull;

import java.time.Duration;

/**
 * The database, opened once, read through the directories {@code :common} already owns.
 *
 * <h2>It never migrates</h2>
 * steward-worker owns the schema and is the only process that applies a migration. This one reads
 * and writes rows inside a schema somebody else put there - so a container that starts against an
 * older schema than its jar expects is a deployment in the middle of an update, and the answer is
 * to wait for the worker rather than to race it.
 *
 * <h2>Why the interface writes rows at all</h2>
 * Triggering an update or a backup is not a call to anybody: it is a row in {@code update_request},
 * the same row {@code /update} in Discord writes. That is what makes an update countable,
 * cancellable and counted down in front of every player - and it is why this service needs no
 * permission over containers in order to ask for one.
 */
public final class Data implements AutoCloseable {

    /** How far back a chart asks by default, when the caller names no window. */
    public static final Duration DEFAULT_WINDOW = Duration.ofHours(6);

    private final Database database;
    private final UpdateDirectory updates;
    private final MetricDirectory metrics;
    private final PhaseDirectory phase;
    private final RosterDirectory roster;
    private final AuditDirectory audit;
    private final AccessDirectory access;

    public Data(final @NotNull DatabaseSpec config) {
        this.database = Database.create(DatabaseConfig.builder(config.jdbcUrl())
                .username(config.username())
                .password(config.password())
                .poolName("steward-ui")
                .maximumPoolSize(config.maximumPoolSize())
                .build());
        this.updates = UpdateDirectory.using(database.dataSource());
        this.metrics = MetricDirectory.using(database.dataSource());
        this.phase = PhaseDirectory.using(database.dataSource());
        this.roster = RosterDirectory.using(database.dataSource());
        this.audit = AuditDirectory.using(database.dataSource());
        // Borrowing, not owning: `using` hands back a directory over this pool, so closing the pool
        // below is the only close there is. `AccessDirectory.open` would build a second pool.
        this.access = AccessDirectory.using(database.dataSource());
    }

    public @NotNull UpdateDirectory updates() {
        return updates;
    }

    public @NotNull MetricDirectory metrics() {
        return metrics;
    }

    public @NotNull PhaseDirectory phase() {
        return phase;
    }

    public @NotNull RosterDirectory roster() {
        return roster;
    }

    public @NotNull AuditDirectory audit() {
        return audit;
    }

    /**
     * Granting and revoking access.
     *
     * <p>The only writing this interface does that is not a row in {@code update_request}. Till
     * decided on 2026-09-13 that the interface may grant and revoke, so there are now two ways to
     * the same state - here and {@code /access} in Discord. The trade is accepted and its cost is
     * paid in the journal: every grant and every revocation from here writes an {@code audit_log}
     * row naming the admin who clicked, because two doors into one room need a visitors' book.</p>
     */
    public @NotNull AccessDirectory access() {
        return access;
    }

    @Override
    public void close() {
        database.close();
    }
}
