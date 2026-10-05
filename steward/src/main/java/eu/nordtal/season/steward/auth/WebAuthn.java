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

/**
 * The WebAuthn ceremonies, and the only class in this repository that speaks Jackson.
 *
 * Everything crossing its surface is a {@code String}, so the library's mapper and Gson never meet one object.
 */
public final class WebAuthn {

    private static final Logger log = LoggerFactory.getLogger(WebAuthn.class);

    private static final StewardTexts.Steward.Answer ANSWER =
            StewardTexts.TEXTS.steward().answer();

    /** What the browser shows in its dialog and stores beside the key. */
    private static final String DISPLAY_NAME = "Nordtal Steward";

    /** How long the browser is told to keep its dialog open; advisory only. */
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
                // Narrower than the relying party id: the one page a browser may have been on.
                .origins(Set.of(originOf(publicUrl)))
                // No trust source is configured, so every authenticator counts as untrusted.
                .allowUntrustedAttestation(true)
                .build();
    }

    /** The browser's origin, from the address an operator wrote down: scheme, host and port. */
    static String originOf(final String publicUrl) {
        final URI parsed = URI.create(publicUrl);
        final String authority = parsed.getPort() < 0 ? parsed.getHost() : parsed.getHost() + ":" + parsed.getPort();
        return parsed.getScheme() + "://" + authority;
    }

    /** The domain keys are registered against, for the interface to show. */
    public String relyingPartyId() {
        return relyingPartyId;
    }

    /** Starts a registration, answered as one request to park and one to hand the browser. */
    public Ceremony startRegistration(final DiscordId discordId, final String displayName) {
        final PublicKeyCredentialCreationOptions request =
                relyingParty.startRegistration(StartRegistrationOptions.builder()
                        .user(UserIdentity.builder()
                                // The handle, not name/displayName, is what anything is looked up by.
                                .name(displayName)
                                .displayName(displayName)
                                .id(Credentials.handleOf(discordId))
                                .build())
                        .authenticatorSelection(AuthenticatorSelectionCriteria.builder()
                                // DISCOURAGED: sign-in is never usernameless, and slots are scarce.
                                .residentKey(ResidentKeyRequirement.DISCOURAGED)
                                // PREFERRED, not REQUIRED: a key with no PIN set would else be refused.
                                .userVerification(UserVerificationRequirement.PREFERRED)
                                .build())
                        .timeout(DIALOG.toMillis())
                        .build());
        try {
            return new Ceremony(request.toJson(), request.toCredentialsCreateJson());
        } catch (IOException impossible) {
            // Only reachable with a second jackson-databind on the classpath.
            throw new IllegalStateException(
                    "the registration request could not be serialised -"
                            + " check for a second jackson-databind on the classpath",
                    impossible);
        }
    }

    /**
     * Finishes a registration and writes the key down.
     *
     * @param parked what {@link #startRegistration} said to park
     * @param answer the browser's {@code PublicKeyCredential}, as it serialised it
     * @param label what the person calls this key
     * @param discordId whose account this is, checked against the parked request
     * @return the key as it was stored
     * @throws Refused with a message safe to show: a replayed challenge, a known key, a bad signature or origin
     */
    public Registered finishRegistration(
            final String parked, final String answer, final String label, final DiscordId discordId) throws Refused {
        final PublicKeyCredentialCreationOptions request;
        final PublicKeyCredential<AuthenticatorAttestationResponse, ClientRegistrationExtensionOutputs> response;
        try {
            request = PublicKeyCredentialCreationOptions.fromJson(parked);
        } catch (IOException unreadable) {
            // Only reachable if the column was edited by hand, or the jar changed mid-ceremony.
            throw new Refused(ANSWER.ceremonyStale(true), unreadable);
        }
        try {
            response = PublicKeyCredential.parseRegistrationResponseJson(answer);
        } catch (IOException malformed) {
            throw new Refused(ANSWER.answerUnreadable(), malformed);
        }

        // Checked against the parked request so a session that changed hands cannot attach a key.
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

    /**
     * Starts an authentication for one account, answered as one request to park and one to hand the browser.
     *
     * @throws Refused when the account has no key, which the caller turns into the setup page
     */
    public Ceremony startAssertion(final DiscordId discordId) throws Refused {
        if (credentials.of(discordId).isEmpty()) {
            // An empty allowCredentials would make the browser offer every passkey it holds.
            throw new Refused(ANSWER.noKey(), null);
        }
        final AssertionRequest request = relyingParty.startAssertion(StartAssertionOptions.builder()
                .userHandle(Credentials.handleOf(discordId))
                // Matches registration: REQUIRED would refuse the bare USB key registration accepted.
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

    /**
     * Finishes an authentication: verifies the signature and moves the counter on.
     *
     * @param parked what {@link #startAssertion} said to park
     * @param answer the browser's {@code PublicKeyCredential}, as it serialised it
     * @param discordId whose session this is, checked against the parked request
     * @return the key that answered
     * @throws Refused with a message safe to show: a replayed challenge, another's key, a bad signature or origin
     */
    public Held finishAssertion(final String parked, final String answer, final DiscordId discordId) throws Refused {
        final AssertionRequest request;
        final PublicKeyCredential<AuthenticatorAssertionResponse, ClientAssertionExtensionOutputs> response;
        try {
            request = AssertionRequest.fromJson(parked);
        } catch (IOException unreadable) {
            // The column holds one ceremony; this means a registration was started and finished as one.
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
            // The library throws on every failure it knows about; this covers the one it does not.
            throw new Refused(ANSWER.keyNotAccepted(), null);
        }
        credentials.used(result.getCredential().getCredentialId(), result.getSignatureCount());
        return new Held(result.getCredential().label(), result.isUserVerified());
    }

    /** The key that just answered, for the journal and for the sentence on screen. */
    public record Held(String label, boolean userVerified) {}

    /** One request in two forms: the library's JSON to park, and the same wrapped for {@code navigator.credentials}. */
    public record Ceremony(String parked, String forBrowser) {}

    /** What was written down, for the answer the browser gets back. */
    public record Registered(ByteArray credentialId, String label, boolean userVerified, boolean backedUp) {}

    /** A ceremony that did not pass, with why as a message of Steward's bundle, which is safe to show. */
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
