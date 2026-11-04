-- Every server names the setting and the rule of a settings refusal. A refusal an older server wrote in words only
-- names its group, with the rule VALID, which Steward words without the free text; detail stays until no release
-- writes it.
UPDATE smp_refusal AS r
SET setting = i.payload -> 'change' ->> 'group', rule = 'VALID', value = NULL
FROM smp_inbox AS i
WHERE i.id = r.request AND r.reason = 'SETTINGS_REFUSED' AND r.setting IS NULL;
ALTER TABLE smp_refusal DROP CONSTRAINT smp_refusal_settings_named;
ALTER TABLE smp_refusal
    ADD CONSTRAINT smp_refusal_settings_named CHECK ((reason = 'SETTINGS_REFUSED') = (setting IS NOT NULL));

UPDATE hunger_games_refusal AS r
SET setting = i.payload -> 'change' ->> 'group', rule = 'VALID', value = NULL
FROM hunger_games_inbox AS i
WHERE i.id = r.request AND r.reason = 'SETTINGS_REFUSED' AND r.setting IS NULL;
ALTER TABLE hunger_games_refusal DROP CONSTRAINT hunger_games_refusal_settings_named;
ALTER TABLE hunger_games_refusal
    ADD CONSTRAINT hunger_games_refusal_settings_named
        CHECK ((reason = 'SETTINGS_REFUSED') = (setting IS NOT NULL));
