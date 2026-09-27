package eu.nordtal.s2.dev;

import java.util.List;

/**
 * The guard on {@code reset}, the one command that deletes something that cannot be rebuilt.
 *
 * The service's name has to be typed back, not "yes": smp's volume holds a hand-built world.
 */
final class ResetGuard {

    /** The four services that run one of our plugins, and the module that builds it. */
    static final List<String> SERVICES = List.of("proxy", "limbo", "hunger-games", "smp");

    private ResetGuard() {}

    /** @return whether {@code wanted} is exactly one of {@link #SERVICES} */
    static boolean known(final String wanted) {
        return SERVICES.contains(wanted);
    }

    /** @return whether {@code typed} is the non-empty name {@code wanted}, character for character */
    static boolean confirmed(final String wanted, final String typed) {
        return !wanted.isEmpty() && wanted.equals(typed);
    }
}
