package eu.nordtal.season.database.update;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.jdbi.v3.core.mapper.RowMapper;
import org.jdbi.v3.core.statement.StatementContext;
import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;
import org.jdbi.v3.sqlobject.statement.UseRowMapper;
import org.jspecify.annotations.Nullable;

/** The SQL of the steps and facts of a run. {@link UpdateDirectory} is its API. */
interface RunStepDao {

    /** Returns the steps of the run in the order they began. */
    @SqlQuery("SELECT step, service, outcome FROM run_step WHERE run = :run ORDER BY seq")
    @UseRowMapper(StepMapper.class)
    List<RunProgress.Step> steps(@Bind("run") long run);

    /** Reads a row of {@link #steps}. */
    final class StepMapper implements RowMapper<RunProgress.Step> {

        @Override
        public RunProgress.Step map(final ResultSet row, final StatementContext context) throws SQLException {
            final String outcome = row.getString("outcome");
            return new RunProgress.Step(
                    RunStep.Kind.valueOf(row.getString("step")),
                    row.getString("service"),
                    outcome == null ? null : RunStep.Outcome.valueOf(outcome));
        }
    }

    /** Writes a step that begins now, numbered after the last step of the run. */
    @SqlQuery("""
            INSERT INTO run_step (run, seq, step, service)
            SELECT :run, coalesce(max(seq), 0) + 1, :step, :service FROM run_step WHERE run = :run
            RETURNING id
            """)
    long begin(@Bind("run") long run, @Bind("step") String step, @Bind("service") @Nullable String service);

    /** Ends a step of that run that is open. A step that ended gives zero rows. */
    @SqlUpdate("""
            UPDATE run_step SET finished = now(), outcome = :outcome, reason = :reason, exit_code = :exitCode
            WHERE id = :step AND run = :run AND finished IS NULL
            """)
    int end(
            @Bind("run") long run,
            @Bind("step") long step,
            @Bind("outcome") String outcome,
            @Bind("reason") @Nullable String reason,
            @Bind("exitCode") @Nullable Integer exitCode);

    @SqlUpdate("""
            UPDATE run_step SET finished = now(), outcome = :outcome, reason = :reason
            WHERE run = :run AND finished IS NULL
            """)
    int endOpen(@Bind("run") long run, @Bind("outcome") String outcome, @Bind("reason") @Nullable String reason);

    @SqlUpdate("""
            INSERT INTO run_change (step, artefact, version_from, version_to, image)
            VALUES (:step, :artefact, :from, :to, :image)
            ON CONFLICT (step, artefact) DO UPDATE
                SET version_from = EXCLUDED.version_from, version_to = EXCLUDED.version_to, image = EXCLUDED.image
            """)
    void change(
            @Bind("step") long step,
            @Bind("artefact") String artefact,
            @Bind("from") @Nullable String from,
            @Bind("to") @Nullable String to,
            @Bind("image") @Nullable String image);

    @SqlUpdate("""
            INSERT INTO run_image_removed (step, image, bytes) VALUES (:step, :image, :bytes)
            ON CONFLICT (step, image) DO NOTHING
            """)
    void imageRemoved(@Bind("step") long step, @Bind("image") String image, @Bind("bytes") long bytes);

    @SqlUpdate("INSERT INTO run_room (step, bytes_needed, bytes_free) VALUES (:step, :needed, :free)")
    void room(@Bind("step") long step, @Bind("needed") long needed, @Bind("free") long free);

    @SqlUpdate("INSERT INTO run_offsite (step, snapshot, bytes_added) VALUES (:step, :snapshot, :added)")
    void offsite(@Bind("step") long step, @Bind("snapshot") String snapshot, @Bind("added") long added);

    @SqlUpdate("INSERT INTO run_cache_freed (step, bytes) VALUES (:step, :bytes)")
    void cacheFreed(@Bind("step") long step, @Bind("bytes") long bytes);

    @SqlUpdate("""
            INSERT INTO backup_archive (volume, file, bytes, took, saved_by, stop_verified)
            VALUES (:volume, :file, :bytes, make_interval(secs => :millis / 1000.0), :step, :stopVerified)
            """)
    void archive(
            @Bind("step") long step,
            @Bind("volume") String volume,
            @Bind("file") String file,
            @Bind("bytes") long bytes,
            @Bind("millis") long millis,
            @Bind("stopVerified") boolean stopVerified);

    @SqlUpdate("""
            UPDATE backup_archive SET pruned_by = :step, prune_rule = :rule
            WHERE file = :file AND pruned_by IS NULL
            """)
    void pruned(@Bind("step") long step, @Bind("file") String file, @Bind("rule") String rule);

    /** Returns the stage of the run, none before a statement wrote one. */
    @SqlQuery("SELECT stage FROM steward_inbox WHERE id = :run")
    Optional<String> stage(@Bind("run") long run);

    @SqlQuery("""
            SELECT id, seq, step, service, started, finished, outcome, reason, exit_code
            FROM run_step WHERE run = :run ORDER BY seq
            """)
    @RegisterConstructorMapper(StepRow.class)
    List<StepRow> rows(@Bind("run") long run);

    /** A row of {@code run_step} before its facts are joined to it. */
    record StepRow(
            long id,
            int seq,
            RunStep.Kind step,
            @Nullable String service,
            Instant started,
            @Nullable Instant finished,
            RunStep.@Nullable Outcome outcome,
            RunStep.@Nullable Reason reason,
            @Nullable Integer exitCode) {}

    @SqlQuery("""
            SELECT c.step, c.artefact, c.version_from, c.version_to, c.image
            FROM run_change c JOIN run_step s ON s.id = c.step WHERE s.run = :run ORDER BY c.step, c.artefact
            """)
    @RegisterConstructorMapper(ChangeRow.class)
    List<ChangeRow> changes(@Bind("run") long run);

    record ChangeRow(
            long step,
            String artefact,
            @Nullable String versionFrom,
            @Nullable String versionTo,
            RunStep.@Nullable Image image) {}

    @SqlQuery("""
            SELECT p.step, p.from_service, p.to_service, p.moved, p.returned
            FROM run_players p JOIN run_step s ON s.id = p.step WHERE s.run = :run ORDER BY p.step, p.from_service
            """)
    @RegisterConstructorMapper(MoveRow.class)
    List<MoveRow> moves(@Bind("run") long run);

    record MoveRow(long step, String fromService, String toService, int moved, int returned) {}

    @SqlQuery("""
            SELECT r.step, r.image, r.bytes
            FROM run_image_removed r JOIN run_step s ON s.id = r.step WHERE s.run = :run ORDER BY r.step, r.image
            """)
    @RegisterConstructorMapper(RemovedRow.class)
    List<RemovedRow> imagesRemoved(@Bind("run") long run);

    record RemovedRow(long step, String image, long bytes) {}

    /** The single numbers of a step: the build cache freed, the room check and the offsite copy. */
    @SqlQuery("""
            SELECT s.id AS step, f.bytes AS cache_freed, r.bytes_needed, r.bytes_free, o.snapshot, o.bytes_added
            FROM run_step s
            LEFT JOIN run_cache_freed f ON f.step = s.id
            LEFT JOIN run_room r ON r.step = s.id
            LEFT JOIN run_offsite o ON o.step = s.id
            WHERE s.run = :run AND (f.step IS NOT NULL OR r.step IS NOT NULL OR o.step IS NOT NULL)
            """)
    @RegisterConstructorMapper(NumbersRow.class)
    List<NumbersRow> numbers(@Bind("run") long run);

    record NumbersRow(
            long step,
            @Nullable Long cacheFreed,
            @Nullable Long bytesNeeded,
            @Nullable Long bytesFree,
            @Nullable String snapshot,
            @Nullable Long bytesAdded) {}

    /** The archives the run saved or pruned, each with the step that did so. */
    @SqlQuery("""
            SELECT a.saved_by, a.pruned_by, a.volume, a.file, a.bytes,
                   cast(extract(epoch FROM a.took) * 1000 AS bigint) AS took_millis, a.stop_verified, a.prune_rule
            FROM backup_archive a
            WHERE a.saved_by IN (SELECT id FROM run_step WHERE run = :run)
               OR a.pruned_by IN (SELECT id FROM run_step WHERE run = :run)
            ORDER BY a.id
            """)
    @RegisterConstructorMapper(ArchiveRow.class)
    List<ArchiveRow> archives(@Bind("run") long run);

    record ArchiveRow(
            long savedBy,
            @Nullable Long prunedBy,
            String volume,
            String file,
            long bytes,
            long tookMillis,
            boolean stopVerified,
            RunStep.@Nullable PruneRule pruneRule) {}

    @SqlUpdate("UPDATE steward_inbox SET stage = :stage WHERE id = :run AND stage IS DISTINCT FROM :stage")
    void stage(@Bind("run") long run, @Bind("stage") String stage);

    /** Sets the stage of every failed run to {@code FAILED}, where no statement wrote it. */
    @SqlUpdate("UPDATE steward_inbox SET stage = 'FAILED' WHERE status = 'FAILED' AND stage IS DISTINCT FROM 'FAILED'")
    void failedStages();

    /** Writes the services that a request names as not moving. */
    @SqlUpdate("""
            INSERT INTO run_service (run, service, moving)
            SELECT :run, service, false FROM unnest(cast(:services AS text[])) AS named (service)
            ON CONFLICT (run, service) DO NOTHING
            """)
    void named(@Bind("run") long run, @Bind("services") String[] services);

    /** Marks the services that a run stops as moving, and adds those that the request did not name. */
    @SqlUpdate("""
            INSERT INTO run_service (run, service, moving)
            SELECT :run, service, true FROM unnest(cast(:services AS text[])) AS stopped (service)
            ON CONFLICT (run, service) DO UPDATE SET moving = true
            """)
    void moving(@Bind("run") long run, @Bind("services") String[] services);

    /** Adds players moved off a service to the latest move of the run, which steward-agent began before it. */
    @SqlUpdate("""
            INSERT INTO run_players (step, from_service, to_service, moved)
            SELECT id, :from, :to, :players FROM run_step
            WHERE run = :run AND step = 'PLAYERS_MOVED'
            ORDER BY seq DESC
            LIMIT 1
            ON CONFLICT (step, from_service, to_service) DO UPDATE SET moved = run_players.moved + EXCLUDED.moved
            """)
    int moved(@Bind("run") long run, @Bind("from") String from, @Bind("to") String to, @Bind("players") int players);

    /** Counts players back on the latest move between those two services, at most the players moved. */
    @SqlUpdate("""
            UPDATE run_players SET returned = least(moved, returned + :players)
            WHERE step = (SELECT max(step) FROM run_players WHERE from_service = :from AND to_service = :to)
              AND from_service = :from AND to_service = :to
            """)
    int returned(@Bind("from") String from, @Bind("to") String to, @Bind("players") int players);
}
