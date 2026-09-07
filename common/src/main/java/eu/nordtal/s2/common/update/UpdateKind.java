package eu.nordtal.s2.common.update;

/**
 * What an {@link UpdateRequest} asks the updater to do. Stored verbatim in
 * {@code update_request.kind}, which a database {@code CHECK} restricts to these four.
 *
 * <p>Separate kinds rather than one command with flags, on purpose: a button that says "check what
 * is new" must not be able to become a network-wide restart because a column defaulted. Each is a
 * different amount of damage and each is asked for by name.</p>
 */
public enum UpdateKind {

    /** Resolve every source, compare against the volumes, write the report. Writes no file. */
    REPORT,

    /**
     * Swap jars into running servers. <b>Retired 2026-09-07 and never submitted again.</b>
     *
     * <p>Kept only so that the rows already in a deployed database still map to something. This is
     * finding 147 with a name: it replaced a running JVM's jar underneath it, and every class that
     * JVM had not yet loaded was gone - which killed {@code onDisable} halfway through, and would
     * have taken both fighters' saved inventories with it had a duel been running. It was removed
     * rather than repaired, because a repair would have left the same button in the same place.
     * {@link #UPDATE} is what replaced it. See {@code V12__update_is_one_run.sql}.</p>
     */
    APPLY,

    /**
     * The whole thing, under one confirmation: count down, stop the servers whose jars change,
     * migrate, swap, start them again, and wait until each one reports healthy.
     *
     * <p>Only the services that actually change are stopped - a server with nothing to install is
     * an outage with nothing to show for it - and the updater is never one of them, because it is
     * the process running the sequence.</p>
     */
    UPDATE,

    /**
     * The same sequence with nothing installed: count down, stop, start, wait.
     *
     * <p>Kept as its own kind because "that server is wedged, take it round once" is a thing to
     * want independently of whether there is a new version, and doing it through Arcane by hand
     * skips the countdown that is the only warning players get.</p>
     */
    RESTART;

    /** @return whether this kind stops servers, which is what a confirmation is asked for */
    public boolean stopsServers() {
        return this == UPDATE || this == RESTART;
    }
}
