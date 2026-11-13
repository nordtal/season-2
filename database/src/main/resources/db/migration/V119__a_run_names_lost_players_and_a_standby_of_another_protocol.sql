-- Players back warns when fewer players came back than the run moved out, and standby up fails a standby that
-- answers with another protocol than the service it stands in for.
ALTER TABLE run_step DROP CONSTRAINT run_step_reason_check;
ALTER TABLE run_step ADD CONSTRAINT run_step_reason_check
    CHECK (reason IN ('UNREACHABLE', 'UNEXPECTED', 'CANCELLED', 'INTERRUPTED', 'NO_CONTAINER', 'REFUSED',
                      'NOT_HEALTHY', 'UNVERIFIED_STOP', 'PLAYERS_STAYED', 'PLAYERS_UNKNOWN', 'PLAYERS_LOST',
                      'NO_ROOM', 'TOOL_FAILED', 'NO_REPOSITORY', 'REFUSED_LOCAL_BUILD', 'RELEASED_MEANWHILE',
                      'OLDER_RELEASE', 'HELD', 'NOT_IN_SCOPE', 'NOTHING_HELD', 'UNNAMED', 'NEVER_DOWN',
                      'SELF', 'UNKNOWN', 'NOT_A_VOLUME', 'NO_VOLUMES', 'NOTHING_TO_REMOVE', 'NOT_SAVED',
                      'LEFT_DOWN', 'SOURCE_FAILED', 'NOT_IN_RELEASE', 'UNCLAIMED', 'UNVERIFIABLE',
                      'LOCAL_BUILD', 'FOREIGN_NEWER', 'VELOCITY_AHEAD', 'MISSING_SECRET', 'PROTOCOL_MISMATCH'));
