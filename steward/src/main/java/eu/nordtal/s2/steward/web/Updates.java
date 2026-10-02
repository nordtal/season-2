package eu.nordtal.s2.steward.web;

import eu.nordtal.s2.common.id.DiscordId;
import eu.nordtal.s2.database.Actor;
import eu.nordtal.s2.database.DatabaseText;
import eu.nordtal.s2.database.update.UpdateKind;
import eu.nordtal.s2.database.update.UpdateReports;
import eu.nordtal.s2.database.update.UpdateRequest;
import eu.nordtal.s2.messages.Refused;
import eu.nordtal.s2.steward.auth.DiscordAuth;
import eu.nordtal.s2.steward.data.Data;
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

    /** Serializes nulls, so {@code /api/updates/active} can answer "none" as an explicit null. */
    private static final com.google.gson.Gson ACTIVE_JSON =
            new com.google.gson.GsonBuilder().serializeNulls().create();

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

    /** The one open run, as {@code run: null} when there is none. */
    void active(final Context ctx) {
        final Map<String, Object> answer = new java.util.HashMap<>();
        answer.put("run", data().updates().open().map(this::describe).orElse(null));
        ctx.contentType("application/json").result(ACTIVE_JSON.toJson(answer));
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
            written = data().updates().submit(kind, Actor.person(DiscordId.of(who.id())), delay, ask.services);
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

    /** One request as the interface shows it; a report that cannot be parsed is kept as text. */
    Map<String, Object> describe(final UpdateRequest request) {
        return describe(request, data().updates().scopeOf(request.id()));
    }

    /** The same, for a known {@code scope}, where empty is the whole network. */
    static Map<String, Object> describe(final UpdateRequest request, final List<String> scope) {
        final Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", request.id());
        row.put("kind", request.kind().name());
        row.put("status", request.status().name());
        row.put("actorKind", request.actor().kind().name());
        row.put("actorId", java.util.Objects.requireNonNullElse(request.actor().id(), ""));
        row.put("scope", scope);
        row.put("requested", String.valueOf(request.requested()));
        row.put("scheduledFor", String.valueOf(request.scheduledFor()));
        row.put("countdownEnd", String.valueOf(request.countdownEnd()));
        row.put("moving", request.moving());
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
        /** The compose services this run is for; absent or empty is the whole network. */
        java.util.@Nullable List<String> services;
    }
}
