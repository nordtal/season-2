package eu.nordtal.s2.common.access;

import java.security.SecureRandom;

/**
 * Generates the four-character codes shown on the login screen.
 *
 * 31 symbols without {@code 0/O} and {@code 1/I/L} give 923 521 codes, which is safe only together with
 * the bot's {@code RedemptionLimit} on failed redemptions; neither is enough without the other. Collisions
 * are retried by the caller. {@link SecureRandom}, because the code is a credential.
 */
final class LinkCodes {

    private static final String ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
    private static final int LENGTH = 4;

    private LinkCodes() {}

    static String random() {
        final SecureRandom random = new SecureRandom();
        final StringBuilder code = new StringBuilder(LENGTH);
        for (int index = 0; index < LENGTH; index++) {
            code.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return code.toString();
    }
}
