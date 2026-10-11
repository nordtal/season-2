package eu.nordtal.season.steward.auth;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Optional;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Seals a secret for the database with AES-256-GCM and the JDK alone: the 12-byte nonce, then ciphertext and tag.
 * The context, such as the row's account, is authenticated with it, so a sealed value copied elsewhere fails to open.
 */
public final class SecretBox {

    private static final int KEY_BYTES = 32;
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final String CIPHER = "AES/GCM/NoPadding";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKeySpec key;

    private SecretBox(final byte[] key) {
        this.key = new SecretKeySpec(key, "AES");
    }

    /**
     * The box for a key given as 64 hex digits, as {@code openssl rand -hex 32} prints it; empty when blank.
     *
     * @throws IllegalArgumentException for anything else, so a mistyped key stops the start rather than the codes
     */
    public static Optional<SecretBox> of(final String hex) {
        final String trimmed = hex.strip();
        if (trimmed.isEmpty()) {
            return Optional.empty();
        }
        final byte[] key;
        try {
            key = HexFormat.of().parseHex(trimmed);
        } catch (IllegalArgumentException notHex) {
            throw new IllegalArgumentException("the key is not hex digits - `openssl rand -hex 32` makes one", notHex);
        }
        if (key.length != KEY_BYTES) {
            throw new IllegalArgumentException("the key is " + key.length + " bytes, and AES-256 takes " + KEY_BYTES
                    + " - `openssl rand -hex 32` makes one");
        }
        return Optional.of(new SecretBox(key));
    }

    /** Seals {@code plain} under a fresh nonce, bound to {@code context}. */
    public byte[] seal(final byte[] plain, final byte[] context) {
        final byte[] nonce = new byte[NONCE_BYTES];
        RANDOM.nextBytes(nonce);
        try {
            final Cipher cipher = Cipher.getInstance(CIPHER);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(context);
            final byte[] sealed = cipher.doFinal(plain);
            return ByteBuffer.allocate(NONCE_BYTES + sealed.length)
                    .put(nonce)
                    .put(sealed)
                    .array();
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException("AES-GCM is not available", impossible);
        }
    }

    /**
     * Opens what {@link #seal} sealed for the same context.
     *
     * @throws IllegalStateException when the key, the context or the bytes are not the ones it was sealed with
     */
    public byte[] open(final byte[] sealed, final byte[] context) {
        if (sealed.length <= NONCE_BYTES) {
            throw new IllegalStateException("a sealed secret is shorter than its nonce");
        }
        try {
            final Cipher cipher = Cipher.getInstance(CIPHER);
            cipher.init(
                    Cipher.DECRYPT_MODE,
                    key,
                    new GCMParameterSpec(TAG_BITS, Arrays.copyOfRange(sealed, 0, NONCE_BYTES)));
            cipher.updateAAD(context);
            return cipher.doFinal(sealed, NONCE_BYTES, sealed.length - NONCE_BYTES);
        } catch (GeneralSecurityException refused) {
            throw new IllegalStateException(
                    "a sealed secret does not open: another key, another row, or changed bytes", refused);
        }
    }
}
