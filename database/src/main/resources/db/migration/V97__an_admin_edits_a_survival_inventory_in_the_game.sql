-- Steward asks the server a state belongs to to open a player's Survival inventory or ender chest for an admin who is
-- on that server. The server edits the live inventory of a player who holds it, else the stored state.
CREATE TABLE smp_open_inventory
(
    request   bigint      PRIMARY KEY
        CONSTRAINT smp_open_inventory_request_fkey REFERENCES smp_inbox (id) ON DELETE CASCADE,
    -- The admin's player, who sees the window.
    admin     uuid        NOT NULL,
    player    uuid        NOT NULL,
    container varchar(16) NOT NULL
        CONSTRAINT smp_open_inventory_container_check CHECK (container IN ('INVENTORY', 'ENDER_CHEST'))
);
COMMENT ON TABLE smp_open_inventory IS 'Owned by smp, which claims its inbox: the Survival container of a player an OPEN_INVENTORY row of smp_inbox opens for an admin.';

CREATE TABLE hunger_games_open_inventory
(
    request   bigint      PRIMARY KEY
        CONSTRAINT hunger_games_open_inventory_request_fkey REFERENCES hunger_games_inbox (id) ON DELETE CASCADE,
    -- The admin's player, who sees the window.
    admin     uuid        NOT NULL,
    player    uuid        NOT NULL,
    container varchar(16) NOT NULL
        CONSTRAINT hunger_games_open_inventory_container_check CHECK (container IN ('INVENTORY', 'ENDER_CHEST'))
);
COMMENT ON TABLE hunger_games_open_inventory IS 'Owned by hunger-games, which claims its inbox: the Survival container of a player an OPEN_INVENTORY row of hunger_games_inbox opens for an admin.';

GRANT SELECT ON smp_open_inventory, hunger_games_open_inventory TO ${role_read};
GRANT INSERT ON smp_open_inventory, hunger_games_open_inventory TO ${role_steward_ui};

ALTER TABLE smp_inbox DROP CONSTRAINT smp_inbox_kind_check;
ALTER TABLE smp_inbox
    ADD CONSTRAINT smp_inbox_kind_check
        CHECK (kind IN ('COMPLETE_OBJECTIVE', 'UNLOCK_MILESTONE', 'PREVIEW_MESSAGE', 'PREVIEW_DOCUMENT',
                        'CHECK_SETTINGS', 'SET_AURA', 'SET_SPINS', 'STORE_MODE_STATE', 'REMOVE_STACK',
                        'OPEN_INVENTORY'));
COMMENT ON TABLE smp_inbox IS 'Owned by smp, which claims and carries out every row. steward asks for a track action, a person''s aura or spins, a player''s held state, the removal of a stack from it or its window for an admin, an admin''s preview of a text or a document and a try of a settings change.';

ALTER TABLE hunger_games_inbox DROP CONSTRAINT hunger_games_inbox_kind_check;
ALTER TABLE hunger_games_inbox
    ADD CONSTRAINT hunger_games_inbox_kind_check
        CHECK (kind IN ('START_GAME', 'PREVIEW_MESSAGE', 'PREVIEW_DOCUMENT', 'CHECK_SETTINGS', 'STORE_MODE_STATE',
                        'REMOVE_STACK', 'OPEN_INVENTORY'));
COMMENT ON TABLE hunger_games_inbox IS 'Owned by hunger-games, which claims and carries out every row. steward asks for the start, a player''s held state, the removal of a stack from it or its window for an admin, an admin''s preview of a text or a document and a try of a settings change.';

ALTER TABLE smp_refusal DROP CONSTRAINT smp_refusal_reason_check;
ALTER TABLE smp_refusal
    ADD CONSTRAINT smp_refusal_reason_check CHECK (reason IN (
        'NO_ACTIVE_MILESTONE', 'NO_SUCH_OBJECTIVE', 'MILESTONE_NOT_ACTIVE', 'NOT_HERE', 'SETTINGS_REFUSED',
        'STACK_CHANGED', 'NOT_EDITABLE'));
ALTER TABLE hunger_games_refusal DROP CONSTRAINT hunger_games_refusal_reason_check;
ALTER TABLE hunger_games_refusal
    ADD CONSTRAINT hunger_games_refusal_reason_check CHECK (reason IN (
        'WRONG_PHASE', 'NO_GAME', 'WRONG_STATE', 'BELOW_HARD_MINIMUM', 'BELOW_SOFT_MINIMUM', 'NOT_HERE',
        'SETTINGS_REFUSED', 'STACK_CHANGED', 'NOT_EDITABLE'));

ALTER TABLE journal_entry DROP CONSTRAINT journal_entry_action_check;
ALTER TABLE journal_entry
    ADD CONSTRAINT journal_entry_action_check CHECK (action IN (
        'ADMIN_ROOT', 'GRANT_ADMIN', 'REVOKE_ADMIN', 'ENFORCE_PACK', 'EXEMPT_PACK', 'GRANT_ACCESS', 'REVOKE_ACCESS',
        'SET_PLAYTIME', 'LINK', 'UNLINK', 'SETTLE', 'SET_PHASE', 'SET_LAUNCH', 'SET_SMP_START', 'REGISTER_KEY',
        'HELD_KEY', 'RENAME_KEY', 'REMOVE_KEY', 'ADD_AUTHENTICATOR', 'USED_AUTHENTICATOR', 'REMOVE_AUTHENTICATOR',
        'FORGET_FACTORS', 'WEB_PUSH_SUBSCRIBE', 'WEB_PUSH_UNSUBSCRIBE', 'WEB_PUSH_TEST', 'SET_ALERT_PREFERENCE',
        'CONSOLE', 'SAVE_SETTINGS', 'SAVE_MESSAGES', 'ADD_PLUGIN', 'REMOVE_PLUGIN', 'REQUEST_RUN', 'CANCEL_RUN',
        'DOWNLOAD_BACKUP', 'ANNOUNCE', 'COMPLETE_OBJECTIVE', 'UNLOCK_MILESTONE', 'START_GAME', 'UNANCHORED_GRANT',
        'LINK_CODES_EXHAUSTED', 'SET_AURA', 'SET_SPINS', 'SERVICE_DOWN', 'BACKUP_STALE', 'DISK_FULL', 'MEMORY_FULL',
        'IMAGE_DRIFT', 'PACK_DEAD', 'RUN_FAILED', 'PAYMENT_NEEDS_LOOK', 'LINK_REFUSED', 'LINK_FAILED',
        'PURCHASE_FAILED', 'DM_NOT_DELIVERED', 'ROLE_NOT_CHANGED', 'ROLE_AMBIGUOUS', 'ROLE_NOT_CREATED',
        'LOCK_NOT_KEPT', 'LOCK_WITHOUT_CHANNEL', 'REMOVE_STACK', 'OPEN_INVENTORY'));
