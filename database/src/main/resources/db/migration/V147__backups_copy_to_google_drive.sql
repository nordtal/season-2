-- A Google account that an admin connected for remote backup copies.
CREATE TABLE backup_google_accounts
(
    id             uuid        CONSTRAINT backup_google_accounts_pkey PRIMARY KEY,
    -- The OpenID subject, which stays the same when the address changes.
    google_subject text        NOT NULL CONSTRAINT backup_google_accounts_subject_key UNIQUE,
    email          text        NOT NULL,
    -- The Drive folder that steward created, the only one the drive.file scope lets it see.
    folder_id      text        NOT NULL,
    folder_name    text        NOT NULL,
    -- The refresh token, sealed with AES-256-GCM under the backup key and bound to 'backup_google_accounts:' || id.
    -- NULL once the account is disconnected.
    refresh_token  bytea,
    connected_at   timestamptz NOT NULL,
    disconnected_at timestamptz,
    CONSTRAINT backup_google_accounts_token_iff_connected CHECK ((refresh_token IS NULL) = (disconnected_at IS NOT NULL))
);
COMMENT ON TABLE backup_google_accounts IS 'Owned by steward-ui: one Google account that receives backup copies. A disconnected account keeps its row for its repositories.';

-- One restic repository per account and series, kept after a disconnect so its recovery file stays valid.
CREATE TABLE backup_repositories
(
    id                     uuid        CONSTRAINT backup_repositories_pkey PRIMARY KEY,
    account_id             uuid        NOT NULL
        CONSTRAINT backup_repositories_account_fkey REFERENCES backup_google_accounts (id),
    series                 varchar(8)  NOT NULL CONSTRAINT backup_repositories_series_check CHECK (series IN ('stack', 'plane')),
    -- The folder below the account's Drive folder that holds the repository.
    subpath                text        NOT NULL,
    -- The restic password, sealed with AES-256-GCM under the backup key and bound to 'backup_repositories:' || id.
    password               bytea       NOT NULL,
    created_at             timestamptz NOT NULL,
    -- Set when an admin downloads the recovery file, which steward hands out once.
    recovery_downloaded_at timestamptz,
    CONSTRAINT backup_repositories_account_series_key UNIQUE (account_id, series)
);
COMMENT ON TABLE backup_repositories IS 'Owned by steward-ui: the restic repository of one series in one Google account.';

-- Which account each series copies to, and how the last copy went.
CREATE TABLE backup_series_state
(
    series           varchar(8)  CONSTRAINT backup_series_state_pkey PRIMARY KEY
        CONSTRAINT backup_series_state_series_check CHECK (series IN ('stack', 'plane')),
    -- NULL while the series copies nowhere.
    account_id       uuid,
    last_copied_at   timestamptz,
    last_snapshot_id text,
    last_bytes       bigint      CONSTRAINT backup_series_state_last_bytes_check CHECK (last_bytes >= 0),
    last_error       text,
    last_failed_at   timestamptz,
    CONSTRAINT backup_series_state_repository_fkey FOREIGN KEY (account_id, series)
        REFERENCES backup_repositories (account_id, series)
);
COMMENT ON TABLE backup_series_state IS 'Owned by steward-ui: the active account of a series and the outcome of its last remote copy.';
INSERT INTO backup_series_state (series) VALUES ('stack'), ('plane');

ALTER TABLE journal_entry DROP CONSTRAINT journal_entry_action_check;
ALTER TABLE journal_entry
    ADD CONSTRAINT journal_entry_action_check CHECK (action IN (
        'ADMIN_ROOT', 'GRANT_ADMIN', 'REVOKE_ADMIN', 'ENFORCE_PACK', 'EXEMPT_PACK', 'GRANT_ACCESS', 'REVOKE_ACCESS',
        'SET_PLAYTIME', 'LINK', 'UNLINK', 'SETTLE', 'REGISTER_KEY', 'HELD_KEY', 'RENAME_KEY', 'REMOVE_KEY',
        'ADD_AUTHENTICATOR', 'USED_AUTHENTICATOR', 'REMOVE_AUTHENTICATOR', 'FORGET_FACTORS', 'WEB_PUSH_SUBSCRIBE',
        'WEB_PUSH_UNSUBSCRIBE', 'WEB_PUSH_TEST', 'SET_ALERT_PREFERENCE', 'CONSOLE', 'SAVE_SETTINGS', 'SAVE_MESSAGES',
        'SAVE_NPC', 'REMOVE_NPC', 'ADD_PLUGIN', 'REMOVE_PLUGIN', 'REQUEST_RUN', 'CANCEL_RUN', 'DOWNLOAD_BACKUP',
        'CONNECT_BACKUP_ACCOUNT', 'DISCONNECT_BACKUP_ACCOUNT', 'CHOOSE_BACKUP_ACCOUNT', 'DOWNLOAD_RECOVERY_FILE',
        'ANNOUNCE', 'COMPLETE_OBJECTIVE', 'UNLOCK_MILESTONE', 'START_GAME', 'LINK_CODES_EXHAUSTED', 'SET_AURA',
        'SET_SPINS', 'SERVICE_DOWN', 'BACKUP_STALE', 'DISK_FULL', 'MEMORY_FULL', 'IMAGE_DRIFT', 'PACK_DEAD',
        'RUN_FAILED', 'PAYMENT_NEEDS_LOOK', 'LINK_REFUSED', 'LINK_FAILED', 'PURCHASE_FAILED', 'DM_NOT_DELIVERED',
        'ROLE_NOT_CHANGED', 'ROLE_AMBIGUOUS', 'ROLE_NOT_CREATED', 'LOCK_NOT_KEPT', 'LOCK_WITHOUT_CHANNEL',
        'REMOVE_STACK', 'OPEN_INVENTORY', 'RESET_TRACK', 'SET_LAUNCH_DATE', 'LAUNCH_NOW', 'CLOSE_SEASON',
        'SET_MAINTENANCE', 'SWITCH_MODE', 'START_CHANNEL_HIDDEN', 'LANGUAGE_NOT_KEPT', 'CHANNEL_SEEN'));

GRANT SELECT, INSERT, UPDATE ON backup_google_accounts, backup_repositories, backup_series_state TO ${role_steward_ui};
