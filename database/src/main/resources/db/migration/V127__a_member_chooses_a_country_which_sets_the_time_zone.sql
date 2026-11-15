-- A member chooses a country. The bot writes it beside the language and the time zone that follows from it.
ALTER TABLE discord_user ADD COLUMN country varchar(32);

-- The category whose channels the bot last closed to every other language, so it takes the denials back from a
-- former category after a restart.
CREATE TABLE discord_language_category
(
    language varchar(32) PRIMARY KEY,
    category varchar(32) NOT NULL
);
COMMENT ON TABLE discord_language_category IS 'Owned by discord-bot: the category of each language whose channels it last closed to the roles of the other languages.';
GRANT SELECT ON discord_language_category TO ${role_read};
GRANT SELECT, INSERT, UPDATE, DELETE ON discord_language_category TO ${role_discord_bot};

-- A country is a role the bot keeps, as a region was.
ALTER TABLE journal_role DROP CONSTRAINT journal_role_role_check;
ALTER TABLE journal_role
    ADD CONSTRAINT journal_role_role_check CHECK (role IN (
        'ACCESS', 'DONOR', 'ADMIN', 'LANGUAGE', 'REGION', 'COUNTRY', 'LOCK'));

ALTER TABLE journal_entry DROP CONSTRAINT journal_entry_action_check;
ALTER TABLE journal_entry
    ADD CONSTRAINT journal_entry_action_check CHECK (action IN (
        'ADMIN_ROOT', 'GRANT_ADMIN', 'REVOKE_ADMIN', 'ENFORCE_PACK', 'EXEMPT_PACK', 'GRANT_ACCESS', 'REVOKE_ACCESS',
        'SET_PLAYTIME', 'LINK', 'UNLINK', 'SETTLE', 'REGISTER_KEY', 'HELD_KEY', 'RENAME_KEY', 'REMOVE_KEY',
        'ADD_AUTHENTICATOR', 'USED_AUTHENTICATOR', 'REMOVE_AUTHENTICATOR', 'FORGET_FACTORS', 'WEB_PUSH_SUBSCRIBE',
        'WEB_PUSH_UNSUBSCRIBE', 'WEB_PUSH_TEST', 'SET_ALERT_PREFERENCE', 'CONSOLE', 'SAVE_SETTINGS', 'SAVE_MESSAGES',
        'ADD_PLUGIN', 'REMOVE_PLUGIN', 'REQUEST_RUN', 'CANCEL_RUN', 'DOWNLOAD_BACKUP', 'ANNOUNCE', 'COMPLETE_OBJECTIVE',
        'UNLOCK_MILESTONE', 'START_GAME', 'LINK_CODES_EXHAUSTED', 'SET_AURA', 'SET_SPINS', 'SERVICE_DOWN',
        'BACKUP_STALE', 'DISK_FULL', 'MEMORY_FULL', 'IMAGE_DRIFT', 'PACK_DEAD', 'RUN_FAILED', 'PAYMENT_NEEDS_LOOK',
        'LINK_REFUSED', 'LINK_FAILED', 'PURCHASE_FAILED', 'DM_NOT_DELIVERED', 'ROLE_NOT_CHANGED', 'ROLE_AMBIGUOUS',
        'ROLE_NOT_CREATED', 'LOCK_NOT_KEPT', 'LOCK_WITHOUT_CHANNEL', 'REMOVE_STACK', 'OPEN_INVENTORY', 'RESET_TRACK',
        'SET_LAUNCH_DATE', 'LAUNCH_NOW', 'CLOSE_SEASON', 'SET_MAINTENANCE', 'SWITCH_MODE', 'START_CHANNEL_HIDDEN',
        'LANGUAGE_NOT_KEPT', 'CHANNEL_SEEN'));

ALTER TABLE journal_entry DROP CONSTRAINT journal_entry_level_iff_alert;
ALTER TABLE journal_entry
    ADD CONSTRAINT journal_entry_level_iff_alert CHECK ((level IS NOT NULL) = (action IN (
        'SERVICE_DOWN', 'BACKUP_STALE', 'DISK_FULL', 'MEMORY_FULL', 'IMAGE_DRIFT', 'PACK_DEAD', 'RUN_FAILED',
        'PAYMENT_NEEDS_LOOK', 'LINK_REFUSED', 'LINK_FAILED', 'PURCHASE_FAILED', 'DM_NOT_DELIVERED', 'ROLE_NOT_CHANGED',
        'ROLE_AMBIGUOUS', 'ROLE_NOT_CREATED', 'LOCK_NOT_KEPT', 'LOCK_WITHOUT_CHANNEL', 'START_CHANNEL_HIDDEN',
        'LANGUAGE_NOT_KEPT', 'CHANNEL_SEEN')));

-- The countries replaced the regions, and the start channel's message replaced the change channel's, so an admin's
-- values under those keys go; their nodes follow by cascade.
DELETE FROM setting_override
WHERE service = 'discord-bot'
  AND name = 'onboarding'
  AND (path = 'change-channel' OR path = 'regions' OR path LIKE 'regions.%' OR path LIKE 'regions[%');
