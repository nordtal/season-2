package eu.nordtal.season.steward.auth;

import com.yubico.webauthn.data.ByteArray;
import eu.nordtal.season.common.id.DiscordId;
import eu.nordtal.season.database.access.Person;
import eu.nordtal.season.steward.data.Data;
import eu.nordtal.season.steward.data.ExampleValues;
import io.javalin.http.Context;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Serves {@code /api/me} and {@code /api/message-examples}. */
public final class Profile {

    private static final Logger log = LoggerFactory.getLogger(Profile.class);

    private final Function<Context, Sessions.Session> requireSession;
    private final Function<Context, Optional<Sessions.Session>> session;
    private final Credentials credentials;
    private final Authenticators authenticators;
    private final Data data;
    private final Supplier<Optional<String>> signInMissing;
    private final WebAuthn webauthn;
    private final ExampleValues exampleValues;

    public Profile(
            final Function<Context, Sessions.Session> requireSession,
            final Function<Context, Optional<Sessions.Session>> session,
            final Credentials credentials,
            final Authenticators authenticators,
            final Data data,
            final Supplier<Optional<String>> signInMissing,
            final WebAuthn webauthn,
            final ExampleValues exampleValues) {
        this.requireSession = requireSession;
        this.session = session;
        this.credentials = credentials;
        this.authenticators = authenticators;
        this.data = data;
        this.signInMissing = signInMissing;
        this.webauthn = webauthn;
        this.exampleValues = exampleValues;
    }

    public void messageExamples(final Context ctx) {
        final Sessions.Session who = requireSession.apply(ctx);
        ctx.json(exampleValues.of(who.signedInDiscordId(), who.signedInDisplayName()));
    }

    /** {@code backedUp} is null when the authenticator did not report it. */
    public record SecurityKey(
            String id,
            String label,
            Instant registeredAt,
            @Nullable Instant lastUsedAt,
            @Nullable List<String> transports,
            @Nullable Boolean backedUp) {}

    /** The session fields are null while nobody is signed in. */
    public record Me(
            boolean signedIn,
            @Nullable DiscordId id,
            @Nullable String name,
            @Nullable String csrf,
            @Nullable Instant signedInAt,
            @Nullable Instant expiresAt,
            @Nullable String signInUnavailable,
            @Nullable List<SecurityKey> keys,
            @Nullable Authenticator authenticator,
            boolean authenticatorOffered,
            @Nullable Boolean verified,
            @Nullable Instant verifiedAt,
            @Nullable String relyingPartyId,
            @Nullable Instant stepUpUntil,
            List<StepUpHold> stepUpHolds,
            @Nullable String discordAvatarUrl) {}

    public record Authenticator(Instant addedAt, @Nullable Instant lastUsedAt) {}

    public record StepUpHold(SecondFactor.StepUpLength hold, long minutes) {}

    private static final List<StepUpHold> HOLDS = Arrays.stream(SecondFactor.StepUpLength.values())
            .map(hold -> new StepUpHold(hold, hold.length().toMinutes()))
            .toList();

    private boolean offered() {
        return authenticators.offered();
    }

    private @Nullable Authenticator appOf(final DiscordId discordId) {
        return authenticators
                .of(discordId)
                .map(row -> new Authenticator(Objects.requireNonNull(row.confirmedAt()), row.lastUsedAt()))
                .orElse(null);
    }

    private List<SecurityKey> keysOf(final DiscordId discordId) {
        final List<SecurityKey> listed = new ArrayList<>();
        for (final Credentials.Key key : credentials.of(discordId)) {
            listed.add(new SecurityKey(
                    new ByteArray(key.credentialId()).getBase64Url(),
                    key.label(),
                    key.createdAt(),
                    key.lastUsedAt(),
                    key.transports().isEmpty() ? null : key.transports(),
                    key.backedUp()));
        }
        return listed;
    }

    /** Returns the Discord avatar URL from the {@code person} row, or empty on any failure. */
    private Optional<String> avatarOf(final DiscordId discordId) {
        try {
            return data.access()
                    .personOf(discordId)
                    .map(Person::discordAvatarUrl)
                    .filter(url -> url != null && !url.isBlank());
        } catch (final RuntimeException e) {
            log.warn("could not read the Discord avatar of {}, so /api/me answers none", discordId, e);
            return Optional.empty();
        }
    }

    public void whoAmI(final Context ctx) {
        final Optional<Sessions.Session> found = session.apply(ctx);
        final @Nullable String missing = signInMissing.get().orElse(null);
        if (found.isEmpty()) {
            ctx.json(new Me(
                    false, null, null, null, null, null, missing, null, null, offered(), null, null, null, null, HOLDS,
                    null));
            return;
        }
        final Sessions.Session who = found.get();
        // Only this route returns the CSRF token.
        ctx.json(new Me(
                true,
                who.signedInDiscordId(),
                who.signedInDisplayName(),
                who.csrf(),
                who.createdAt(),
                who.expiresAt(),
                missing,
                keysOf(who.signedInDiscordId()),
                appOf(who.signedInDiscordId()),
                offered(),
                who.verified(),
                who.verifiedAt(),
                webauthn.relyingPartyId(),
                who.stepUpUntil(),
                HOLDS,
                avatarOf(who.signedInDiscordId()).orElse(null)));
    }
}
