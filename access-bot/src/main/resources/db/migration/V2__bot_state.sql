-- State that only the bot has, and that only the bot reads. None of it is on the login path, so
-- none of it is reachable through :common's AccessDirectory - the proxy and the plugins never see
-- these tables.
--
-- Added in stage B (2026-08-30). Everything here exists for the same reason: something the bot
-- does exactly once must survive a restart. A Guava cache or an in-memory Set would make a restart
-- silently repeat it - a second copy of a managed message, a second admin ping about the same
-- payment, a second "your access runs out" DM.


-- The four bot-maintained messages (contribution DE/EN, link DE/EN).
--
-- Without this table, "post or edit my message on startup" is not answerable: the bot would have
-- to scan channel history and guess which message is its own, and a failed guess leaves a
-- duplicate that nobody can tell apart. The kind is the primary key because there is exactly one
-- message per kind, whatever channel configuration points it at.
CREATE TABLE managed_message
(
    -- CONTRIBUTION_EN, CONTRIBUTION_DE, LINK_EN, LINK_DE. Deliberately not constrained by a CHECK:
    -- adding a fifth managed message must not need a migration.
    kind       varchar(32) PRIMARY KEY,

    -- Where it was posted. If configuration moves the message to another channel, this no longer
    -- matches and the bot posts a fresh one rather than editing a message in the old channel.
    channel_id varchar(32) NOT NULL,
    message_id varchar(32) NOT NULL,

    updated    timestamptz NOT NULL DEFAULT now()
);


-- One row per bunq payment the bot has raised to the admin channel, so it raises it exactly once.
--
-- The poll loop sees the same unmatchable payment on every pass - bunq keeps returning it - and
-- without this the admin channel would get the same line every poll interval, forever. The bunq
-- payment id is the primary key: the insert is the deduplication, so two overlapping polls cannot
-- both decide they are the first.
CREATE TABLE payment_notice
(
    bunq_payment_id bigint PRIMARY KEY,

    -- UNMATCHED, EXPIRED_REFERENCE, BELOW_MINIMUM, ...
    reason          varchar(32) NOT NULL,

    detail          text,
    reported        timestamptz NOT NULL DEFAULT now()
);


-- One row per DM the bot has sent about one particular end of access, so it sends it exactly once.
--
-- Keyed by the end of the period rather than by the user: buying more access moves valid_until, so
-- the next period gets its own reminder without anything having to be cleaned up here.
CREATE TABLE expiry_notice
(
    discord_id  varchar(32) NOT NULL
        REFERENCES discord_user (discord_id) ON DELETE CASCADE,

    -- The access_grant.valid_until this notice was about.
    valid_until timestamptz NOT NULL,

    -- SOON (the lead-time reminder) or EXPIRED.
    kind        varchar(16) NOT NULL
        CONSTRAINT expiry_notice_kind_check CHECK (kind IN ('SOON', 'EXPIRED')),

    sent        timestamptz NOT NULL DEFAULT now(),

    PRIMARY KEY (discord_id, valid_until, kind)
);
