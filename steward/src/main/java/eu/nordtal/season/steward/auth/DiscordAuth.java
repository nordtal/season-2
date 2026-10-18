package eu.nordtal.season.steward.auth;

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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
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

    private final WebSpec.DiscordSpec config;
    private final String redirectUri;
    private final String api;
    private static final Duration ANSWER = Duration.ofSeconds(15);

    private final WebClient web;

    public DiscordAuth(final WebSpec.DiscordSpec config, final String publicUrl) {
        this(config, publicUrl, DISCORD_API);
    }

    public DiscordAuth(final WebSpec.DiscordSpec config, final String publicUrl, final String api) {
        this.config = config;
        this.redirectUri = publicUrl + "/auth/callback";
        this.api = plaintextOnlyToOurselves(api);
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
            return Outcome.refused(e.why());
        }

        final JsonObject user;
        final JsonObject member;
        try {
            user = getJson("/users/@me", accessToken);
            member = getJson("/users/@me/guilds/" + config.guildId() + "/member", accessToken);
        } catch (AuthException e) {
            // A 404 means the user is not in the guild.
            return Outcome.refused(e.status() == 404 ? REFUSED.notInGuild() : e.why());
        }

        final String id = user.get("id").getAsString();
        final String name = member.has("nick") && !member.get("nick").isJsonNull()
                ? member.get("nick").getAsString()
                : user.get("username").getAsString();

        log.info("Discord confirmed {} ({})", name, id);
        return Outcome.signedIn(new Account(id, name));
    }

    private String exchange(final String code) {
        final Map<String, String> form = new LinkedHashMap<>();
        form.put("client_id", config.clientId());
        form.put("client_secret", config.clientSecret());
        form.put("grant_type", "authorization_code");
        form.put("code", code);
        form.put("redirect_uri", redirectUri);
        final Reply response = send(() -> web.postForm(URI.create(api + "/oauth2/token"), form));
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
        final Reply response = send(() -> web.bearer(accessToken).get(URI.create(api + path)));
        if (response.status() != 200) {
            log.warn("Discord answered {} for {}", response.status(), path);
            throw new AuthException(response.status(), REFUSED.discordAnswered(response.status()));
        }
        return Json.decode(response.body(), JsonObject.class);
    }

    private Reply send(final Exchange exchange) {
        try {
            return exchange.send();
        } catch (final InterruptedIOException e) {
            throw new AuthException(503, REFUSED.interrupted());
        } catch (final IOException e) {
            throw new AuthException(502, REFUSED.discordUnreachable(String.valueOf(e.getMessage())));
        }
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

    public record Outcome(
            @Nullable Account account, @Nullable MessageRef refusal) {

        public static Outcome signedIn(final Account account) {
            return new Outcome(account, null);
        }

        public static Outcome refused(final MessageRef why) {
            return new Outcome(null, why);
        }

        public boolean ok() {
            return account != null;
        }
    }

    private static final class AuthException extends RuntimeException {

        private final int status;

        private final transient MessageRef why;

        AuthException(final int status, final MessageRef why) {
            super(why.key(), null, false, false);
            this.status = status;
            this.why = why;
        }

        int status() {
            return status;
        }

        MessageRef why() {
            return why;
        }
    }
}
