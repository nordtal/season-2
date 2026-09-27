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
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The guild's roles and channels, so a Discord id can be picked rather than typed.
 *
 * Needs the bot's read-only token, which is never sent to a browser, logged or written into an
 * answer - only {@code {id, name}} leaves here. One answer is cached for {@link #TTL}, since a
 * configuration page with eleven pickers would otherwise ask Discord's rate-limited endpoint eleven times.
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

    private @Nullable Cached roles;
    private @Nullable Cached channels;

    public DiscordDirectory(final UiSpec.DiscordSpec config, final String api) {
        this.config = config;
        this.api = api;
        this.http = HttpClient.newBuilder().connectTimeout(CONNECT).build();
    }

    /**
     * Whether this can answer at all, and if not, why.
     *
     * @return the reason it cannot answer, or {@code null} when it can
     */
    public @Nullable String unavailable() {
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
        final @Nullable Cached cached = roles;
        if (cached != null && fresh(cached)) {
            return cached.entries();
        }
        final List<Entry> fetched = new ArrayList<>();
        for (final JsonElement element : fetch("/guilds/" + config.guildId() + "/roles")) {
            final JsonObject role = element.getAsJsonObject();
            final String id = role.get("id").getAsString();
            // @everyone carries the guild's own id and is not a role anybody means to configure.
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
     * Categories are kept and marked, since two channels can share a name across categories.
     */
    public synchronized List<Entry> channels() {
        final @Nullable Cached cached = channels;
        if (cached != null && fresh(cached)) {
            return cached.entries();
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

    private boolean fresh(final @Nullable Cached cached) {
        return cached != null && Duration.between(cached.at(), Instant.now()).compareTo(TTL) < 0;
    }

    private JsonArray fetch(final String path) {
        final HttpResponse<String> response;
        try {
            response = http.send(
                    HttpRequest.newBuilder(URI.create(api + path))
                            // "Bot <token>", not "Bearer": a bot token is not an OAuth access token.
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
            // The body is not passed on: this is the one place a token could leak by way of an error.
            log.warn("Discord answered {} for {}", response.statusCode(), path);
            throw new DirectoryException(
                    502,
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
    public record Entry(
            String id, String name, int position, @Nullable Integer type) {}

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
