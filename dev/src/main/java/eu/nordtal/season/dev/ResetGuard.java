package eu.nordtal.season.dev;

import java.util.List;

/**
 * The guard on {@code reset}, the one command that deletes something that cannot be rebuilt.
 *
 * The user types the name of the service, not "yes", because the volume of smp holds a hand-built world.
 */
final class ResetGuard {

    static final List<String> SERVICES = List.of("proxy", "limbo", "hunger-games", "smp");

    private ResetGuard() {}

    static boolean known(final String wanted) {
        return SERVICES.contains(wanted);
    }

    static boolean confirmed(final String wanted, final String typed) {
        return !wanted.isEmpty() && wanted.equals(typed);
    }
}
