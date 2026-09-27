package eu.nordtal.s2.steward.ui.auth;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import eu.nordtal.s2.steward.ui.config.UiSpec;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Signing in with Discord, and reading the signer's roles without holding the bot's token.
 *
 * {@code guilds.members.read} lets this process read that person's own membership of one guild -
 * their roles and nickname - using their token, not the bot's. Discord confirms who somebody is
 * first and the role is checked after, so a refusal can name the person and the missing role.
 */
public final class DiscordAuth {

    private static final Logger log = LoggerFactory.getLogger(DiscordAuth.class);

    /** Discord's API, and the one system boundary this class has. */
    public static final String DISCORD_API = "https://discord.com/api/v10";

    private static final String AUTHORIZE = "https://discord.com/oauth2/authorize";
    private static final String SCOPES = "identify guilds.members.read";
    private static final Gson GSON = new Gson();

    private final UiSpec.DiscordSpec config;
    private final String redirectUri;
    private final String api;
    /** Connecting to Discord. */
    private static final Duration CONNECT = Duration.ofSeconds(10);

    /**
     * How long an answer may take, once connected; somebody's browser is waiting on it.
     *
     * A connect timeout alone is not a deadline: Discord accepting the connection and then never
     * finishing the answer would otherwise block the request thread with no way out.
     */
    private static final Duration ANSWER = Duration.ofSeconds(15);

    private final HttpClient http;

    public DiscordAuth(final UiSpec.DiscordSpec config, final String publicUrl) {
        this(config, publicUrl, DISCORD_API);
    }

    /**
     * The same sign-in against a different API base.
     *
     * A test can put a stand-in where {@link #DISCORD_API} goes and still exercise the real state
     * parameter, role check, session and cookie.
     */
    public DiscordAuth(final UiSpec.DiscordSpec config, final String publicUrl, final String api) {
        this.config = config;
        this.redirectUri = publicUrl + "/auth/callback";
        this.api = plaintextOnlyToOurselves(api);
        this.http = HttpClient.newBuilder().connectTimeout(CONNECT).build();
    }

    /**
     * Refuses a plaintext API base that is not on this machine.
     *
     * A stand-in for a test runs on {@code 127.0.0.1} over plain HTTP; everything else sent in
     * cleartext would carry the client secret and a bearer token on the wire.
     */
    private static String plaintextOnlyToOurselves(final String api) {
        final URI uri = URI.create(api);
        if ("https".equalsIgnoreCase(uri.getScheme())) {
            return api;
        }
        final String host = uri.getHost();
        if ("http".equalsIgnoreCase(uri.getScheme())
                && ("127.0.0.1".equals(host)
                        || "localhost".equals(host)
                        // With the brackets: URI.getHost() answers "[::1]", never "::1".
                        || "[::1]".equals(host)
                        || "[0:0:0:0:0:0:0:1]".equalsIgnoreCase(host))) {
            return api;
        }
        throw new IllegalArgumentException(api + " is not an address this sign-in will send a client"
                + " secret to. It is https, or plain http to this machine for a test, and nothing"
                + " else.");
    }

    /** What is missing before anybody can sign in, or empty when the configuration is complete. */
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

    /**
     * Where the browser is sent. {@code state} is this session's one-time value.
     *
     * {@code prompt=consent} makes signing out mean something: on a screen where the next button
     * can stop a Minecraft server, being asked again is the feature. It is not the second factor -
     * it only proves the browser has a Discord session, which a stolen laptop already has.
     */
    public URI authorizeUrl(final String state) {
        return URI.create(AUTHORIZE
                + "?response_type=code"
                + "&client_id=" + encode(config.clientId())
                + "&scope=" + encode(SCOPES)
                + "&redirect_uri=" + encode(redirectUri)
                + "&prompt=consent"
                + "&state=" + encode(state));
    }

    /**
     * Exchanges the code and says who this is, refusing anybody who is not in the guild.
     *
     * Whether they may in is decided elsewhere, in the admin tree; Discord's roles decide nothing here.
     *
     * @return the account, or a refusal that says why in words an admin can act on
     */
    public Outcome signIn(final String code) {
        final Optional<String> missing = whatIsMissing();
        if (missing.isPresent()) {
            return Outcome.refused("this interface is not configured for sign-in yet: " + missing.get() + " is empty");
        }
        final String accessToken;
        try {
            accessToken = exchange(code);
        } catch (AuthException e) {
            return Outcome.refused(Objects.toString(e.getMessage(), e.getClass().getSimpleName()));
        }

        final JsonObject user;
        final JsonObject member;
        try {
            user = getJson("/users/@me", accessToken);
            member = getJson("/users/@me/guilds/" + config.guildId() + "/member", accessToken);
        } catch (AuthException e) {
            // A 404 here is somebody not in the guild, not an outage.
            return Outcome.refused(
                    e.status() == 404
                            ? "you are not a member of the Nordtal guild"
                            : Objects.toString(e.getMessage(), e.getClass().getSimpleName()));
        }

        final List<String> roles = new ArrayList<>();
        final JsonArray fromDiscord = member.getAsJsonArray("roles");
        if (fromDiscord != null) {
            for (final JsonElement role : fromDiscord) {
                roles.add(role.getAsString());
            }
        }
        final String id = user.get("id").getAsString();
        final String name = member.has("nick") && !member.get("nick").isJsonNull()
                ? member.get("nick").getAsString()
                : user.get("username").getAsString();

        log.info("Discord confirmed {} ({})", name, id);
        return Outcome.signedIn(new Account(id, name, List.copyOf(roles)));
    }

    private String exchange(final String code) {
        final String form = "client_id=" + encode(config.clientId())
                + "&client_secret=" + encode(config.clientSecret())
                + "&grant_type=authorization_code"
                + "&code=" + encode(code)
                + "&redirect_uri=" + encode(redirectUri);
        final HttpResponse<String> response = send(HttpRequest.newBuilder(URI.create(api + "/oauth2/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form)));
        if (response.statusCode() != 200) {
            throw new AuthException(
                    response.statusCode(),
                    "Discord refused the sign-in (" + response.statusCode() + "). The usual cause "
                            + "is a redirect URI that is not registered on the application: this one sends "
                            + redirectUri);
        }
        final JsonObject json = GSON.fromJson(response.body(), JsonObject.class);
        if (json == null || !json.has("access_token")) {
            throw new AuthException(502, "Discord's answer carried no access token");
        }
        return json.get("access_token").getAsString();
    }

    private JsonObject getJson(final String path, final String accessToken) {
        final HttpResponse<String> response = send(HttpRequest.newBuilder(URI.create(api + path))
                .header("Authorization", "Bearer " + accessToken)
                .GET());
        if (response.statusCode() != 200) {
            throw new AuthException(
                    response.statusCode(), "Discord answered " + response.statusCode() + " for " + path);
        }
        return GSON.fromJson(response.body(), JsonObject.class);
    }

    /** Every call to Discord goes through here, and every one of them carries {@link #ANSWER}. */
    private HttpResponse<String> send(final HttpRequest.Builder request) {
        try {
            return http.send(request.timeout(ANSWER).build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new AuthException(502, "Discord could not be reached: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AuthException(503, "interrupted while talking to Discord");
        }
    }

    private static String encode(final String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /** Who signed in. Roles are kept so a later refusal can say which one was missing. */
    public record Account(String id, String name, List<String> roles) {}

    /** Signed in, or refused with a reason a person can act on. */
    public record Outcome(
            @Nullable Account account, @Nullable String refusal) {

        public static Outcome signedIn(final Account account) {
            return new Outcome(account, null);
        }

        public static Outcome refused(final String why) {
            return new Outcome(null, why);
        }

        public boolean ok() {
            return account != null;
        }
    }

    private static final class AuthException extends RuntimeException {

        private final int status;

        AuthException(final int status, final String message) {
            super(message);
            this.status = status;
        }

        int status() {
            return status;
        }
    }
}
