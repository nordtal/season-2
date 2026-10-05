package eu.nordtal.season.steward.data;

import eu.nordtal.season.database.access.AccessDirectory;
import eu.nordtal.season.database.audit.AuditDirectory;
import eu.nordtal.season.database.inbox.BotRequest;
import eu.nordtal.season.database.inbox.HungerGamesRequest;
import eu.nordtal.season.database.inbox.Inbox;
import eu.nordtal.season.database.inbox.SmpRequest;
import eu.nordtal.season.database.metric.MetricDirectory;
import eu.nordtal.season.database.online.OnlineRoster;
import eu.nordtal.season.database.payment.Bookings;
import eu.nordtal.season.database.payment.PaymentRequests;
import eu.nordtal.season.database.phase.PhaseDirectory;
import eu.nordtal.season.database.update.UpdateDirectory;
import eu.nordtal.season.settings.Database;
import java.time.Clock;
import java.time.Duration;

/** The directories the web reads and writes through, over Steward's one pool, which it borrows and never closes. */
public final class Data {

    /** How far back a chart looks when the caller names no window. */
    public static final Duration DEFAULT_WINDOW = Duration.ofHours(6);

    private final Database database;
    private final UpdateDirectory updates;
    private final MetricDirectory metrics;
    private final PhaseDirectory phase;
    private final PaymentRequests payments;
    private final Bookings bookings;
    private final AuditDirectory audit;
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
        this.access = AccessDirectory.using(database.dataSource(), clock);
        this.roster = OnlineRoster.using(database.dataSource(), clock);
        this.smp = Inbox.over(database.dataSource(), SmpRequest.TABLE);
        this.hungerGames = Inbox.over(database.dataSource(), HungerGamesRequest.TABLE);
        this.bot = Inbox.over(database.dataSource(), BotRequest.TABLE);
    }

    /** The pool itself, for {@code steward_session} and the tables only the web reads. */
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

    /** Books a payment in one transaction, which a booking by hand on the Access page goes through. */
    public Bookings bookings() {
        return bookings;
    }

    public AuditDirectory audit() {
        return audit;
    }

    /** Granting and revoking access, each writing an {@code audit_log} row naming the admin who clicked. */
    public AccessDirectory access() {
        return access;
    }

    /** Who is connected right now and on which server, as the proxy writes it. */
    public OnlineRoster roster() {
        return roster;
    }

    /** The SMP's inbox, which the track actions are asked through. */
    public Inbox<SmpRequest> smp() {
        return smp;
    }

    /** The Hunger Games server's inbox, which the start is asked through. */
    public Inbox<HungerGamesRequest> hungerGames() {
        return hungerGames;
    }

    /** The bot's inbox, through which every access change is asked for. */
    public Inbox<BotRequest> bot() {
        return bot;
    }
}
