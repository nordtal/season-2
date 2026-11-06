-- Every server names the setting and the rule of a settings refusal, and no release writes or reads detail. The
-- settings-named check covers the setting-only check.
ALTER TABLE smp_refusal
    DROP CONSTRAINT smp_refusal_detail_only_settings,
    DROP CONSTRAINT smp_refusal_setting_only_settings,
    DROP COLUMN detail;

ALTER TABLE hunger_games_refusal
    DROP CONSTRAINT hunger_games_refusal_detail_only_settings,
    DROP CONSTRAINT hunger_games_refusal_setting_only_settings,
    DROP COLUMN detail;

-- smp has no setting rails.junction-speed, so an admin's value for it goes; its nodes follow by cascade.
DELETE FROM setting_override WHERE service = 'smp' AND name = 'rails' AND path = 'junction-speed';
