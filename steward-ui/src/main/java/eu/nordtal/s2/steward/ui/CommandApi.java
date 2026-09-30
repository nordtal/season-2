package eu.nordtal.s2.steward.ui;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import eu.nordtal.s2.commands.Argument;
import eu.nordtal.s2.commands.Declaration;
import eu.nordtal.s2.commands.Surface;
import eu.nordtal.s2.commands.Target;
import eu.nordtal.s2.commands.Values;
import eu.nordtal.s2.commands.remote.CommandOutcome;
import eu.nordtal.s2.commands.remote.NewCommandRequest;
import eu.nordtal.s2.commands.remote.RequestArguments;
import eu.nordtal.s2.common.id.DiscordId;
import eu.nordtal.s2.database.audit.AuditLine;
import eu.nordtal.s2.database.inbox.BotRequest;
import eu.nordtal.s2.database.inbox.Request;
import eu.nordtal.s2.steward.ui.auth.DiscordAuth;
import eu.nordtal.s2.steward.ui.data.Data;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.Context;
import io.javalin.http.NotFoundResponse;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The admin commands that also exist in the game, asked for from a browser as requests in the servers' inboxes.
 * A request is named to the browser as {@code <target>:<id>}, or {@code announce:<id>:<language>} for one line.
 */
final class CommandApi {

    private static final Logger log = LoggerFactory.getLogger(CommandApi.class);

    /** How long the asker waits before the row is EXPIRED. */
    private static final Duration PATIENCE = Duration.ofMinutes(2);

    private final @Nullable Data data;

    private final Function<Context, DiscordAuth.Account> accounts;

    private final Clock clock;

    CommandApi(final @Nullable Data data, final Function<Context, DiscordAuth.Account> accounts, final Clock clock) {
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
        this.data = data;
        this.accounts = accounts;
    }

    private Data data() {
        return Objects.requireNonNull(data, "no database - this route is not available without one");
    }

    /** Writes one request for {@code declaration} and its journal line, and returns the name the browser polls. */
    String submit(final Context ctx, final Declaration declaration, final @Nullable JsonObject sent) {
        if (!declaration.surfaces().contains(Surface.WEB)) {
            throw new BadRequestResponse(declaration.name() + " is not released to the interface.");
        }
        final DiscordAuth.Account who = accounts.apply(ctx);
        final String arguments = encode(declaration, sent);

        // requested_by is for a person to read; the id goes into actor, which is varchar(32).
        final String requestedBy = who.name() + " (" + who.id() + ")";

        // One statement: a committed row with no journal line would run a command silently.
        final long id = data().commands()
                .submit(
                        new NewCommandRequest(
                                declaration.target().name(),
                                String.join(" ", declaration.path()),
                                arguments,
                                "WEB",
                                requestedBy,
                                Optional.of(DiscordId.of(who.id())),
                                // The target re-reads what it needs from the Discord id.
                                Optional.empty(),
                                "de",
                                clock.instant().plus(PATIENCE)),
                        new AuditLine(
                                "COMMAND",
                                who.id(),
                                declaration.name(),
                                null,
                                "asked by " + who.name() + " from the web interface"
                                        + (arguments.isBlank() ? "" : ": " + arguments)));

        log.info("{} asked for {} {}", who.name(), declaration.name(), arguments);
        return declaration.target().name().toLowerCase(java.util.Locale.ROOT) + ":" + id;
    }

    /** {@code GET /api/commands/{id}}: what became of it, the id being what {@link #submit} answered. */
    void outcome(final Context ctx) {
        final String[] name = ctx.pathParam("id").split(":", -1);
        final Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("id", ctx.pathParam("id"));
        try {
            if (name.length == 3 && name[0].equals("announce")) {
                announced(Long.parseLong(name[1]), name[2], answer);
            } else if (name.length == 2) {
                final long id = Long.parseLong(name[1]);
                final CommandOutcome outcome = data().commands()
                        .outcome(Target.valueOf(name[0].toUpperCase(java.util.Locale.ROOT)), id)
                        .orElseThrow(() -> new NotFoundResponse("There is no request " + ctx.pathParam("id") + "."));
                answer.put("status", outcome.status().name());
                outcome.result().ifPresent(result -> answer.put("result", result));
            } else {
                throw new BadRequestResponse(ctx.pathParam("id") + " is not a request.");
            }
        } catch (final IllegalArgumentException malformed) {
            throw new BadRequestResponse(ctx.pathParam("id") + " is not a request.");
        }
        ctx.json(answer);
    }

    /** One language's line of an announcement: the request's status, and whether that language's text went out. */
    private void announced(final long id, final String language, final Map<String, Object> answer) {
        final Request<BotRequest> row = data().bot()
                .find(id)
                .filter(found -> found.payload() instanceof BotRequest.Announce)
                .orElseThrow(() -> new NotFoundResponse("There is no announcement " + id + "."));
        answer.put("status", Announcements.status(row.status()));
        Announcements.result(row, language).ifPresent(result -> answer.put("result", result));
    }

    /** The arguments as the row carries them, through {@link Values} and {@link RequestArguments#encode}. */
    private static String encode(final Declaration declaration, final @Nullable JsonObject arguments) {
        final Map<String, Object> supplied = new LinkedHashMap<>();
        for (final Argument argument : declaration.arguments()) {
            final JsonElement sent = arguments == null ? null : arguments.get(argument.name());
            if (sent == null
                    || sent.isJsonNull()
                    || (sent.isJsonPrimitive() && sent.getAsString().isBlank())) {
                continue;
            }
            // getAsString() below throws on an object or array.
            if (!sent.isJsonPrimitive()) {
                throw new BadRequestResponse(argument.name() + " is a single value, not a "
                        + (sent.isJsonArray() ? "list" : "structure") + ".");
            }
            supplied.put(
                    argument.name(),
                    switch (argument.kind()) {
                        case INTEGER -> {
                            try {
                                yield Integer.valueOf(sent.getAsString().strip());
                            } catch (final NumberFormatException e) {
                                throw new BadRequestResponse(argument.name() + " is a whole number between "
                                        + argument.min() + " and " + argument.max() + ".");
                            }
                        }
                        // No roster of online players to pick one from.
                        case PLAYER ->
                            throw new BadRequestResponse(declaration.name()
                                    + " takes a Minecraft player, and this interface has no way to pick one."
                                    + " Use the command in the game.");
                        case ACCOUNT -> {
                            final String id = sent.getAsString().strip();
                            if (!id.chars().allMatch(digit -> digit >= '0' && digit <= '9')) {
                                throw new BadRequestResponse(
                                        argument.name() + " is a Discord id - pick the person from the list.");
                            }
                            yield id;
                        }
                        case WORD, GREEDY_STRING, CHOICE, REFERENCE -> sent.getAsString();
                    });
        }
        try {
            return RequestArguments.encode(declaration, new Values(declaration, supplied));
        } catch (final IllegalArgumentException e) {
            throw new BadRequestResponse(e.getMessage());
        }
    }
}
