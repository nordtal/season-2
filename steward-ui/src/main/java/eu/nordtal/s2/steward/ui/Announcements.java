package eu.nordtal.s2.steward.ui;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import eu.nordtal.s2.commands.announce.AnnounceCommands;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.Context;
import org.jetbrains.annotations.NotNull;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Announcements an admin writes by hand, one text per language.
 *
 * <p><b>Each language is one {@code announce} row</b>, the same row the SMP writes at a milestone,
 * so the bot has one path for both senders. The rows are not written in one transaction: each is
 * work the bot claims and answers on its own, and the page reads each answer back by its id.</p>
 *
 * <p><b>The look back is the request rows themselves.</b> {@code command_request} already holds
 * every announcement, the SMP's included, for as long as its retention keeps settled rows - which is
 * "recent", and that is all this list claims to be. A table kept only for looking back would be a
 * second copy of the same lines.</p>
 */
final class Announcements {

    /** Discord refuses a message longer than this, and the bot would answer "not posted". */
    static final int MAX_LENGTH = 2000;

    private static final Pattern TAG = Pattern.compile("[a-z]{2,8}");
    private static final int RECENT = 20;

    private final DataSource dataSource;
    private final CommandApi commands;

    /** @param dataSource null in a test that runs without a database, which never calls these */
    Announcements(final DataSource dataSource, final @NotNull CommandApi commands) {
        this.dataSource = dataSource;
        this.commands = commands;
    }

    /** {@code GET /api/announcements} - the latest announcements, by either sender, newest first. */
    void recent(final @NotNull Context ctx) {
        final List<Map<String, Object>> recent = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
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
                    line.put("requested", rows.getTimestamp("requested").toInstant().toString());
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
     * {@code POST /api/announcements} - {@code {texts: {<tag>: <text>, ...}}}, one row per entry.
     *
     * <p>Every entry must carry text: a language sent empty is refused rather than skipped, because
     * an announcement that quietly went out in one language of two is the mistake this form exists
     * to prevent. Nothing is written unless every entry passes.</p>
     */
    void send(final @NotNull Context ctx) {
        final JsonObject body;
        try {
            body = JsonParser.parseString(ctx.body()).getAsJsonObject();
        } catch (final RuntimeException malformed) {
            throw new BadRequestResponse("The body is not the JSON this endpoint takes.");
        }
        final JsonElement texts = body.get("texts");
        if (texts == null || !texts.isJsonObject() || texts.getAsJsonObject().isEmpty()) {
            throw new BadRequestResponse("texts is one text per language.");
        }
        final Map<String, String> checked = new LinkedHashMap<>();
        for (final Map.Entry<String, JsonElement> entry : texts.getAsJsonObject().entrySet()) {
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
                throw new BadRequestResponse("The " + tag + " text is longer than Discord takes ("
                        + MAX_LENGTH + " characters).");
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
