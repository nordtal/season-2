package eu.nordtal.season.stewardagent;

import java.util.Optional;

/**
 * Which compose project a test against the real daemon may look at, and which one it may act on.
 *
 * Acting means a stop, an exec or a dump, so it never defaults to the stack on this host.
 */
public final class TestProject {

    /** Names a scratch project; unset, the tests that act on containers are skipped. */
    public static final String VARIABLE = "NORDTAL_TEST_PROJECT";

    private TestProject() {}

    /** The project a test only reads: the scratch one when named, otherwise the stack on this host. */
    public static String reading() {
        return acting().orElse("nordtal-s2");
    }

    /** The project a test may stop, exec into or dump, or empty when none was named. */
    public static Optional<String> acting() {
        final String named = System.getenv(VARIABLE);
        return named == null || named.isBlank() ? Optional.empty() : Optional.of(named.strip());
    }
}
