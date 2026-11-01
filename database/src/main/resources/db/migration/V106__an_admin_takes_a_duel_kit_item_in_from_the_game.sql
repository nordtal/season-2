-- A take-in names what the item becomes, a wheel prize or a piece of a duel kit, so the window in the game says so.
-- A row written without it is a wheel prize, as every take-in was.
ALTER TABLE smp_take_in_item
    ADD COLUMN purpose varchar(16) NOT NULL DEFAULT 'WHEEL_PRIZE'
        CONSTRAINT smp_take_in_item_purpose_check CHECK (purpose IN ('WHEEL_PRIZE', 'DUEL_KIT'));
COMMENT ON COLUMN smp_take_in_item.purpose IS 'What the item becomes: WHEEL_PRIZE or DUEL_KIT.';
COMMENT ON TABLE smp_take_in_item IS 'Owned by smp, which claims its inbox: the player a TAKE_IN_ITEM row of smp_inbox opens a window for, what the item becomes, and the item put into it.';
COMMENT ON TABLE smp_inbox IS 'Owned by smp, which claims and carries out every row; steward withdraws its own take-in. steward asks for a track action, a person''s aura or spins, a player''s held state, the removal of a stack from it or its window for an admin, an admin''s preview of a text or a document, a try of a settings change and an item an admin takes in as a wheel prize or for a duel kit.';
