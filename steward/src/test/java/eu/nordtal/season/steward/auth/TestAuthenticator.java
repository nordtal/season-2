package eu.nordtal.season.steward.auth;

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

/** A software security key that produces {@code none} attestations and signed assertions for tests. */
public final class TestAuthenticator {

    private static final Gson GSON = new Gson();
    private static final Base64.Encoder URL = Base64.getUrlEncoder().withoutPadding();

    /** The flags UP (user present), UV (user verified) and AT (attested credential data follows). */
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

    /** The credential id of this authenticator, as base64url. */
    public String credentialId() {
        return URL.encodeToString(credentialId);
    }

    /**
     * Returns a registration answer to the request that the server just issued.
     *
     * @param creationOptions the response of {@code /auth/webauthn/register/start}, unchanged
     * @param origin the page the browser claims to have been on
     */
    public String register(final String creationOptions, final String origin) {
        final JsonObject publicKey =
                GSON.fromJson(creationOptions, JsonObject.class).getAsJsonObject("publicKey");
        return register(creationOptions, origin, publicKey.get("challenge").getAsString());
    }

    /** Returns a registration answer for a challenge of the caller. */
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
     * Returns an assertion answer to the challenge that the server just issued.
     *
     * @param requestOptions the response of {@code /auth/webauthn/authenticate/start}, unchanged
     * @param origin the page the browser claims to have been on
     */
    public String assertion(final String requestOptions, final String origin) {
        final JsonObject publicKey =
                GSON.fromJson(requestOptions, JsonObject.class).getAsJsonObject("publicKey");
        return assertion(requestOptions, origin, publicKey.get("challenge").getAsString(), 0);
    }

    /** Returns an assertion answer for a challenge and a signature counter of the caller. */
    public String assertion(
            final String requestOptions, final String origin, final String challenge, final long signCount) {
        final JsonObject publicKey =
                GSON.fromJson(requestOptions, JsonObject.class).getAsJsonObject("publicKey");
        final String relyingPartyId = publicKey.get("rpId").getAsString();

        final String clientData = "{\"type\":\"webauthn.get\",\"challenge\":\"" + challenge + "\",\"origin\":\""
                + origin + "\",\"crossOrigin\":false}";
        final byte[] clientDataBytes = clientData.getBytes(StandardCharsets.UTF_8);
        // An assertion has no attested credential data and no AT flag.
        final ByteBuffer authData = ByteBuffer.allocate(32 + 1 + 4);
        authData.put(sha256(relyingPartyId.getBytes(StandardCharsets.UTF_8)));
        authData.put((byte) (0x01 | 0x04));
        authData.putInt((int) signCount);
        final byte[] authenticatorData = authData.array();

        // The signature is over authenticatorData + SHA-256(clientDataJSON), in that order.
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

    /** Builds the attestation object of a self-asserted registration: {@code {fmt: "none", attStmt: {}, authData: …}}. */
    private byte[] attestationObject(final String relyingPartyId) {
        return CBORObject.NewMap()
                .Add("fmt", "none")
                .Add("attStmt", CBORObject.NewMap())
                .Add("authData", CBORObject.FromObject(authenticatorData(relyingPartyId)))
                .EncodeToBytes();
    }

    /** Builds the authenticator data: rpIdHash, flags, signCount, a zero aaguid, credentialIdLength, credentialId and the COSE public key. */
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

    /** Builds the public key as COSE_Key: EC2, ES256, P-256, with x and y of 32 bytes each. */
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

    /** Returns a coordinate as 32 bytes. {@code BigInteger.toByteArray()} can return 31 or 33. */
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
