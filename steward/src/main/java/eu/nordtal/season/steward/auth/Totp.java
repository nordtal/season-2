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
 * RFC 6238 time-based codes over RFC 4226, with the JDK's HMAC alone.
 *
 * SHA-1, six digits, 30 seconds from the epoch, a 160-bit secret: every app's defaults; one step either side.
 */
public final class Totp {

    /** RFC 6238's default time step X, counted from T0 = 0. */
    public static final long STEP_SECONDS = 30;

    /** What the app shows; RFC 4226 asks for at least six. */
    public static final int DIGITS = 6;

    /** 160 bits, as RFC 4226 recommends; the least it allows is 128. */
    private static final int SECRET_BYTES = 20;

    private static final String ALGORITHM = "HmacSHA1";
    private static final String BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final SecureRandom RANDOM = new SecureRandom();

    private Totp() {}

    /** A fresh secret for one enrolment. */
    public static byte[] newSecret() {
        final byte[] secret = new byte[SECRET_BYTES];
        RANDOM.nextBytes(secret);
        return secret;
    }

    /** The time step an instant falls in: RFC 6238's T, {@code floor((now-T0)/X)}. */
    public static long stepAt(final Instant at) {
        return Math.floorDiv(at.getEpochSecond(), STEP_SECONDS);
    }

    /** RFC 4226's HOTP of one counter, which for TOTP is the time step: dynamic truncation, then the low digits. */
    public static String code(final byte[] secret, final long counter, final int digits) {
        final byte[] hash;
        try {
            final Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret, ALGORITHM));
            hash = mac.doFinal(
                    java.nio.ByteBuffer.allocate(Long.BYTES).putLong(counter).array());
        } catch (GeneralSecurityException impossible) {
            // Every JDK ships HmacSHA1, and any byte array is a valid HMAC key.
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

    /**
     * The step whose code was typed, among this one and one either side, or empty.
     *
     * Spaces are ignored, since apps show the code in two halves; each candidate is compared in constant time.
     */
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

    /** RFC 4648 Base32 without padding, as the Key Uri Format and every app's manual entry take the secret. */
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

    /** The {@code otpauth://} URI the QR code carries, in the Key Uri Format with every parameter spelled out. */
    public static String uri(final String issuer, final String account, final byte[] secret) {
        return "otpauth://totp/" + encode(issuer) + ":" + encode(account)
                + "?secret=" + base32(secret)
                + "&issuer=" + encode(issuer)
                + "&algorithm=SHA1&digits=" + DIGITS + "&period=" + STEP_SECONDS;
    }

    /** Percent-encoding as a URI path wants it: a space is {@code %20}, never {@code +}. */
    private static String encode(final String text) {
        return URLEncoder.encode(text, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
