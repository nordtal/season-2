package eu.nordtal.season.steward.auth;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import eu.nordtal.season.common.http.Reply;
import eu.nordtal.season.common.http.WebClient;
import eu.nordtal.season.common.id.Actor;
import eu.nordtal.season.common.id.DiscordId;
import eu.nordtal.season.common.json.Json;
import eu.nordtal.season.messages.MessageRef;
import eu.nordtal.season.steward.config.WebSpec;
import eu.nordtal.season.steward.texts.StewardTexts;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Signs a user in with Discord. */
public final class DiscordAuth {

    private static final StewardTexts.Steward.Answer REFUSED =
            StewardTexts.TEXTS.steward().answer();

    private static final Logger log = LoggerFactory.getLogger(DiscordAuth.class);

    public static final String DISCORD_API = "https://discord.com/api/v10";

    private static final String AUTHORIZE = "https://discord.com/oauth2/authorize";
    private static final String SCOPES = "identify guilds.members.read";
    private static final Duration ANSWER = Duration.ofSeconds(15);
    static final Duration DEFAULT_BACKOFF = Duration.ofSeconds(5);
    static final Duration MAX_BACKOFF = Duration.ofMinutes(10);

    private final WebSpec.DiscordSpec config;
    private final String redirectUri;
    private final String api;
    private final InstantSource clock;
    private final WebClient web;
    private final AtomicReference<Instant> blockedUntil = new AtomicReference<>(Instant.EPOCH);

    public DiscordAuth(final WebSpec.DiscordSpec config, final String publicUrl) {
        this(config, publicUrl, DISCORD_API, InstantSource.system());
    }

    public DiscordAuth(final WebSpec.DiscordSpec config, final String publicUrl, final String api) {
        this(config, publicUrl, api, InstantSource.system());
    }

    public DiscordAuth(
            final WebSpec.DiscordSpec config, final String publicUrl, final String api, final InstantSource clock) {
        this.config = config;
        this.redirectUri = publicUrl + "/auth/callback";
        this.api = plaintextOnlyToOurselves(api);
        this.clock = Objects.requireNonNull(clock, "clock");
        this.web = WebClient.create(Duration.ofSeconds(10), ANSWER);
    }

    /** Refuses a plaintext API base on any other machine. */
    private static String plaintextOnlyToOurselves(final String api) {
        final URI uri = URI.create(api);
        if ("https".equalsIgnoreCase(uri.getScheme())) {
            return api;
        }
        final String host = uri.getHost();
        if ("http".equalsIgnoreCase(uri.getScheme())
                && ("127.0.0.1".equals(host)
                        || "localhost".equals(host)
                        // URI.getHost() returns an IPv6 host with brackets.
                        || "[::1]".equals(host)
                        || "[0:0:0:0:0:0:0:1]".equalsIgnoreCase(host))) {
            return api;
        }
        throw new IllegalArgumentException(api + " is not an address this sign-in will send a client"
                + " secret to. It is https, or plain http to this machine for a test, and nothing"
                + " else.");
    }

    public Optional<String> whatIsMissing() {
        if (config.clientId().isBlank()) {
            return Optional.of("discord.client-id");
        }
        if (config.clientSecret().isBlank()) {
            return Optional.of("discord.client-secret (an environment variable)");
        }
        if (config.guildId().isBlank()) {
            return Optional.of("discord.guild-id");
        }
        return Optional.empty();
    }

    public String redirectUri() {
        return redirectUri;
    }

    public URI authorizeUrl(final String state) {
        return URI.create(AUTHORIZE
                + "?response_type=code"
                + "&client_id=" + encode(config.clientId())
                + "&scope=" + encode(SCOPES)
                + "&redirect_uri=" + encode(redirectUri)
                + "&prompt=consent"
                + "&state=" + encode(state));
    }

    /** Exchanges {@code code} for the user and refuses a user who is not in the guild. */
    public Outcome signIn(final String code) {
        final Optional<String> missing = whatIsMissing();
        if (missing.isPresent()) {
            return Outcome.refused(REFUSED.signInUnconfigured(missing.get()));
        }
        final String accessToken;
        try {
            accessToken = exchange(code);
        } catch (AuthException e) {
            return Outcome.refused(e);
        }

        final JsonObject user;
        final JsonObject member;
        try {
            user = getJson("/users/@me", accessToken);
            member = getJson("/users/@me/guilds/" + config.guildId() + "/member", accessToken);
        } catch (AuthException e) {
            // A 404 means the user is not in the guild.
            return e.status() == 404 ? Outcome.refused(REFUSED.notInGuild()) : Outcome.refused(e);
        }

        final String id = user.get("id").getAsString();
        final String name = member.has("nick") && !member.get("nick").isJsonNull()
                ? member.get("nick").getAsString()
                : user.get("username").getAsString();

        log.info("discord sign-in confirmed: actor=\"{}\" member={}", name, id);
        return Outcome.signedIn(new Account(id, name));
    }

    private String exchange(final String code) {
        final Map<String, String> form = new LinkedHashMap<>();
        form.put("client_id", config.clientId());
        form.put("client_secret", config.clientSecret());
        form.put("grant_type", "authorization_code");
        form.put("code", code);
        form.put("redirect_uri", redirectUri);
        final Reply response = send(() -> web.postForm(URI.create(api + "/oauth2/token"), form), true);
        if (response.status() != 200) {
            throw new AuthException(response.status(), REFUSED.signInRefused(response.status(), redirectUri));
        }
        final JsonObject json = Json.decode(response.body(), JsonObject.class);
        if (json == null || !json.has("access_token")) {
            throw new AuthException(502, REFUSED.noAccessToken());
        }
        return json.get("access_token").getAsString();
    }

    private JsonObject getJson(final String path, final String accessToken) {
        final Reply response = send(() -> web.bearer(accessToken).get(URI.create(api + path)), false);
        if (response.status() != 200) {
            log.warn("discord api refused: status={} path={}", response.status(), path);
            throw new AuthException(response.status(), REFUSED.discordAnswered(response.status()));
        }
        return Json.decode(response.body(), JsonObject.class);
    }

    /**
     * Sends {@code exchange} unless every sign-in is paused.
     *
     * @param pausesAll whether a 429 on this route pauses every sign-in, as the token endpoint's limit is the app's
     */
    private Reply send(final Exchange exchange, final boolean pausesAll) {
        final Instant now = clock.instant();
        final Instant paused = blockedUntil.get();
        if (now.isBefore(paused)) {
            throw AuthException.rateLimited(Duration.between(now, paused));
        }
        final Reply response;
        try {
            response = exchange.send();
        } catch (final InterruptedIOException e) {
            throw new AuthException(503, REFUSED.interrupted());
        } catch (final IOException e) {
            throw new AuthException(502, REFUSED.discordUnreachable(String.valueOf(e.getMessage())));
        }
        if (response.status() == 429) {
            final Duration pause = retryAfter(response, now);
            if (pausesAll || global(response)) {
                final Instant until = blockedUntil.accumulateAndGet(
                        now.plus(pause), (current, candidate) -> candidate.isAfter(current) ? candidate : current);
                log.warn("discord rate-limited every sign-in: pause={} until={}", pause, until);
            } else {
                log.warn(
                        "discord rate-limited one sign-in: pause={} path={}",
                        pause,
                        response.uri().getPath());
            }
            throw AuthException.rateLimited(pause);
        }
        return response;
    }

    /** Returns whether Discord marks a 429 as its global limit, by header or by body. */
    static boolean global(final Reply response) {
        if (response.header("X-RateLimit-Global").map("true"::equalsIgnoreCase).orElse(false)) {
            return true;
        }
        return field(response.body(), "global")
                .filter(value ->
                        value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean())
                .map(JsonElement::getAsBoolean)
                .orElse(false);
    }

    /** Returns the wait a 429 names in Retry-After, as seconds or a date, or else in the body's retry_after. */
    static Duration retryAfter(final Reply response, final Instant now) {
        final Duration raw = response.header("Retry-After")
                .flatMap(value -> parseRetryAfter(value, now))
                .or(() -> field(response.body(), "retry_after")
                        .filter(value -> value.isJsonPrimitive()
                                && value.getAsJsonPrimitive().isNumber())
                        .flatMap(value -> positiveSeconds(value.getAsDouble())))
                .orElse(DEFAULT_BACKOFF);
        return raw.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : raw;
    }

    private static Optional<Duration> parseRetryAfter(final String raw, final Instant now) {
        final String trimmed = raw.strip();
        if (trimmed.isEmpty()) {
            return Optional.empty();
        }
        final Optional<Duration> seconds = positiveSeconds(trimmed);
        if (seconds.isPresent()) {
            return seconds;
        }
        try {
            final Instant target = ZonedDateTime.parse(trimmed, DateTimeFormatter.RFC_1123_DATE_TIME)
                    .toInstant();
            final Duration until = Duration.between(now, target);
            return until.isPositive() ? Optional.of(until) : Optional.empty();
        } catch (final DateTimeParseException ignored) {
            return Optional.empty();
        }
    }

    private static Optional<JsonElement> field(final String body, final String name) {
        if (body.isBlank()) {
            return Optional.empty();
        }
        try {
            final JsonObject json = Json.decode(body, JsonObject.class);
            return json == null ? Optional.empty() : Optional.ofNullable(json.get(name));
        } catch (final RuntimeException notAnObject) {
            return Optional.empty();
        }
    }

    private static Optional<Duration> positiveSeconds(final String raw) {
        try {
            return positiveSeconds(Double.parseDouble(raw));
        } catch (final NumberFormatException ignored) {
            return Optional.empty();
        }
    }

    private static Optional<Duration> positiveSeconds(final double seconds) {
        if (!Double.isFinite(seconds) || seconds <= 0.0) {
            return Optional.empty();
        }
        final long millis = (long) Math.ceil(seconds * 1000.0);
        return Optional.of(Duration.ofMillis(Math.max(1L, millis)));
    }

    @FunctionalInterface
    private interface Exchange {
        Reply send() throws IOException;
    }

    private static String encode(final String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    public record Account(String id, String name) {

        public Actor actor() {
            return Actor.person(DiscordId.of(id));
        }
    }

    /** The result of a sign-in. {@code retryAfter} is set when Discord limited it, and names the wait. */
    public record Outcome(
            @Nullable Account account,
            @Nullable MessageRef refusal,
            int status,
            @Nullable Duration retryAfter) {

        public static Outcome signedIn(final Account account) {
            return new Outcome(account, null, 200, null);
        }

        public static Outcome refused(final MessageRef why) {
            return new Outcome(null, why, 403, null);
        }

        private static Outcome refused(final AuthException failed) {
            return failed.retryAfter() == null
                    ? refused(failed.why())
                    : new Outcome(null, failed.why(), 429, failed.retryAfter());
        }

        public boolean ok() {
            return account != null;
        }
    }

    private static final class AuthException extends RuntimeException {

        private final int status;

        private final transient MessageRef why;

        private final @Nullable Duration retryAfter;

        AuthException(final int status, final MessageRef why) {
            this(status, why, null);
        }

        private AuthException(final int status, final MessageRef why, final @Nullable Duration retryAfter) {
            super(why.key(), null, false, false);
            this.status = status;
            this.why = why;
            this.retryAfter = retryAfter;
        }

        static AuthException rateLimited(final Duration wait) {
            return new AuthException(429, REFUSED.tooManySignIns(wait), wait);
        }

        @Nullable
        Duration retryAfter() {
            return retryAfter;
        }

        int status() {
            return status;
        }

        MessageRef why() {
            return why;
        }
    }
}
