-- Every server reads the spawn's protected areas and Nordtal spawn and the wheel's blocks under their new names, so
-- an admin's values under the old names go; their nodes follow by cascade.
DELETE FROM setting_override
WHERE service = 'smp'
  AND ((name = 'spawn' AND (path = 'spawn-regions' OR path = 'first-join-spawn' OR path LIKE 'first-join-spawn.%'))
    OR (name = 'wheel' AND path = 'wheel-regions'));

-- An area takes its corners in either order, so no server refuses with CORNERS any more.
UPDATE smp_refusal SET rule = 'VALID' WHERE rule = 'CORNERS';
ALTER TABLE smp_refusal DROP CONSTRAINT smp_refusal_rule_check;
ALTER TABLE smp_refusal
    ADD CONSTRAINT smp_refusal_rule_check CHECK (rule IN (
        'REQUIRED', 'POSITIVE', 'NOT_NEGATIVE', 'AT_LEAST', 'AT_MOST', 'ABOVE', 'ENTRIES', 'MOST_ENTRIES',
        'LONGEST', 'UNIQUE', 'ASCENDING', 'KNOWN', 'TRACK', 'OBJECTIVE_NAMES', 'VALID'));

UPDATE hunger_games_refusal SET rule = 'VALID' WHERE rule = 'CORNERS';
ALTER TABLE hunger_games_refusal DROP CONSTRAINT hunger_games_refusal_rule_check;
ALTER TABLE hunger_games_refusal
    ADD CONSTRAINT hunger_games_refusal_rule_check CHECK (rule IN (
        'REQUIRED', 'POSITIVE', 'NOT_NEGATIVE', 'AT_LEAST', 'AT_MOST', 'ABOVE', 'ENTRIES', 'MOST_ENTRIES',
        'LONGEST', 'UNIQUE', 'ASCENDING', 'KNOWN', 'TRACK', 'OBJECTIVE_NAMES', 'VALID'));
