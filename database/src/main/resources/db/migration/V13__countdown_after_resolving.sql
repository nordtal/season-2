-- The countdown starts when the updater knows there is work, not when somebody asked for one.
--
-- V7 gave `not_before` to the submitter: every surface wrote `now() + 30s` and the updater was
-- forbidden to claim the row until then. That was right while an update was "ask Arcane to
-- redeploy" and wrong the moment the run became resolve, stop, swap, start, verify - because
-- resolving is the step that finds out whether anything is going to happen at all, and it happened
-- AFTER the countdown had already been shown to everybody playing.
--
-- What that cost, on the ordinary run: an admin types /update now, thirty seconds of "the servers
-- are going down" is counted down to every player on the network, and the run then answers
-- "everything is already current" and takes nothing down. The warning is the thing that has to be
-- believed, and a warning that is usually wrong is one people learn to ignore - which is exactly
-- the warning that is standing there on the day it is true.
--
-- So the order is now: claim, check Arcane, resolve, take the lock, plan. If the plan has no work
-- the request is settled quietly, with no countdown at any point. If it does, the updater writes
-- `not_before = now() + 30s` on its OWN claimed row and waits it out.


-- The partial index has to cover a row that is counting down, and such a row is RUNNING.
--
-- Both readers of it changed shape with the same edit. The proxy's countdown query
-- (`UpdateDao#countingDown`) and the cancel behind "Stop the countdown"
-- (`UpdateDao#cancelCountdown`) look for `status IN ('PENDING','RUNNING') AND not_before > now()`;
-- the updater's claim still looks for `status = 'PENDING' AND not_before <= now()`. A partial index
-- restricted to PENDING serves the second and not the first, so the proxy - which asks every five
-- seconds, for the life of the network - would have gone to a sequential scan over the whole
-- history of every update ever asked for.
--
-- Widened rather than replaced by two indexes: the set of rows that are PENDING or RUNNING at any
-- moment is at most a handful (one serve, one claim at a time), so one index over both statuses is
-- the same size as one over PENDING alone and answers both questions.
DROP INDEX IF EXISTS update_request_pending;

CREATE INDEX update_request_pending
    ON update_request (not_before, id)
    WHERE status IN ('PENDING', 'RUNNING');

COMMENT ON COLUMN update_request.not_before IS
    'The instant the run may go ahead. Written as now() by whoever asks, and moved forward by the '
        'updater once it has resolved a plan with work in it - which is what the proxy counts down '
        'to and what "Stop the countdown" withdraws. See V13.';
