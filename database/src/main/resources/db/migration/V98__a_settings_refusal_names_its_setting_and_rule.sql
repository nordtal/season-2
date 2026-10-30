-- A settings refusal names the setting and the rule it broke, which Steward words. The free-text detail stays
-- readable and writable until every server writes the typed values.
ALTER TABLE smp_refusal
    ADD COLUMN setting text,
    ADD COLUMN rule    varchar(32)
        CONSTRAINT smp_refusal_rule_check CHECK (rule IN (
            'REQUIRED', 'POSITIVE', 'NOT_NEGATIVE', 'AT_LEAST', 'AT_MOST', 'ABOVE', 'ENTRIES', 'MOST_ENTRIES',
            'LONGEST', 'UNIQUE', 'ASCENDING', 'CORNERS', 'KNOWN', 'TRACK', 'OBJECTIVE_NAMES', 'VALID')),
    ADD COLUMN value   text;
ALTER TABLE smp_refusal DROP CONSTRAINT smp_refusal_detail_iff_settings;
ALTER TABLE smp_refusal
    ADD CONSTRAINT smp_refusal_detail_only_settings CHECK (detail IS NULL OR reason = 'SETTINGS_REFUSED'),
    ADD CONSTRAINT smp_refusal_setting_only_settings CHECK (setting IS NULL OR reason = 'SETTINGS_REFUSED'),
    ADD CONSTRAINT smp_refusal_rule_iff_setting CHECK ((rule IS NULL) = (setting IS NULL)),
    ADD CONSTRAINT smp_refusal_value_only_rule CHECK (value IS NULL OR rule IS NOT NULL),
    ADD CONSTRAINT smp_refusal_settings_named
        CHECK (reason <> 'SETTINGS_REFUSED' OR detail IS NOT NULL OR setting IS NOT NULL);
COMMENT ON COLUMN smp_refusal.setting IS 'The path of the setting a SETTINGS_REFUSED names, or its group where no one setting is at fault.';
COMMENT ON COLUMN smp_refusal.rule IS 'The rule of the server''s check that the setting broke.';
COMMENT ON COLUMN smp_refusal.value IS 'The value the rule names: a limit, another setting or the value at fault, or null.';

ALTER TABLE hunger_games_refusal
    ADD COLUMN setting text,
    ADD COLUMN rule    varchar(32)
        CONSTRAINT hunger_games_refusal_rule_check CHECK (rule IN (
            'REQUIRED', 'POSITIVE', 'NOT_NEGATIVE', 'AT_LEAST', 'AT_MOST', 'ABOVE', 'ENTRIES', 'MOST_ENTRIES',
            'LONGEST', 'UNIQUE', 'ASCENDING', 'CORNERS', 'KNOWN', 'TRACK', 'OBJECTIVE_NAMES', 'VALID')),
    ADD COLUMN value   text;
ALTER TABLE hunger_games_refusal DROP CONSTRAINT hunger_games_refusal_detail_iff_settings;
ALTER TABLE hunger_games_refusal
    ADD CONSTRAINT hunger_games_refusal_detail_only_settings CHECK (detail IS NULL OR reason = 'SETTINGS_REFUSED'),
    ADD CONSTRAINT hunger_games_refusal_setting_only_settings
        CHECK (setting IS NULL OR reason = 'SETTINGS_REFUSED'),
    ADD CONSTRAINT hunger_games_refusal_rule_iff_setting CHECK ((rule IS NULL) = (setting IS NULL)),
    ADD CONSTRAINT hunger_games_refusal_value_only_rule CHECK (value IS NULL OR rule IS NOT NULL),
    ADD CONSTRAINT hunger_games_refusal_settings_named
        CHECK (reason <> 'SETTINGS_REFUSED' OR detail IS NOT NULL OR setting IS NOT NULL);
COMMENT ON COLUMN hunger_games_refusal.setting IS 'The path of the setting a SETTINGS_REFUSED names, or its group where no one setting is at fault.';
COMMENT ON COLUMN hunger_games_refusal.rule IS 'The rule of the server''s check that the setting broke.';
COMMENT ON COLUMN hunger_games_refusal.value IS 'The value the rule names: a limit, another setting or the value at fault, or null.';
