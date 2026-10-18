package eu.nordtal.season.steward.update;

import eu.nordtal.season.common.id.Actor;
import eu.nordtal.season.database.JournalDetail;
import eu.nordtal.season.database.inbox.StewardRequest;
import eu.nordtal.season.database.update.UpdateDirectory;
import eu.nordtal.season.database.update.UpdateKind;
import eu.nordtal.season.database.update.UpdateReport;
import eu.nordtal.season.database.update.UpdateReports;
import eu.nordtal.season.database.update.UpdateRequest;
import eu.nordtal.season.database.update.UpdateStatus;
import eu.nordtal.season.messages.MessageRef;
import eu.nordtal.season.steward.QueryLimit;
import eu.nordtal.season.steward.auth.DiscordAuth;
import eu.nordtal.season.steward.data.Data;
import eu.nordtal.season.steward.journal.Journaling;
import eu.nordtal.season.steward.texts.RequestRefused;
import eu.nordtal.season.steward.texts.StewardTexts;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.Context;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** The {@code /api/updates} family: asking for a run, cancelling one, and reading rows back. */
public final class Updates {

    private static final StewardTexts.Steward.Answer ANSWER =
            StewardTexts.TEXTS.steward().answer();

    private static final Logger log = LoggerFactory.getLogger(Updates.class);

    private final @Nullable Data data;
    private final Function<Context, DiscordAuth.Account> accounts;
    private final Consumer<Context> stepUp;

    /** @param stepUp refuses a request whose session's step-up has ended, for the kinds that need one */
    public Updates(
            final @Nullable Data data,
            final Function<Context, DiscordAuth.Account> accounts,
            final Consumer<Context> stepUp) {
        this.data = data;
        this.accounts = accounts;
        this.stepUp = stepUp;
    }

    private Data data() {
        return Objects.requireNonNull(data, "this route needs the database, which this instance has none of");
    }

    public void list(final Context ctx) {
        ctx.json(data().updates().recent(QueryLimit.of(ctx, 20, 200)).stream()
                .map(this::describe)
                .toList());
    }

    public void active(final Context ctx) {
        ctx.json(new ActiveRun(data().updates().open().map(this::describe).orElse(null)));
    }

    public void lookup(final Context ctx) {
        final long id = Long.parseLong(ctx.pathParam("id"));
        ctx.json(data().updates()
                .find(id)
                .map(this::describe)
                .orElseThrow(() -> new RequestRefused(404, ANSWER.noRequest(String.valueOf(id)))));
    }

    /** Writes a request row for a run. Kinds that need a step-up require one. It touches no container. */
    public void ask(final Context ctx) {
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
        if (needsStepUp(kind)) {
            stepUp.accept(ctx);
        }
        if ((kind == UpdateKind.DOWN || kind == UpdateKind.RECREATE)
                && (ask.services == null || ask.services.isEmpty())) {
            throw new BadRequestResponse("a " + kind + " has to name the services it is for");
        }
        final DiscordAuth.Account who = accounts.apply(ctx);
        final Duration delay = ask.delaySeconds == null || ask.delaySeconds <= 0
                ? Duration.ZERO
                : Duration.ofSeconds(ask.delaySeconds);
        final boolean replaceLocal = Boolean.TRUE.equals(ask.replaceLocal);
        if (replaceLocal && kind != UpdateKind.UPDATE) {
            throw new BadRequestResponse("replaceLocal is for an UPDATE only, the one run that replaces a build");
        }
        final UpdateRequest written;
        try {
            written = replaceLocal
                    ? data().updates()
                            .submit(
                                    new StewardRequest.Update(UpdateDirectory.cleaned(ask.services), true),
                                    who.actor(),
                                    delay)
                    : data().updates().submit(kind, who.actor(), delay, ask.services);
        } catch (final IllegalArgumentException named) {
            throw new BadRequestResponse(named.getMessage());
        }
        log.info(
                "{} asked for {} as request {}{}",
                who.name(),
                kind,
                written.id(),
                ask.services == null || ask.services.isEmpty() ? "" : " for " + String.join(", ", ask.services));
        final List<String> scope = data().updates().scopeOf(written.id());
        Journaling.line(ctx, new JournalDetail.RunAsked(written.id(), written.kind(), scope));
        ctx.status(202).json(describe(written, scope));
    }

    /** Returns whether a run of this kind replaces, stops or destroys something without undo. */
    static boolean needsStepUp(final UpdateKind kind) {
        return switch (kind) {
            case UPDATE, DOWN, RECREATE, DEPLOY, RESTORE, REMOVE_PLUGIN, INSTALL -> true;
            case RESTART, START, BACKUP -> false;
        };
    }

    /** Cancels the earliest run in its countdown and refuses with 409 when none is counting down. */
    public void cancel(final Context ctx) {
        final DiscordAuth.Account who = accounts.apply(ctx);
        final var cancelled = data().updates().cancelCountdown();
        if (cancelled.isEmpty()) {
            throw new RequestRefused(409, ANSWER.tooLate());
        }
        Journaling.line(
                ctx,
                new JournalDetail.RunCancelled(
                        cancelled.get().id(), cancelled.get().kind()));
        log.info(
                "{} cancelled {} request {}",
                who.name(),
                cancelled.get().kind(),
                cancelled.get().id());
        ctx.json(describe(cancelled.get()));
    }

    /**
     * One run. A report that does not parse is {@code resultText}.
     *
     * @param scope the services of the run, and empty means the whole network
     * @param moving the services the run stops
     * @param said the message of each report line by service
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
            Map<String, MessageRef> said,
            @Nullable Boolean savedSomething,
            @Nullable String resultText) {}

    /** The answer of {@code GET /api/updates/active}, with {@code null} when no run is open. */
    public record ActiveRun(@Nullable Run run) {}

    Run describe(final UpdateRequest request) {
        return describe(request, data().updates().scopeOf(request.id()));
    }

    /** Returns the run for {@code scope}, and an empty scope means the whole network. */
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
                report.map(UpdateReport::said).orElse(Map.of()),
                report.map(UpdateReport::savedSomething).orElse(null),
                report.isPresent() || result == null || result.isBlank() ? null : result);
    }

    /** The body of {@code POST /api/updates}. */
    static final class Ask {
        @Nullable
        String kind;

        @Nullable
        Long delaySeconds;
        java.util.@Nullable List<String> services;
        /** For an update: true confirms that the update replaces builds made on the host. */
        @Nullable
        Boolean replaceLocal;
    }
}
