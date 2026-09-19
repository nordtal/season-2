-- season-2-ops/125: a service can be held down on purpose, and stays down until somebody says so.
--
-- The owner's ask: besides Recreate there should be a Down that stops a service and keeps it down
-- until the Start button that then appears is pressed. Two things a database has to carry for that
-- sentence to survive a restart of the worker and of the interface: two more kinds of request, and
-- a row that says which services are being held.

ALTER TABLE update_request
    DROP CONSTRAINT update_request_kind_check;

-- NOT VALID for the same reason V14 gave: this only widens the list, so re-validating the whole
-- history buys nothing. APPLY is still in it and still retired - a retired value has to stay
-- readable.
ALTER TABLE update_request
    ADD CONSTRAINT update_request_kind_check
        CHECK (kind IN ('REPORT', 'APPLY', 'UPDATE', 'RESTART', 'BACKUP', 'DOWN', 'START'))
        NOT VALID;

COMMENT ON COLUMN update_request.kind IS
    'REPORT resolves and says what is new. UPDATE counts down, stops the servers whose jars '
        'change, migrates, swaps and starts them again. RESTART is that sequence with nothing '
        'installed. BACKUP is that sequence with a volume snapshot in the gap. DOWN counts down '
        'and stops, and does NOT start again. START is the other half of DOWN. APPLY is retired.';

-- WHICH SERVICES ARE BEING HELD DOWN, and nothing else.
--
-- A row here is a decision a person made, not an observation of the container runtime. That is the
-- whole distinction the interface has to draw: a service that is down because somebody pressed Down
-- and a service that is down because it crashed must not look the same, and the only place that
-- difference exists is here. Reading it from Docker is impossible in principle - a stopped
-- container says nothing about why.
--
-- NOT a column on a service table, because there is no service table: the services are named in
-- `Topology` in Java and in `compose.yml`, and inventing a registry row for each of them here would
-- create a second list that can disagree with those two. A hold is a fact about an event, so it
-- lives on its own and the absence of a row is the ordinary case.
CREATE TABLE service_hold (
    -- The compose service name, the same string `update_request.scope` carries.
    service    text PRIMARY KEY,
    since      timestamptz NOT NULL DEFAULT now(),
    -- Who pressed it, in the same shape as `update_request.requested_by`. Nullable because a hold
    -- written by something other than a person has nobody to name.
    held_by    text,
    -- The DOWN run that put it here. ON DELETE SET NULL rather than CASCADE: the hold outliving the
    -- row that explains it is a worse answer than the hold disappearing quietly, because the
    -- service would then be started by the next run with nobody having asked for that.
    request_id bigint REFERENCES update_request (id) ON DELETE SET NULL,
    CONSTRAINT service_hold_service_check CHECK (service ~ '^[a-z0-9-]+$')
);

COMMENT ON TABLE service_hold IS
    'One row per service that was deliberately stopped and must stay stopped until somebody starts '
        'it (season-2-ops/125). No row is the ordinary case.';
