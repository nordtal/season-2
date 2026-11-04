-- The season phase splits into three switches of its own: the launch date, which alone decides whether the season
-- is closed, counting down or launched, maintenance, and the active game mode. Whether the season is open is never
-- stored: a launch date that passes opens it by itself. season_phase stays until no running release reads it.
CREATE TABLE season_state
(
    id          boolean     PRIMARY KEY DEFAULT true CONSTRAINT season_state_singleton CHECK (id),
    launch      timestamptz,
    maintenance boolean     NOT NULL,
    mode        varchar(16) NOT NULL CONSTRAINT season_state_mode_check CHECK (mode IN ('SMP', 'HUNGER_GAMES')),
    updated     timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE season_state IS 'Owned by steward, where an admin sets the launch date, maintenance and the game mode; one row. steward-agent writes the mode when a mode switch moves the players.';
COMMENT ON COLUMN season_state.launch IS 'When the season opens: null is closed, a future instant counts down, a past one is launched.';
COMMENT ON COLUMN season_state.maintenance IS 'Whether members who would be let in wait in the waiting room.';
COMMENT ON COLUMN season_state.mode IS 'The active game mode: only its server runs, and admitted members land on it.';

-- The phase so far: an SMP or Hunger Games phase is that mode, launched; MAINTENANCE is the SMP in maintenance. Each
-- keeps its launch date, else the start of its track, else now. Before the launch only a future date survives.
INSERT INTO season_state (id, launch, maintenance, mode)
SELECT true,
       CASE
           WHEN phase = 'PRE_LAUNCH' THEN CASE WHEN launch > now() THEN launch END
           ELSE COALESCE(launch, fresh_start, now())
       END,
       phase = 'MAINTENANCE',
       CASE WHEN phase IN ('SMP', 'MAINTENANCE') THEN 'SMP' ELSE 'HUNGER_GAMES' END
FROM season_phase
WHERE id;

-- A database without the phase row starts closed, out of maintenance, on the Hunger Games.
INSERT INTO season_state (id, launch, maintenance, mode)
VALUES (true, NULL, false, 'HUNGER_GAMES')
ON CONFLICT (id) DO NOTHING;

GRANT SELECT ON season_state TO ${role_read};
GRANT UPDATE ON season_state TO ${role_steward_ui};

-- How each change of the three switches is journaled.
CREATE TABLE journal_season_launch
(
    entry        uuid        PRIMARY KEY CONSTRAINT journal_season_launch_entry_fkey REFERENCES journal_entry (id) ON DELETE CASCADE,
    from_launch  timestamptz,
    to_launch    timestamptz,
    moved_grants bigint      NOT NULL CONSTRAINT journal_season_launch_moved_grants_check CHECK (moved_grants >= 0),
    reason       text        CONSTRAINT journal_season_launch_reason_check CHECK (length(btrim(reason)) > 0)
);
COMMENT ON TABLE journal_season_launch IS 'Owned by steward: the launch dates a SET_LAUNCH_DATE, LAUNCH_NOW or CLOSE_SEASON moved between, the access grants it moved and why, when it was said.';

CREATE TABLE journal_maintenance
(
    entry       uuid    PRIMARY KEY CONSTRAINT journal_maintenance_entry_fkey REFERENCES journal_entry (id) ON DELETE CASCADE,
    maintenance boolean NOT NULL,
    reason      text    CONSTRAINT journal_maintenance_reason_check CHECK (length(btrim(reason)) > 0)
);
COMMENT ON TABLE journal_maintenance IS 'Owned by steward: whether a SET_MAINTENANCE turned maintenance on or off, and why, when it was said.';

CREATE TABLE journal_season_mode
(
    entry     uuid        PRIMARY KEY CONSTRAINT journal_season_mode_entry_fkey REFERENCES journal_entry (id) ON DELETE CASCADE,
    from_mode varchar(16) NOT NULL CONSTRAINT journal_season_mode_from_mode_check CHECK (from_mode IN ('SMP', 'HUNGER_GAMES')),
    to_mode   varchar(16) NOT NULL CONSTRAINT journal_season_mode_to_mode_check CHECK (to_mode IN ('SMP', 'HUNGER_GAMES')),
    reason    text        CONSTRAINT journal_season_mode_reason_check CHECK (length(btrim(reason)) > 0)
);
COMMENT ON TABLE journal_season_mode IS 'Owned by steward-agent: the game modes a SWITCH_MODE moved between, and why, when it was said.';

GRANT SELECT, INSERT ON journal_season_launch, journal_maintenance, journal_season_mode
    TO ${role_steward_ui}, ${role_discord_bot};

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
        'LOCK_NOT_KEPT', 'LOCK_WITHOUT_CHANNEL', 'REMOVE_STACK', 'OPEN_INVENTORY', 'RESET_TRACK', 'SET_LAUNCH_DATE',
        'LAUNCH_NOW', 'CLOSE_SEASON', 'SET_MAINTENANCE', 'SWITCH_MODE'));

-- A mode switch is a run: it moves everyone to the waiting room, swaps the servers and writes the mode when done.
ALTER TABLE steward_inbox DROP CONSTRAINT steward_inbox_kind_check;
ALTER TABLE steward_inbox ADD CONSTRAINT steward_inbox_kind_check
    CHECK (kind IN ('UPDATE', 'RESTART', 'BACKUP', 'DOWN', 'START', 'RESTORE', 'RECREATE', 'DEPLOY', 'REMOVE_PLUGIN',
                    'INSTALL', 'SWITCH_MODE'));

CREATE TABLE steward_switch_mode
(
    request bigint      PRIMARY KEY
        CONSTRAINT steward_switch_mode_request_fkey REFERENCES steward_inbox (id) ON DELETE CASCADE,
    target  varchar(16) NOT NULL
        CONSTRAINT steward_switch_mode_target_check CHECK (target IN ('SMP', 'HUNGER_GAMES')),
    reason  text
        CONSTRAINT steward_switch_mode_reason_check CHECK (reason IS NULL OR btrim(reason) <> '')
);
COMMENT ON TABLE steward_switch_mode IS 'Owned by steward-agent, which claims its inbox: the mode a SWITCH_MODE row of steward_inbox switches to, and the reason the asker gave, which the journal entry of the switch carries.';

GRANT SELECT ON steward_switch_mode TO ${role_read};
GRANT INSERT ON steward_switch_mode TO ${role_steward_ui};
