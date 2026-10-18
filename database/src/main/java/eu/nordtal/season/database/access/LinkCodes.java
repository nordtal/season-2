package eu.nordtal.season.database.access;

import java.security.SecureRandom;

/**
 * Generates the four-character codes shown on the login screen.
 * The 31-symbol alphabet is safe only with the bot's {@code RedemptionLimit}. The caller retries collisions.
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
