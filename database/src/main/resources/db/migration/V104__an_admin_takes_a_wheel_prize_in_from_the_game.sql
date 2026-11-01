-- Steward asks smp to open a window for an admin's player, who puts in one item that becomes a wheel prize whole.
-- The row stays running while the window is open; the answer is the item, or nothing when the window closed empty.
CREATE TABLE smp_take_in_item
(
    request bigint       PRIMARY KEY
        CONSTRAINT smp_take_in_item_request_fkey REFERENCES smp_inbox (id) ON DELETE CASCADE,
    -- The admin's player, who sees the window.
    player  uuid         NOT NULL,
    -- The namespaced id of the item's material.
    item    varchar(128),
    -- The whole stack as Base64 of the bytes Paper serialises it to.
    data    text,
    CONSTRAINT smp_take_in_item_item_iff_data CHECK ((item IS NULL) = (data IS NULL))
);
COMMENT ON TABLE smp_take_in_item IS 'Owned by smp, which claims its inbox: the player a TAKE_IN_ITEM row of smp_inbox opens a window for, and the item put into it.';

GRANT SELECT ON smp_take_in_item TO ${role_read};
GRANT INSERT ON smp_take_in_item TO ${role_steward_ui};
GRANT UPDATE (item, data) ON smp_take_in_item TO ${role_smp};

-- Steward withdraws a request it asked for, pending or left running by a window that is still open.
GRANT UPDATE (status, finished, started) ON smp_inbox, hunger_games_inbox TO ${role_steward_ui};

ALTER TABLE smp_inbox DROP CONSTRAINT smp_inbox_kind_check;
ALTER TABLE smp_inbox
    ADD CONSTRAINT smp_inbox_kind_check
        CHECK (kind IN ('COMPLETE_OBJECTIVE', 'UNLOCK_MILESTONE', 'PREVIEW_MESSAGE', 'PREVIEW_DOCUMENT',
                        'CHECK_SETTINGS', 'SET_AURA', 'SET_SPINS', 'STORE_MODE_STATE', 'REMOVE_STACK',
                        'OPEN_INVENTORY', 'TAKE_IN_ITEM'));
COMMENT ON TABLE smp_inbox IS 'Owned by smp, which claims and carries out every row. steward asks for a track action, a person''s aura or spins, a player''s held state, the removal of a stack from it or its window for an admin, an admin''s preview of a text or a document, a try of a settings change and an item an admin takes in as a wheel prize.';
