-- An alert is a journal entry with a level. What it measured is alert_measure rows, and an event's values are in
-- the journal_ table of its family. Its title and lines are rendered when shown. admin_alert goes with its rows,
-- which are not converted, and so does every POST_ALERT request to the bot, whose payload named such a row.
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
        'LOCK_NOT_KEPT', 'LOCK_WITHOUT_CHANNEL'));

ALTER TABLE journal_entry
    ADD COLUMN level  varchar(8) CONSTRAINT journal_entry_level_check CHECK (level IN ('OK', 'WARN', 'DOWN')),
    ADD COLUMN routed timestamptz;
ALTER TABLE journal_entry
    ADD CONSTRAINT journal_entry_level_iff_alert CHECK ((level IS NOT NULL) = (action IN (
        'SERVICE_DOWN', 'BACKUP_STALE', 'DISK_FULL', 'MEMORY_FULL', 'IMAGE_DRIFT', 'PACK_DEAD', 'RUN_FAILED',
        'PAYMENT_NEEDS_LOOK', 'LINK_REFUSED', 'LINK_FAILED', 'PURCHASE_FAILED', 'DM_NOT_DELIVERED', 'ROLE_NOT_CHANGED',
        'ROLE_AMBIGUOUS', 'ROLE_NOT_CREATED', 'LOCK_NOT_KEPT', 'LOCK_WITHOUT_CHANNEL'))),
    ADD CONSTRAINT journal_entry_routed_is_an_alerts CHECK (routed IS NULL OR level IS NOT NULL);
COMMENT ON COLUMN journal_entry.level IS 'How bad an alert is, null for an entry that is no alert. OK clears the alerts of its type before it.';
COMMENT ON COLUMN journal_entry.routed IS 'When steward sent the alert to the browsers and the admin channel, null until then.';
COMMENT ON COLUMN journal_entry.run IS 'The run the entry asked for, cancelled, removed a plugin through or saw fail, whose kind, scope and steps it shows.';
COMMENT ON COLUMN journal_entry.payment IS 'The payment request a booking settled or an alert is about, whose reference, order, amount and status it shows.';
CREATE INDEX journal_entry_unrouted_idx ON journal_entry (occurred) WHERE level IS NOT NULL AND routed IS NULL;
CREATE INDEX journal_entry_alert_idx ON journal_entry (occurred DESC, id DESC) WHERE level IS NOT NULL;
-- A run fails once, so it is raised once.
CREATE UNIQUE INDEX journal_entry_one_alert_per_run ON journal_entry (action, run) WHERE level IS NOT NULL AND run IS NOT NULL;

CREATE TABLE alert_measure
(
    entry    uuid             NOT NULL CONSTRAINT alert_measure_entry_fkey REFERENCES journal_entry (id) ON DELETE CASCADE,
    position integer          NOT NULL,
    gauge    varchar(24)      NOT NULL CONSTRAINT alert_measure_gauge_check CHECK (gauge IN (
        'DISK_PERCENT', 'MEMORY_PERCENT', 'BACKUP_AGE_HOURS', 'CONTAINER_STATE', 'HEALTH', 'RESTARTS', 'EXIT_CODE',
        'OLDER_IMAGE', 'REGISTRY', 'PACK')),
    subject  text             NOT NULL,
    value    double precision,
    "limit"  double precision,
    state    text,
    PRIMARY KEY (entry, position)
);
COMMENT ON TABLE alert_measure IS 'Owned by steward: what an alert measured, one row per fact in the order measured. The gauge decides which of value, limit and state are set.';
COMMENT ON COLUMN alert_measure.subject IS 'What it was measured on: a service, a backup series, the disk, the memory, the registry or the resource pack.';

CREATE TABLE journal_bank_payment
(
    entry        uuid        PRIMARY KEY CONSTRAINT journal_bank_payment_entry_fkey REFERENCES journal_entry (id) ON DELETE CASCADE,
    bank_payment bigint      NOT NULL CONSTRAINT journal_bank_payment_raised_once UNIQUE,
    problem      varchar(24) NOT NULL CONSTRAINT journal_bank_payment_problem_check CHECK (problem IN (
        'UNKNOWN_REFERENCE', 'NOT_OPEN', 'CLAIMED', 'BELOW_TIER')),
    cents        integer     NOT NULL,
    reference    text,
    CONSTRAINT journal_bank_payment_reference_iff_unknown CHECK ((problem = 'UNKNOWN_REFERENCE') = (reference IS NOT NULL))
);
COMMENT ON TABLE journal_bank_payment IS 'Owned by steward: the bunq payment a PAYMENT_NEEDS_LOOK is about, why it was not booked and its amount. The reference is its own only when no request has it, else the request''s.';

CREATE TABLE journal_role
(
    entry uuid        PRIMARY KEY CONSTRAINT journal_role_entry_fkey REFERENCES journal_entry (id) ON DELETE CASCADE,
    role  varchar(16) CONSTRAINT journal_role_role_check CHECK (role IN ('ACCESS', 'DONOR', 'ADMIN', 'LANGUAGE', 'REGION', 'LOCK')),
    given boolean,
    name  text,
    CONSTRAINT journal_role_kind_or_name CHECK ((role IS NOT NULL AND given IS NOT NULL) <> (name IS NOT NULL))
);
COMMENT ON TABLE journal_role IS 'Owned by discord-bot: the role a ROLE_NOT_CHANGED did not give or take, or the name of the role a ROLE_AMBIGUOUS or ROLE_NOT_CREATED is about.';

CREATE TABLE journal_error
(
    entry uuid PRIMARY KEY CONSTRAINT journal_error_entry_fkey REFERENCES journal_entry (id) ON DELETE CASCADE,
    error text NOT NULL
);
COMMENT ON TABLE journal_error IS 'Owned by discord-bot: what Discord or the bot answered when an action of an alert failed, as it came.';

CREATE TABLE journal_channel_failure
(
    entry    uuid    NOT NULL CONSTRAINT journal_channel_failure_entry_fkey REFERENCES journal_entry (id) ON DELETE CASCADE,
    position integer NOT NULL,
    channel  text    NOT NULL,
    error    text    NOT NULL,
    PRIMARY KEY (entry, position)
);
COMMENT ON TABLE journal_channel_failure IS 'Owned by discord-bot: a channel a LOCK_NOT_KEPT could not close to the lock role and what Discord answered, one row each.';

GRANT SELECT, INSERT ON alert_measure, journal_bank_payment, journal_role, journal_error, journal_channel_failure
    TO ${role_steward_ui}, ${role_discord_bot};
GRANT UPDATE (routed) ON journal_entry TO ${role_steward_ui};

DELETE FROM bot_inbox WHERE kind = 'POST_ALERT';
DROP TABLE admin_alert;
