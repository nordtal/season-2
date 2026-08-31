package eu.nordtal.s2.common.phase;

import eu.nordtal.s2.common.SeasonPhase;

import javax.sql.DataSource;

/**
 * The current {@link SeasonPhase}, as seen by every process: the bot, the proxy and the plugins.
 * <p>
 * The phase is <b>one row in PostgreSQL</b> and nobody caches it as truth
 * ({@code docs/season-phases.md}). This interface is how that row is read and the only way it is
 * written.
 * </p>
 *
 * <h2>Why the switch is one method</h2>
 * Two paths switch the phase - {@code /phase set} in Discord, the normal one, and a command on the
 * Velocity proxy, the emergency one for when the bot or Discord is down. Both must write an
 * {@code audit_log} entry, and two writers means two places where that is easy to forget. So there
 * is exactly one method, {@link #switchPhase(SeasonPhase, String, String)}, and underneath it the
 * update, the audit row and the {@code NOTIFY} are a <b>single SQL statement</b>: not "remember to
 * call the audit too", but "there is no way to express the write without it".
 *
 * <h2>Platform</h2>
 * Nothing here refers to Paper, Velocity or JDA, and nothing refers to JDBI or HikariCP either -
 * the factory takes a {@link DataSource}, a JDK type.
 *
 * <h2>Lifetime</h2>
 * One instance per process, over a pool somebody else owns. There is deliberately no
 * pool-creating factory to match {@code AccessDirectory#open}: every process that reads the phase
 * already reads access as well, and the two share one pool - the bot hands in jcore's
 * {@code Database#dataSource()}, the proxy hands in its own {@code AccessPool}. Nothing here holds
 * a resource, so there is nothing to close.
 */
public interface PhaseDirectory {

    /**
     * Reads the phase over a connection pool the caller owns.
     *
     * @param dataSource the pool - the same one {@code AccessDirectory.using(DataSource)} is given
     * @return a directory over that pool
     */
    static PhaseDirectory using(final DataSource dataSource) {
        return new JdbiPhaseDirectory(dataSource);
    }

    /**
     * The phase right now, read from the row every time it is asked.
     * <p>
     * <b>Database failures propagate.</b> They are not folded into
     * {@link SeasonPhase#MAINTENANCE}, because a caller has to be able to tell "the season is in
     * maintenance" from "I could not reach the database" - the proxy's documented fallback is to
     * keep using the <em>last known</em> phase and only to assume {@code MAINTENANCE} when it has
     * never read one at all ({@code docs/season-phases.md#the-gate}), and it cannot implement that
     * against a method that answers {@code MAINTENANCE} to both questions.
     * </p>
     *
     * @return the current phase; {@link SeasonPhase#MAINTENANCE} if the row is somehow missing or
     *         holds a value this build does not know - an unreadable phase must never be more
     *         permissive than the real one
     */
    SeasonPhase currentPhase();

    /**
     * Switches the phase <b>and</b> records who did it, in one statement.
     * <p>
     * A switch to the phase that is already current is not an error: it writes the same row, and
     * the audit entry says so. {@link PhaseChange#unchanged()} is how a caller reports it.
     * </p>
     *
     * @param phase  the phase to switch to
     * @param actor  the Discord id of the admin who caused it, or {@code null} for a switch no
     *               human asked for - which neither of today's two callers is, since both are
     *               authorised by {@code discord_user.admin} and therefore know the id
     * @param reason free text for whoever reads the admin channel later, may be {@code null}
     * @return what the row said before and what it says now
     * @throws IllegalStateException if the {@code season_phase} row does not exist - it is seeded
     *                               by {@code V4} and there is no code path that removes it
     */
    PhaseChange switchPhase(SeasonPhase phase, String actor, String reason);
}
