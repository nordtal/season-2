package eu.nordtal.s2.steward.web;

import com.yubico.webauthn.data.ByteArray;
import eu.nordtal.s2.common.id.DiscordId;
import eu.nordtal.s2.database.access.Person;
import eu.nordtal.s2.steward.auth.Credentials;
import eu.nordtal.s2.steward.auth.DiscordAuth;
import eu.nordtal.s2.steward.auth.Sessions;
import eu.nordtal.s2.steward.auth.WebAuthn;
import eu.nordtal.s2.steward.data.Data;
import eu.nordtal.s2.steward.data.ExampleValues;
import io.javalin.http.Context;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** {@code /api/me} and {@code /api/message-examples}: what the signed-in account is told about itself. */
final class Profile {

    private static final Logger log = LoggerFactory.getLogger(Profile.class);

    private final Function<Context, Sessions.Session> requireSession;
    private final Function<Context, Optional<Sessions.Session>> session;
    private final @Nullable Credentials credentials;
    private final @Nullable Data data;
    private final DiscordAuth discord;
    private final @Nullable WebAuthn webauthn;
    private final @Nullable ExampleValues exampleValues;

    Profile(
            final Function<Context, Sessions.Session> requireSession,
            final Function<Context, Optional<Sessions.Session>> session,
            final @Nullable Credentials credentials,
            final @Nullable Data data,
            final DiscordAuth discord,
            final @Nullable WebAuthn webauthn,
            final @Nullable ExampleValues exampleValues) {
        this.requireSession = requireSession;
        this.session = session;
        this.credentials = credentials;
        this.data = data;
        this.discord = discord;
        this.webauthn = webauthn;
        this.exampleValues = exampleValues;
    }

    private Credentials credentials() {
        return Objects.requireNonNull(credentials, "this route needs credentials, which this instance has none of");
    }

    private Data data() {
        return Objects.requireNonNull(data, "this route needs the database, which this instance has none of");
    }

    private WebAuthn webauthn() {
        return Objects.requireNonNull(webauthn, "this route needs webauthn, which this instance has none of");
    }

    private ExampleValues exampleValues() {
        return Objects.requireNonNull(exampleValues, "this route needs exampleValues, which this instance has none of");
    }

    /** {@code GET /api/message-examples}: one example value per placeholder type and property. */
    void messageExamples(final Context ctx) {
        final Sessions.Session who = requireSession.apply(ctx);
        ctx.json(exampleValues().of(who.signedInDiscordId(), who.signedInDisplayName()));
    }

    /** One registered security key; {@code backedUp} is absent when the authenticator did not say. */
    public record SecurityKey(
            String id,
            String label,
            Instant registeredAt,
            @Nullable Instant lastUsedAt,
            @Nullable List<String> transports,
            @Nullable Boolean backedUp) {}

    /**
     * {@code GET /api/me}: who is signed in, the CSRF token every write needs, and what the key covers.
     *
     * Everything about the session is absent while nobody is signed in.
     */
    public record Me(
            boolean signedIn,
            @Nullable DiscordId id,
            @Nullable String name,
            @Nullable String csrf,
            @Nullable Instant signedInAt,
            @Nullable Instant expiresAt,
            @Nullable String signInUnavailable,
            String webauthn,
            @Nullable List<SecurityKey> keys,
            @Nullable Boolean verified,
            @Nullable Instant verifiedAt,
            @Nullable String relyingPartyId,
            long stepUpMinutes,
            @Nullable String discordAvatarUrl) {}

    /** The keys of one account, as {@code /api/me} lists them. */
    private List<SecurityKey> keysOf(final DiscordId discordId) {
        final List<SecurityKey> listed = new ArrayList<>();
        for (final Credentials.Key key : credentials().of(discordId)) {
            final String transports = key.transports();
            listed.add(new SecurityKey(
                    new ByteArray(key.credentialId()).getBase64Url(),
                    key.label(),
                    key.createdAt(),
                    key.lastUsedAt(),
                    transports == null || transports.isBlank() ? null : List.of(transports.split(",")),
                    key.backedUp()));
        }
        return listed;
    }

    /**
     * The Discord avatar {@code /api/people} would print for this account, or empty on any failure.
     *
     * Read from the {@code person} row directly, since the roster's list call pages the whole access list.
     */
    private Optional<String> avatarOf(final DiscordId discordId) {
        try {
            return data().access()
                    .personOf(discordId)
                    .map(Person::discordAvatarUrl)
                    .filter(url -> url != null && !url.isBlank());
        } catch (final RuntimeException e) {
            log.warn("could not read the Discord avatar of {}, so /api/me answers none", discordId, e);
            return Optional.empty();
        }
    }

    void whoAmI(final Context ctx) {
        final Optional<Sessions.Session> found = session.apply(ctx);
        final @Nullable String missing = discord.whatIsMissing().orElse(null);
        final String webauthn = "A security key is required: it is asked for at every sign-in, and"
                + " again before anything that changes something - one touch covers the next "
                + Web.STEP_UP.toMinutes() + " minutes.";
        if (found.isEmpty()) {
            ctx.json(new Me(
                    false,
                    null,
                    null,
                    null,
                    null,
                    null,
                    missing,
                    webauthn,
                    null,
                    null,
                    null,
                    null,
                    Web.STEP_UP.toMinutes(),
                    null));
            return;
        }
        final Sessions.Session who = found.get();
        // The CSRF token is written with the row, and this route is the one place it may be read.
        ctx.json(new Me(
                true,
                who.signedInDiscordId(),
                who.signedInDisplayName(),
                who.csrf(),
                who.createdAt(),
                who.expiresAt(),
                missing,
                webauthn,
                keysOf(who.signedInDiscordId()),
                who.verified(),
                who.verifiedAt(),
                webauthn().relyingPartyId(),
                Web.STEP_UP.toMinutes(),
                avatarOf(who.signedInDiscordId()).orElse(null)));
    }
}
