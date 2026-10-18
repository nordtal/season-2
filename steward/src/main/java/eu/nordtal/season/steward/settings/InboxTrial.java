package eu.nordtal.season.steward.settings;

import eu.nordtal.season.common.id.Actor;
import eu.nordtal.season.common.language.Locales;
import eu.nordtal.season.common.time.Backoff;
import eu.nordtal.season.common.time.Waiting;
import eu.nordtal.season.database.inbox.HungerGamesRequest;
import eu.nordtal.season.database.inbox.Inbox;
import eu.nordtal.season.database.inbox.InboxStatus;
import eu.nordtal.season.database.inbox.Request;
import eu.nordtal.season.database.inbox.Schedule;
import eu.nordtal.season.database.inbox.SettingsChange;
import eu.nordtal.season.database.inbox.SmpRequest;
import eu.nordtal.season.internalapi.agent.Topology;
import eu.nordtal.season.messages.Messages;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/** Asks the SMP or the Hunger Games server through its inbox, and withdraws the question it does not answer in time. */
public final class InboxTrial implements ServerTrial {

    /** Long enough for a running server to claim and answer, short enough for a save to wait on. */
    static final Duration PATIENCE = Duration.ofSeconds(5);

    private static final Backoff POLL = Backoff.fixed(Duration.ofMillis(200));

    private final Inbox<SmpRequest> smp;
    private final Inbox<HungerGamesRequest> hungerGames;
    private final Messages refusals;
    private final Waiting clock;

    /** @param refusals the database bundle that words a server's refusal */
    public InboxTrial(
            final Inbox<SmpRequest> smp,
            final Inbox<HungerGamesRequest> hungerGames,
            final Messages refusals,
            final Waiting clock) {
        this.smp = Objects.requireNonNull(smp, "smp");
        this.hungerGames = Objects.requireNonNull(hungerGames, "hungerGames");
        this.refusals = Objects.requireNonNull(refusals, "refusals");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public Verdict ask(final String service, final SettingsChange change, final Actor actor) {
        return switch (service) {
            case Topology.SMP -> ask(smp, new SmpRequest.CheckSettings(change), actor);
            case Topology.HUNGER_GAMES -> ask(hungerGames, new HungerGamesRequest.CheckSettings(change), actor);
            default -> Verdict.UNASKED;
        };
    }

    private <P> Verdict ask(final Inbox<P> inbox, final P question, final Actor actor) {
        final long id = inbox.submit(question, actor, Schedule.within(PATIENCE)).id();
        final Optional<Request<P>> answered = clock.until(
                () -> inbox.find(id)
                        .filter(row -> row.status() != InboxStatus.PENDING && row.status() != InboxStatus.RUNNING),
                PATIENCE,
                POLL);
        if (answered.isEmpty()) {
            inbox.cancel(id, null);
            return new Verdict(Verdict.Kind.UNANSWERED, null);
        }
        final Request<P> row = answered.get();
        return switch (row.status()) {
            case DONE -> new Verdict(Verdict.Kind.TAKEN, null);
            case REFUSED ->
                new Verdict(
                        Verdict.Kind.REFUSED,
                        row.refusal()
                                .map(refusal -> refusals.format(Locales.DEFAULT, refusal.message()))
                                .orElse(null));
            default -> new Verdict(Verdict.Kind.UNANSWERED, null);
        };
    }
}
