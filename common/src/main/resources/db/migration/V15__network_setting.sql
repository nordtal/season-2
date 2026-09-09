-- Values the PROXY decides and rewrites, as distinct from the ones the bot decides once.
--
-- The command allowlist lived in bot_setting for one afternoon (2026-09-09), and the reasoning
-- for that was honest and worth repeating: it is one row of text, and a table for it is a
-- migration. What it cost was the truth of a name. V3 introduced bot_setting as "values the bot
-- decides once and must never decide again", and this row is neither - it belongs to
-- network-control and is rewritten from network.yml on every proxy start. A table whose comment
-- describes something other than what is in it is not a small cost: the next person to read V3
-- believes it, and the row after this one gets filed by the same reasoning until nothing in
-- either table means what it says.
--
-- So this is the proxy's own table, and the rule that comes with it is the rule V3 already
-- states for its own: what goes in here is a value the proxy owns. A value the bot owns goes
-- there. Neither table is a general key/value store for whatever needs one.
--
-- A separate migration rather than an edit to V3, for the reason V3 itself gives: V3 is applied
-- to real databases and Flyway validates the checksum of an applied migration.
CREATE TABLE network_setting
(
    key     varchar(64) PRIMARY KEY,
    value   text        NOT NULL,
    created timestamptz NOT NULL DEFAULT now()
);


-- The command allowlist: every command a player who is not an admin may type, one path per line.
--
-- No row is seeded here. An absent row and an empty list are deliberately different answers -
-- "no proxy has ever published" against "the proxy published nothing" - because the backends'
-- filter fails OPEN on the first and CLOSED on the second, and seeding a row would erase that
-- distinction on a fresh database, which is exactly where it matters. network-control writes it
-- at startup from network.yml#command-allowlist and sends NOTIFY nordtal_allowlist when the
-- value actually moved.
--
-- The value is the proxy's file, not a second source of truth: nothing reads this row to decide
-- what the allowlist should be. It is a transport, and the poll behind it is the guarantee.
