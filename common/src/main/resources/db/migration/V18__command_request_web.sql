-- A command asked for in the Steward web interface, with a name on it.
--
-- A separate migration rather than an edit to V11, for the reason V3, V8, V9 and V16 already wrote
-- down: V11 is committed and has been applied, and Flyway validates the checksum of an applied
-- migration. Editing it would break every existing deployment on startup.
--
-- WHY THIS IS NOT JUST `CONSOLE`. The interface could have written its rows as CONSOLE and saved a
-- migration. It must not: V11's `command_request_console_is_anonymous` pins a CONSOLE row to
-- having no identity at all, deliberately, because the server console has none. Every admin
-- command sent from the web would therefore have been anonymous - which is exactly the question
-- the journal and the "letzte Aktionen" tile exist to answer. A surface that cannot say who acted
-- is worse than no surface.
--
-- SYSTEM is deliberately NOT added here. It is a Surface value that describes where a command may
-- be registered, not a source a row is ever written with: `announce` travels as CONSOLE, correctly,
-- because nobody typed it. Adding a value nothing writes is a CHECK that stops checking.

-- NOT VALID, AND THEN VALIDATED SEPARATELY, for both of the constraints below.
--
-- A plain ADD CONSTRAINT scans the whole table while holding ACCESS EXCLUSIVE, so every command
-- submission waits for the scan. Today that is 28 rows and microseconds; a season from now this
-- table is every command anybody sent all year, and by then this file cannot be changed - it will
-- have been applied and Flyway will hold its checksum. NOT VALID takes the lock only long enough
-- to record the rule, VALIDATE CONSTRAINT then re-reads under a lock that lets writes through, and
-- the rule applies to new rows from the first statement onwards either way.
--
-- The source check is replaced rather than altered, which Postgres has no single statement for. It
-- goes in under a temporary name so that the moment where neither constraint is in force is one
-- statement long instead of a full table scan long.
ALTER TABLE command_request
    ADD CONSTRAINT command_request_source_check_new
        CHECK (source IN ('DISCORD', 'GAME', 'CONSOLE', 'WEB')) NOT VALID;

ALTER TABLE command_request
    VALIDATE CONSTRAINT command_request_source_check_new;

ALTER TABLE command_request
    DROP CONSTRAINT command_request_source_check;

ALTER TABLE command_request
    RENAME CONSTRAINT command_request_source_check_new TO command_request_source_check;

-- The same rule DISCORD has, and for the same reason: the target re-reads `discord_user.admin`
-- after it claims the row, because the flag can change while the row is waiting. A row with no
-- Discord id cannot be re-authorised, so it must never be written in the first place.
--
-- The web session IS a Discord identity - that is how somebody signed in - so there is nothing to
-- invent here. `mc_uuid` stays optional: an admin who has never linked a Minecraft account can
-- still press a button.
ALTER TABLE command_request
    ADD CONSTRAINT command_request_web_knows_who
        CHECK (source <> 'WEB' OR discord_id IS NOT NULL) NOT VALID;

ALTER TABLE command_request
    VALIDATE CONSTRAINT command_request_web_knows_who;
