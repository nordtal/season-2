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

ALTER TABLE command_request
    DROP CONSTRAINT command_request_source_check;

ALTER TABLE command_request
    ADD CONSTRAINT command_request_source_check
        CHECK (source IN ('DISCORD', 'GAME', 'CONSOLE', 'WEB'));

-- The same rule DISCORD has, and for the same reason: the target re-reads `discord_user.admin`
-- after it claims the row, because the flag can change while the row is waiting. A row with no
-- Discord id cannot be re-authorised, so it must never be written in the first place.
--
-- The web session IS a Discord identity - that is how somebody signed in - so there is nothing to
-- invent here. `mc_uuid` stays optional: an admin who has never linked a Minecraft account can
-- still press a button.
ALTER TABLE command_request
    ADD CONSTRAINT command_request_web_knows_who
        CHECK (source <> 'WEB' OR discord_id IS NOT NULL);
