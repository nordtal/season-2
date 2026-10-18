-- Steward asks a running server whether it would start with a change to its settings before it saves the change.
-- The change travels as JSON, like a preview, and the request keeps nothing else.
ALTER TABLE smp_inbox DROP CONSTRAINT smp_inbox_kind_check;
ALTER TABLE smp_inbox
    ADD CONSTRAINT smp_inbox_kind_check
        CHECK (kind IN ('COMPLETE_OBJECTIVE', 'UNLOCK_MILESTONE', 'PREVIEW_MESSAGE', 'CHECK_SETTINGS'));
ALTER TABLE smp_inbox DROP CONSTRAINT smp_inbox_payload_iff_preview;
ALTER TABLE smp_inbox
    ADD CONSTRAINT smp_inbox_payload_iff_json
        CHECK ((kind IN ('PREVIEW_MESSAGE', 'CHECK_SETTINGS')) = (payload IS NOT NULL));
COMMENT ON TABLE smp_inbox IS 'Owned by smp, which claims and carries out every row. steward asks for a track action, an admin''s preview of a text and a try of a settings change.';

ALTER TABLE hunger_games_inbox DROP CONSTRAINT hunger_games_inbox_kind_check;
ALTER TABLE hunger_games_inbox
    ADD CONSTRAINT hunger_games_inbox_kind_check CHECK (kind IN ('START_GAME', 'PREVIEW_MESSAGE', 'CHECK_SETTINGS'));
ALTER TABLE hunger_games_inbox DROP CONSTRAINT hunger_games_inbox_payload_iff_preview;
ALTER TABLE hunger_games_inbox
    ADD CONSTRAINT hunger_games_inbox_payload_iff_json
        CHECK ((kind IN ('PREVIEW_MESSAGE', 'CHECK_SETTINGS')) = (payload IS NOT NULL));
COMMENT ON TABLE hunger_games_inbox IS 'Owned by hunger-games, which claims and carries out every row. steward asks for the start, an admin''s preview of a text and a try of a settings change.';
