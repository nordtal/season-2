-- An admin trying a text for a key has it shown to them alone, where the key is shown: the bot sends it as a direct
-- message, the server their player is on shows it in game. Nothing is saved, so each is one more kind of request.
ALTER TABLE bot_inbox DROP CONSTRAINT bot_inbox_kind_check;
ALTER TABLE bot_inbox
    ADD CONSTRAINT bot_inbox_kind_check
        CHECK (kind IN ('GRANT', 'REVOKE', 'UNLINK', 'SET_PLAYTIME', 'ANNOUNCE', 'POST_ALERT', 'PAYMENT_BOOKED',
                        'PREVIEW_MESSAGE'));
COMMENT ON TABLE bot_inbox IS 'Owned by discord-bot, which claims and carries out every row. steward asks for access changes, announcements, an alert in the admin channel, the word to a payer whose payment it booked and an admin''s preview of a text, smp for its announcements.';

ALTER TABLE smp_inbox DROP CONSTRAINT smp_inbox_kind_check;
ALTER TABLE smp_inbox
    ADD CONSTRAINT smp_inbox_kind_check CHECK (kind IN ('COMPLETE_OBJECTIVE', 'UNLOCK_MILESTONE', 'PREVIEW_MESSAGE'));
COMMENT ON TABLE smp_inbox IS 'Owned by smp, which claims and carries out every row. steward asks for a track action and an admin''s preview of a text.';

ALTER TABLE hunger_games_inbox DROP CONSTRAINT hunger_games_inbox_kind_check;
ALTER TABLE hunger_games_inbox
    ADD CONSTRAINT hunger_games_inbox_kind_check CHECK (kind IN ('START_GAME', 'PREVIEW_MESSAGE'));
COMMENT ON TABLE hunger_games_inbox IS 'Owned by hunger-games, which claims and carries out every row. steward asks for the start and an admin''s preview of a text.';
