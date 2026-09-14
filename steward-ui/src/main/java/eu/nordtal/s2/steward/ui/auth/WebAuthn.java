package eu.nordtal.s2.steward.ui.auth;

import com.yubico.webauthn.FinishRegistrationOptions;
import com.yubico.webauthn.RegistrationResult;
import com.yubico.webauthn.RelyingParty;
import com.yubico.webauthn.RelyingPartyV2;
import com.yubico.webauthn.StartRegistrationOptions;
import com.yubico.webauthn.data.AuthenticatorAttestationResponse;
import com.yubico.webauthn.data.AuthenticatorSelectionCriteria;
import com.yubico.webauthn.data.AuthenticatorTransport;
import com.yubico.webauthn.data.ByteArray;
import com.yubico.webauthn.data.ClientRegistrationExtensionOutputs;
import com.yubico.webauthn.data.PublicKeyCredential;
import com.yubico.webauthn.data.PublicKeyCredentialCreationOptions;
import com.yubico.webauthn.data.RelyingPartyIdentity;
import com.yubico.webauthn.data.ResidentKeyRequirement;
import com.yubico.webauthn.data.UserIdentity;
import com.yubico.webauthn.data.UserVerificationRequirement;
import com.yubico.webauthn.exception.RegistrationFailedException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * The WebAuthn ceremonies - and the only class in this repository that speaks Jackson.
 *
 * <h2>The boundary is exactly this wide, on purpose</h2>
 * {@code com.yubico:webauthn-server-core} brings {@code jackson-databind} with it, and this
 * repository's rule is that Jackson goes in no Paper plugin and not into {@code :common}. Javalin
 * here is wired to Gson. So the two mappers must never meet the same object: <b>everything that
 * crosses this class's surface is a {@code String}</b>. The library serialises its own structures
 * with its own mapper; the routes hand those strings to a browser and park them in a column, and
 * Gson never sees anything but the outer envelope this service builds by hand.
 *
 * <p>Letting both mappers loose on one object graph is the single likeliest defect in this feature,
 * and the way it would show up is a sign-in that works for one brand of authenticator and fails for
 * another - because the fields that differ are the optional ones.</p>
 *
 * <h2>What a ceremony is, in two sentences</h2>
 * The server issues a challenge, the authenticator signs it, and the server checks the answer
 * <em>against the request it issued</em> - not merely against the challenge, which is why the whole
 * request is parked rather than 32 bytes of it. The parking is {@code steward_session}'s job (see
 * {@code V20}); this class only produces and consumes the strings.
 */
public final class WebAuthn {

    private static final Logger log = LoggerFactory.getLogger(WebAuthn.class);

    /**
     * What the browser prints in its own dialog and stores beside the key forever.
     *
     * <p>In code rather than in {@code steward-ui.yml}: it is the product's name, not a property of
     * a deployment, and a second copy in a config file would only ever be a way for the dev and
     * production deployments to disagree about what they are called.</p>
     */
    private static final String DISPLAY_NAME = "Nordtal Steward";

    /**
     * How long the browser is told to keep its dialog open.
     *
     * <p>Advisory - a browser may ignore it - which is why the column has its own ten-minute
     * window and does not rely on this. Two minutes is long enough to find a key in a drawer and
     * short enough that a forgotten dialog is not still waiting when somebody comes back.</p>
     */
    private static final Duration DIALOG = Duration.ofMinutes(2);

    private final RelyingPartyV2<Credentials.Key> relyingParty;
    private final Credentials credentials;
    private final String relyingPartyId;

    public WebAuthn(final @NotNull String relyingPartyId, final @NotNull String publicUrl,
                    final @NotNull Credentials credentials) {
        this.relyingPartyId = Objects.requireNonNull(relyingPartyId, "relyingPartyId");
        this.credentials = Objects.requireNonNull(credentials, "credentials");
        this.relyingParty = RelyingParty.builder()
                .identity(RelyingPartyIdentity.builder()
                        .id(relyingPartyId)
                        .name(DISPLAY_NAME)
                        .build())
                .credentialRepositoryV2(credentials)
                // THE ORIGIN IS THE ONE ADDRESS THIS SERVICE ANSWERS ON, and it is deliberately
                // narrower than the relying party id. The id says which keys work here
                // (everything under nordtal.eu, so a key survives the move to production); this
                // says which page a browser is allowed to have been on when one answered. Making
                // it as wide as the id would mean any subdomain could relay a ceremony through
                // this service, which is the thing the id's own comment warns about.
                .origins(Set.of(originOf(publicUrl)))
                // SPELLED OUT RATHER THAN LEFT TO A DEFAULT, because it reads like a lowered bar
                // and is not one. Attestation is a manufacturer's signed statement about what kind
                // of authenticator this is; trusting it needs a list of manufacturer certificates
                // and is how an organisation says "only these two models of YubiKey". Till chose
                // any authenticator, so there is no such list here, no trust source is configured,
                // and every key is therefore "untrusted" in that narrow sense - including an
                // iPhone's. Refusing them would refuse all of them. It says nothing about whether
                // the signature verifies, which is checked either way.
                .allowUntrustedAttestation(true)
                .build();
    }

    /**
     * The browser's origin, from the address an operator wrote down.
     *
     * <p>Scheme, host and - only if it was written - port. {@code new URI(...).getPort()} answers
     * -1 when none was given, and a default port spelled out is a different string from one left
     * out, which is exactly the comparison the library performs.</p>
     */
    static @NotNull String originOf(final @NotNull String publicUrl) {
        final URI parsed = URI.create(publicUrl);
        final String authority = parsed.getPort() < 0
                ? parsed.getHost()
                : parsed.getHost() + ":" + parsed.getPort();
        return parsed.getScheme() + "://" + authority;
    }

    /** The domain keys are registered against, for the interface to show. */
    public @NotNull String relyingPartyId() {
        return relyingPartyId;
    }

    /**
     * Starts a registration.
     *
     * <p>Keys this account already has go into {@code excludeCredentials}, which is what makes an
     * authenticator say "you have already registered this one" rather than silently creating a
     * second credential on the same device. The library reads them from the repository; nothing
     * here has to pass them.</p>
     *
     * @return the request in two forms - one to park, one to hand the browser
     */
    public @NotNull Ceremony startRegistration(final @NotNull String discordId,
                                               final @NotNull String displayName) {
        final PublicKeyCredentialCreationOptions request = relyingParty.startRegistration(
                StartRegistrationOptions.builder()
                        .user(UserIdentity.builder()
                                // `name` is what a passkey manager lists the entry under, and
                                // `displayName` is what it prints in bold. Both are the person's
                                // Discord name; the id below is the handle, and is the only one of
                                // the three that anything is ever looked up by.
                                .name(displayName)
                                .displayName(displayName)
                                .id(Credentials.handleOf(discordId))
                                .build())
                        .authenticatorSelection(AuthenticatorSelectionCriteria.builder()
                                // DISCOURAGED, and that is not a lowering of the bar. A
                                // discoverable credential is what makes a usernameless sign-in
                                // possible - and this sign-in is never usernameless: Discord has
                                // already said who this is before a key is ever asked for. On a
                                // hardware key a discoverable credential costs one of about
                                // twenty-five permanent slots, so asking for one we cannot use
                                // would spend somebody's YubiKey on nothing.
                                .residentKey(ResidentKeyRequirement.DISCOURAGED)
                                // PREFERRED rather than REQUIRED, because Till chose "any
                                // authenticator" (2026-09-14). REQUIRED refuses a hardware key
                                // that has no PIN set, at registration, with a message the browser
                                // writes - and the first person that happens to would be locked
                                // out of a fresh deployment with no way in. Every phone and every
                                // passkey verifies anyway; a bare USB key contributes possession,
                                // which is the factor that was actually missing.
                                .userVerification(UserVerificationRequirement.PREFERRED)
                                .build())
                        .timeout(DIALOG.toMillis())
                        .build());
        try {
            return new Ceremony(request.toJson(), request.toCredentialsCreateJson());
        } catch (IOException impossible) {
            // The library serialising its own freshly built object. If this ever throws, the
            // classpath has two Jacksons on it and that is the thing to go and look at.
            throw new IllegalStateException("the registration request could not be serialised -"
                    + " check for a second jackson-databind on the classpath", impossible);
        }
    }

    /**
     * Finishes a registration and writes the key down.
     *
     * @param parked   what {@link #startRegistration} said to park, back out of the session
     * @param answer   the browser's {@code PublicKeyCredential}, as it serialised it
     * @param label    what the person calls this key
     * @param discordId whose account this is - checked against the parked request, not trusted
     * @return the key as it was stored
     * @throws Refused with a sentence that is safe to show, for every way this can legitimately go
     *                 wrong: a replayed challenge, a key already registered, a signature that does
     *                 not verify, a browser on the wrong origin
     */
    public @NotNull Registered finishRegistration(final @NotNull String parked,
                                                  final @NotNull String answer,
                                                  final @NotNull String label,
                                                  final @NotNull String discordId)
            throws Refused {
        final PublicKeyCredentialCreationOptions request;
        final PublicKeyCredential<AuthenticatorAttestationResponse,
                ClientRegistrationExtensionOutputs> response;
        try {
            request = PublicKeyCredentialCreationOptions.fromJson(parked);
        } catch (IOException unreadable) {
            // A row this service wrote itself. Only reachable if the column was edited by hand or
            // the jar changed under a ceremony that was in flight across a deployment.
            throw new Refused("that registration was started by a different version of this"
                    + " service - start again", unreadable);
        }
        try {
            response = PublicKeyCredential.parseRegistrationResponseJson(answer);
        } catch (IOException malformed) {
            throw new Refused("the browser's answer could not be read", malformed);
        }

        // THE ACCOUNT IS CHECKED AGAINST THE PARKED REQUEST, not against what arrived. The parked
        // request was written by this service for one session; if the session has meanwhile
        // become a different person's, finishing it must not attach their key to the first
        // person's account. It cannot happen today - the id rotates at sign-in - and it is one
        // comparison.
        final String intended = Credentials.accountOf(request.getUser().getId()).orElse("");
        if (!intended.equals(discordId)) {
            throw new Refused("that registration was started for a different account", null);
        }

        final RegistrationResult result;
        try {
            result = relyingParty.finishRegistration(FinishRegistrationOptions.builder()
                    .request(request)
                    .response(response)
                    .build());
        } catch (RegistrationFailedException refused) {
            log.info("a registration for {} was refused: {}", discordId, refused.getMessage());
            throw new Refused("that key could not be registered: " + refused.getMessage(), refused);
        }

        final Set<AuthenticatorTransport> transports =
                new TreeSet<>(response.getResponse().getTransports());
        credentials.add(discordId, result.getKeyId().getId(), result.getPublicKeyCose(),
                result.getSignatureCount(), label, transports,
                result.isBackupEligible(), result.isBackedUp());
        return new Registered(result.getKeyId().getId(), label.trim(), result.isUserVerified(),
                result.isBackedUp());
    }

    /**
     * One request, in the two forms it is needed in.
     *
     * @param parked     the library's own JSON, for {@code steward_session.webauthn_request}
     * @param forBrowser the same thing wrapped as {@code {"publicKey": …}}, which is what
     *                   {@code navigator.credentials.create} takes - handed to the browser as an
     *                   opaque string and never re-parsed on this side
     */
    public record Ceremony(@NotNull String parked, @NotNull String forBrowser) {
    }

    /** What was written down, for the answer the browser gets back. */
    public record Registered(@NotNull ByteArray credentialId, @NotNull String label,
                             boolean userVerified, boolean backedUp) {
    }

    /**
     * A ceremony that did not pass - for an ordinary reason.
     *
     * <p>Checked rather than unchecked, because every one of these has a sentence the person in
     * front of the browser should read, and a route that forgot to catch it would show them a 500
     * instead. The message is safe to show: the library's own refusals name what did not match,
     * never a secret.</p>
     */
    public static final class Refused extends Exception {

        public Refused(final @NotNull String message, final @Nullable Throwable cause) {
            super(message, cause);
        }
    }
}
