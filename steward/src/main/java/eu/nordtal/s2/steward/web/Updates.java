package eu.nordtal.s2.steward.web;

import eu.nordtal.s2.database.Actor;
import eu.nordtal.s2.database.DatabaseText;
import eu.nordtal.s2.database.audit.AuditLine;
import eu.nordtal.s2.database.update.UpdateKind;
import eu.nordtal.s2.database.update.UpdateReport;
import eu.nordtal.s2.database.update.UpdateReports;
import eu.nordtal.s2.database.update.UpdateRequest;
import eu.nordtal.s2.database.update.UpdateStatus;
import eu.nordtal.s2.messages.Refused;
import eu.nordtal.s2.steward.auth.DiscordAuth;
import eu.nordtal.s2.steward.data.Data;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.ConflictResponse;
import io.javalin.http.Context;
import io.javalin.http.NotFoundResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** The {@code /api/updates} family: asking for a run, cancelling one, and reading rows back. */
final class Updates {

    private static final Logger log = LoggerFactory.getLogger(Updates.class);

    private final @Nullable Data data;
    private final Function<Context, DiscordAuth.Account> accounts;

    Updates(final @Nullable Data data, final Function<Context, DiscordAuth.Account> accounts) {
        this.data = data;
        this.accounts = accounts;
    }

    private Data data() {
        return Objects.requireNonNull(data, "this route needs the database, which this instance has none of");
    }

    void list(final Context ctx) {
        ctx.json(data().updates().recent(Web.limit(ctx, 20, 200)).stream()
                .map(this::describe)
                .toList());
    }

    /** The one open run, absent when there is none. */
    void active(final Context ctx) {
        ctx.json(new ActiveRun(data().updates().open().map(this::describe).orElse(null)));
    }

    void lookup(final Context ctx) {
        final long id = Long.parseLong(ctx.pathParam("id"));
        ctx.json(data().updates()
                .find(id)
                .map(this::describe)
                .orElseThrow(() -> new NotFoundResponse("no request " + id)));
    }

    /** Asks for a run by writing a row; nothing here talks to a container. */
    void ask(final Context ctx) {
        final Ask ask = ctx.bodyAsClass(Ask.class);
        if (ask == null || ask.kind == null) {
            throw new BadRequestResponse("kind is UPDATE, BACKUP, RESTART, DOWN, START, RECREATE or DEPLOY");
        }
        final UpdateKind kind;
        try {
            kind = UpdateKind.valueOf(ask.kind.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BadRequestResponse(ask.kind + " is not a kind of run");
        }
        // An unnamed scope would put the whole network down, or throw every player out without a countdown.
        if ((kind == UpdateKind.DOWN || kind == UpdateKind.RECREATE)
                && (ask.services == null || ask.services.isEmpty())) {
            throw new BadRequestResponse("a " + kind + " has to name the services it is for");
        }
        final DiscordAuth.Account who = accounts.apply(ctx);
        // `scheduled_for`: the run loop refuses to claim the row until then.
        final Duration delay = ask.delaySeconds == null || ask.delaySeconds <= 0
                ? Duration.ZERO
                : Duration.ofSeconds(ask.delaySeconds);
        final UpdateRequest written;
        try {
            written = data().updates().submit(kind, who.actor(), delay, ask.services);
        } catch (final Refused refused) {
            throw new ConflictResponse(DatabaseText.english(refused.refusal().message()));
        } catch (final IllegalArgumentException named) {
            // A restore and a plugin removal carry more than services and have routes of their own.
            throw new BadRequestResponse(named.getMessage());
        }
        log.info(
                "{} asked for {} as request {}{}",
                who.name(),
                kind,
                written.id(),
                ask.services == null || ask.services.isEmpty() ? "" : " for " + String.join(", ", ask.services));
        ctx.status(202).json(describe(written));
    }

    /** Cancels the earliest run still counting down; an empty answer means its countdown ran out meanwhile. */
    void cancel(final Context ctx) {
        final DiscordAuth.Account who = accounts.apply(ctx);
        final var cancelled =
                data().updates().cancelCountdown("Cancelled in Steward by " + who.name() + " (" + who.id() + ")");
        if (cancelled.isEmpty()) {
            throw new ConflictResponse("too late - the countdown has already run out");
        }
        data().audit()
                .record(AuditLine.of(
                        "CANCEL_RUN",
                        who.actor(),
                        Map.of(
                                "run",
                                cancelled.get().id(),
                                "kind",
                                cancelled.get().kind().name())));
        log.info(
                "{} cancelled {} request {}",
                who.name(),
                cancelled.get().kind(),
                cancelled.get().id());
        ctx.json(describe(cancelled.get()));
    }

    /**
     * One run as the interface shows it; a report that cannot be parsed is kept as {@code resultText}.
     *
     * @param scope the services this run is for, where empty is the whole network
     * @param moving the services the run stops, written when its countdown starts
     */
    public record Run(
            long id,
            UpdateKind kind,
            UpdateStatus status,
            Actor.Kind actorKind,
            String actorId,
            List<String> scope,
            Instant requested,
            Instant scheduledFor,
            @Nullable Instant countdownEnd,
            List<String> moving,
            @Nullable Instant started,
            @Nullable Instant finished,
            @Nullable UpdateReport report,
            @Nullable Boolean savedSomething,
            @Nullable String resultText) {}

    /** {@code GET /api/updates/active}: the one open run, absent when there is none. */
    public record ActiveRun(@Nullable Run run) {}

    /** One request as the interface shows it. */
    Run describe(final UpdateRequest request) {
        return describe(request, data().updates().scopeOf(request.id()));
    }

    /** The same, for a known {@code scope}, where empty is the whole network. */
    static Run describe(final UpdateRequest request, final List<String> scope) {
        final String result = request.result();
        final Optional<UpdateReport> report =
                result == null || result.isBlank() ? Optional.empty() : UpdateReports.parse(result);
        return new Run(
                request.id(),
                request.kind(),
                request.status(),
                request.actor().kind(),
                java.util.Objects.requireNonNullElse(request.actor().id(), ""),
                scope,
                request.requested(),
                request.scheduledFor(),
                request.countdownEnd(),
                request.moving(),
                request.started(),
                request.finished(),
                report.orElse(null),
                report.map(UpdateReport::savedSomething).orElse(null),
                report.isPresent() || result == null || result.isBlank() ? null : result);
    }

    /** The body of {@code POST /api/updates}. */
    static final class Ask {
        @Nullable
        String kind;

        @Nullable
        Long delaySeconds;
        /** The compose services this run is for; absent or empty is the whole network. */
        java.util.@Nullable List<String> services;
    }
}
