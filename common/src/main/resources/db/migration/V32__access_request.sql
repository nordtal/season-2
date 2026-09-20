-- The bot's inbox for access changes: one row per request, the answer written back into the same
-- row (season-2-community/08).
--
-- WHY THIS EXISTS AT ALL. Granting access is four things - a row, a Discord role, a direct message
-- in the recipient's own language, and a line in the admin channel - and only the bot holds a JDA
-- session, so only the bot can do three of them. Until now steward-ui did the first one itself and
-- silently skipped the rest: somebody granted access through the web never found out. The answer is
-- that the bot executes and every surface only asks.
--
-- WHY A TABLE AND NOT HTTP. The processes share one PostgreSQL and nothing else, and a row survives
-- a bot that happens to be restarting - which is exactly the case an HTTP call cannot survive and
-- the reason Till chose this shape ("am besten spricht die UI über postgres mit dem Bot",
-- 2026-09-20). This is `update_request`'s pattern, one table later.
--
-- No `interval` literal anywhere, for the reason V4 gives: `now() + interval '1 day'` is calendar
-- arithmetic in the session's time zone. Everything here is an absolute instant.
CREATE TABLE access_request
(
    id           bigserial PRIMARY KEY,

    -- What to do. Ordered by what it touches, and deliberately five verbs rather than one command
    -- with flags: a grant must not be able to become a revoke because a column defaulted.
    kind         varchar(16) NOT NULL
        CONSTRAINT access_request_kind_check
            CHECK (kind IN ('GRANT', 'REVOKE', 'SETTLE', 'UNLINK', 'SET_PLAYTIME')),

    -- PENDING -> RUNNING -> DONE | FAILED, or PENDING -> EXPIRED. Nothing goes back. EXPIRED means
    -- exactly one thing - nothing ever picked this up - which is why the executing side never
    -- writes it, the same rule `command_request` follows.
    status       varchar(16) NOT NULL DEFAULT 'PENDING'
        CONSTRAINT access_request_status_check
            CHECK (status IN ('PENDING', 'RUNNING', 'DONE', 'FAILED', 'EXPIRED')),

    -- Who the change is about: a Discord id for GRANT, REVOKE, UNLINK and SET_PLAYTIME, a payment
    -- reference for SETTLE. Free text rather than a foreign key to `discord_user`, for the reason
    -- `update_request.requested_by` gives: a request about an account the bot has not seen yet must
    -- still be recordable, and a SETTLE's subject is not an account at all.
    subject      varchar(64) NOT NULL,

    -- The one number a kind needs, as text: days for GRANT, seconds for SET_PLAYTIME, NULL for the
    -- three that need none. Text because the column has to hold two different units and a numeric
    -- column that means days in one row and seconds in the next is a column that means nothing.
    argument     varchar(64),

    -- Which surface asked, for the audit trail. The bot treats them identically.
    source       varchar(16) NOT NULL
        CONSTRAINT access_request_source_check
            CHECK (source IN ('DISCORD', 'STEWARD', 'GAME', 'CONSOLE')),

    -- Who asked: a Discord id, as `command_request` has recorded it since V18. NULL for a request
    -- nobody signed - a scheduled sweep, the console.
    requested_by varchar(64),

    requested    timestamptz NOT NULL DEFAULT now(),

    -- When to stop waiting. A bot that is never coming back and a bot that is busy look identical
    -- from the outside, and without this the row between them waits for ever. The executing side
    -- refuses to claim a row past this instant; the expiry itself is written by whoever looks.
    expires      timestamptz NOT NULL,

    started      timestamptz,
    finished     timestamptz,

    -- What happened, as JSON, in the same shape an update run writes back into `update_request`:
    -- the row carries its own answer, so no surface composes a second rendering of it.
    result       text
);

-- The bot's claim is "the oldest unexpired pending row"; partial, so it stays small however long
-- the history grows.
CREATE INDEX access_request_pending
    ON access_request (id)
    WHERE status = 'PENDING';
