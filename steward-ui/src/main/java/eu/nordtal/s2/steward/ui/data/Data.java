package eu.nordtal.s2.steward.ui.data;

import eu.nordtal.jcore.persistence.sql.Database;
import eu.nordtal.jcore.persistence.sql.DatabaseConfig;
import eu.nordtal.s2.commands.remote.CommandRequests;
import eu.nordtal.s2.database.Jdbis;
import eu.nordtal.s2.database.access.AccessDirectory;
import eu.nordtal.s2.database.audit.AuditDirectory;
import eu.nordtal.s2.database.inbox.BotRequest;
import eu.nordtal.s2.database.inbox.Inbox;
import eu.nordtal.s2.database.metric.MetricDirectory;
import eu.nordtal.s2.database.payment.PaymentRequests;
import eu.nordtal.s2.database.phase.PhaseDirectory;
import eu.nordtal.s2.database.update.UpdateDirectory;
import eu.nordtal.s2.settings.DatabaseSpec;
import java.time.Clock;
import java.time.Duration;

/** The database, opened once and read through the directories {@code :common} owns; it never migrates. */
public final class Data implements AutoCloseable {

    /** How far back a chart looks when the caller names no window. */
    public static final Duration DEFAULT_WINDOW = Duration.ofHours(6);

    private final Database database;
    private final UpdateDirectory updates;
    private final MetricDirectory metrics;
    private final PhaseDirectory phase;
    private final PaymentRequests payments;
    private final AuditDirectory audit;
    private final AccessDirectory access;
    private final CommandRequests commands;
    private final Inbox<BotRequest> bot;

    public Data(final DatabaseSpec config, final Clock clock) {
        this.database = Database.create(DatabaseConfig.builder(config.jdbcUrl())
                .username(config.username())
                .password(config.password())
                .poolName("steward-ui")
                .maximumPoolSize(config.maximumPoolSize())
                .build());
        this.database.jdbi().installPlugin(Jdbis.ids());
        this.updates = UpdateDirectory.using(database.dataSource());
        this.metrics = MetricDirectory.using(database.dataSource());
        this.phase = PhaseDirectory.using(database.dataSource(), clock);
        this.payments = new PaymentRequests(database.dataSource());
        this.audit = AuditDirectory.using(database.dataSource());
        // Borrowing, not owning: closing the pool below is the only close there is.
        this.access = AccessDirectory.using(database.dataSource(), clock);
        this.commands = CommandRequests.over(database.dataSource(), clock);
        this.bot = Inbox.over(database.dataSource(), BotRequest.TABLE);
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

    public PaymentRequests payments() {
        return payments;
    }

    public AuditDirectory audit() {
        return audit;
    }

    /** Granting and revoking access, each writing an {@code audit_log} row naming the admin who clicked. */
    public AccessDirectory access() {
        return access;
    }

    /** The servers' inboxes, as the web actions ask them to run a command of the catalogue. */
    public CommandRequests commands() {
        return commands;
    }

    /** The bot's inbox, through which every access change is asked for. */
    public Inbox<BotRequest> bot() {
        return bot;
    }

    @Override
    public void close() {
        database.close();
    }
}
