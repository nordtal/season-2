package eu.nordtal.s2.steward.api;

import eu.nordtal.s2.common.time.Backoff;
import eu.nordtal.s2.common.time.Waiting;
import eu.nordtal.s2.database.Actor;
import eu.nordtal.s2.database.DatabaseText;
import eu.nordtal.s2.database.inbox.HungerGamesRequest;
import eu.nordtal.s2.database.inbox.Inbox;
import eu.nordtal.s2.database.inbox.InboxStatus;
import eu.nordtal.s2.database.inbox.LimboRequest;
import eu.nordtal.s2.database.inbox.ProxyRequest;
import eu.nordtal.s2.database.inbox.Reload;
import eu.nordtal.s2.database.inbox.Request;
import eu.nordtal.s2.database.inbox.Schedule;
import eu.nordtal.s2.database.inbox.SmpRequest;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import javax.sql.DataSource;

/** Asks a Minecraft service for a reload as a request in its inbox, and waits a few seconds for the answer. */
public final class InboxReloader implements ConfigApi.Reloader {

    /** How long a service may take; a reload re-reads a handful of files. */
    private static final Duration ANSWER_WITHIN = Duration.ofSeconds(10);

    private static final Duration LOOK_EVERY = Duration.ofMillis(200);

    private final Map<String, Supplier<Optional<ConfigApi.Reloaded>>> services;
    private final Waiting waiting;
    private final Duration answerWithin;

    public InboxReloader(final DataSource dataSource, final Waiting waiting) {
        this(dataSource, waiting, ANSWER_WITHIN);
    }

    /** The same, with a patience a test can shorten. */
    InboxReloader(final DataSource dataSource, final Waiting waiting, final Duration answerWithin) {
        this.answerWithin = answerWithin;
        final Inbox<SmpRequest> smp = Inbox.over(dataSource, SmpRequest.TABLE);
        final Inbox<HungerGamesRequest> hungerGames = Inbox.over(dataSource, HungerGamesRequest.TABLE);
        final Inbox<LimboRequest> limbo = Inbox.over(dataSource, LimboRequest.TABLE);
        final Inbox<ProxyRequest> proxy = Inbox.over(dataSource, ProxyRequest.TABLE);
        this.services = Map.of(
                "smp", () -> ask(smp, new Reload()),
                "hunger-games", () -> ask(hungerGames, new Reload()),
                "limbo", () -> ask(limbo, new Reload()),
                "proxy", () -> ask(proxy, new Reload()));
        this.waiting = waiting;
    }

    @Override
    public Optional<ConfigApi.Reloaded> reload(final String service) {
        final Supplier<Optional<ConfigApi.Reloaded>> ask = services.get(service);
        if (ask == null) {
            throw new IllegalArgumentException(service + " has no inbox to ask for a reload; restart it instead.");
        }
        return ask.get();
    }

    private <P> Optional<ConfigApi.Reloaded> ask(final Inbox<P> inbox, final P reload) {
        // Steward itself: this process does not know which browser asked and must not invent one.
        final Request<P> asked = inbox.submit(reload, Actor.STEWARD, Schedule.within(answerWithin));
        return waiting.until(
                        () -> inbox.find(asked.id()).filter(row -> row.status().settled()),
                        answerWithin.plusSeconds(1),
                        Backoff.fixed(LOOK_EVERY))
                .filter(row -> row.status() != InboxStatus.EXPIRED)
                .map(row -> new ConfigApi.Reloaded(row.status() == InboxStatus.DONE, text(row)));
    }

    private static <P> String text(final Request<P> row) {
        return row.refusal()
                .map(refusal -> DatabaseText.english(refusal.message()))
                .orElseGet(() -> row.outcome(Object.class).map(String::valueOf).orElse(""));
    }
}
