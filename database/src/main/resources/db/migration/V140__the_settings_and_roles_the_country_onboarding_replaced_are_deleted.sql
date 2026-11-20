-- The country onboarding replaced the region onboarding, and the bot no longer reads these overrides.
-- Their nodes cascade.
DELETE FROM setting_override
WHERE service = 'discord-bot'
  AND name = 'onboarding'
  AND (path IN ('change-channel', 'regions') OR path LIKE 'regions.%' OR path LIKE 'regions[%');

-- The region roles were deleted in Discord, and no role the bot uses is stored under these keys.
DELETE FROM discord_role WHERE role_key LIKE 'region/%';
