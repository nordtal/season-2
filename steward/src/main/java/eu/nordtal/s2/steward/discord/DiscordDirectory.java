package eu.nordtal.s2.steward.discord;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import eu.nordtal.s2.common.http.Reply;
import eu.nordtal.s2.common.http.WebClient;
import eu.nordtal.s2.common.json.Json;
import eu.nordtal.s2.messages.MessageRef;
import eu.nordtal.s2.steward.config.WebSpec;
import eu.nordtal.s2.steward.texts.StewardTexts;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The guild's channels, so a Discord id can be picked rather than typed.
 *
 * Only {@code {id, name}} leaves here, never the bot's token; one answer is cached for {@link #TTL}.
 */
public final class DiscordDirectory {

    private static final Logger log = LoggerFactory.getLogger(DiscordDirectory.class);

    private static final Duration CONNECT = Duration.ofSeconds(5);
    private static final Duration ANSWER = Duration.ofSeconds(10);
    private static final StewardTexts.Steward.Answer REFUSED =
            StewardTexts.TEXTS.steward().answer();
    private static final StewardTexts.Steward.Said SAID =
            StewardTexts.TEXTS.steward().said();

    /** How long one answer is reused: long enough to draw a page, short enough to feel live. */
    static final Duration TTL = Duration.ofSeconds(60);

    private final WebSpec.DiscordSpec config;
    private final String api;
    private final WebClient web;

    private @Nullable Cached channels;

    private final Clock clock;

    public DiscordDirectory(final WebSpec.DiscordSpec config, final String api, final Clock clock) {
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
        this.config = config;
        this.api = api;
        // "Bot <token>", not "Bearer": a bot token is not an OAuth access token.
        this.web = WebClient.create(CONNECT, ANSWER).header("Authorization", "Bot " + config.botToken());
    }

    /** Why this cannot answer, or {@code null} when it can. */
    public @Nullable MessageRef unavailable() {
        if (config.guildId().isBlank()) {
            return SAID.noGuildId();
        }
        if (config.botToken().isBlank()) {
            return SAID.noBotToken();
        }
        return null;
    }

    /** The guild's channels in Discord's order, categories included since names repeat across them. */
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
        channels = new Cached(List.copyOf(fetched), clock.instant());
        return channels.entries();
    }

    private boolean fresh(final @Nullable Cached cached) {
        return cached != null && Duration.between(cached.at(), clock.instant()).compareTo(TTL) < 0;
    }

    private JsonArray fetch(final String path) {
        final Reply response;
        try {
            response = web.get(URI.create(api + path));
        } catch (final InterruptedIOException exception) {
            throw new DirectoryException(503, REFUSED.interrupted());
        } catch (final IOException exception) {
            throw new DirectoryException(502, REFUSED.discordUnreachable(String.valueOf(exception.getMessage())));
        }
        if (response.status() != 200) {
            // The body is not passed on, since an error is the one place a token could leak.
            log.warn("Discord answered {} for {}", response.status(), path);
            throw new DirectoryException(
                    502,
                    switch (response.status()) {
                        case 401 -> REFUSED.botTokenRefused();
                        case 403 -> REFUSED.guildUnreadable();
                        case 404 -> REFUSED.unknownGuild();
                        case 429 -> REFUSED.rateLimited();
                        default -> REFUSED.discordAnswered(response.status());
                    });
        }
        return Json.decode(response.body(), JsonArray.class);
    }

    /** One channel that can be picked; {@code type} is Discord's channel type, or {@code null} where it sends none. */
    public record Entry(
            String id, String name, int position, @Nullable Integer type) {}

    private record Cached(List<Entry> entries, Instant at) {}

    /** Discord did not answer, or answered no, with the status the browser should see and why, as a message. */
    public static final class DirectoryException extends RuntimeException {

        private final int status;
        private final transient MessageRef why;

        DirectoryException(final int status, final MessageRef why) {
            super(why.key());
            this.status = status;
            this.why = why;
        }

        public int status() {
            return status;
        }

        public MessageRef why() {
            return why;
        }
    }
}
