-- The rows written before their kinds had tables are history nobody converts; without a kind's row they read as
-- nothing, so they go.
DELETE FROM bank_inbox;
DELETE FROM smp_inbox WHERE kind <> 'PREVIEW_MESSAGE';
DELETE FROM hunger_games_inbox WHERE kind <> 'PREVIEW_MESSAGE';

-- Every kind of the bank's is a row of its own, so its payload goes; the servers' previews keep theirs alone.
ALTER TABLE bank_inbox DROP COLUMN payload;
ALTER TABLE smp_inbox
    ADD CONSTRAINT smp_inbox_payload_iff_preview CHECK ((kind = 'PREVIEW_MESSAGE') = (payload IS NOT NULL));
ALTER TABLE hunger_games_inbox
    ADD CONSTRAINT hunger_games_inbox_payload_iff_preview
        CHECK ((kind = 'PREVIEW_MESSAGE') = (payload IS NOT NULL));
