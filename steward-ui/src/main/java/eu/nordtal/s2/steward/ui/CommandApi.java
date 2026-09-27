package eu.nordtal.s2.steward.ui;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import eu.nordtal.s2.commands.Argument;
import eu.nordtal.s2.commands.Catalogue;
import eu.nordtal.s2.commands.Declaration;
import eu.nordtal.s2.commands.Surface;
import eu.nordtal.s2.commands.Values;
import eu.nordtal.s2.commands.remote.RequestArguments;
import eu.nordtal.s2.common.audit.AuditLine;
import eu.nordtal.s2.common.command.CommandOutcome;
import eu.nordtal.s2.common.command.NewCommandRequest;
import eu.nordtal.s2.steward.ui.auth.DiscordAuth;
import eu.nordtal.s2.steward.ui.data.Data;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.Context;
import io.javalin.http.NotFoundResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The admin commands that also exist in the game, asked for from a browser.
 *
 * Writes a {@code command_request} row addressed to the process that owns the command and reads
 * the answer out of the same row; this process holds no connection to any server.
 */
final class CommandApi {

    private static final Logger log = LoggerFactory.getLogger(CommandApi.class);

    /** How long the asker waits before the row is EXPIRED. */
    private static final Duration PATIENCE = Duration.ofMinutes(2);

    /** Null only in a test that never calls a route on this class. */
    private final @Nullable Data data;

    /** Who is asking. The same seam {@code StewardUi} uses, so a test can stand in front of it. */
    private final Function<Context, DiscordAuth.Account> accounts;

    CommandApi(final @Nullable Data data, final Function<Context, DiscordAuth.Account> accounts) {
        this.data = data;
        this.accounts = accounts;
    }

    /** The database, for a route that cannot be reached without one. */
    private Data data() {
        return Objects.requireNonNull(data, "no database - this route is not available without one");
    }

    /** {@code GET /api/commands} - what this interface may ask for, and what each one needs. */
    void list(final Context ctx) {
        ctx.json(available().stream().map(CommandApi::describe).toList());
    }

    /** {@code POST /api/commands} - write the row, answer with its id. */
    void ask(final Context ctx) {
        final JsonObject body = bodyOf(ctx);
        final String name = text(body, "name");
        final Declaration declaration = available().stream()
                .filter(candidate -> candidate.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new BadRequestResponse(name + " is not a command this interface may ask for."));

        final JsonElement sent = body.get("arguments");
        if (sent != null && !sent.isJsonNull() && !sent.isJsonObject()) {
            throw new BadRequestResponse("`arguments` is an object of argument name to value.");
        }
        final long id = submit(ctx, declaration, sent == null || sent.isJsonNull() ? null : sent.getAsJsonObject());

        final Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("id", String.valueOf(id));
        answer.put("name", declaration.name());
        answer.put("status", "PENDING");
        ctx.status(202).json(answer);
    }

    /**
     * Writes one row for {@code declaration} and its journal line, and returns the row's id.
     *
     * The one door every button that reaches a Paper server goes through.
     */
    long submit(final Context ctx, final Declaration declaration, final @Nullable JsonObject sent) {
        if (!declaration.surfaces().contains(Surface.WEB)) {
            // Saying it now rather than as an expired row two minutes from now.
            throw new BadRequestResponse(declaration.name() + " is not released to the interface.");
        }
        final DiscordAuth.Account who = accounts.apply(ctx);
        final String arguments = encode(declaration, sent);

        // requested_by is varchar(64) for a person to read; actor is varchar(32), so the id goes there.
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
                                Optional.of(who.id()),
                                // Not looked up: the target re-reads what it needs from the Discord id.
                                Optional.empty(),
                                "de",
                                Instant.now().plus(PATIENCE)),
                        new AuditLine(
                                "COMMAND",
                                who.id(),
                                declaration.name(),
                                null,
                                "asked by " + who.name() + " from the web interface"
                                        + (arguments.isBlank() ? "" : ": " + arguments)));

        log.info("{} asked for {} {}", who.name(), declaration.name(), arguments);
        return id;
    }

    /** {@code GET /api/commands/{id}} - what became of it. */
    void outcome(final Context ctx) {
        final long id;
        try {
            id = Long.parseLong(ctx.pathParam("id"));
        } catch (final NumberFormatException e) {
            throw new BadRequestResponse(ctx.pathParam("id") + " is not a request id.");
        }
        final CommandOutcome outcome = data().commands()
                .outcome(id)
                .orElseThrow(() -> new NotFoundResponse("There is no request " + id + "."));

        final Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("id", String.valueOf(id));
        answer.put("status", outcome.status().name());
        outcome.result().ifPresent(result -> answer.put("result", result));
        ctx.json(answer);
    }

    /** Every declaration that carries {@link Surface#WEB}, by name. */
    private static List<Declaration> available() {
        return Catalogue.all().stream()
                .filter(declaration -> declaration.surfaces().contains(Surface.WEB))
                .sorted(java.util.Comparator.comparing(Declaration::name))
                .toList();
    }

    private static Map<String, Object> describe(final Declaration declaration) {
        final Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", declaration.name());
        row.put("path", declaration.path());
        row.put("target", declaration.target().name());
        row.put("adminOnly", declaration.adminOnly());
        row.put("irreversible", declaration.irreversible());
        final List<Map<String, Object>> arguments = new ArrayList<>();
        for (final Argument argument : declaration.arguments()) {
            final Map<String, Object> field = new LinkedHashMap<>();
            field.put("name", argument.name());
            field.put("kind", argument.kind().name());
            field.put("required", argument.required());
            if (argument.kind() == Argument.Kind.INTEGER) {
                field.put("min", argument.min());
                field.put("max", argument.max());
            }
            if (argument.kind() == Argument.Kind.CHOICE) {
                field.put("choices", argument.choices());
            }
            arguments.add(field);
        }
        row.put("arguments", arguments);
        return row;
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
            // Every branch below calls getAsString(), which throws on an object or array otherwise.
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
                        // No roster of online players to pick a PLAYER from; ACCOUNT sends a Discord id instead.
                        case PLAYER ->
                            throw new BadRequestResponse(declaration.name()
                                    + " takes a Minecraft player, and this interface has no way to pick one."
                                    + " Use the command in the game.");
                        case ACCOUNT -> {
                            final String id = sent.getAsString().strip();
                            // Checked here too, so a typo is a sentence rather than an exception two layers down.
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

    private static JsonObject bodyOf(final Context ctx) {
        try {
            return JsonParser.parseString(ctx.body()).getAsJsonObject();
        } catch (final JsonSyntaxException | IllegalStateException | IllegalArgumentException e) {
            throw new BadRequestResponse("The body has to be a JSON object with a `name` field.");
        }
    }

    private static String text(final JsonObject body, final String field) {
        final JsonElement value = body.get(field);
        if (value == null || !value.isJsonPrimitive() || value.getAsString().isBlank()) {
            throw new BadRequestResponse("`" + field + "` is missing.");
        }
        return value.getAsString();
    }
}
