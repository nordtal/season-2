-- A resolve step that finds a service's own secrets file without a value it must hold names that reason,
-- and each missing name as a line of its own.
ALTER TABLE run_step DROP CONSTRAINT run_step_reason_check;
ALTER TABLE run_step ADD CONSTRAINT run_step_reason_check
    CHECK (reason IN ('UNREACHABLE', 'UNEXPECTED', 'CANCELLED', 'INTERRUPTED', 'NO_CONTAINER', 'REFUSED',
                      'NOT_HEALTHY', 'UNVERIFIED_STOP', 'PLAYERS_STAYED', 'PLAYERS_UNKNOWN', 'NO_ROOM',
                      'TOOL_FAILED', 'NO_REPOSITORY', 'REFUSED_LOCAL_BUILD', 'RELEASED_MEANWHILE',
                      'OLDER_RELEASE', 'HELD', 'NOT_IN_SCOPE', 'NOTHING_HELD', 'UNNAMED', 'NEVER_DOWN',
                      'SELF', 'UNKNOWN', 'NOT_A_VOLUME', 'NO_VOLUMES', 'NOTHING_TO_REMOVE', 'NOT_SAVED',
                      'LEFT_DOWN', 'SOURCE_FAILED', 'NOT_IN_RELEASE', 'UNCLAIMED', 'UNVERIFIABLE',
                      'LOCAL_BUILD', 'FOREIGN_NEWER', 'VELOCITY_AHEAD', 'MISSING_SECRET'));
