package eu.nordtal.s2.common.update;

/**
 * What an {@link UpdateRequest} asks steward-worker to do, stored in {@code update_request.kind}.
 *
 * Separate kinds, so a harmless button cannot become a restart through a default.
 * {@code UpdateDirectoryIntegrationTest} holds this enum against the database {@code CHECK}.
 */
public enum UpdateKind {

    /** Resolve every source, compare against the volumes, write the report. Writes no file. */
    REPORT,

    /** Swapping jars into running servers; never submitted, kept so existing rows still map. */
    APPLY,

    /**
     * The whole update under one confirmation: count down, stop what changes, migrate, swap, restart.
     *
     * Only services that change are stopped, and never the worker.
     */
    UPDATE,

    /**
     * The same sequence with nothing installed: count down, stop, start, wait.
     *
     * Kept as its own kind because "that server is wedged, take it round once" is a thing to
     * want independently of whether there is a new version, and doing it with a shell on the host
     * skips the countdown that is the only warning players get.
     */
    RESTART,

    /**
     * The same sequence again, with a volume backup in the gap: count down, stop, save, start.
     *
     * <b>Why steward-worker carries this and no outside schedule does</b>
     *
     * Anything else that could take a nightly snapshot - a cron job, a backup policy in a panel -
     * would have to stop the containers using a volume first, because a file-level copy of a world
     * Paper is writing to is torn and fails at <em>restore</em> rather than at backup. A stop
     * nobody announced takes the world out from under whoever is standing in it with no countdown
     * at all, and the countdown is the entire reason this network has a request row rather than a
     * cron job. So the stopping is done here, by the sequence that already knows how to warn people
     * and how to say whether everything came back.
     *
     * <b>Nobody types this at 04:45</b>
     *
     * {@code /backup now} exists and is what an admin uses. The nightly one is written by
     * steward-worker's own clock.
     *
     * The clock belongs to the process that performs the work, so a network with {@code smp}
     * down still gets a backup.
     *
     * {@link UpdateDirectory#lastSuccessfulBackup(java.time.Duration)} answers whether the world
     * about to be deleted was just saved; it currently has no caller.
     */
    BACKUP,

    /**
     * Count down, stop the named services, and <b>leave them stopped</b>.
     *
     * <b>Why this is a kind and not a flag on RESTART</b>
     *
     * The two differ in exactly the dangerous half: a restart that fails to come back is an
     * incident, and a DOWN that does not come back is the entire point. Nothing that watches a run
     * could tell those apart from a flag, and the one that is supposed to end with a stopped server
     * is the one an operator asked for by name.
     *
     * Which services it is for comes from {@code update_request.scope}, the same column a scoped
     * update run uses. An empty scope is therefore the whole network, which is true here too and is
     * the reason the interface never offers this without naming a service.
     *
     * <b>What keeps it down</b>
     *
     * A row in {@code service_hold}, written when the stop succeeds. It is what makes the state
     * survive a restart of the worker and of the interface, and it is what a later run reads so it
     * never starts a service somebody is holding. Nothing expires it and nothing times it out: the
     * owner's rule is that without a press of the button, nothing happens.
     */
    DOWN,

    /**
     * The other half of {@link #DOWN}: take the hold off and start the services again.
     *
     * No countdown, because nothing goes down - counting down to a server coming back would be
     * thirty seconds of warning about good news. Otherwise it is an ordinary run: it writes a
     * report, it waits for health, and it says so if a service did not come back.
     */
    START;

    /** @return whether this kind stops servers, which is what a confirmation is asked for */
    public boolean stopsServers() {
        return this == UPDATE || this == RESTART || this == BACKUP || this == DOWN;
    }

    /**
     * @return whether this kind is one half of the deliberate down/up pair, which is the one place
     *         where a stopped service is the finished state rather than a failure
     */
    public boolean isHold() {
        return this == DOWN || this == START;
    }
}
