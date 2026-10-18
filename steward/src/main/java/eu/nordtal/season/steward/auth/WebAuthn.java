package eu.nordtal.season.steward.auth;

import com.yubico.webauthn.AssertionRequest;
import com.yubico.webauthn.AssertionResultV2;
import com.yubico.webauthn.FinishAssertionOptions;
import com.yubico.webauthn.FinishRegistrationOptions;
import com.yubico.webauthn.RegistrationResult;
import com.yubico.webauthn.RelyingParty;
import com.yubico.webauthn.RelyingPartyV2;
import com.yubico.webauthn.StartAssertionOptions;
import com.yubico.webauthn.StartRegistrationOptions;
import com.yubico.webauthn.data.AuthenticatorAssertionResponse;
import com.yubico.webauthn.data.AuthenticatorAttestationResponse;
import com.yubico.webauthn.data.AuthenticatorSelectionCriteria;
import com.yubico.webauthn.data.AuthenticatorTransport;
import com.yubico.webauthn.data.ByteArray;
import com.yubico.webauthn.data.ClientAssertionExtensionOutputs;
import com.yubico.webauthn.data.ClientRegistrationExtensionOutputs;
import com.yubico.webauthn.data.PublicKeyCredential;
import com.yubico.webauthn.data.PublicKeyCredentialCreationOptions;
import com.yubico.webauthn.data.RelyingPartyIdentity;
import com.yubico.webauthn.data.ResidentKeyRequirement;
import com.yubico.webauthn.data.UserIdentity;
import com.yubico.webauthn.data.UserVerificationRequirement;
import com.yubico.webauthn.exception.AssertionFailedException;
import com.yubico.webauthn.exception.RegistrationFailedException;
import eu.nordtal.season.common.id.DiscordId;
import eu.nordtal.season.messages.MessageRef;
import eu.nordtal.season.steward.texts.StewardTexts;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Runs the WebAuthn ceremonies with the Yubico library. Requests cross its methods as JSON {@code String}s. */
public final class WebAuthn {

    private static final Logger log = LoggerFactory.getLogger(WebAuthn.class);

    private static final StewardTexts.Steward.Answer ANSWER =
            StewardTexts.TEXTS.steward().answer();

    private static final String DISPLAY_NAME = "Nordtal Steward";

    private static final Duration DIALOG = Duration.ofMinutes(2);

    private final RelyingPartyV2<Credentials.Key> relyingParty;
    private final Credentials credentials;
    private final String relyingPartyId;

    public WebAuthn(final String relyingPartyId, final String publicUrl, final Credentials credentials) {
        this.relyingPartyId = Objects.requireNonNull(relyingPartyId, "relyingPartyId");
        this.credentials = Objects.requireNonNull(credentials, "credentials");
        this.relyingParty = RelyingParty.builder()
                .identity(RelyingPartyIdentity.builder()
                        .id(relyingPartyId)
                        .name(DISPLAY_NAME)
                        .build())
                .credentialRepositoryV2(credentials)
                // The origin is narrower than the relying party id.
                .origins(Set.of(originOf(publicUrl)))
                // Accepts every authenticator, since no attestation trust source is configured.
                .allowUntrustedAttestation(true)
                .build();
    }

    static String originOf(final String publicUrl) {
        final URI parsed = URI.create(publicUrl);
        final String authority = parsed.getPort() < 0 ? parsed.getHost() : parsed.getHost() + ":" + parsed.getPort();
        return parsed.getScheme() + "://" + authority;
    }

    public String relyingPartyId() {
        return relyingPartyId;
    }

    public Ceremony startRegistration(final DiscordId discordId, final String displayName) {
        final PublicKeyCredentialCreationOptions request =
                relyingParty.startRegistration(StartRegistrationOptions.builder()
                        .user(UserIdentity.builder()
                                // Lookups use the handle, not the name.
                                .name(displayName)
                                .displayName(displayName)
                                .id(Credentials.handleOf(discordId))
                                .build())
                        .authenticatorSelection(AuthenticatorSelectionCriteria.builder()
                                // Sign-in is never usernameless.
                                .residentKey(ResidentKeyRequirement.DISCOURAGED)
                                // REQUIRED would refuse a key without a PIN.
                                .userVerification(UserVerificationRequirement.PREFERRED)
                                .build())
                        .timeout(DIALOG.toMillis())
                        .build());
        try {
            return new Ceremony(request.toJson(), request.toCredentialsCreateJson());
        } catch (IOException impossible) {
            // Happens only with a second jackson-databind on the classpath.
            throw new IllegalStateException(
                    "the registration request could not be serialised -"
                            + " check for a second jackson-databind on the classpath",
                    impossible);
        }
    }

    /** @throws Refused when the answer fails verification */
    public Registered finishRegistration(
            final String parked, final String answer, final String label, final DiscordId discordId) throws Refused {
        final PublicKeyCredentialCreationOptions request;
        final PublicKeyCredential<AuthenticatorAttestationResponse, ClientRegistrationExtensionOutputs> response;
        try {
            request = PublicKeyCredentialCreationOptions.fromJson(parked);
        } catch (IOException unreadable) {
            // Happens only if the column was edited by hand or the jar changed mid-ceremony.
            throw new Refused(ANSWER.ceremonyStale(true), unreadable);
        }
        try {
            response = PublicKeyCredential.parseRegistrationResponseJson(answer);
        } catch (IOException malformed) {
            throw new Refused(ANSWER.answerUnreadable(), malformed);
        }

        // The stored request must belong to the same account.
        final String intended = Credentials.accountOf(request.getUser().getId()).orElse("");
        if (!intended.equals(discordId.value())) {
            throw new Refused(ANSWER.ceremonyOtherAccount(true), null);
        }

        final RegistrationResult result;
        try {
            result = relyingParty.finishRegistration(FinishRegistrationOptions.builder()
                    .request(request)
                    .response(response)
                    .build());
        } catch (RegistrationFailedException refused) {
            log.info("a registration for {} was refused: {}", discordId, refused.getMessage());
            throw new Refused(ANSWER.keyRefused(true, String.valueOf(refused.getMessage())), refused);
        }

        final Set<AuthenticatorTransport> transports =
                new TreeSet<>(response.getResponse().getTransports());
        credentials.add(
                discordId,
                result.getKeyId().getId(),
                result.getPublicKeyCose(),
                result.getSignatureCount(),
                label,
                transports,
                result.isBackupEligible(),
                result.isBackedUp());
        return new Registered(result.getKeyId().getId(), label.trim(), result.isUserVerified(), result.isBackedUp());
    }

    public Ceremony startAssertion(final DiscordId discordId) throws Refused {
        if (credentials.of(discordId).isEmpty()) {
            // An empty allowCredentials makes the browser offer every passkey.
            throw new Refused(ANSWER.noKey(), null);
        }
        final AssertionRequest request = relyingParty.startAssertion(StartAssertionOptions.builder()
                .userHandle(Credentials.handleOf(discordId))
                // REQUIRED would refuse a key that registration accepted.
                .userVerification(UserVerificationRequirement.PREFERRED)
                .timeout(DIALOG.toMillis())
                .build());
        try {
            return new Ceremony(request.toJson(), request.toCredentialsGetJson());
        } catch (IOException impossible) {
            throw new IllegalStateException(
                    "the assertion request could not be serialised -"
                            + " check for a second jackson-databind on the classpath",
                    impossible);
        }
    }

    /** @throws Refused when the answer fails verification */
    public Held finishAssertion(final String parked, final String answer, final DiscordId discordId) throws Refused {
        final AssertionRequest request;
        final PublicKeyCredential<AuthenticatorAssertionResponse, ClientAssertionExtensionOutputs> response;
        try {
            request = AssertionRequest.fromJson(parked);
        } catch (IOException unreadable) {
            // The column holds one ceremony. A failure means a registration was finished as an assertion.
            throw new Refused(ANSWER.ceremonyStale(false), unreadable);
        }
        try {
            response = PublicKeyCredential.parseAssertionResponseJson(answer);
        } catch (IOException malformed) {
            throw new Refused(ANSWER.answerUnreadable(), malformed);
        }

        final String intended =
                request.getUserHandle().flatMap(Credentials::accountOf).orElse("");
        if (!intended.equals(discordId.value())) {
            throw new Refused(ANSWER.ceremonyOtherAccount(false), null);
        }

        final AssertionResultV2<Credentials.Key> result;
        try {
            result = relyingParty.finishAssertion(FinishAssertionOptions.builder()
                    .request(request)
                    .response(response)
                    .build());
        } catch (AssertionFailedException refused) {
            log.info("an assertion for {} was refused: {}", discordId, refused.getMessage());
            throw new Refused(ANSWER.keyRefused(false, String.valueOf(refused.getMessage())), refused);
        }
        if (!result.isSuccess()) {
            // The library throws on known failures. This covers any other.
            throw new Refused(ANSWER.keyNotAccepted(), null);
        }
        credentials.used(result.getCredential().getCredentialId(), result.getSignatureCount());
        return new Held(result.getCredential().label(), result.isUserVerified());
    }

    public record Held(String label, boolean userVerified) {}

    /**
     * Holds the library's JSON to store ({@code parked}).
     * {@code forBrowser} is the same request for {@code navigator.credentials}.
     */
    public record Ceremony(String parked, String forBrowser) {}

    public record Registered(ByteArray credentialId, String label, boolean userVerified, boolean backedUp) {}

    /** {@code why} is safe to show. */
    public static final class Refused extends Exception {

        private static final long serialVersionUID = 1L;

        private final transient MessageRef why;

        public Refused(final MessageRef why, final @Nullable Throwable cause) {
            super(why.key(), cause);
            this.why = Objects.requireNonNull(why, "why");
        }

        public MessageRef why() {
            return why;
        }
    }
}
