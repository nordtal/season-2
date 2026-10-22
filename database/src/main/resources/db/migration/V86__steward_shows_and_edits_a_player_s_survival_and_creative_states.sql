-- Steward shows each player's Survival and Creative states per server and removes stacks from them. A server writes the
-- state a player holds when the player leaves, so every state of a player who is away is in the database.
-- The default stays while the previous release, which writes no status, runs beside this one.
ALTER TABLE game_mode_state
    ADD COLUMN status varchar(8) NOT NULL DEFAULT 'STORED'
        CONSTRAINT game_mode_state_status_check CHECK (status IN ('STORED', 'AWAY', 'HELD'));
COMMENT ON COLUMN game_mode_state.status IS
    'STORED: the mode the player does not hold, and the row is its state. AWAY: the mode the player held on leaving, and the row is its state until the player joins again. HELD: the server holds the state, and the row is a copy as of saved.';
-- A switch reads the state of the mode entered back and writes the state of the mode left, so the older of two rows is
-- the state the player holds.
UPDATE game_mode_state older
SET status = 'HELD'
FROM game_mode_state newer
WHERE newer.server = older.server
  AND newer.player_uuid = older.player_uuid
  AND newer.mode <> older.mode
  AND newer.saved > older.saved;
CREATE UNIQUE INDEX game_mode_state_one_held ON game_mode_state (server, player_uuid) WHERE status <> 'STORED';

-- Steward locks a state with FOR UPDATE, which needs the UPDATE grant, and sets saved when it removes a stack.
GRANT SELECT ON game_mode_state, game_mode_state_item TO ${role_steward_ui};
GRANT UPDATE (saved) ON game_mode_state TO ${role_steward_ui};
GRANT DELETE ON game_mode_state_item TO ${role_steward_ui};

-- Steward asks the server that holds a state to store it, or to remove a stack from it.
CREATE TABLE smp_store_mode_state
(
    request bigint PRIMARY KEY CONSTRAINT smp_store_mode_state_request_fkey REFERENCES smp_inbox (id) ON DELETE CASCADE,
    player  uuid   NOT NULL
);
COMMENT ON TABLE smp_store_mode_state IS 'Owned by smp, which claims its inbox: the player whose held state a STORE_MODE_STATE row of smp_inbox stores.';

CREATE TABLE smp_remove_stack
(
    request   bigint      PRIMARY KEY
        CONSTRAINT smp_remove_stack_request_fkey REFERENCES smp_inbox (id) ON DELETE CASCADE,
    player    uuid        NOT NULL,
    mode      varchar(16) NOT NULL CONSTRAINT smp_remove_stack_mode_check CHECK (mode IN ('SURVIVAL', 'CREATIVE')),
    container varchar(16) NOT NULL
        CONSTRAINT smp_remove_stack_container_check CHECK (container IN ('INVENTORY', 'ENDER_CHEST')),
    slot      smallint    NOT NULL CONSTRAINT smp_remove_stack_slot_check CHECK (slot >= 0),
    -- The stack Steward showed in the slot. The server refuses the removal when the slot holds another.
    item      varchar(64) NOT NULL,
    amount    integer     NOT NULL CONSTRAINT smp_remove_stack_amount_check CHECK (amount > 0)
);
COMMENT ON TABLE smp_remove_stack IS 'Owned by smp, which claims its inbox: the stack a REMOVE_STACK row of smp_inbox removes from a player''s held state.';

CREATE TABLE hunger_games_store_mode_state
(
    request bigint PRIMARY KEY
        CONSTRAINT hunger_games_store_mode_state_request_fkey REFERENCES hunger_games_inbox (id) ON DELETE CASCADE,
    player  uuid   NOT NULL
);
COMMENT ON TABLE hunger_games_store_mode_state IS 'Owned by hunger-games, which claims its inbox: the player whose held state a STORE_MODE_STATE row of hunger_games_inbox stores.';

CREATE TABLE hunger_games_remove_stack
(
    request   bigint      PRIMARY KEY
        CONSTRAINT hunger_games_remove_stack_request_fkey REFERENCES hunger_games_inbox (id) ON DELETE CASCADE,
    player    uuid        NOT NULL,
    mode      varchar(16) NOT NULL
        CONSTRAINT hunger_games_remove_stack_mode_check CHECK (mode IN ('SURVIVAL', 'CREATIVE')),
    container varchar(16) NOT NULL
        CONSTRAINT hunger_games_remove_stack_container_check CHECK (container IN ('INVENTORY', 'ENDER_CHEST')),
    slot      smallint    NOT NULL CONSTRAINT hunger_games_remove_stack_slot_check CHECK (slot >= 0),
    -- The stack Steward showed in the slot. The server refuses the removal when the slot holds another.
    item      varchar(64) NOT NULL,
    amount    integer     NOT NULL CONSTRAINT hunger_games_remove_stack_amount_check CHECK (amount > 0)
);
COMMENT ON TABLE hunger_games_remove_stack IS 'Owned by hunger-games, which claims its inbox: the stack a REMOVE_STACK row of hunger_games_inbox removes from a player''s held state.';

GRANT SELECT ON smp_store_mode_state, smp_remove_stack, hunger_games_store_mode_state, hunger_games_remove_stack
    TO ${role_read};
GRANT INSERT ON smp_store_mode_state, smp_remove_stack, hunger_games_store_mode_state, hunger_games_remove_stack
    TO ${role_steward_ui};

ALTER TABLE smp_inbox DROP CONSTRAINT smp_inbox_kind_check;
ALTER TABLE smp_inbox
    ADD CONSTRAINT smp_inbox_kind_check
        CHECK (kind IN ('COMPLETE_OBJECTIVE', 'UNLOCK_MILESTONE', 'PREVIEW_MESSAGE', 'CHECK_SETTINGS', 'SET_AURA',
                        'SET_SPINS', 'STORE_MODE_STATE', 'REMOVE_STACK'));
COMMENT ON TABLE smp_inbox IS 'Owned by smp, which claims and carries out every row. steward asks for a track action, a person''s aura or spins, a player''s held state or the removal of a stack from it, an admin''s preview of a text and a try of a settings change.';

ALTER TABLE hunger_games_inbox DROP CONSTRAINT hunger_games_inbox_kind_check;
ALTER TABLE hunger_games_inbox
    ADD CONSTRAINT hunger_games_inbox_kind_check
        CHECK (kind IN ('START_GAME', 'PREVIEW_MESSAGE', 'CHECK_SETTINGS', 'STORE_MODE_STATE', 'REMOVE_STACK'));
COMMENT ON TABLE hunger_games_inbox IS 'Owned by hunger-games, which claims and carries out every row. steward asks for the start, a player''s held state or the removal of a stack from it, an admin''s preview of a text and a try of a settings change.';

ALTER TABLE smp_refusal DROP CONSTRAINT smp_refusal_reason_check;
ALTER TABLE smp_refusal
    ADD CONSTRAINT smp_refusal_reason_check CHECK (reason IN (
        'NO_ACTIVE_MILESTONE', 'NO_SUCH_OBJECTIVE', 'MILESTONE_NOT_ACTIVE', 'NOT_HERE', 'SETTINGS_REFUSED',
        'STACK_CHANGED'));
ALTER TABLE hunger_games_refusal DROP CONSTRAINT hunger_games_refusal_reason_check;
ALTER TABLE hunger_games_refusal
    ADD CONSTRAINT hunger_games_refusal_reason_check CHECK (reason IN (
        'WRONG_PHASE', 'NO_GAME', 'WRONG_STATE', 'BELOW_HARD_MINIMUM', 'BELOW_SOFT_MINIMUM', 'NOT_HERE',
        'SETTINGS_REFUSED', 'STACK_CHANGED'));

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
        'LOCK_NOT_KEPT', 'LOCK_WITHOUT_CHANNEL', 'REMOVE_STACK'));

CREATE TABLE journal_removed_stack
(
    entry  uuid        PRIMARY KEY
        CONSTRAINT journal_removed_stack_entry_fkey REFERENCES journal_entry (id) ON DELETE CASCADE,
    item   varchar(64) NOT NULL,
    amount integer     NOT NULL CONSTRAINT journal_removed_stack_amount_check CHECK (amount > 0)
);
COMMENT ON TABLE journal_removed_stack IS 'Owned by steward: the stack a REMOVE_STACK removed.';

GRANT SELECT, INSERT ON journal_removed_stack TO ${role_steward_ui}, ${role_discord_bot};
