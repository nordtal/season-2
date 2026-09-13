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

        final DiscordAuth.Account who = accounts.apply(ctx);
        final JsonElement sent = body.get("arguments");
        if (sent != null && !sent.isJsonNull() && !sent.isJsonObject()) {
            throw new BadRequestResponse("`arguments` is an object of argument name to value.");
        }
        final String arguments = encode(declaration,
                sent == null || sent.isJsonNull() ? null : sent.getAsJsonObject());

        final long id = data.commands().submit(new NewCommandRequest(
                declaration.target().name(),
                String.join(" ", declaration.path()),
                arguments,
                "WEB",
                who.name() + " (" + who.id() + ")",
                Optional.of(who.id()),
                // The Minecraft account is not looked up. The target re-reads what it needs, and an
                // admin who has never linked one can still press a button.
                Optional.empty(),
                "de",
                Instant.now().plus(PATIENCE)));

        data.audit().record("COMMAND", who.name() + " (" + who.id() + ")",
                declaration.name(), null,
                arguments.isBlank() ? "from the web interface" : "from the web interface: " + arguments);
        log.info("{} asked for {} {}", who.name(), declaration.name(), arguments);

        final Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("id", String.valueOf(id));
        answer.put("name", declaration.name());
        answer.put("status", "PENDING");
        ctx.status(202).json(answer);
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
            supplied.put(argument.name(), switch (argument.kind()) {
                case INTEGER -> {
                    try {
                        yield Integer.valueOf(sent.getAsString().strip());
                    } catch (final NumberFormatException e) {
                        throw new BadRequestResponse(argument.name() + " is a whole number between "
                                + argument.min() + " and " + argument.max() + ".");
                    }
                }
                case PLAYER, ACCOUNT -> throw new BadRequestResponse(declaration.name()
                        + " takes a person, and this interface has no way to pick one yet."
                        + " Use the command in Discord or in the game.");
                case WORD, GREEDY_STRING, CHOICE -> sent.getAsString();
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
