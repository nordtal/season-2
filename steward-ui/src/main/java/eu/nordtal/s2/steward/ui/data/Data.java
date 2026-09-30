package eu.nordtal.s2.steward.ui.data;

import eu.nordtal.jcore.persistence.sql.Database;
import eu.nordtal.jcore.persistence.sql.DatabaseConfig;
import eu.nordtal.s2.database.access.AccessDirectory;
import eu.nordtal.s2.database.access.AccessRequests;
import eu.nordtal.s2.database.access.RosterDirectory;
import eu.nordtal.s2.database.audit.AuditDirectory;
import eu.nordtal.s2.database.command.CommandRequests;
import eu.nordtal.s2.database.metric.MetricDirectory;
import eu.nordtal.s2.database.phase.PhaseDirectory;
import eu.nordtal.s2.database.update.UpdateDirectory;
import eu.nordtal.s2.steward.ui.config.DatabaseSpec;
import java.time.Duration;

/** The database, opened once and read through the directories {@code :common} owns; it never migrates. */
public final class Data implements AutoCloseable {

    /** How far back a chart looks when the caller names no window. */
    public static final Duration DEFAULT_WINDOW = Duration.ofHours(6);

    private final Database database;
    private final UpdateDirectory updates;
    private final MetricDirectory metrics;
    private final PhaseDirectory phase;
    private final RosterDirectory roster;
    private final AuditDirectory audit;
    private final AccessDirectory access;
    private final CommandRequests commands;
    private final AccessRequests accessRequests;

    public Data(final DatabaseSpec config) {
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
        // Borrowing, not owning: closing the pool below is the only close there is.
        this.access = AccessDirectory.using(database.dataSource());
        this.commands = CommandRequests.borrowing(database.dataSource());
        this.accessRequests = AccessRequests.on(database.dataSource());
    }

    /** The pool itself, for {@code steward_session}, the one table this service owns rather than borrows. */
    public javax.sql.DataSource dataSource() {
        return database.dataSource();
    }

    public UpdateDirectory updates() {
        return updates;
    }

    public MetricDirectory metrics() {
        return metrics;
    }

    public PhaseDirectory phase() {
        return phase;
    }

    public RosterDirectory roster() {
        return roster;
    }

    public AuditDirectory audit() {
        return audit;
    }

    /** Granting and revoking access, each writing an {@code audit_log} row naming the admin who clicked. */
    public AccessDirectory access() {
        return access;
    }

    /** The command transport, the same table {@code /access grant} in Discord travels on. */
    public CommandRequests commands() {
        return commands;
    }

    public AccessRequests accessRequests() {
        return accessRequests;
    }

    @Override
    public void close() {
        database.close();
    }
}
