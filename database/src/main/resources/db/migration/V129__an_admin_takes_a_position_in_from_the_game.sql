-- Steward asks smp where an admin's player stands, or which block they look at, to fill a position in a setting.
-- The answer is the player's world, place and facing; smp refuses when the player is not on it or looks at no block.
CREATE TABLE smp_take_position
(
    request bigint           PRIMARY KEY
        CONSTRAINT smp_take_position_request_fkey REFERENCES smp_inbox (id) ON DELETE CASCADE,
    -- The admin's player.
    player  uuid             NOT NULL,
    -- Whether the answer is the block the player looks at, else the place the player stands on.
    block   boolean          NOT NULL,
    world   varchar(128),
    x       double precision,
    y       double precision,
    z       double precision,
    -- The facing in degrees, where 0 faces south.
    yaw     real,
    -- The look up or down in degrees, where 0 faces ahead.
    pitch   real
);
COMMENT ON TABLE smp_take_position IS 'Owned by smp, which claims its inbox: the player a TAKE_POSITION row of smp_inbox asks about, and where that player is.';

GRANT SELECT ON smp_take_position TO ${role_read};
GRANT INSERT ON smp_take_position TO ${role_steward_ui};
GRANT UPDATE (world, x, y, z, yaw, pitch) ON smp_take_position TO ${role_smp};

ALTER TABLE smp_inbox DROP CONSTRAINT smp_inbox_kind_check;
ALTER TABLE smp_inbox
    ADD CONSTRAINT smp_inbox_kind_check
        CHECK (kind IN ('COMPLETE_OBJECTIVE', 'UNLOCK_MILESTONE', 'PREVIEW_MESSAGE', 'PREVIEW_DOCUMENT',
                        'CHECK_SETTINGS', 'SET_AURA', 'SET_SPINS', 'STORE_MODE_STATE', 'REMOVE_STACK',
                        'OPEN_INVENTORY', 'TAKE_IN_ITEM', 'SEND_PACK', 'TAKE_POSITION'));
COMMENT ON TABLE smp_inbox IS 'Owned by smp, which claims and carries out every row; steward withdraws its own take-in. steward asks for a track action, a person''s aura or spins, a player''s held state, the removal of a stack from it or its window for an admin, an admin''s preview of a text or a document, a try of a settings change, an item an admin takes in as a wheel prize and where an admin''s player is, for a position in a setting. steward-agent asks for the current resource pack to be sent to the players.';

ALTER TABLE smp_refusal DROP CONSTRAINT smp_refusal_reason_check;
ALTER TABLE smp_refusal
    ADD CONSTRAINT smp_refusal_reason_check CHECK (reason IN (
        'NO_ACTIVE_MILESTONE', 'NO_SUCH_OBJECTIVE', 'MILESTONE_NOT_ACTIVE', 'NOT_HERE', 'SETTINGS_REFUSED',
        'STACK_CHANGED', 'NOT_EDITABLE', 'NO_BLOCK_IN_SIGHT'));
