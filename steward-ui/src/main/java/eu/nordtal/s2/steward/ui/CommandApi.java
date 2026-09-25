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
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * The admin commands that also exist in the game, asked for from a browser.
 *
 * <p><b>Nothing here reimplements a command.</b> Concept §10b: "comes to the interface" is one new
 * {@link Surface} value and one line per {@link Declaration}. This class writes a
 * {@code command_request} row addressed to the process that owns the command and reads the answer
 * out of the same row - the transport the Discord bot has used to reach a Paper server since V11.
 * There is no RCON here and no tmux, and this process holds no connection to any server.</p>
 *
 * <p><b>The row says who asked.</b> {@code source = 'WEB'}, and V18 pins a WEB row to carrying the
 * asker's Discord id for the same reason a DISCORD row does: the target re-reads
 * {@code discord_user.admin} after it claims the row, because the flag can change while the row
 * waits. Writing these as {@code CONSOLE} would have saved a migration and made every admin
 * command from this interface anonymous.</p>
 */
final class CommandApi {

    private static final Logger log = LoggerFactory.getLogger(CommandApi.class);

    /**
     * How long the asker waits.
     *
     * <p>Long enough for a server that is busy with a tick, short enough that a browser watching a
     * spinner learns that nothing claimed the row - which is the one diagnosis worth having, and
     * the reason EXPIRED is a status of its own.</p>
     */
    private static final Duration PATIENCE = Duration.ofMinutes(2);

    private final Data data;

    /** Who is asking. The same seam {@code StewardUi} uses, so a test can stand in front of it. */
    private final Function<Context, DiscordAuth.Account> accounts;

    CommandApi(final @NotNull Data data,
               final @NotNull Function<Context, DiscordAuth.Account> accounts) {
        this.data = data;
        this.accounts = accounts;
    }

    /** {@code GET /api/commands} - what this interface may ask for, and what each one needs. */
    void list(final @NotNull Context ctx) {
        ctx.json(available().stream().map(CommandApi::describe).toList());
    }

    /** {@code POST /api/commands} - write the row, answer with its id. */
    void ask(final @NotNull Context ctx) {
        final JsonObject body = bodyOf(ctx);
        final String name = text(body, "name");
        final Declaration declaration = available().stream()
                .filter(candidate -> candidate.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new BadRequestResponse(
                        name + " is not a command this interface may ask for."));

        final JsonElement sent = body.get("arguments");
        if (sent != null && !sent.isJsonNull() && !sent.isJsonObject()) {
            throw new BadRequestResponse("`arguments` is an object of argument name to value.");
        }
        final long id = submit(ctx, declaration,
                sent == null || sent.isJsonNull() ? null : sent.getAsJsonObject());

        final Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("id", String.valueOf(id));
        answer.put("name", declaration.name());
        answer.put("status", "PENDING");
        ctx.status(202).json(answer);
    }

    /**
     * Writes one row for {@code declaration} and its journal line, and returns the row's id.
     *
     * <p>The one door every button that reaches a Paper server goes through - the generic card and
     * the designed controls on the service pages alike - so the refusals, the identity on the row
     * and the journal line cannot differ between them.</p>
     */
    long submit(final @NotNull Context ctx, final @NotNull Declaration declaration,
                final JsonObject sent) {
        if (!declaration.surfaces().contains(Surface.WEB)) {
            // The target refuses a WEB row for a declaration without the surface, and it would do
            // so two minutes from now, as an expired row. Saying it here is the same answer, now.
            throw new BadRequestResponse(declaration.name() + " is not released to the interface.");
        }
        final DiscordAuth.Account who = accounts.apply(ctx);
        final String arguments = encode(declaration, sent);

        // Two different columns for two different things, and they are not interchangeable.
        // `command_request.requested_by` is varchar(64) and exists to be read by a person, so it
        // carries the name. `audit_log.actor` is varchar(32) and is documented as the admin's
        // Discord id - which is what the bot writes there, and what a snowflake plus a name plus
        // brackets does not fit into: any display name of 11 characters or more overflowed the
        // column and took the whole request down with it. The name is not lost; it goes in the
        // detail, which is `text`.
        final String requestedBy = who.name() + " (" + who.id() + ")";

        // The row and its journal line go in together, as one statement, which is the one place in
        // this repository where the journal is transactional with what it describes. The reason is
        // what this row is: not a note about something that happened, but work a target will claim
        // and run. A committed row whose journal line failed would be a command running while this
        // handler told the operator it had not - and the next thing an operator does when told that
        // is press the button again. CommandRequests#submit(NewCommandRequest, AuditLine) carries
        // the full argument.
        final long id = data.commands().submit(new NewCommandRequest(
                declaration.target().name(),
                String.join(" ", declaration.path()),
                arguments,
                "WEB",
                requestedBy,
                Optional.of(who.id()),
                // The Minecraft account is not looked up. The target re-reads what it needs, and an
                // admin who has never linked one can still press a button.
                Optional.empty(),
                "de",
                Instant.now().plus(PATIENCE)),
                new AuditLine("COMMAND", who.id(), declaration.name(), null,
                        "asked by " + who.name() + " from the web interface"
                                + (arguments.isBlank() ? "" : ": " + arguments)));

        log.info("{} asked for {} {}", who.name(), declaration.name(), arguments);
        return id;
    }

    /** {@code GET /api/commands/{id}} - what became of it. */
    void outcome(final @NotNull Context ctx) {
        final long id;
        try {
            id = Long.parseLong(ctx.pathParam("id"));
        } catch (final NumberFormatException e) {
            throw new BadRequestResponse(ctx.pathParam("id") + " is not a request id.");
        }
        final CommandOutcome outcome = data.commands().outcome(id)
                .orElseThrow(() -> new NotFoundResponse("There is no request " + id + "."));

        final Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("id", String.valueOf(id));
        answer.put("status", outcome.status().name());
        outcome.result().ifPresent(result -> answer.put("result", result));
        ctx.json(answer);
    }

    // ---------------------------------------------------------------------------------------

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
        // The adapters owe a confirmation on an irreversible command - the flag lives on the
        // declaration so "which commands are dangerous" is one list rather than one per surface.
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

    /**
     * The arguments as the row carries them.
     *
     * <p>Built through {@link Values} and {@link RequestArguments#encode} rather than by joining
     * strings, so the same refusals a chat adapter gets apply here: a missing required argument, a
     * value that cannot survive the round trip, a choice that is not one of the choices.</p>
     */
    private static String encode(final Declaration declaration, final JsonObject arguments) {
        final Map<String, Object> supplied = new LinkedHashMap<>();
        for (final Argument argument : declaration.arguments()) {
            final JsonElement sent = arguments == null ? null : arguments.get(argument.name());
            if (sent == null || sent.isJsonNull()
                    || (sent.isJsonPrimitive() && sent.getAsString().isBlank())) {
                continue;
            }
            // Every branch below reaches getAsString(), which throws UnsupportedOperationException
            // on an object or an array - outside the refusals this method is built out of, so a
            // client sending `{"text": {"a": 1}}` got a 500 for what is an ordinary bad request.
            if (!sent.isJsonPrimitive()) {
                throw new BadRequestResponse(argument.name() + " is a single value, not a "
                        + (sent.isJsonArray() ? "list" : "structure") + ".");
            }
            supplied.put(argument.name(), switch (argument.kind()) {
                case INTEGER -> {
                    try {
                        yield Integer.valueOf(sent.getAsString().strip());
                    } catch (final NumberFormatException e) {
                        throw new BadRequestResponse(argument.name() + " is a whole number between "
                                + argument.min() + " and " + argument.max() + ".");
                    }
                }
                // A PLAYER is a Minecraft name on a chat surface and a UUID on the row, and
                // this interface has no roster of online players to pick one from. An ACCOUNT is
                // different and always was: the row carries a Discord id, /api/people lists them,
                // and the browser sends the id it picked - so the only thing left to do here is
                // refuse anything that is not one.
                case PLAYER -> throw new BadRequestResponse(declaration.name()
                        + " takes a Minecraft player, and this interface has no way to pick one."
                        + " Use the command in the game.");
                case ACCOUNT -> {
                    final String id = sent.getAsString().strip();
                    // ASCII digits, exactly as RequestArguments checks on the way out. Doing it
                    // here as well is what turns a browser's typo into a sentence rather than an
                    // IllegalArgumentException from two layers down.
                    if (!id.chars().allMatch(digit -> digit >= '0' && digit <= '9')) {
                        throw new BadRequestResponse(argument.name()
                                + " is a Discord id - pick the person from the list.");
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
