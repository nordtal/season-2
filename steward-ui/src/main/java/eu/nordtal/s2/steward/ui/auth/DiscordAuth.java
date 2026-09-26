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
import java.util.Optional;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Signing in with Discord, and reading the signer's roles without holding the bot's token.
 *
 * <h2>The two scopes, and why the second one</h2>
 * {@code identify} says who this is. {@code guilds.members.read} lets this process read <b>that
 * person's own</b> membership of one guild - their roles and their nickname - using their token,
 * not the bot's. The alternative would be giving the interface the bot's token so it could look
 * anybody up, which is a much larger key for a much smaller question.
 *
 * <h2>The order of the checks is deliberate</h2>
 * Discord confirms who somebody is <em>first</em>, and the role is checked <em>after</em>. That way
 * a refusal can name the person and the role they are missing, which is a sentence an admin can act
 * on, rather than an anonymous no that looks identical to a broken configuration.
 *
 * <h2>What this is not</h2>
 * §10a wants a security key after this, always, on the grounds that a token without one is a back
 * door. That is not built in this alpha and is not pretended otherwise: a stolen Discord session is
 * currently the whole of the authentication, and the sign-in page says so.
 *
 * <p><b>Unverified end to end as of 2026-09-13:</b> the flow needs a client secret and a registered
 * redirect URI, which are Till's to create. What has been checked from this host is only that
 * {@code discord.com/api/v10} answers.</p>
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
     * And answering.
     *
     * <p><b>A connect timeout is not a deadline</b>, and the difference has a person in it: these
     * two calls happen inside {@code /auth/callback}, with somebody's browser waiting on the
     * response. Discord accepting the connection and then not finishing the answer - an incident on
     * their side, a middlebox holding the socket - blocked that request thread with no way out and
     * no page to show for it. Fifteen seconds is far more than the API takes and far less than a
     * person will wait.</p>
     */
    private static final Duration ANSWER = Duration.ofSeconds(15);

    private final HttpClient http;

    public DiscordAuth(final @NotNull UiSpec.DiscordSpec config, final @NotNull String publicUrl) {
        this(config, publicUrl, DISCORD_API);
    }

    /**
     * The same sign-in against a different API base.
     *
     * <p>There is one reason this exists and it is worth naming: {@link #DISCORD_API} is the only
     * thing in the whole sign-in that is not this repository's own code. A test that puts something
     * else there gets to exercise the real state parameter, the real role check, the real session
     * and the real cookie - which is the half that has actually had bugs in it. Stubbing anything
     * further in would prove only that the stub agrees with itself.</p>
     */
    public DiscordAuth(
            final @NotNull UiSpec.DiscordSpec config, final @NotNull String publicUrl, final @NotNull String api) {
        this.config = config;
        this.redirectUri = publicUrl + "/auth/callback";
        this.api = plaintextOnlyToOurselves(api);
        this.http = HttpClient.newBuilder().connectTimeout(CONNECT).build();
    }

    /**
     * Refuses a plaintext API base that is not on this machine.
     *
     * <p>The constructor above exists so a test can put a stand-in where {@code discord.com} goes,
     * and a stand-in runs on {@code 127.0.0.1} over plain HTTP. Everything this class then sends to
     * that address is the client secret and a bearer token - which is fine into a loopback socket
     * and is a credential on the wire anywhere else. Production uses {@link #DISCORD_API} and never
     * reaches this, so the check costs nothing and removes the way a configuration mistake or a
     * future caller could quietly turn the sign-in into cleartext.</p>
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
                        // With the brackets: URI.getHost() answers "[::1]", never "::1", so the
                        // bare form this line used to carry matched nothing and IPv6 loopback was
                        // refused despite being listed.
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

    public @NotNull String redirectUri() {
        return redirectUri;
    }

    /**
     * Where the browser is sent. {@code state} is this session's one-time value.
     *
     * <h2>{@code prompt=consent}, and it used to be {@code prompt=none}</h2>
     * Measured by Till on an iPhone, 2026-09-14: after signing out, signing back in asked
     * <em>nothing</em> - no Discord dialog, no anything - and the interface was simply open again.
     * Half of that was the missing security key and is package C; this is the other half.
     * {@code prompt=none} tells Discord to send an already-consented user straight back without
     * showing them anything, which is exactly what it did.
     *
     * <p>The price is Till's, chosen on the same day with this description: {@code consent} shows
     * the permission list on every single sign-in, which is one more screen than most people want
     * to see. It is what makes signing out mean something - and on a screen where the next button
     * can stop a Minecraft server, being asked is the feature.</p>
     *
     * <p><b>It is not the second factor and must never be mistaken for one.</b> Discord consenting
     * proves the browser has a Discord session, which is the thing a stolen laptop already has.</p>
     */
    public @NotNull URI authorizeUrl(final @NotNull String state) {
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
     * <p>Whether they may in is not decided here: that is the admin tree in the database, which
     * the caller asks next. Discord's roles are carried along and decide nothing.</p>
     *
     * @return the account, or a refusal that says why in words an admin can act on
     */
    public @NotNull Outcome signIn(final @NotNull String code) {
        final Optional<String> missing = whatIsMissing();
        if (missing.isPresent()) {
            return Outcome.refused("this interface is not configured for sign-in yet: " + missing.get() + " is empty");
        }
        final String accessToken;
        try {
            accessToken = exchange(code);
        } catch (AuthException e) {
            return Outcome.refused(e.getMessage());
        }

        final JsonObject user;
        final JsonObject member;
        try {
            user = getJson("/users/@me", accessToken);
            member = getJson("/users/@me/guilds/" + config.guildId() + "/member", accessToken);
        } catch (AuthException e) {
            // A 404 here is the ordinary case of somebody who is simply not in the guild, and it
            // has to read like that rather than like an outage.
            return Outcome.refused(e.status() == 404 ? "you are not a member of the Nordtal guild" : e.getMessage());
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
    public record Account(
            @NotNull String id,
            @NotNull String name,
            @NotNull List<String> roles) {}

    /** Signed in, or refused with a reason a person can act on. */
    public record Outcome(Account account, String refusal) {

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
