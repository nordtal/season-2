-- The bot's Discord roles are the rows of discord_role, so the role ids the access group held go.
--
-- These are the access and donor role and each language entry's role. A language entry keeps everything else.

DELETE FROM setting_override
WHERE service = 'discord-bot' AND name = 'access' AND path IN ('roles.access', 'roles.donor');

UPDATE setting_override
SET value = (SELECT jsonb_agg(CASE jsonb_typeof(language) WHEN 'object' THEN language - 'role' ELSE language END
                              ORDER BY position)
             FROM jsonb_array_elements(value) WITH ORDINALITY AS entry (language, position))
WHERE service = 'discord-bot' AND name = 'access' AND path = 'languages' AND jsonb_typeof(value) = 'array'
  AND EXISTS (SELECT FROM jsonb_array_elements(value) AS entry (language)
              WHERE jsonb_typeof(language) = 'object' AND language -> 'role' IS NOT NULL);
