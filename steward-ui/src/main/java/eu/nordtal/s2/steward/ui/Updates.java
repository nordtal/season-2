package eu.nordtal.s2.steward.ui;

import eu.nordtal.s2.common.update.RunRefused;
import eu.nordtal.s2.common.update.UpdateKind;
import eu.nordtal.s2.common.update.UpdateReports;
import eu.nordtal.s2.common.update.UpdateRequest;
import eu.nordtal.s2.common.update.UpdateSource;
import eu.nordtal.s2.steward.ui.auth.DiscordAuth;
import eu.nordtal.s2.steward.ui.data.Data;
import eu.nordtal.s2.steward.ui.internal.InternalClient;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.ConflictResponse;
import io.javalin.http.Context;
import io.javalin.http.NotFoundResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** The {@code /api/updates} family: asking for a run, cancelling one, and reading rows back. */
final class Updates {

    private static final Logger log = LoggerFactory.getLogger(Updates.class);

    /** The Javalin mapper drops nulls; `/api/updates/active` answers "none" as an explicit null. */
    private static final com.google.gson.Gson ACTIVE_JSON =
            new com.google.gson.GsonBuilder().serializeNulls().create();

    /**
     * How long a forced re-read of the update sources may take.
     *
     * Longer than the client's usual timeout: this call asks every source again and the slowness
     * is the work, not a fault.
     */
    private static final Duration RESOLVE_DEADLINE = Duration.ofMinutes(2);

    private final @Nullable Data data;
    private final InternalClient worker;
    private final Function<Context, DiscordAuth.Account> accounts;

    Updates(
            final @Nullable Data data,
            final InternalClient worker,
            final Function<Context, DiscordAuth.Account> accounts) {
        this.data = data;
        this.worker = worker;
        this.accounts = accounts;
    }

    private Data data() {
        return Objects.requireNonNull(data, "this route needs the database, which this instance has none of");
    }

    void list(final Context ctx) {
        // The same limit helper every other list on this class uses.
        ctx.json(data().updates().recent(StewardUi.limit(ctx, 20, 200)).stream()
                .map(this::describe)
                .toList());
    }

    /**
     * The one open run, or none.
     *
     * A map because {@code run} must be present as null, not dropped.
     */
    void active(final Context ctx) {
        final Map<String, Object> answer = new java.util.HashMap<>();
        answer.put("run", data().updates().open().map(this::describe).orElse(null));
        ctx.contentType("application/json").result(ACTIVE_JSON.toJson(answer));
    }

    /**
     * What a run would do, without doing it.
     *
     * {@code refresh} drops the worker's cache and asks every source again, which is why this
     * route gets its own deadline instead of the usual pass-through timeout.
     */
    void available(final Context ctx) {
        final boolean again = ctx.queryParam("refresh") != null;
        ctx.contentType("application/json")
                .result(
                        again
                                ? worker.get("/api/updates/available?refresh", RESOLVE_DEADLINE)
                                : worker.get("/api/updates/available"));
    }

    void lookup(final Context ctx) {
        final long id = Long.parseLong(ctx.pathParam("id"));
        ctx.json(data().updates()
                .find(id)
                .map(this::describe)
                .orElseThrow(() -> new NotFoundResponse("no request " + id)));
    }

    /** Asking for an update, a backup or a restart is writing a row - nothing here talks to a container. */
    void ask(final Context ctx) {
        final Ask ask = ctx.bodyAsClass(Ask.class);
        if (ask == null || ask.kind == null) {
            throw new BadRequestResponse("kind is UPDATE, BACKUP, RESTART, DOWN or START");
        }
        final UpdateKind kind;
        try {
            kind = UpdateKind.valueOf(ask.kind.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BadRequestResponse(ask.kind + " is not a kind of run");
        }
        // DOWN is the one kind where an unnamed scope would stop the whole network.
        if (kind == UpdateKind.DOWN && (ask.services == null || ask.services.isEmpty())) {
            throw new BadRequestResponse("a DOWN has to name the services it puts down");
        }
        final DiscordAuth.Account who = accounts.apply(ctx);
        // `not_before` is scheduling: the worker refuses to claim the row until then.
        final Duration delay = ask.delaySeconds == null || ask.delaySeconds <= 0
                ? Duration.ZERO
                : Duration.ofSeconds(ask.delaySeconds);
        final UpdateRequest written;
        try {
            written = data().updates()
                    .submit(kind, UpdateSource.CONSOLE, who.name() + " (" + who.id() + ")", delay, ask.services);
        } catch (final RunRefused refused) {
            throw new ConflictResponse(refusal(refused));
        }
        log.info(
                "{} asked for {} as request {}{}",
                who.name(),
                kind,
                written.id(),
                ask.services == null || ask.services.isEmpty() ? "" : " for " + String.join(", ", ask.services));
        ctx.status(202).json(describe(written));
    }

    /**
     * Cancels the earliest PENDING or RUNNING row whose {@code not_before} is still in the future.
     *
     * An empty answer means the countdown reached zero while the request was in flight, not an
     * error in this process.
     */
    void cancel(final Context ctx) {
        final DiscordAuth.Account who = accounts.apply(ctx);
        final var cancelled =
                data().updates().cancelCountdown("Cancelled in Steward by " + who.name() + " (" + who.id() + ")");
        if (cancelled.isEmpty()) {
            throw new ConflictResponse("too late - the countdown has already run out");
        }
        // The actor is the Discord id, never the composed "name (id)": audit_log.actor is varchar(32).
        data().audit()
                .record(
                        "CANCEL_RUN",
                        who.id(),
                        String.valueOf(cancelled.get().id()),
                        null,
                        cancelled.get().kind() + " request " + cancelled.get().id() + " cancelled by " + who.name()
                                + " from the web interface");
        log.info(
                "{} cancelled {} request {}",
                who.name(),
                cancelled.get().kind(),
                cancelled.get().id());
        ctx.json(describe(cancelled.get()));
    }

    /** One sentence for a refused run: which run is in the way, or which service is already down. */
    static String refusal(final RunRefused refused) {
        return switch (refused.reason()) {
            case RUN_OPEN -> {
                final UpdateRequest open = Objects.requireNonNull(refused.open(), "RUN_OPEN always names the run");
                yield "Run #" + open.id() + " is still " + open.status().name().toLowerCase(java.util.Locale.ROOT);
            }
            case ALREADY_HELD ->
                String.join(", ", refused.services()) + (refused.services().size() == 1 ? " is" : " are")
                        + " already down";
        };
    }

    /**
     * One request as the interface shows it.
     *
     * The {@code result} column is JSON and is parsed here into a structure; a report that cannot
     * be parsed is reported as such with its text kept rather than treated as a failure.
     */
    Map<String, Object> describe(final UpdateRequest request) {
        return describe(request, data().updates().scopeOf(request.id()));
    }

    /** @param scope the services the run is for; empty is the whole network, as everywhere else */
    static Map<String, Object> describe(final UpdateRequest request, final List<String> scope) {
        final Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", request.id());
        row.put("kind", request.kind().name());
        row.put("status", request.status().name());
        row.put("source", request.source().name());
        row.put("requestedBy", request.requestedBy());
        row.put("scope", scope);
        // Mirrors steward-worker's ActionEntry.of(UpdateRequest) rather than sharing it.
        final ActorFields actor = ActorFields.of(request.requestedBy());
        row.put("actorDiscordId", actor.discordId());
        row.put("actorLabel", actor.label());
        row.put("system", actor.system());
        row.put("requested", String.valueOf(request.requested()));
        row.put("notBefore", String.valueOf(request.notBefore()));
        row.put("started", String.valueOf(request.started()));
        row.put("finished", String.valueOf(request.finished()));
        if (request.result() != null && !request.result().isBlank()) {
            UpdateReports.parse(request.result())
                    .ifPresentOrElse(
                            report -> {
                                row.put("report", report);
                                row.put("savedSomething", report.savedSomething());
                            },
                            () -> row.put("resultText", request.result()));
        }
        return row;
    }

    /** The body of {@code POST /api/updates}. */
    static final class Ask {
        @Nullable
        String kind;

        @Nullable
        Long delaySeconds;
        /**
         * Which compose services this run is for.
         *
         * Absent or empty is the whole network; a scoped run still counts down and waits for health.
         */
        java.util.@Nullable List<String> services;
    }
}
