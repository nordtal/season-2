package eu.nordtal.s2.steward.ui.data;

import eu.nordtal.jcore.persistence.sql.Database;
import eu.nordtal.jcore.persistence.sql.DatabaseConfig;
import eu.nordtal.s2.common.access.AccessDirectory;
import eu.nordtal.s2.common.access.AccessRequests;
import eu.nordtal.s2.common.audit.AuditDirectory;
import eu.nordtal.s2.common.command.CommandRequests;
import eu.nordtal.s2.common.metric.MetricDirectory;
import eu.nordtal.s2.common.phase.PhaseDirectory;
import eu.nordtal.s2.common.roster.RosterDirectory;
import eu.nordtal.s2.common.update.UpdateDirectory;
import eu.nordtal.s2.steward.ui.config.DatabaseSpec;
import java.time.Duration;

/**
 * The database, opened once, read through the directories {@code :common} already owns.
 *
 * It never migrates: steward-worker owns the schema and is the only process that applies one.
 * Triggering an update or a backup is a row in {@code update_request}, the same row {@code /update}
 * in Discord writes, which is why this service needs no permission over containers to ask for one.
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

    /**
     * The pool itself, for the one table this service owns rather than borrows.
     *
     * {@code steward_session} is not shared with the bot, plugins or updater, so its SQL lives in
     * {@code :steward-ui} beside the sign-in it belongs to; the migration is still in {@code :common}.
     */
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

    /**
     * Granting and revoking access.
     *
     * The only writing this interface does that is not an {@code update_request} row. Two doors
     * into the same room - here and {@code /access} in Discord - so every grant and revocation
     * from here writes an {@code audit_log} row naming the admin who clicked.
     */
    public AccessDirectory access() {
        return access;
    }

    /**
     * The command transport - the same table {@code /access grant} in Discord travels on.
     *
     * A row addressed to the process that owns the command, claimed by its inbox, with the answer
     * written back into the same row; there is no RCON here and no tmux.
     */
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
