package eu.nordtal.s2.common.access;

import java.security.SecureRandom;

/**
 * Generates the codes shown on the login screen.
 * <p>
 * Eight characters from a 32-symbol alphabet is about 40 bits of entropy - roughly 1.1 trillion
 * possibilities. That is deliberate: unlike the bunq payment reference, a link code is a bearer
 * credential for taking over somebody's account link, and nothing rate-limits a guess besides
 * Discord's own interaction limits on the modal that redeems it. The alphabet excludes
 * {@code 0/O} and {@code 1/I/L}, which a player has to read off a disconnect screen and type back
 * into Discord accurately.
 * </p>
 * <p>
 * {@link SecureRandom} rather than {@link java.util.concurrent.ThreadLocalRandom}: this value is
 * a short-lived credential, not a display id, so it should not be predictable even in principle.
 * </p>
 */
final class LinkCodes {

    private static final String ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
    private static final int LENGTH = 8;

    /** Not shared across threads on purpose - contention on one SecureRandom is not worth avoiding
     * a per-call instantiation for something called at most once per login attempt. */
    private LinkCodes() {
    }

    static String random() {
        final SecureRandom random = new SecureRandom();
        final StringBuilder code = new StringBuilder(LENGTH);
        for (int index = 0; index < LENGTH; index++) {
            code.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return code.toString();
    }
}
