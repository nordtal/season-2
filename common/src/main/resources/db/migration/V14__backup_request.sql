-- A fifth kind of update request: BACKUP.
--
-- The network's volume backup is a RUN and not a schedule, and this one line in a CHECK is what
-- makes it one. Arcane can already back a volume up on a timer of its own, and it can already be
-- told to stop the containers holding that volume first (`StopContainers` on the policy). Both
-- halves of that are wrong for a Minecraft server:
--
--   * a stop nobody announced takes the world out from under whoever is standing in it, with no
--     countdown at all - and the thirty-second countdown is the whole reason this table exists
--     rather than a cron job; and
--   * `StopContainers` off instead means the snapshot is taken of a world Paper is writing to,
--     which fails at RESTORE rather than at backup - the worst possible place for it to fail.
--
-- So Arcane's policy keeps deciding WHERE a snapshot goes (local, S3, both) and nothing else, its
-- StopContainers flag stays off, and the updater does the stopping: count down, stop, ask Arcane
-- for one backup per volume, wait for each, start again, wait for every healthcheck. That is the
-- same sequence a RESTART already runs, with one step inserted in the gap - which is exactly why
-- it is a kind here rather than a mechanism of its own.
--
-- WHO WRITES THE NIGHTLY ONE IS NOT THE UPDATER, and that is deliberate. `serve` is not a scheduler
-- and must never become one (docs/updater.md): a timer inside it would mean a crash restart at
-- three in the morning could move a version. The nightly row is written by `smp`, which already
-- owns a daily clock for the farm world and writes this one fifteen minutes ahead of it - see
-- smp's config.yml#backup-time. The consequence is written down rather than hidden: a season with
-- `smp` down has no nightly backup, and nothing else in the stack will notice.


ALTER TABLE update_request
    DROP CONSTRAINT update_request_kind_check;

-- NOT VALID for the reason V12 gave: every row already in this table was written under the older
-- list, this only widens it, and re-reading the whole history of a table nothing ever updates in
-- bulk buys nothing. APPLY is still in the list and still retired - UpdateKind explains why a
-- retired value stays readable.
ALTER TABLE update_request
    ADD CONSTRAINT update_request_kind_check
        CHECK (kind IN ('REPORT', 'APPLY', 'UPDATE', 'RESTART', 'BACKUP')) NOT VALID;

COMMENT ON COLUMN update_request.kind IS
    'REPORT resolves and says what is new. UPDATE counts down, stops the servers whose jars '
        'change, migrates, swaps and starts them again. RESTART is that sequence with nothing '
        'installed. BACKUP is that sequence with an Arcane volume backup in the gap. APPLY is '
        'retired and refused - see UpdateKind and V12.';
