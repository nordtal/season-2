package eu.nordtal.s2.steward.auth;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import eu.nordtal.s2.common.http.Reply;
import eu.nordtal.s2.common.http.WebClient;
import eu.nordtal.s2.common.id.Actor;
import eu.nordtal.s2.common.id.DiscordId;
import eu.nordtal.s2.common.json.Json;
import eu.nordtal.s2.steward.config.WebSpec;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Signing in with Discord, and reading the signer's roles with their own token rather than the bot's.
 *
 * Identity comes first and the role check after, so a refusal can name the person and the missing role.
 */
public final class DiscordAuth {

    private static final Logger log = LoggerFactory.getLogger(DiscordAuth.class);

    /** Discord's API base. */
    public static final String DISCORD_API = "https://discord.com/api/v10";

    private static final String AUTHORIZE = "https://discord.com/oauth2/authorize";
    private static final String SCOPES = "identify guilds.members.read";

    private final WebSpec.DiscordSpec config;
    private final String redirectUri;
    private final String api;
    /** How long an answer may take once connected, since a connect timeout alone never ends a stalled answer. */
    private static final Duration ANSWER = Duration.ofSeconds(15);

    private final WebClient web;

    public DiscordAuth(final WebSpec.DiscordSpec config, final String publicUrl) {
        this(config, publicUrl, DISCORD_API);
    }

    /** The same sign-in against a different API base, for a test's stand-in. */
    public DiscordAuth(final WebSpec.DiscordSpec config, final String publicUrl, final String api) {
        this.config = config;
        this.redirectUri = publicUrl + "/auth/callback";
        this.api = plaintextOnlyToOurselves(api);
        this.web = WebClient.create(Duration.ofSeconds(10), ANSWER);
    }

    /** Refuses a plaintext API base off this machine, which would send the client secret in cleartext. */
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
     * Where the browser is sent, with this session's one-time {@code state}.
     *
     * {@code prompt=consent} makes signing out mean something; it is not a second factor.
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
     * Whether they may in is the admin tree's decision; Discord's roles decide nothing here.
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
        final Map<String, String> form = new LinkedHashMap<>();
        form.put("client_id", config.clientId());
        form.put("client_secret", config.clientSecret());
        form.put("grant_type", "authorization_code");
        form.put("code", code);
        form.put("redirect_uri", redirectUri);
        final Reply response = send(() -> web.postForm(URI.create(api + "/oauth2/token"), form));
        if (response.status() != 200) {
            throw new AuthException(
                    response.status(),
                    "Discord refused the sign-in (" + response.status() + "). The usual cause "
                            + "is a redirect URI that is not registered on the application: this one sends "
                            + redirectUri);
        }
        final JsonObject json = Json.decode(response.body(), JsonObject.class);
        if (json == null || !json.has("access_token")) {
            throw new AuthException(502, "Discord's answer carried no access token");
        }
        return json.get("access_token").getAsString();
    }

    private JsonObject getJson(final String path, final String accessToken) {
        final Reply response = send(() -> web.bearer(accessToken).get(URI.create(api + path)));
        if (response.status() != 200) {
            throw new AuthException(response.status(), "Discord answered " + response.status() + " for " + path);
        }
        return Json.decode(response.body(), JsonObject.class);
    }

    /** Every call to Discord, each under the {@link #ANSWER} deadline. */
    private Reply send(final Exchange exchange) {
        try {
            return exchange.send();
        } catch (final InterruptedIOException e) {
            throw new AuthException(503, "interrupted while talking to Discord");
        } catch (final IOException e) {
            throw new AuthException(502, "Discord could not be reached: " + e.getMessage());
        }
    }

    /** One call to Discord, sent when {@link #send} asks for it. */
    @FunctionalInterface
    private interface Exchange {
        Reply send() throws IOException;
    }

    private static String encode(final String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /** Who signed in, with the roles kept so a later refusal can name the missing one. */
    public record Account(String id, String name, List<String> roles) {

        /** Returns them as the actor of whatever they ask for. */
        public Actor actor() {
            return Actor.person(DiscordId.of(id));
        }
    }

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
