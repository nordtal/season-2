-- A live swap is a run: it counts down to the players on the SMP and has smp send them the current resource pack,
-- with no service stopped.
--
-- A SEND_PACK row keeps its pack in smp_send_pack. This only adds.

ALTER TABLE steward_inbox DROP CONSTRAINT steward_inbox_kind_check;
ALTER TABLE steward_inbox ADD CONSTRAINT steward_inbox_kind_check
    CHECK (kind IN ('UPDATE', 'RESTART', 'BACKUP', 'DOWN', 'START', 'RESTORE', 'RECREATE', 'DEPLOY', 'REMOVE_PLUGIN',
                    'INSTALL', 'SWITCH_MODE', 'LIVE_SWAP'));

ALTER TABLE smp_inbox DROP CONSTRAINT smp_inbox_kind_check;
ALTER TABLE smp_inbox
    ADD CONSTRAINT smp_inbox_kind_check
        CHECK (kind IN ('COMPLETE_OBJECTIVE', 'UNLOCK_MILESTONE', 'PREVIEW_MESSAGE', 'PREVIEW_DOCUMENT',
                        'CHECK_SETTINGS', 'SET_AURA', 'SET_SPINS', 'STORE_MODE_STATE', 'REMOVE_STACK',
                        'OPEN_INVENTORY', 'TAKE_IN_ITEM', 'SEND_PACK'));
COMMENT ON TABLE smp_inbox IS 'Owned by smp, which claims and carries out every row; steward withdraws its own take-in. steward asks for a track action, a person''s aura or spins, a player''s held state, the removal of a stack from it or its window for an admin, an admin''s preview of a text or a document, a try of a settings change and an item an admin takes in as a wheel prize. steward-agent asks for the current resource pack to be sent to the players.';

CREATE TABLE smp_send_pack
(
    request bigint      PRIMARY KEY
        CONSTRAINT smp_send_pack_request_fkey REFERENCES smp_inbox (id) ON DELETE CASCADE,
    -- Where the players' clients download the zip.
    url     text        NOT NULL,
    -- The SHA-1 of the zip in lower-case hex.
    sha1    char(40)    NOT NULL CONSTRAINT smp_send_pack_sha1_hex CHECK (sha1 ~ '^[0-9a-f]{40}$')
);
COMMENT ON TABLE smp_send_pack IS 'Owned by smp, which claims its inbox: the resource pack a SEND_PACK row of smp_inbox sends to the players.';
