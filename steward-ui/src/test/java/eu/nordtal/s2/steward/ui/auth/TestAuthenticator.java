package eu.nordtal.s2.steward.ui.auth;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.upokecenter.cbor.CBORObject;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;

/**
 * A security key, in software, for the tests.
 *
 * <h2>Why this exists rather than a mock</h2>
 * The thing under test is whether a browser's answer is accepted or refused, and every interesting
 * case is a <em>malformed or mismatched answer</em>: a challenge that was never issued, an origin
 * that is not this service, a credential that belongs to somebody else. A mock of the library
 * would prove that the mock agrees with itself; what is needed is real bytes in the real shape, so
 * that the real verification has something to refuse.
 *
 * <p>So this produces exactly what an authenticator produces: CBOR inside base64url inside JSON.
 * It is about eighty lines because that is genuinely all an authenticator's registration answer is
 * when the attestation format is {@code none} - which is the format every platform authenticator
 * and every self-attesting key uses, and the one this service accepts.</p>
 *
 * <h2>What it deliberately does not do</h2>
 * <b>Registration signs nothing.</b> An {@code fmt: "none"} attestation carries no signature at all -
 * the public key is asserted, not attested - so it needs no private key. An assertion does, and
 * {@link #assertion(String, String)} is where the pair generated in the constructor is finally
 * used.
 *
 * <p>It is also happy to lie. Every one of the four methods takes the origin and the challenge as
 * parameters precisely so a test can hand over ones that are wrong, and
 * {@link #assertion(String, String, String, long)} takes the signature counter too - a counter
 * that has gone backwards is what a cloned authenticator looks like.</p>
 */
public final class TestAuthenticator {

    private static final Gson GSON = new Gson();
    private static final Base64.Encoder URL = Base64.getUrlEncoder().withoutPadding();

    /** UP (user present) | UV (user verified) | AT (attested credential data follows). */
    private static final byte FLAGS = 0x01 | 0x04 | 0x40;

    private final KeyPair keyPair;
    private final byte[] credentialId;

    public TestAuthenticator() {
        try {
            final KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"));
            this.keyPair = generator.generateKeyPair();
        } catch (Exception impossible) {
            throw new IllegalStateException("this JVM has no P-256", impossible);
        }
        this.credentialId = new byte[32];
        new SecureRandom().nextBytes(credentialId);
    }

    /** The credential id this authenticator will answer for, base64url as the browser sends it. */
    public String credentialId() {
        return URL.encodeToString(credentialId);
    }

    /**
     * A registration answer to the request the server just issued.
     *
     * @param creationOptions what {@code /auth/webauthn/register/start} returned, verbatim
     * @param origin          the page the browser claims to have been on
     */
    public String register(final String creationOptions, final String origin) {
        final JsonObject publicKey =
                GSON.fromJson(creationOptions, JsonObject.class).getAsJsonObject("publicKey");
        return register(creationOptions, origin, publicKey.get("challenge").getAsString());
    }

    /**
     * The same, with the challenge chosen by the caller - so that a test can answer a question
     * nobody asked.
     */
    public String register(final String creationOptions, final String origin, final String challenge) {
        final JsonObject publicKey =
                GSON.fromJson(creationOptions, JsonObject.class).getAsJsonObject("publicKey");
        final String relyingPartyId = publicKey.getAsJsonObject("rp").get("id").getAsString();

        final String clientData = "{\"type\":\"webauthn.create\",\"challenge\":\"" + challenge + "\",\"origin\":\""
                + origin + "\",\"crossOrigin\":false}";

        final JsonObject response = new JsonObject();
        response.addProperty("clientDataJSON", URL.encodeToString(clientData.getBytes(StandardCharsets.UTF_8)));
        response.addProperty("attestationObject", URL.encodeToString(attestationObject(relyingPartyId)));
        final com.google.gson.JsonArray transports = new com.google.gson.JsonArray();
        transports.add("usb");
        response.add("transports", transports);

        final JsonObject credential = new JsonObject();
        credential.addProperty("type", "public-key");
        credential.addProperty("id", credentialId());
        credential.addProperty("rawId", credentialId());
        credential.add("response", response);
        credential.add("clientExtensionResults", new JsonObject());
        return GSON.toJson(credential);
    }

    /**
     * An assertion answer to the challenge the server just issued.
     *
     * @param requestOptions what {@code /auth/webauthn/authenticate/start} returned, verbatim
     * @param origin         the page the browser claims to have been on
     */
    public String assertion(final String requestOptions, final String origin) {
        final JsonObject publicKey =
                GSON.fromJson(requestOptions, JsonObject.class).getAsJsonObject("publicKey");
        return assertion(requestOptions, origin, publicKey.get("challenge").getAsString(), 0);
    }

    /**
     * The same, with the challenge and the signature counter chosen by the caller.
     *
     * <p>Both are here so a test can lie about them: a challenge nobody issued, and a counter that
     * has gone backwards - which is what a cloned authenticator looks like and the one thing the
     * counter exists to catch.</p>
     */
    public String assertion(
            final String requestOptions, final String origin, final String challenge, final long signCount) {
        final JsonObject publicKey =
                GSON.fromJson(requestOptions, JsonObject.class).getAsJsonObject("publicKey");
        final String relyingPartyId = publicKey.get("rpId").getAsString();

        final String clientData = "{\"type\":\"webauthn.get\",\"challenge\":\"" + challenge + "\",\"origin\":\""
                + origin + "\",\"crossOrigin\":false}";
        final byte[] clientDataBytes = clientData.getBytes(StandardCharsets.UTF_8);
        // NO ATTESTED CREDENTIAL DATA, so no AT flag: an assertion carries rpIdHash, flags and the
        // counter and nothing else. Sending the registration's authenticator data here is the
        // mistake that looks like it works right up to the signature check.
        final ByteBuffer authData = ByteBuffer.allocate(32 + 1 + 4);
        authData.put(sha256(relyingPartyId.getBytes(StandardCharsets.UTF_8)));
        authData.put((byte) (0x01 | 0x04));
        authData.putInt((int) signCount);
        final byte[] authenticatorData = authData.array();

        // The signature is over authenticatorData ‖ SHA-256(clientDataJSON), in that order. The
        // whole of what a security key does, and the only part of this class that needs the
        // private half of the pair.
        final byte[] signed = new byte[authenticatorData.length + 32];
        System.arraycopy(authenticatorData, 0, signed, 0, authenticatorData.length);
        System.arraycopy(sha256(clientDataBytes), 0, signed, authenticatorData.length, 32);
        final byte[] signature;
        try {
            final Signature ecdsa = Signature.getInstance("SHA256withECDSA");
            ecdsa.initSign(keyPair.getPrivate());
            ecdsa.update(signed);
            signature = ecdsa.sign();
        } catch (Exception impossible) {
            throw new IllegalStateException("this JVM cannot sign with P-256", impossible);
        }

        final JsonObject response = new JsonObject();
        response.addProperty("clientDataJSON", URL.encodeToString(clientDataBytes));
        response.addProperty("authenticatorData", URL.encodeToString(authenticatorData));
        response.addProperty("signature", URL.encodeToString(signature));

        final JsonObject credential = new JsonObject();
        credential.addProperty("type", "public-key");
        credential.addProperty("id", credentialId());
        credential.addProperty("rawId", credentialId());
        credential.add("response", response);
        credential.add("clientExtensionResults", new JsonObject());
        return GSON.toJson(credential);
    }

    /**
     * {@code {fmt: "none", attStmt: {}, authData: …}} - the whole of a self-asserted registration.
     */
    private byte[] attestationObject(final String relyingPartyId) {
        return CBORObject.NewMap()
                .Add("fmt", "none")
                .Add("attStmt", CBORObject.NewMap())
                .Add("authData", CBORObject.FromObject(authenticatorData(relyingPartyId)))
                .EncodeToBytes();
    }

    /**
     * rpIdHash ‖ flags ‖ signCount ‖ aaguid ‖ credentialIdLength ‖ credentialId ‖ COSE public key.
     *
     * <p>The AAGUID is sixteen zero bytes, which is what an authenticator that declines to say what
     * model it is reports - and what every {@code none} attestation carries.</p>
     */
    private byte[] authenticatorData(final String relyingPartyId) {
        final byte[] cose = coseKey();
        final ByteBuffer data = ByteBuffer.allocate(32 + 1 + 4 + 16 + 2 + credentialId.length + cose.length);
        data.put(sha256(relyingPartyId.getBytes(StandardCharsets.UTF_8)));
        data.put(FLAGS);
        data.putInt(0);
        data.put(new byte[16]);
        data.putShort((short) credentialId.length);
        data.put(credentialId);
        data.put(cose);
        return data.array();
    }

    /** The public key as COSE_Key: EC2 / ES256 / P-256, with x and y as 32 bytes each. */
    private byte[] coseKey() {
        final ECPublicKey pub = (ECPublicKey) keyPair.getPublic();
        return CBORObject.NewMap()
                .Add(1, 2) // kty: EC2
                .Add(3, -7) // alg: ES256
                .Add(-1, 1) // crv: P-256
                .Add(-2, CBORObject.FromObject(coordinate(pub.getW().getAffineX())))
                .Add(-3, CBORObject.FromObject(coordinate(pub.getW().getAffineY())))
                .EncodeToBytes();
    }

    /**
     * A coordinate as exactly 32 bytes.
     *
     * <p>{@code BigInteger.toByteArray()} is two's complement: it prepends a zero byte whenever the
     * top bit is set, and drops leading zeroes otherwise - so a perfectly valid key produces 31 or
     * 33 bytes about half the time, and a COSE key of the wrong length is refused by the library
     * with a message about the curve. Left-padded and trimmed here rather than debugged there.</p>
     */
    private static byte[] coordinate(final BigInteger value) {
        final byte[] raw = value.toByteArray();
        final byte[] padded = new byte[32];
        if (raw.length > 32) {
            System.arraycopy(raw, raw.length - 32, padded, 0, 32);
        } else {
            System.arraycopy(raw, 0, padded, 32 - raw.length, raw.length);
        }
        return padded;
    }

    private static byte[] sha256(final byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("this JVM has no SHA-256", impossible);
        }
    }
}
