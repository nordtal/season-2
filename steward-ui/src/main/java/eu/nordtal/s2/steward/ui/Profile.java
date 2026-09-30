package eu.nordtal.s2.steward.ui;

import com.yubico.webauthn.data.ByteArray;
import eu.nordtal.s2.database.access.Person;
import eu.nordtal.s2.steward.ui.auth.Credentials;
import eu.nordtal.s2.steward.ui.auth.DiscordAuth;
import eu.nordtal.s2.steward.ui.auth.Sessions;
import eu.nordtal.s2.steward.ui.auth.WebAuthn;
import eu.nordtal.s2.steward.ui.data.Data;
import eu.nordtal.s2.steward.ui.data.ExampleValues;
import io.javalin.http.Context;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    /** The keys of one account, as {@code /api/me} lists them. */
    private List<Map<String, Object>> keysOf(final String discordId) {
        final List<Map<String, Object>> listed = new ArrayList<>();
        for (final Credentials.Key key : credentials().of(discordId)) {
            final Map<String, Object> one = new LinkedHashMap<>();
            one.put("id", new ByteArray(key.credentialId()).getBase64Url());
            one.put("label", key.label());
            one.put("registeredAt", key.createdAt().toString());
            if (key.lastUsedAt() != null) {
                one.put("lastUsedAt", key.lastUsedAt().toString());
            }
            if (key.transports() != null && !key.transports().isBlank()) {
                one.put("transports", List.of(key.transports().split(",")));
            }
            // Absent rather than false when the authenticator did not say.
            if (key.backedUp() != null) {
                one.put("backedUp", key.backedUp());
            }
            listed.add(one);
        }
        return listed;
    }

    /**
     * The Discord avatar {@code /api/people} would print for this account, or empty on any failure.
     *
     * Read from the {@code person} row directly, since the roster's list call pages the whole access list.
     */
    private Optional<String> avatarOf(final String discordId) {
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
        final Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("signedIn", found.isPresent());
        found.ifPresent(who -> {
            answer.put("id", who.signedInDiscordId());
            answer.put("name", who.signedInDisplayName());
            // Written with the row; this route is the one place it may be read.
            answer.put("csrf", who.csrf());
            answer.put("signedInAt", who.createdAt().toString());
            answer.put("expiresAt", who.expiresAt().toString());
            // `keys` empty is the forced setup page; `verified` is whether a key was held this session.
            answer.put("keys", keysOf(who.signedInDiscordId()));
            answer.put("verified", who.verified());
            if (who.verifiedAt() != null) {
                answer.put("verifiedAt", who.verifiedAt().toString());
            }
            answer.put("relyingPartyId", webauthn().relyingPartyId());
            avatarOf(who.signedInDiscordId()).ifPresent(url -> answer.put("discordAvatarUrl", url));
        });
        discord.whatIsMissing().ifPresent(missing -> answer.put("signInUnavailable", missing));
        answer.put(
                "webauthn",
                "A security key is required: it is asked for at every sign-in, and"
                        + " again before anything that changes something - one touch covers the next "
                        + StewardUi.STEP_UP.toMinutes() + " minutes.");
        // A number too, so the dialog does not repeat a literal that could drift.
        answer.put("stepUpMinutes", StewardUi.STEP_UP.toMinutes());
        ctx.json(answer);
    }
}
