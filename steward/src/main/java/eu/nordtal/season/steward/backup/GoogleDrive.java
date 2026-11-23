package eu.nordtal.season.steward.backup;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import eu.nordtal.season.common.http.Reply;
import eu.nordtal.season.common.http.WebClient;
import eu.nordtal.season.common.json.Json;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Speaks Google's OAuth and the Drive API for one OAuth client.
 * The {@code drive.file} scope shows steward only the files it created, so the folder is found by name among them.
 */
public final class GoogleDrive {

    /** The name of the folder that holds the repositories in each connected account. */
    public static final String FOLDER = "Nordtal Backups";

    static final String SCOPES = "https://www.googleapis.com/auth/drive.file openid email profile";

    private static final String FOLDER_TYPE = "application/vnd.google-apps.folder";

    private static final Duration ANSWER = Duration.ofSeconds(20);

    /** Where Google answers. A test points them at a stand-in on loopback. */
    public record Endpoints(URI authorize, URI token, URI userinfo, URI revoke, URI files) {

        public static final Endpoints GOOGLE = new Endpoints(
                URI.create("https://accounts.google.com/o/oauth2/v2/auth"),
                URI.create("https://oauth2.googleapis.com/token"),
                URI.create("https://openidconnect.googleapis.com/v1/userinfo"),
                URI.create("https://oauth2.googleapis.com/revoke"),
                URI.create("https://www.googleapis.com/drive/v3/files"));

        public Endpoints {
            for (final URI uri : new URI[] {authorize, token, userinfo, revoke, files}) {
                WebClient.tokenSafe("google endpoint", uri.toString());
            }
        }
    }

    /** The tokens of a finished consent. */
    public record Tokens(String accessToken, String refreshToken) {

        @Override
        public String toString() {
            return "Tokens[redacted]";
        }
    }

    /** The Google account behind a token: its OpenID subject and address. */
    public record Identity(String subject, String email) {}

    public record Folder(String id, String name) {}

    private final String clientId;
    private final String clientSecret;
    private final Endpoints endpoints;
    private final WebClient web;

    public GoogleDrive(final String clientId, final String clientSecret, final Endpoints endpoints) {
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.endpoints = endpoints;
        this.web = WebClient.create(Duration.ofSeconds(10), ANSWER);
    }

    public boolean offered() {
        return !clientId.isBlank() && !clientSecret.isBlank();
    }

    public String clientId() {
        return clientId;
    }

    public String clientSecret() {
        return clientSecret;
    }

    /** Returns the consent page, which asks for offline access so that Google hands out a refresh token. */
    public URI authorizeUrl(final String redirectUri, final String state, final String verifier) {
        return URI.create(endpoints.authorize()
                + "?response_type=code"
                + "&client_id=" + encode(clientId)
                + "&redirect_uri=" + encode(redirectUri)
                + "&scope=" + encode(SCOPES)
                + "&access_type=offline"
                + "&prompt=consent"
                + "&include_granted_scopes=false"
                + "&code_challenge=" + challenge(verifier)
                + "&code_challenge_method=S256"
                + "&state=" + encode(state));
    }

    /** Trades the code of the consent for tokens. A consent without a refresh token is refused. */
    public Tokens exchange(final String code, final String verifier, final String redirectUri) throws IOException {
        final Map<String, String> form = new LinkedHashMap<>();
        form.put("client_id", clientId);
        form.put("client_secret", clientSecret);
        form.put("grant_type", "authorization_code");
        form.put("code", code);
        form.put("code_verifier", verifier);
        form.put("redirect_uri", redirectUri);
        final JsonObject json = ok(web.postForm(endpoints.token(), form), "token");
        final @Nullable String access = text(json, "access_token");
        final @Nullable String refresh = text(json, "refresh_token");
        if (access == null || refresh == null) {
            throw new IOException("Google returned no refresh token");
        }
        final @Nullable String scope = text(json, "scope");
        if (scope == null || !scope.contains("auth/drive.file")) {
            throw new IOException("Google did not grant access to Drive files");
        }
        return new Tokens(access, refresh);
    }

    public Identity identity(final String accessToken) throws IOException {
        final JsonObject json = ok(web.bearer(accessToken).get(endpoints.userinfo()), "userinfo");
        final @Nullable String subject = text(json, "sub");
        final @Nullable String email = text(json, "email");
        if (subject == null || email == null) {
            throw new IOException("Google named no account");
        }
        return new Identity(subject, email);
    }

    /** Returns the backup folder that steward created in the account, and creates it on the first connect. */
    public Folder folder(final String accessToken) throws IOException {
        final String query = "name='" + FOLDER + "' and mimeType='" + FOLDER_TYPE + "' and trashed=false";
        final URI list = URI.create(endpoints.files()
                + "?q=" + encode(query)
                + "&spaces=drive&orderBy=createdTime&fields=" + encode("files(id,name)"));
        final JsonObject found = ok(web.bearer(accessToken).get(list), "files");
        final JsonElement files = found.get("files");
        if (files instanceof JsonArray array && !array.isEmpty() && array.get(0) instanceof JsonObject first) {
            final @Nullable String id = text(first, "id");
            if (id != null) {
                return new Folder(id, FOLDER);
            }
        }
        final JsonObject body = new JsonObject();
        body.addProperty("name", FOLDER);
        body.addProperty("mimeType", FOLDER_TYPE);
        final URI create = URI.create(endpoints.files() + "?fields=" + encode("id,name"));
        final JsonObject made = ok(web.bearer(accessToken).postJson(create, body), "files");
        final @Nullable String id = text(made, "id");
        if (id == null) {
            throw new IOException("Google created no folder");
        }
        return new Folder(id, FOLDER);
    }

    /** Revokes {@code token} and every token of its grant. Google answers 400 for a token it revoked already. */
    public void revoke(final String token) throws IOException {
        final Reply reply = web.postForm(endpoints.revoke(), Map.of("token", token));
        if (!reply.ok() && reply.status() != 400) {
            throw new IOException("Google answered " + reply.status() + " to the revocation");
        }
    }

    /** Returns the S256 challenge of a PKCE verifier. */
    static String challenge(final String verifier) {
        try {
            final byte[] digest =
                    MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException("every JVM has SHA-256", e);
        }
    }

    /** Returns the JSON of a 2xx answer. The error names only the status, as Google's body may echo a token. */
    private static JsonObject ok(final Reply reply, final String what) throws IOException {
        if (!reply.ok()) {
            throw new IOException("Google answered " + reply.status() + " on " + what);
        }
        final JsonObject json = Json.decode(reply.body(), JsonObject.class);
        if (json == null) {
            throw new IOException("Google answered " + what + " without JSON");
        }
        return json;
    }

    private static @Nullable String text(final JsonObject json, final String name) {
        final JsonElement value = json.get(name);
        return value == null || !value.isJsonPrimitive() || value.getAsString().isBlank() ? null : value.getAsString();
    }

    private static String encode(final String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
