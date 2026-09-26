-- A run may name the services it is for.
--
-- Until now every run was the whole network. The owner's ask is the small one: it should be
-- possible to start an update run for a single service, following the usual procedure. That last
-- half is the part that matters - a scoped run is not an abbreviated one. It still resolves, still
-- counts down, still parks the players, still waits for health, still writes the same report. The
-- only thing that is smaller is what it touches.
--
-- NULL IS THE WHOLE NETWORK, and it has to be: every row written before this column existed is one,
-- and so is every /update somebody types without naming anything. A default of '' would have made
-- "no services" indistinguishable from "all of them", which is the difference between a run that
-- updates the network and one that updates nothing.
ALTER TABLE update_request
    ADD COLUMN scope text;

-- Comma-separated compose service names - `smp`, or `smp,limbo`.
--
-- Not text[], and that is a judgement rather than an oversight: exactly one process reads this
-- column (steward-worker, when it claims the row), the value is at most a handful of short words,
-- and an array needs a mapper of its own in every place a row is read. It is also not a table of
-- its own for the same reason - a join for four words nobody queries by.
--
-- The CHECK is what keeps the NULL meaning above honest: a row may say "everything" (NULL) or it
-- may name services, and it may never say "" or ",," - both of which would resolve to a run that
-- stops nothing while claiming to be scoped.
ALTER TABLE update_request
    ADD CONSTRAINT update_request_scope_check
        CHECK (scope IS NULL OR scope ~ '^[a-z0-9-]+(,[a-z0-9-]+)*$');

COMMENT ON COLUMN update_request.scope IS
    'Comma-separated compose services this run is for; NULL is the whole network.';
