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
    RESTART,

    /**
     * The same sequence again, with a volume backup in the gap: count down, stop, save, start.
     *
     * <h2>Why the updater carries this and Arcane's own schedule does not</h2>
     * Arcane can run a backup policy on a timer of its own, and it can be told to stop the
     * containers using a volume first. Both halves of that are wrong here. A stop nobody announced
     * takes the world out from under whoever is standing in it with no countdown at all - and the
     * countdown is the entire reason this network has a request row rather than a cron job. So
     * Arcane's {@code StopContainers} stays <b>off</b>, its policy decides only <em>where</em> a
     * snapshot goes, and the stopping is done here, by the sequence that already knows how to warn
     * people and how to say whether everything came back.
     *
     * <h2>Nobody types this at 04:45</h2>
     * {@code /backup now} exists and is what an admin uses. The nightly one is written by
     * {@code smp}, which already owns a daily clock for the farm world - see {@code smp}'s
     * {@code NightlyBackup}. That is deliberate and it is the rule {@code serve} is protected by:
     * <b>the updater is not a scheduler</b>, and a timer inside it would be one however small.
     */
    BACKUP;

    /** @return whether this kind stops servers, which is what a confirmation is asked for */
    public boolean stopsServers() {
        return this == UPDATE || this == RESTART || this == BACKUP;
    }
}
