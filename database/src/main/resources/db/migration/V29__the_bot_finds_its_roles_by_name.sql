-- The bot finds every Discord role it uses by name and keeps the role it found by id.
--
-- Steward holds a name per role. On first need the bot adopts the role of exactly that name, or creates it, and
-- writes its id here; from then on it follows the id alone, so an admin may rename the role in Discord. Only a role
-- that is gone is looked up by name again.
--
-- This only adds. The ids configured so far are carried over as they stand, so the bot takes over the roles it
-- already used instead of creating new ones; the access group's id rows stay where they are, read by nothing, and
-- go a release later.

CREATE TABLE discord_role
(
    -- What the role stands for: access, donor, admin, lock, language/<tag> or region/<zone>. No CHECK, so a new
    -- kind of role needs no schema change.
    role_key varchar(80) PRIMARY KEY CONSTRAINT discord_role_role_key_check CHECK (length(role_key) > 0),
    role_id  varchar(32) NOT NULL CONSTRAINT discord_role_role_id_check CHECK (role_id ~ '^[0-9]{1,20}$'),
    adopted  timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE discord_role IS 'Owned by discord-bot: the Discord role it found or created for each role it uses.';
-- A role stands for one thing.
CREATE UNIQUE INDEX discord_role_role_id_key ON discord_role (role_id);

-- The access and donor roles and the language roles the access group named by id. The admin role was never a row:
-- it came from the environment, and the bot carries it over itself.
INSERT INTO discord_role (role_key, role_id)
SELECT CASE path WHEN 'roles.access' THEN 'access' ELSE 'donor' END, value #>> '{}'
FROM setting_override
WHERE service = 'discord-bot' AND name = 'access' AND path IN ('roles.access', 'roles.donor')
  AND jsonb_typeof(value) = 'string' AND value #>> '{}' ~ '^[0-9]{1,20}$'
ON CONFLICT DO NOTHING;

INSERT INTO discord_role (role_key, role_id)
SELECT 'language/' || (language ->> 'tag'), language ->> 'role'
FROM setting_override,
     jsonb_array_elements(CASE jsonb_typeof(value) WHEN 'array' THEN value ELSE '[]' END) AS language
WHERE service = 'discord-bot' AND name = 'access' AND path = 'languages'
  AND jsonb_typeof(language) = 'object' AND length(language ->> 'tag') > 0 AND language ->> 'role' ~ '^[0-9]{1,20}$'
ON CONFLICT DO NOTHING;

GRANT SELECT, INSERT, UPDATE, DELETE ON discord_role TO ${role_discord_bot};
