-- The updater's inbox: one row per "please check", "please install", "please restart", and the
-- answer written back into the same row. See docs/updater.md#how-it-is-operated.
--
-- WHY A TABLE AT ALL. The updater is a separate container. Neither the Discord bot nor the SMP
-- plugin can call it - no shared process, no socket, and deliberately no Docker socket anywhere in
-- this deployment. What all four processes do share is one PostgreSQL, so the request travels
-- through it: a row, a `pg_notify`, and the updater listening. That is the same machinery the
-- phase model already uses (V4, `nordtal_phase`), so it is not a new kind of wiring - and it means
-- a request survives an updater that happens to be restarting at that moment, which a socket call
-- would not.
--
-- WHY THE ANSWER IS THE SAME ROW rather than a second table. There is exactly one answer per
-- request and it is never amended; a `result` column is the whole of it. A separate table would
-- buy a join and the chance of an answer with no question.
--
-- No `interval` anywhere, for the reason V4 states at length: `now() + interval '1 minute'` is
-- calendar arithmetic in the session's time zone, which the JDBC driver takes from the writing
-- JVM's default. `not_before` is written by the caller as an absolute instant instead.
CREATE TABLE update_request
(
    id           bigserial PRIMARY KEY,

    -- What was asked for. The three are ordered by how much they can break, and each is a
    -- superset of nothing: REPORT never writes, APPLY writes jars and the schema, RESTART takes
    -- the whole network down. They are deliberately NOT one command with flags - a button that
    -- says "apply" must not be able to become a restart because a column defaulted.
    kind         varchar(16) NOT NULL
        CONSTRAINT update_request_kind_check
            CHECK (kind IN ('REPORT', 'APPLY', 'RESTART')),

    -- PENDING -> RUNNING -> DONE | FAILED, or PENDING -> CANCELLED. Nothing goes back.
    --
    -- CANCELLED exists for exactly one thing: the minute between `/smp update restart` and the
    -- restart actually happening. A countdown that cannot be stopped is worse than no countdown -
    -- the admin who mistypes has sixty seconds and no way to use them.
    status       varchar(16) NOT NULL DEFAULT 'PENDING'
        CONSTRAINT update_request_status_check
            CHECK (status IN ('PENDING', 'RUNNING', 'DONE', 'FAILED', 'CANCELLED')),

    -- Which surface it came from, for the audit trail and for nothing else. The updater treats
    -- all three identically - a restart asked for from a chat line is the same restart.
    source       varchar(16) NOT NULL
        CONSTRAINT update_request_source_check
            CHECK (source IN ('DISCORD', 'GAME', 'CONSOLE')),

    -- Who asked: a Discord id, a Minecraft name, or NULL for the console. Free text on purpose -
    -- this is the one column read only by humans, and a foreign key to `discord_user` would mean
    -- an in-game request from an unlinked admin could not be recorded at all.
    requested_by varchar(64),

    requested    timestamptz NOT NULL DEFAULT now(),

    -- The countdown, as an absolute instant. Equal to `requested` for everything that runs at
    -- once; sixty seconds later for a restart, which is the warning players get.
    --
    -- It is stored rather than computed by whoever announces it, because two processes read it:
    -- the updater, which must not act before it, and network-control, which counts down towards
    -- it for every player on the network. A number in two config files would drift.
    not_before   timestamptz NOT NULL DEFAULT now(),

    started      timestamptz,
    finished     timestamptz,

    -- The report, verbatim - the same text `updater apply` prints to stdout. Read back by the bot
    -- into an embed and by the plugin into chat, so whatever a person sees is what actually
    -- happened rather than a second rendering of it.
    result       text
);

-- The updater's claim query and network-control's countdown query are both "the pending work,
-- oldest first". A partial index keeps that to the handful of rows that are actually pending,
-- however long the history gets.
CREATE INDEX update_request_pending
    ON update_request (not_before, id)
    WHERE status = 'PENDING';
