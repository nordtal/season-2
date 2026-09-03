package eu.nordtal.s2.common.access;

import java.security.SecureRandom;

/**
 * Generates the codes shown on the login screen.
 * <p>
 * <b>Four characters from a 31-symbol alphabet: 923 521 possibilities</b>, about 19.8 bits. That is
 * not enough on its own, and it is not meant to be. A link code is a bearer credential for taking
 * over somebody's account link, so shortening it from eight characters to four was decided
 * together with the countermeasure that makes four safe - a cap on failed redemptions per Discord
 * account, enforced in the bot's modal handler ({@code RedemptionLimit}). Five wrong guesses an
 * hour against 923 521 possibilities is on the order of twenty years of guessing per account.
 * <b>Neither half is worth anything without the other</b>: raising the length back up without
 * removing the cap is merely wasteful, but removing the cap without lengthening the code again
 * leaves nothing but Discord's own interaction limits between a guesser and somebody's account.
 * </p>
 * <p>
 * The four characters are what a player reads off a disconnect screen and types into a Discord
 * modal, which is the whole reason to want them short. The alphabet excludes {@code 0/O} and
 * {@code 1/I/L} for the same reason - 31 symbols, not 32; this comment said 32 until 2026-09-03
 * and was simply wrong, which cost nothing only because nothing computed with it.
 * </p>
 * <p>
 * Collisions with a live code are handled by the caller, which retries with a fresh candidate -
 * see {@code JdbiAccessDirectory#MAX_LINK_CODE_ATTEMPTS}. They are no longer astronomically
 * unlikely at this length: with a hundred codes alive at once the chance of one candidate
 * colliding is about one in nine thousand, which is why the retry loop matters now rather than
 * being theatre.
 * </p>
 * <p>
 * {@link SecureRandom} rather than {@link java.util.concurrent.ThreadLocalRandom}: this value is
 * a short-lived credential, not a display id, so it should not be predictable even in principle.
 * Four characters make that stricter, not looser - a predictable generator would hand over the
 * whole space at once, and no attempt cap defends against knowing the answer.
 * </p>
 */
final class LinkCodes {

    private static final String ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
    private static final int LENGTH = 4;

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
