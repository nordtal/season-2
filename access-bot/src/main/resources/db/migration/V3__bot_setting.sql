-- Values the bot decides once and must never decide again.
--
-- A separate migration rather than an edit to V2: V2 is committed and has been applied to local
-- databases, and Flyway validates the checksum of an applied migration. Rewriting it in place -
-- which is what stage A did to V1 - is only safe while nothing anywhere has run it.
--
-- There is exactly one row in here today, and it is worth a table rather than a file because the
-- bot may run as more than one container against one database, and because a config volume is
-- something an operator edits while a file the bot silently rewrote is not.
CREATE TABLE bot_setting
(
    key     varchar(64) PRIMARY KEY,
    value   text        NOT NULL,
    created timestamptz NOT NULL DEFAULT now()
);


-- The payment watermark: payments created before it are ignored, completely and forever.
--
-- 'payment.watermark' is written by the first start that finds it missing, with the instant of
-- that start, and is never rewritten - the insert is ON CONFLICT DO NOTHING, so two containers
-- starting together agree on whichever one got there first rather than each stamping its own.
--
-- It used to be a guessed date in access.yml. That is a value nobody can get right in advance:
-- too early and the first poll books up to 50 historical payments on the bunq account, with the
-- grants, roles, DMs and public thank-yous that go with them; too late and real purchases in the
-- gap are silently ignored. The moment the bot first ran is the only cut-off that is correct by
-- construction.
--
-- access.yml's payment.watermark still overrides this when it is set, and overriding does not
-- replace the stored value - so emptying the override again falls back to the original first-start
-- instant rather than to "now".
