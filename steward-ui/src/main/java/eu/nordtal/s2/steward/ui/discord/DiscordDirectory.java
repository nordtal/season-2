package eu.nordtal.s2.steward.ui.discord;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import eu.nordtal.s2.steward.ui.config.UiSpec;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The guild's roles and channels, so a Discord id can be PICKED rather than typed.
 *
 * <h2>Why this exists</h2>
 * Every id in {@code access.yml} is an eighteen-digit number that exists in exactly one place a
 * person can read it: Discord's own right-click menu, behind a developer-mode switch most people
 * have never turned on. Typing one into a text field is a transcription with no feedback - the
 * wrong one is a valid snowflake, so nothing refuses it, and the first sign of the mistake is a
 * message appearing in a channel nobody meant. A list of names that writes the id for you removes
 * the whole class of mistake, and it is the reason those ids stopped being mandatory at all: what
 * a deployment could not ask a person for, it can now ask Discord for.
 *
 * <h2>The token</h2>
 * This needs the bot's token, which is why {@code steward-ui} now has one - <b>read-only, and for
 * this</b>. It is never sent to a browser, never logged and never written into an answer: what
 * leaves here is a list of {@code {id, name}}, which is public inside the guild anyway. The token
 * lives under {@code discord.bot-token}, whose key {@code ConfigEntry#secret} already matches, so
 * the configuration editor shows it as set and never shows its value.
 *
 * <h2>The cache</h2>
 * Discord's guild endpoints are rate limited per route, and a configuration page with eleven
 * pickers on it would otherwise ask eleven times. One answer is kept for {@link #TTL}; a stale list
 * is a role created in the last minute not appearing yet, which is a refresh away and is a much
 * smaller problem than a 429 that empties every picker on the page at once.
 */
public final class DiscordDirectory {

    private static final Logger log = LoggerFactory.getLogger(DiscordDirectory.class);

    private static final Gson GSON = new Gson();
    private static final Duration CONNECT = Duration.ofSeconds(5);
    private static final Duration ANSWER = Duration.ofSeconds(10);

    /** How long one answer is reused. Long enough to draw a page, short enough to feel live. */
    static final Duration TTL = Duration.ofSeconds(60);

    private final UiSpec.DiscordSpec config;
    private final String api;
    private final HttpClient http;

    private Cached roles;
    private Cached channels;

    public DiscordDirectory(final @NotNull UiSpec.DiscordSpec config, final @NotNull String api) {
        this.config = config;
        this.api = api;
        this.http = HttpClient.newBuilder().connectTimeout(CONNECT).build();
    }

    /**
     * Whether this can answer at all, and if not, why - in a sentence the sign-in page's own
     * "which value is missing" style, because "the picker is empty" is not a diagnosis.
     *
     * @return the reason it cannot answer, or {@code null} when it can
     */
    public String unavailable() {
        if (config.guildId().isBlank()) {
            return "discord.guild-id is not set, so there is no guild to list.";
        }
        if (config.botToken().isBlank()) {
            return "discord.bot-token is not set. Steward needs the bot's token - read-only, and "
                    + "only for this - to ask Discord what the guild's roles and channels are "
                    + "called. Without it the ids still work; they just have to be typed.";
        }
        return null;
    }

    /** The guild's roles, @everyone excluded, highest first - the order Discord itself draws. */
    public synchronized List<Entry> roles() {
        if (fresh(roles)) {
            return roles.entries();
        }
        final List<Entry> fetched = new ArrayList<>();
        for (final JsonElement element : fetch("/guilds/" + config.guildId() + "/roles")) {
            final JsonObject role = element.getAsJsonObject();
            final String id = role.get("id").getAsString();
            // @everyone carries the guild's own id and is not a role anybody means to configure:
            // giving it to somebody is a no-op and pinging it is a thing to do by accident once.
            if (id.equals(config.guildId())) {
                continue;
            }
            fetched.add(new Entry(
                    id,
                    role.get("name").getAsString(),
                    role.has("position") ? role.get("position").getAsInt() : 0,
                    null));
        }
        fetched.sort(Comparator.comparingInt(Entry::position).reversed());
        roles = new Cached(List.copyOf(fetched), Instant.now());
        return roles.entries();
    }

    /**
     * The guild's channels, in the order Discord draws them.
     *
     * <p>Categories are kept and marked, because "general" under Info and "general" under Season
     * are two channels with one name and the category is the only thing that tells them apart.</p>
     */
    public synchronized List<Entry> channels() {
        if (fresh(channels)) {
            return channels.entries();
        }
        final List<Entry> fetched = new ArrayList<>();
        for (final JsonElement element : fetch("/guilds/" + config.guildId() + "/channels")) {
            final JsonObject channel = element.getAsJsonObject();
            fetched.add(new Entry(
                    channel.get("id").getAsString(),
                    channel.get("name").getAsString(),
                    channel.has("position") ? channel.get("position").getAsInt() : 0,
                    channel.has("type") ? channel.get("type").getAsInt() : null));
        }
        fetched.sort(Comparator.comparingInt(Entry::position));
        channels = new Cached(List.copyOf(fetched), Instant.now());
        return channels.entries();
    }

    private boolean fresh(final Cached cached) {
        return cached != null && Duration.between(cached.at(), Instant.now()).compareTo(TTL) < 0;
    }

    private JsonArray fetch(final String path) {
        final HttpResponse<String> response;
        try {
            response = http.send(
                    HttpRequest.newBuilder(URI.create(api + path))
                            // "Bot <token>", not "Bearer": a bot token is not an OAuth access token and
                            // Discord answers 401 for the wrong prefix with no hint that it was the prefix.
                            .header("Authorization", "Bot " + config.botToken())
                            .timeout(ANSWER)
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
        } catch (final IOException exception) {
            throw new DirectoryException(502, "Discord could not be reached: " + exception.getMessage());
        } catch (final InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new DirectoryException(503, "interrupted while talking to Discord");
        }
        if (response.statusCode() != 200) {
            // The body is not passed on. A 401 from Discord echoes nothing secret today, but this
            // is the one place a token could end up in a browser by way of an error message.
            log.warn("Discord answered {} for {}", response.statusCode(), path);
            throw new DirectoryException(
                    response.statusCode() == 401 ? 502 : 502,
                    switch (response.statusCode()) {
                        case 401 -> "Discord refused the bot token. Check discord.bot-token.";
                        case 403 -> "The bot is in the guild but may not read it.";
                        case 404 ->
                            "Discord does not know that guild. Check discord.guild-id, and "
                                    + "that the bot has been invited to it.";
                        case 429 -> "Discord is rate limiting this. Try again in a moment.";
                        default -> "Discord answered " + response.statusCode() + ".";
                    });
        }
        return GSON.fromJson(response.body(), JsonArray.class);
    }

    /**
     * One thing that can be picked.
     *
     * @param id       the snowflake, which is what gets written into the config file
     * @param name     what it is called in the guild
     * @param position where Discord draws it
     * @param type     Discord's channel type, or {@code null} for a role
     */
    public record Entry(@NotNull String id, @NotNull String name, int position, Integer type) {}

    private record Cached(List<Entry> entries, Instant at) {}

    /** Discord did not answer, or answered no. Carries the status the browser should see. */
    public static final class DirectoryException extends RuntimeException {

        private final int status;

        DirectoryException(final int status, final String message) {
            super(message);
            this.status = status;
        }

        public int status() {
            return status;
        }
    }
}
