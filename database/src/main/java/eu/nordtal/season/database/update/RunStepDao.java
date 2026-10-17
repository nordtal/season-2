package eu.nordtal.season.database.update;

import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;
import org.jspecify.annotations.Nullable;

/** The SQL of a run's steps and facts; {@link UpdateDirectory} is the API. */
interface RunStepDao {

    /** Writes a step that begins now, numbered after the run's last one. */
    @SqlQuery("""
            INSERT INTO run_step (run, seq, step, service)
            SELECT :run, coalesce(max(seq), 0) + 1, :step, :service FROM run_step WHERE run = :run
            RETURNING id
            """)
    long begin(@Bind("run") long run, @Bind("step") String step, @Bind("service") @Nullable String service);

    /** Ends a step of that run that is still open; zero rows for one already ended. */
    @SqlUpdate("""
            UPDATE run_step SET finished = now(), outcome = :outcome, reason = :reason
            WHERE id = :step AND run = :run AND finished IS NULL
            """)
    int end(
            @Bind("run") long run,
            @Bind("step") long step,
            @Bind("outcome") String outcome,
            @Bind("reason") @Nullable String reason);

    /** Ends every step of the run still open, as a run that stops early leaves them. */
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

    @SqlUpdate("UPDATE steward_inbox SET stage = :stage WHERE id = :run AND stage IS DISTINCT FROM :stage")
    void stage(@Bind("run") long run, @Bind("stage") String stage);

    /** Every run settled as failed by a statement that wrote no stage, as an orphan or a restored-over row is. */
    @SqlUpdate("UPDATE steward_inbox SET stage = 'FAILED' WHERE status = 'FAILED' AND stage IS DISTINCT FROM 'FAILED'")
    void failedStages();

    /** The services a request names, none of them moving yet. */
    @SqlUpdate("""
            INSERT INTO run_service (run, service, moving)
            SELECT :run, service, false FROM unnest(cast(:services AS text[])) AS named (service)
            ON CONFLICT (run, service) DO NOTHING
            """)
    void named(@Bind("run") long run, @Bind("services") String[] services);

    /** Marks the services a run stops as moving, adding those its request did not name. */
    @SqlUpdate("""
            INSERT INTO run_service (run, service, moving)
            SELECT :run, service, true FROM unnest(cast(:services AS text[])) AS stopped (service)
            ON CONFLICT (run, service) DO UPDATE SET moving = true
            """)
    void moving(@Bind("run") long run, @Bind("services") String[] services);

    /** Adds players moved off one service to the run's latest move, which steward-agent began before the move. */
    @SqlUpdate("""
            INSERT INTO run_players (step, from_service, to_service, moved)
            SELECT id, :from, :to, :players FROM run_step
            WHERE run = :run AND step = 'PLAYERS_MOVED'
            ORDER BY seq DESC
            LIMIT 1
            ON CONFLICT (step, from_service, to_service) DO UPDATE SET moved = run_players.moved + EXCLUDED.moved
            """)
    int moved(@Bind("run") long run, @Bind("from") String from, @Bind("to") String to, @Bind("players") int players);

    /** Counts players back on the latest move between those two services, never more than it moved. */
    @SqlUpdate("""
            UPDATE run_players SET returned = least(moved, returned + :players)
            WHERE step = (SELECT max(step) FROM run_players WHERE from_service = :from AND to_service = :to)
              AND from_service = :from AND to_service = :to
            """)
    int returned(@Bind("from") String from, @Bind("to") String to, @Bind("players") int players);
}
