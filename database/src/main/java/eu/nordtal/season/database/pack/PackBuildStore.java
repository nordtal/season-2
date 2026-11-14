package eu.nordtal.season.database.pack;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;

/**
 * Records each build of the resource pack. steward-agent writes it, steward reads it.
 *
 * The newest good build is the active pack; a failed one leaves it as it was. Every write signals {@code PACK}.
 */
public interface PackBuildStore {

    /** Returns a store that uses {@code dataSource} and never closes it. */
    static PackBuildStore using(final DataSource dataSource) {
        return new JdbiPackBuildStore(dataSource);
    }

    /**
     * Records a build that starts now and returns its id.
     *
     * @param prizes the wheel's prize list as JSON, as the build reads it
     * @param label the spin button's label, by language
     */
    long start(Instant started, String prizes, Map<String, String> label);

    /** Makes the build the active pack and supersedes the one before it. */
    void succeed(long id, Instant finished, String sha1, long sizeBytes);

    /** Ends the build as failed, with the reason an admin reads. */
    void fail(long id, Instant finished, String error);

    /** Ends every build that still runs as failed, for a start after the agent stopped in the middle of one. */
    void abandon(Instant finished, String error);

    /** Asks steward-agent to build the pack now, unless the active pack is drawn from the same inputs. */
    void askAgain();

    /** Returns when an admin last asked for a build, which steward-agent watches; empty before the first ask. */
    Optional<Instant> asked();

    /** Returns the active build, if a build ever succeeded. */
    Optional<PackBuild> active();

    /** Returns the newest {@code limit} builds, newest first. */
    List<PackBuild> recent(int limit);

    /** How a build stands. */
    enum State {
        BUILDING,
        ACTIVE,
        SUPERSEDED,
        FAILED
    }

    /**
     * One build of the resource pack.
     *
     * @param prizes the prize list as JSON
     * @param sha1 the zip's hash, set once the build made it
     */
    record PackBuild(
            long id,
            State state,
            Instant started,
            @Nullable Instant finished,
            String prizes,
            Map<String, String> label,
            @Nullable String sha1,
            @Nullable Long sizeBytes,
            @Nullable Duration duration,
            @Nullable String error) {}
}
