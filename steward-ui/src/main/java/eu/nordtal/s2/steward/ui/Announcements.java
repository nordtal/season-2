package eu.nordtal.s2.steward.ui;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import eu.nordtal.s2.commands.announce.AnnounceCommands;
import eu.nordtal.s2.common.json.Json;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.Context;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;

/** Announcements an admin writes by hand, one {@code announce} row per language, the same row the SMP writes. */
final class Announcements {

    /** Discord refuses a longer message. */
    static final int MAX_LENGTH = 2000;

    private static final Pattern TAG = Pattern.compile("[a-z]{2,8}");
    private static final int RECENT = 20;

    private final @Nullable DataSource dataSource;
    private final CommandApi commands;

    Announcements(final @Nullable DataSource dataSource, final CommandApi commands) {
        this.dataSource = dataSource;
        this.commands = commands;
    }

    private DataSource dataSource() {
        return Objects.requireNonNull(dataSource, "no database - this route is not available without one");
    }

    /** {@code GET /api/announcements}: the latest, by either sender, newest first. */
    void recent(final Context ctx) {
        final List<Map<String, Object>> recent = new ArrayList<>();
        try (Connection connection = dataSource().getConnection();
                PreparedStatement statement = connection.prepareStatement("""
                     SELECT id, arguments, source, requested_by, requested, status, result
                     FROM command_request
                     WHERE target = 'BOT' AND command = 'announce'
                     ORDER BY id DESC
                     LIMIT ?
                     """)) {
            statement.setInt(1, RECENT);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    final String arguments = rows.getString("arguments");
                    final int space = arguments.indexOf(' ');
                    final Map<String, Object> line = new LinkedHashMap<>();
                    line.put("id", String.valueOf(rows.getLong("id")));
                    line.put("language", space < 0 ? arguments : arguments.substring(0, space));
                    line.put("text", space < 0 ? "" : arguments.substring(space + 1));
                    line.put("source", rows.getString("source"));
                    line.put("requestedBy", rows.getString("requested_by"));
                    line.put(
                            "requested",
                            rows.getTimestamp("requested").toInstant().toString());
                    line.put("status", rows.getString("status"));
                    final String result = rows.getString("result");
                    if (result != null) line.put("result", result);
                    recent.add(line);
                }
            }
        } catch (final SQLException failure) {
            throw new IllegalStateException("could not read the announcements", failure);
        }
        ctx.json(Map.of("recent", recent));
    }

    /**
     * {@code POST /api/announcements} with {@code {texts: {<tag>: <text>, ...}}}, one row per entry.
     *
     * Nothing is written unless every entry carries text.
     */
    void send(final Context ctx) {
        final JsonObject body;
        try {
            body = Json.tree(ctx.body()).getAsJsonObject();
        } catch (final RuntimeException malformed) {
            throw new BadRequestResponse("The body is not the JSON this endpoint takes.");
        }
        final JsonElement texts = body.get("texts");
        if (texts == null || !texts.isJsonObject() || texts.getAsJsonObject().isEmpty()) {
            throw new BadRequestResponse("texts is one text per language.");
        }
        final Map<String, String> checked = new LinkedHashMap<>();
        for (final Map.Entry<String, JsonElement> entry :
                texts.getAsJsonObject().entrySet()) {
            final String tag = entry.getKey();
            if (!TAG.matcher(tag).matches()) {
                throw new BadRequestResponse(tag + " is not a language tag.");
            }
            final JsonElement text = entry.getValue();
            if (text == null || !text.isJsonPrimitive() || text.getAsString().isBlank()) {
                throw new BadRequestResponse("The " + tag + " text is empty.");
            }
            final String stripped = text.getAsString().strip();
            if (stripped.length() > MAX_LENGTH) {
                throw new BadRequestResponse(
                        "The " + tag + " text is longer than Discord takes (" + MAX_LENGTH + " characters).");
            }
            checked.put(tag, stripped);
        }

        final Map<String, String> ids = new LinkedHashMap<>();
        checked.forEach((tag, text) -> {
            final JsonObject arguments = new JsonObject();
            arguments.addProperty("language", tag);
            arguments.addProperty("text", text);
            ids.put(tag, String.valueOf(commands.submit(ctx, AnnounceCommands.ANNOUNCE, arguments)));
        });
        ctx.status(202).json(Map.of("ids", ids));
    }
}
