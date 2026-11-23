ALTER TABLE smp_refusal DROP CONSTRAINT smp_refusal_reason_check;
ALTER TABLE smp_refusal
    ADD CONSTRAINT smp_refusal_reason_check CHECK (reason IN (
        'NO_ACTIVE_MILESTONE', 'NO_SUCH_OBJECTIVE', 'MILESTONE_NOT_ACTIVE', 'NOT_HERE', 'SETTINGS_REFUSED',
        'STACK_CHANGED', 'NOT_EDITABLE', 'NO_BLOCK_IN_SIGHT', 'NO_AREA_DRAFT', 'HOTBAR_FULL', 'WRONG_WORLD',
        'NO_EXAMPLE'));

ALTER TABLE hunger_games_refusal DROP CONSTRAINT hunger_games_refusal_reason_check;
ALTER TABLE hunger_games_refusal
    ADD CONSTRAINT hunger_games_refusal_reason_check CHECK (reason IN (
        'NO_GAME', 'WRONG_STATE', 'BELOW_HARD_MINIMUM', 'BELOW_SOFT_MINIMUM', 'NOT_HERE', 'SETTINGS_REFUSED',
        'STACK_CHANGED', 'NOT_EDITABLE', 'NO_EXAMPLE'));
