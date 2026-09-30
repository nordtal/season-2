-- A fifth kind of update request: BACKUP.
--
-- A backup is a run and not a schedule. Arcane's own timer would either stop the containers with no
-- countdown, taking the world out from under whoever is standing in it, or snapshot a world Paper is
-- still writing to - which fails at restore rather than at backup. So Arcane's policy only decides
-- WHERE a snapshot goes, its StopContainers flag stays off, and the updater does the stopping: the
-- same sequence a RESTART runs, with the backup inserted in the gap.
--
-- The nightly row is written by `smp`, not by the updater: `serve` is not a scheduler, and a timer
-- inside it would mean a crash restart at three in the morning could move a version. The
-- consequence is that a season with `smp` down has no nightly backup and nothing else notices.


ALTER TABLE update_request
    DROP CONSTRAINT update_request_kind_check;

-- NOT VALID: this only widens the list, so re-validating the whole history buys nothing. APPLY is
-- still in the list and still retired, because a retired value has to stay readable.
ALTER TABLE update_request
    ADD CONSTRAINT update_request_kind_check
        CHECK (kind IN ('REPORT', 'APPLY', 'UPDATE', 'RESTART', 'BACKUP')) NOT VALID;

COMMENT ON COLUMN update_request.kind IS
    'REPORT resolves and says what is new. UPDATE counts down, stops the servers whose jars '
        'change, migrates, swaps and starts them again. RESTART is that sequence with nothing '
        'installed. BACKUP is that sequence with an Arcane volume backup in the gap. APPLY is '
        'retired and refused - see UpdateKind and V12.';
