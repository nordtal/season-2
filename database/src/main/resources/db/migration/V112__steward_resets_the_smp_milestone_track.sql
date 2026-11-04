-- An admin starts the SMP's milestone track over from Steward. Steward stamps the request here, and smp resets its
-- track once per stamp, whenever it next looks, so a reset asked for while smp rests applies when it starts.
-- season_phase.fresh_start stays and Steward writes it too, until season_phase goes, so an smp that still reads it
-- resets as well. The phase no longer stamps either.
CREATE TABLE smp_reset_request
(
    id        boolean     PRIMARY KEY DEFAULT true CONSTRAINT smp_reset_request_singleton CHECK (id),
    requested timestamptz NOT NULL
);
COMMENT ON TABLE smp_reset_request IS 'Owned by steward-ui: when an admin last asked the SMP to start its milestones, objectives and contributions over; one row. smp_reset holds the request smp last applied.';

-- The stamp so far, which smp_reset has already applied wherever smp has seen it.
INSERT INTO smp_reset_request (id, requested)
SELECT true, fresh_start FROM season_phase WHERE id AND fresh_start IS NOT NULL;

GRANT SELECT ON smp_reset_request TO ${role_smp}, ${role_steward_ui};
GRANT INSERT, UPDATE ON smp_reset_request TO ${role_steward_ui};
-- steward-ui reads which request smp applied last, to say that a reset still waits.
GRANT SELECT ON smp_reset TO ${role_steward_ui};

COMMENT ON TABLE smp_reset IS 'Owned by smp: which smp_reset_request its milestones, objectives and contributions were last reset for; one row.';
COMMENT ON COLUMN smp_reset.applied IS 'The smp_reset_request.requested this server last started its track over for.';

CREATE TABLE journal_track_reset
(
    entry  uuid PRIMARY KEY CONSTRAINT journal_track_reset_entry_fkey REFERENCES journal_entry (id) ON DELETE CASCADE,
    reason text NOT NULL CONSTRAINT journal_track_reset_reason_check CHECK (length(btrim(reason)) > 0)
);
COMMENT ON TABLE journal_track_reset IS 'Owned by steward: why a RESET_TRACK started the SMP''s track over, when it was said.';
GRANT SELECT, INSERT ON journal_track_reset TO ${role_steward_ui}, ${role_discord_bot};

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
        'LOCK_NOT_KEPT', 'LOCK_WITHOUT_CHANNEL', 'REMOVE_STACK', 'OPEN_INVENTORY', 'RESET_TRACK'));
