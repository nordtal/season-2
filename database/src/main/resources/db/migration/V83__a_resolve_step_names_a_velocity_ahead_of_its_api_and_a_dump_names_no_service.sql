-- A resolve step that finds the newest Velocity ahead of the proxy's API names that reason instead of a failed source.
-- A dump acts on no service, so its step names none.
-- The two UPDATE statements correct the steps written before.
ALTER TABLE run_step DROP CONSTRAINT run_step_reason_check;
ALTER TABLE run_step ADD CONSTRAINT run_step_reason_check
    CHECK (reason IN ('UNREACHABLE', 'UNEXPECTED', 'CANCELLED', 'INTERRUPTED', 'NO_CONTAINER', 'REFUSED',
                      'NOT_HEALTHY', 'UNVERIFIED_STOP', 'PLAYERS_STAYED', 'PLAYERS_UNKNOWN', 'NO_ROOM',
                      'TOOL_FAILED', 'NO_REPOSITORY', 'REFUSED_LOCAL_BUILD', 'RELEASED_MEANWHILE',
                      'OLDER_RELEASE', 'HELD', 'NOT_IN_SCOPE', 'NOTHING_HELD', 'UNNAMED', 'NEVER_DOWN',
                      'SELF', 'UNKNOWN', 'NOT_A_VOLUME', 'NO_VOLUMES', 'NOTHING_TO_REMOVE', 'NOT_SAVED',
                      'LEFT_DOWN', 'SOURCE_FAILED', 'NOT_IN_RELEASE', 'UNCLAIMED', 'UNVERIFIABLE',
                      'LOCAL_BUILD', 'FOREIGN_NEWER', 'VELOCITY_AHEAD'));
UPDATE run_step SET service = NULL WHERE step = 'DUMP_DATABASE';
-- Only the resolver's note about Velocity ended the run's own resolve step with a warning and no reason of its own.
UPDATE run_step SET reason = 'VELOCITY_AHEAD'
WHERE step = 'RESOLVE' AND service IS NULL AND outcome = 'WARNING' AND reason = 'SOURCE_FAILED';
