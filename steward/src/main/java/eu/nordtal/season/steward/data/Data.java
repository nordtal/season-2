package eu.nordtal.season.steward.data;

import eu.nordtal.season.database.access.AccessDirectory;
import eu.nordtal.season.database.audit.AuditDirectory;
import eu.nordtal.season.database.inbox.BotRequest;
import eu.nordtal.season.database.inbox.HungerGamesRequest;
import eu.nordtal.season.database.inbox.Inbox;
import eu.nordtal.season.database.inbox.SmpRequest;
import eu.nordtal.season.database.journal.JournalStream;
import eu.nordtal.season.database.metric.MetricDirectory;
import eu.nordtal.season.database.online.OnlineRoster;
import eu.nordtal.season.database.payment.Bookings;
import eu.nordtal.season.database.payment.PaymentRequests;
import eu.nordtal.season.database.phase.PhaseDirectory;
import eu.nordtal.season.database.update.UpdateDirectory;
import eu.nordtal.season.settings.Database;
import java.time.Clock;
import java.time.Duration;

/** The directories that the web reads and writes through, over the one pool of Steward. It never closes the pool. */
public final class Data {

    /** The chart window when the caller names none. */
    public static final Duration DEFAULT_WINDOW = Duration.ofHours(6);

    private final Database database;
    private final UpdateDirectory updates;
    private final MetricDirectory metrics;
    private final PhaseDirectory phase;
    private final PaymentRequests payments;
    private final Bookings bookings;
    private final AuditDirectory audit;
    private final JournalStream journal;
    private final AccessDirectory access;
    private final OnlineRoster roster;
    private final Inbox<SmpRequest> smp;
    private final Inbox<HungerGamesRequest> hungerGames;
    private final Inbox<BotRequest> bot;

    public Data(final Database database, final Clock clock) {
        this.database = database;
        this.updates = UpdateDirectory.using(database.dataSource());
        this.metrics = MetricDirectory.using(database.dataSource());
        this.phase = PhaseDirectory.using(database.dataSource(), clock);
        this.payments = new PaymentRequests(database.dataSource());
        this.bookings = new Bookings(database.dataSource());
        this.audit = AuditDirectory.using(database.dataSource());
        this.journal = JournalStream.using(database.dataSource());
        this.access = AccessDirectory.using(database.dataSource(), clock);
        this.roster = OnlineRoster.using(database.dataSource(), clock);
        this.smp = Inbox.over(database.dataSource(), SmpRequest.TABLE);
        this.hungerGames = Inbox.over(database.dataSource(), HungerGamesRequest.TABLE);
        this.bot = Inbox.over(database.dataSource(), BotRequest.TABLE);
    }

    /** Returns whether PostgreSQL answers a trivial query within the pool's timeout. */
    public boolean answers() {
        try (java.sql.Connection connection = database.dataSource().getConnection();
                java.sql.Statement statement = connection.createStatement()) {
            return statement.execute("SELECT 1");
        } catch (final java.sql.SQLException | RuntimeException failed) {
            return false;
        }
    }

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

    public Bookings bookings() {
        return bookings;
    }

    public AuditDirectory audit() {
        return audit;
    }

    public JournalStream journal() {
        return journal;
    }

    public AccessDirectory access() {
        return access;
    }

    public OnlineRoster roster() {
        return roster;
    }

    public Inbox<SmpRequest> smp() {
        return smp;
    }

    public Inbox<HungerGamesRequest> hungerGames() {
        return hungerGames;
    }

    public Inbox<BotRequest> bot() {
        return bot;
    }
}
