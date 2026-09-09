-- The updater's inbox: one row per request, and the answer written back into the same row.
--
-- The updater is a separate container and nothing here can call it - there is no socket between the
-- processes - so a request travels through the one PostgreSQL they share: a row, a `pg_notify`, and
-- the updater listening. That way a request survives an updater that happens to be restarting.
--
-- The answer is the same row rather than a second table: a separate one would buy a join and the
-- chance of an answer with no question.
--
-- No `interval` anywhere, for the reason V4 gives: `now() + interval '1 minute'` is calendar
-- arithmetic in the session's time zone. `not_before` is written as an absolute instant.
CREATE TABLE update_request
(
    id           bigserial PRIMARY KEY,

    -- What was asked for, ordered by how much it can break: REPORT never writes, APPLY writes jars
    -- and the schema, RESTART takes the whole network down. Deliberately not one command with
    -- flags - "apply" must not be able to become a restart because a column defaulted.
    kind         varchar(16) NOT NULL
        CONSTRAINT update_request_kind_check
            CHECK (kind IN ('REPORT', 'APPLY', 'RESTART')),

    -- PENDING -> RUNNING -> DONE | FAILED, or PENDING -> CANCELLED. Nothing goes back. CANCELLED
    -- exists for the countdown window, because a countdown that cannot be stopped is worse than no
    -- countdown.
    status       varchar(16) NOT NULL DEFAULT 'PENDING'
        CONSTRAINT update_request_status_check
            CHECK (status IN ('PENDING', 'RUNNING', 'DONE', 'FAILED', 'CANCELLED')),

    -- Which surface it came from, for the audit trail and nothing else: the updater treats all
    -- three identically.
    source       varchar(16) NOT NULL
        CONSTRAINT update_request_source_check
            CHECK (source IN ('DISCORD', 'GAME', 'CONSOLE')),

    -- Who asked: a Discord id, a Minecraft name, or NULL for the console. Free text on purpose - a
    -- foreign key to `discord_user` would mean an in-game request from an unlinked admin could not
    -- be recorded at all.
    requested_by varchar(64),

    requested    timestamptz NOT NULL DEFAULT now(),

    -- The countdown, as an absolute instant. Stored rather than computed by whoever announces it,
    -- because two processes read it - the updater, which must not act before it, and
    -- network-control, which counts down towards it - and two configured values would drift.
    not_before   timestamptz NOT NULL DEFAULT now(),

    started      timestamptz,
    finished     timestamptz,

    -- The report, verbatim, read back by every surface so that nobody composes a second rendering
    -- of what happened.
    result       text
);

-- The updater's claim query and network-control's countdown query are both "the pending work,
-- oldest first"; partial, so it stays small however long the history gets.
CREATE INDEX update_request_pending
    ON update_request (not_before, id)
    WHERE status = 'PENDING';
