package eu.nordtal.season.database.access;

import java.security.SecureRandom;

/** Generates the codes shown on the login screen. */
final class LinkCodes {

    private static final String ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
    static final int LENGTH = 12;

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
