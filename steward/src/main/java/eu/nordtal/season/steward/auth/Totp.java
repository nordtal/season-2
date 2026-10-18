package eu.nordtal.season.steward.auth;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.OptionalLong;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Creates and checks RFC 6238 time-based codes with the JDK's HMAC.
 */
public final class Totp {

    public static final long STEP_SECONDS = 30;

    public static final int DIGITS = 6;

    private static final int SECRET_BYTES = 20;

    private static final String ALGORITHM = "HmacSHA1";
    private static final String BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final SecureRandom RANDOM = new SecureRandom();

    private Totp() {}

    public static byte[] newSecret() {
        final byte[] secret = new byte[SECRET_BYTES];
        RANDOM.nextBytes(secret);
        return secret;
    }

    public static long stepAt(final Instant at) {
        return Math.floorDiv(at.getEpochSecond(), STEP_SECONDS);
    }

    public static String code(final byte[] secret, final long counter, final int digits) {
        final byte[] hash;
        try {
            final Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret, ALGORITHM));
            hash = mac.doFinal(
                    java.nio.ByteBuffer.allocate(Long.BYTES).putLong(counter).array());
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException("HmacSHA1 is not available", impossible);
        }
        final int offset = hash[hash.length - 1] & 0x0f;
        final int binary = ((hash[offset] & 0x7f) << 24)
                | ((hash[offset + 1] & 0xff) << 16)
                | ((hash[offset + 2] & 0xff) << 8)
                | (hash[offset + 3] & 0xff);
        final int modulus = (int) Math.pow(10, digits);
        final String text = Integer.toString(binary % modulus);
        return "0".repeat(digits - text.length()) + text;
    }

    /** Spaces in {@code typed} are ignored. */
    public static OptionalLong matching(final byte[] secret, final String typed, final long now) {
        final String code = typed.replace(" ", "");
        if (code.length() != DIGITS || !code.chars().allMatch(c -> c >= '0' && c <= '9')) {
            return OptionalLong.empty();
        }
        final byte[] given = code.getBytes(StandardCharsets.US_ASCII);
        long matched = Long.MIN_VALUE;
        for (long step = now - 1; step <= now + 1; step++) {
            final byte[] expected = code(secret, step, DIGITS).getBytes(StandardCharsets.US_ASCII);
            if (MessageDigest.isEqual(expected, given)) {
                matched = step;
            }
        }
        return matched == Long.MIN_VALUE ? OptionalLong.empty() : OptionalLong.of(matched);
    }

    public static String base32(final byte[] bytes) {
        final StringBuilder out = new StringBuilder((bytes.length * 8 + 4) / 5);
        int buffer = 0;
        int bits = 0;
        for (final byte b : bytes) {
            buffer = (buffer << 8) | (b & 0xff);
            bits += 8;
            while (bits >= 5) {
                out.append(BASE32.charAt((buffer >> (bits - 5)) & 0x1f));
                bits -= 5;
            }
        }
        if (bits > 0) {
            out.append(BASE32.charAt((buffer << (5 - bits)) & 0x1f));
        }
        return out.toString();
    }

    public static String uri(final String issuer, final String account, final byte[] secret) {
        return "otpauth://totp/" + encode(issuer) + ":" + encode(account)
                + "?secret=" + base32(secret)
                + "&issuer=" + encode(issuer)
                + "&algorithm=SHA1&digits=" + DIGITS + "&period=" + STEP_SECONDS;
    }

    private static String encode(final String text) {
        return URLEncoder.encode(text, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
