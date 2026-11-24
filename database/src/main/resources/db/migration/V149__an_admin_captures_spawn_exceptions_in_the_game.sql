-- An admin captures spawn exception doors and trapdoors in the game, and smp refuses overlapping captures.
ALTER TABLE smp_inbox DROP CONSTRAINT smp_inbox_kind_check;
ALTER TABLE smp_inbox
    ADD CONSTRAINT smp_inbox_kind_check
        CHECK (kind IN ('COMPLETE_OBJECTIVE', 'UNLOCK_MILESTONE', 'PREVIEW_MESSAGE', 'PREVIEW_DOCUMENT',
                        'PREVIEW_NPC', 'CHECK_SETTINGS', 'SET_AURA', 'SET_SPINS', 'STORE_MODE_STATE', 'REMOVE_STACK',
                        'OPEN_INVENTORY', 'TAKE_IN_ITEM', 'SEND_PACK', 'TAKE_POSITION', 'EDIT_AREAS', 'AREA_DRAFT',
                        'EDIT_SPAWN_EXCEPTIONS', 'SPAWN_EXCEPTION_DRAFT'));

ALTER TABLE smp_inbox DROP CONSTRAINT smp_inbox_payload_iff_json;
ALTER TABLE smp_inbox
    ADD CONSTRAINT smp_inbox_payload_iff_json
        CHECK ((kind IN ('CHECK_SETTINGS', 'PREVIEW_NPC', 'EDIT_AREAS', 'AREA_DRAFT',
                         'EDIT_SPAWN_EXCEPTIONS', 'SPAWN_EXCEPTION_DRAFT')) = (payload IS NOT NULL));

ALTER TABLE smp_inbox DROP CONSTRAINT smp_inbox_outcome_only_answered;
ALTER TABLE smp_inbox
    ADD CONSTRAINT smp_inbox_outcome_only_answered
        CHECK (outcome IS NULL OR (status = 'DONE' AND kind IN ('AREA_DRAFT', 'SPAWN_EXCEPTION_DRAFT')));

COMMENT ON COLUMN smp_inbox.outcome IS 'The draft a done AREA_DRAFT or SPAWN_EXCEPTION_DRAFT left for the admin.';

ALTER TABLE smp_refusal DROP CONSTRAINT smp_refusal_reason_check;
ALTER TABLE smp_refusal
    ADD CONSTRAINT smp_refusal_reason_check CHECK (reason IN (
        'NO_ACTIVE_MILESTONE', 'NO_SUCH_OBJECTIVE', 'MILESTONE_NOT_ACTIVE', 'NOT_HERE', 'SETTINGS_REFUSED',
        'STACK_CHANGED', 'NOT_EDITABLE', 'NO_BLOCK_IN_SIGHT', 'NO_AREA_DRAFT', 'HOTBAR_FULL', 'WRONG_WORLD',
        'NO_EXAMPLE', 'CAPTURE_ACTIVE', 'NO_SPAWN_EXCEPTION_DRAFT'));
