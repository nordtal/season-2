-- An admin shapes the areas of a world in the game, and smp answers the draft it holds as JSON.
ALTER TABLE smp_inbox DROP CONSTRAINT smp_inbox_kind_check;
ALTER TABLE smp_inbox
    ADD CONSTRAINT smp_inbox_kind_check
        CHECK (kind IN ('COMPLETE_OBJECTIVE', 'UNLOCK_MILESTONE', 'PREVIEW_MESSAGE', 'PREVIEW_DOCUMENT',
                        'PREVIEW_NPC', 'CHECK_SETTINGS', 'SET_AURA', 'SET_SPINS', 'STORE_MODE_STATE', 'REMOVE_STACK',
                        'OPEN_INVENTORY', 'TAKE_IN_ITEM', 'SEND_PACK', 'TAKE_POSITION', 'EDIT_AREAS', 'AREA_DRAFT'));
ALTER TABLE smp_inbox DROP CONSTRAINT smp_inbox_payload_iff_json;
ALTER TABLE smp_inbox
    ADD CONSTRAINT smp_inbox_payload_iff_json
        CHECK ((kind IN ('CHECK_SETTINGS', 'PREVIEW_NPC', 'EDIT_AREAS', 'AREA_DRAFT')) = (payload IS NOT NULL));

ALTER TABLE smp_inbox
    ADD COLUMN outcome jsonb,
    ADD CONSTRAINT smp_inbox_outcome_only_answered CHECK (outcome IS NULL OR (status = 'DONE' AND kind = 'AREA_DRAFT'));
COMMENT ON COLUMN smp_inbox.outcome IS 'The areas a done AREA_DRAFT left in the admin''s draft.';
COMMENT ON TABLE smp_inbox IS 'Owned by smp, which claims and carries out every row; steward withdraws its own take-in. steward asks for a track action, a person''s aura or spins, a player''s held state, the removal of a stack from it or its window for an admin, an admin''s preview of a text or a document, a try of a settings change, an item an admin takes in as a wheel prize, where an admin''s player is, for a position in a setting, and an admin''s area editor and the steps of its draft. steward-agent asks for the current resource pack to be sent to the players.';

ALTER TABLE smp_refusal DROP CONSTRAINT smp_refusal_reason_check;
ALTER TABLE smp_refusal
    ADD CONSTRAINT smp_refusal_reason_check CHECK (reason IN (
        'NO_ACTIVE_MILESTONE', 'NO_SUCH_OBJECTIVE', 'MILESTONE_NOT_ACTIVE', 'NOT_HERE', 'SETTINGS_REFUSED',
        'STACK_CHANGED', 'NOT_EDITABLE', 'NO_BLOCK_IN_SIGHT', 'NO_AREA_DRAFT', 'HOTBAR_FULL', 'WRONG_WORLD'));
