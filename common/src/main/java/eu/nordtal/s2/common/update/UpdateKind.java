package eu.nordtal.s2.common.update;

/**
 * What an {@link UpdateRequest} asks steward-worker to do. Stored verbatim in
 * {@code update_request.kind}, which a database {@code CHECK} restricts to the values below.
 *
 * <p>Separate kinds rather than one command with flags, on purpose: a button that says "check what
 * is new" must not be able to become a network-wide restart because a column defaulted. Each is a
 * different amount of damage and each is asked for by name.</p>
 *
 * <p>The {@code CHECK} has held five since V14 and seven since V28, so the sentence above no longer
 * counts them - the list below is the truth. {@code UpdateDirectoryIntegrationTest} submits one row
 * of every value against a real database running the real migrations, which is what keeps this enum
 * and that constraint from drifting apart.</p>
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
     * an outage with nothing to show for it - and the worker is never one of them, because it is
     * the process running the sequence.</p>
     */
    UPDATE,

    /**
     * The same sequence with nothing installed: count down, stop, start, wait.
     *
     * <p>Kept as its own kind because "that server is wedged, take it round once" is a thing to
     * want independently of whether there is a new version, and doing it with a shell on the host
     * skips the countdown that is the only warning players get.</p>
     */
    RESTART,

    /**
     * The same sequence again, with a volume backup in the gap: count down, stop, save, start.
     *
     * <h2>Why steward-worker carries this and no outside schedule does</h2>
     * Anything else that could take a nightly snapshot - a cron job, a backup policy in a panel -
     * would have to stop the containers using a volume first, because a file-level copy of a world
     * Paper is writing to is torn and fails at <em>restore</em> rather than at backup. A stop
     * nobody announced takes the world out from under whoever is standing in it with no countdown
     * at all, and the countdown is the entire reason this network has a request row rather than a
     * cron job. So the stopping is done here, by the sequence that already knows how to warn people
     * and how to say whether everything came back.
     *
     * <h2>Nobody types this at 04:45</h2>
     * {@code /backup now} exists and is what an admin uses. The nightly one is written by
     * steward-worker's own clock.
     *
     * <p><b>That is a rule change, made deliberately on 2026-09-13</b>
     * ({@code konzept-eigenstaendiger-stack.md} §9a). It used to be written by {@code smp},
     * because {@code serve} was not allowed to be a scheduler and {@code smp} already owned a
     * daily clock for the farm world - with the consequence, written down at the time and then
     * true for a season, that a network with {@code smp} down had no backup at all and nothing
     * anywhere noticed. The clock now belongs to the process that performs the work.</p>
     *
     * <p>What that broke is the guarantee that came free from the fifteen minutes between
     * {@code smp}'s backup and {@code smp}'s farm reset: the world about to be deleted had just
     * been saved. Two clocks in two containers cannot be held against each other, so the reset
     * asks instead - see {@link UpdateDirectory#lastSuccessfulBackup(java.time.Duration)}, and no
     * provable backup means no reset.</p>
     */
    BACKUP,

    /**
     * Count down, stop the named services, and <b>leave them stopped</b> (season-2-ops/125).
     *
     * <h2>Why this is a kind and not a flag on RESTART</h2>
     * The two differ in exactly the dangerous half: a restart that fails to come back is an
     * incident, and a DOWN that does not come back is the entire point. Nothing that watches a run
     * could tell those apart from a flag, and the one that is supposed to end with a stopped server
     * is the one an operator asked for by name.
     *
     * <p>Which services it is for comes from {@code update_request.scope}, the same column a scoped
     * update run uses. An empty scope is therefore the whole network, which is true here too and is
     * the reason the interface never offers this without naming a service.</p>
     *
     * <h2>What keeps it down</h2>
     * A row in {@code service_hold}, written when the stop succeeds. It is what makes the state
     * survive a restart of the worker and of the interface, and it is what a later run reads so it
     * never starts a service somebody is holding. Nothing expires it and nothing times it out: the
     * owner's rule is that without a press of the button, nothing happens.
     */
    DOWN,

    /**
     * The other half of {@link #DOWN}: take the hold off and start the services again.
     *
     * <p>No countdown, because nothing goes down - counting down to a server coming back would be
     * thirty seconds of warning about good news. Otherwise it is an ordinary run: it writes a
     * report, it waits for health, and it says so if a service did not come back.</p>
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
