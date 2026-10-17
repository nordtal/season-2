-- The journal is journal_entry with one table per action family beside it, every value atomic and stored once.
--
-- An entry names what happened, who did it, the person, account, service, run and payment it concerns; a family's
-- table holds the values only its actions carry. A run's kind and scope and a booking's reference, order and amount
-- are read from the run and the payment they reference. No text is stored: each reader renders the line.
-- The lines of audit_log are not converted: it goes with them.
CREATE TABLE journal_entry
(
    id         uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    occurred   timestamptz NOT NULL DEFAULT now(),
    action     varchar(32) NOT NULL CONSTRAINT journal_entry_action_check CHECK (action IN (
        'ADMIN_ROOT', 'GRANT_ADMIN', 'REVOKE_ADMIN', 'ENFORCE_PACK', 'EXEMPT_PACK', 'GRANT_ACCESS', 'REVOKE_ACCESS',
        'SET_PLAYTIME', 'LINK', 'UNLINK', 'SETTLE', 'SET_PHASE', 'SET_LAUNCH', 'SET_SMP_START', 'REGISTER_KEY',
        'HELD_KEY', 'RENAME_KEY', 'REMOVE_KEY', 'ADD_AUTHENTICATOR', 'USED_AUTHENTICATOR', 'REMOVE_AUTHENTICATOR',
        'FORGET_FACTORS', 'WEB_PUSH_SUBSCRIBE', 'WEB_PUSH_UNSUBSCRIBE', 'WEB_PUSH_TEST', 'SET_ALERT_PREFERENCE',
        'CONSOLE', 'SAVE_SETTINGS', 'SAVE_MESSAGES', 'ADD_PLUGIN', 'REMOVE_PLUGIN', 'REQUEST_RUN', 'CANCEL_RUN',
        'DOWNLOAD_BACKUP', 'ANNOUNCE', 'COMPLETE_OBJECTIVE', 'UNLOCK_MILESTONE', 'START_GAME', 'UNANCHORED_GRANT',
        'LINK_CODES_EXHAUSTED')),
    actor_kind varchar(16) NOT NULL
        CONSTRAINT journal_entry_actor_kind_check CHECK (actor_kind IN ('PERSON', 'STEWARD', 'HOST', 'BOT')),
    actor_id   varchar(32),
    subject    varchar(32) CONSTRAINT journal_entry_subject_is_a_person CHECK (subject ~ '^[0-9]+$'),
    mc_uuid    uuid,
    service    varchar(64) CONSTRAINT journal_entry_service_check CHECK (service ~ '^[a-z0-9-]+$'),
    run        bigint      CONSTRAINT journal_entry_run_fkey REFERENCES steward_inbox (id),
    payment    uuid        CONSTRAINT journal_entry_payment_fkey REFERENCES payment_request (id),
    CONSTRAINT journal_entry_actor_id_iff_person CHECK ((actor_kind = 'PERSON') = (actor_id IS NOT NULL)),
    CONSTRAINT journal_entry_settle_names_its_payment CHECK (action <> 'SETTLE' OR payment IS NOT NULL)
);
COMMENT ON TABLE journal_entry IS 'Owned by steward, whose journal reads it. Every service that changes access, the phase, a setting, a plugin or a server appends an entry and never updates it: what happened, who did it and what it concerns. The values of its action are in the journal_ table of its family.';
COMMENT ON COLUMN journal_entry.run IS 'The run the entry asked for, cancelled or removed a plugin through, whose kind and scope it shows.';
COMMENT ON COLUMN journal_entry.payment IS 'The payment a booking settled, whose reference, order and amount it shows.';
CREATE INDEX journal_entry_occurred_idx ON journal_entry (occurred DESC, id DESC);
CREATE INDEX journal_entry_subject_idx ON journal_entry (subject) WHERE subject IS NOT NULL;
CREATE INDEX journal_entry_run_idx ON journal_entry (run) WHERE run IS NOT NULL;
CREATE INDEX journal_entry_payment_idx ON journal_entry (payment) WHERE payment IS NOT NULL;

CREATE TABLE journal_grant
(
    entry uuid        PRIMARY KEY CONSTRAINT journal_grant_entry_fkey REFERENCES journal_entry (id) ON DELETE CASCADE,
    days  integer     NOT NULL CONSTRAINT journal_grant_days_check CHECK (days > 0),
    until timestamptz NOT NULL
);
COMMENT ON TABLE journal_grant IS 'Owned by steward: the days a GRANT_ACCESS gave and the end of the access it left.';

CREATE TABLE journal_revoke
(
    entry  uuid    PRIMARY KEY CONSTRAINT journal_revoke_entry_fkey REFERENCES journal_entry (id) ON DELETE CASCADE,
    grants integer NOT NULL CONSTRAINT journal_revoke_grants_check CHECK (grants >= 0)
);
COMMENT ON TABLE journal_revoke IS 'Owned by steward: how many grants a REVOKE_ACCESS ended.';

CREATE TABLE journal_playtime
(
    entry   uuid   PRIMARY KEY CONSTRAINT journal_playtime_entry_fkey REFERENCES journal_entry (id) ON DELETE CASCADE,
    seconds bigint NOT NULL CONSTRAINT journal_playtime_seconds_check CHECK (seconds >= 0)
);
COMMENT ON TABLE journal_playtime IS 'Owned by steward: the play time a SET_PLAYTIME set.';

CREATE TABLE journal_unlink
(
    entry        uuid    PRIMARY KEY CONSTRAINT journal_unlink_entry_fkey REFERENCES journal_entry (id) ON DELETE CASCADE,
    self_service boolean NOT NULL
);
COMMENT ON TABLE journal_unlink IS 'Owned by steward: whether an UNLINK was the member''s own.';

CREATE TABLE journal_booking
(
    entry          uuid    PRIMARY KEY CONSTRAINT journal_booking_entry_fkey REFERENCES journal_entry (id) ON DELETE CASCADE,
    days           integer NOT NULL CONSTRAINT journal_booking_days_check CHECK (days > 0),
    donation_cents integer NOT NULL CONSTRAINT journal_booking_donation_cents_check CHECK (donation_cents >= 0)
);
COMMENT ON TABLE journal_booking IS 'Owned by steward: the days a SETTLE booked and the donation it counted, which may differ from the order its payment names.';

CREATE TABLE journal_phase
(
    entry      uuid        PRIMARY KEY CONSTRAINT journal_phase_entry_fkey REFERENCES journal_entry (id) ON DELETE CASCADE,
    from_phase varchar(16) NOT NULL CONSTRAINT journal_phase_from_phase_check
        CHECK (from_phase IN ('PRE_LAUNCH', 'PRE_EVENT', 'START_EVENT', 'SMP', 'MAINTENANCE')),
    to_phase   varchar(16) NOT NULL CONSTRAINT journal_phase_to_phase_check
        CHECK (to_phase IN ('PRE_LAUNCH', 'PRE_EVENT', 'START_EVENT', 'SMP', 'MAINTENANCE')),
    reason     text        CONSTRAINT journal_phase_reason_check CHECK (length(btrim(reason)) > 0)
);
COMMENT ON TABLE journal_phase IS 'Owned by steward: the phases a SET_PHASE moved between, and why, when it was said.';

CREATE TABLE journal_launch
(
    entry uuid        PRIMARY KEY CONSTRAINT journal_launch_entry_fkey REFERENCES journal_entry (id) ON DELETE CASCADE,
    opens timestamptz NOT NULL
);
COMMENT ON TABLE journal_launch IS 'Owned by steward: the opening a SET_LAUNCH set; one that cleared it has no row.';

CREATE TABLE journal_smp_start
(
    entry        uuid        PRIMARY KEY CONSTRAINT journal_smp_start_entry_fkey REFERENCES journal_entry (id) ON DELETE CASCADE,
    starts       timestamptz NOT NULL,
    moved_grants bigint      NOT NULL CONSTRAINT journal_smp_start_moved_grants_check CHECK (moved_grants >= 0)
);
COMMENT ON TABLE journal_smp_start IS 'Owned by steward: the start of paid time a SET_SMP_START set and how many grants it moved; one that cleared it has no row.';

CREATE TABLE journal_key
(
    entry        uuid    PRIMARY KEY CONSTRAINT journal_key_entry_fkey REFERENCES journal_entry (id) ON DELETE CASCADE,
    label        text    NOT NULL,
    unlocked     boolean,
    hold_seconds bigint  CONSTRAINT journal_key_hold_seconds_check CHECK (hold_seconds > 0),
    keys_left    integer CONSTRAINT journal_key_keys_left_check CHECK (keys_left >= 0)
);
COMMENT ON TABLE journal_key IS 'Owned by steward: the security key an action named; HELD_KEY adds whether it was unlocked and how long the step-up holds, REMOVE_KEY how many keys are left.';

CREATE TABLE journal_factor
(
    entry         uuid    PRIMARY KEY CONSTRAINT journal_factor_entry_fkey REFERENCES journal_entry (id) ON DELETE CASCADE,
    hold_seconds  bigint  CONSTRAINT journal_factor_hold_seconds_check CHECK (hold_seconds > 0),
    keys          integer CONSTRAINT journal_factor_keys_check CHECK (keys >= 0),
    authenticator boolean,
    sessions      integer CONSTRAINT journal_factor_sessions_check CHECK (sessions >= 0)
);
COMMENT ON TABLE journal_factor IS 'Owned by steward: how long a USED_AUTHENTICATOR step-up holds, the keys left after REMOVE_AUTHENTICATOR, and what a FORGET_FACTORS cleared.';

CREATE TABLE journal_push
(
    entry   uuid        PRIMARY KEY CONSTRAINT journal_push_entry_fkey REFERENCES journal_entry (id) ON DELETE CASCADE,
    alert   varchar(16) NOT NULL CONSTRAINT journal_push_alert_check
        CHECK (alert IN ('SERVICE', 'BACKUP', 'DISK', 'MEMORY', 'DRIFT', 'PACK', 'RUN', 'PAYMENT', 'BOT')),
    channel varchar(16) CONSTRAINT journal_push_channel_check CHECK (channel IN ('PUSH', 'DISCORD')),
    enabled boolean,
    CONSTRAINT journal_push_channel_with_enabled CHECK ((channel IS NULL) = (enabled IS NULL))
);
COMMENT ON TABLE journal_push IS 'Owned by steward: the alert type a WEB_PUSH_TEST sent, or a SET_ALERT_PREFERENCE switched on or off for a channel.';

CREATE TABLE journal_console
(
    entry   uuid PRIMARY KEY CONSTRAINT journal_console_entry_fkey REFERENCES journal_entry (id) ON DELETE CASCADE,
    command text NOT NULL
);
COMMENT ON TABLE journal_console IS 'Owned by steward: the line a CONSOLE typed into its service.';

CREATE TABLE journal_setting
(
    entry         uuid PRIMARY KEY CONSTRAINT journal_setting_entry_fkey REFERENCES journal_entry (id) ON DELETE CASCADE,
    setting_group text NOT NULL
);
COMMENT ON TABLE journal_setting IS 'Owned by steward: the settings group of its service a SAVE_SETTINGS saved.';

CREATE TABLE journal_bundle
(
    entry  uuid PRIMARY KEY CONSTRAINT journal_bundle_entry_fkey REFERENCES journal_entry (id) ON DELETE CASCADE,
    bundle text NOT NULL
);
COMMENT ON TABLE journal_bundle IS 'Owned by steward: the text bundle a SAVE_MESSAGES saved.';

CREATE TABLE journal_plugin
(
    entry    uuid PRIMARY KEY CONSTRAINT journal_plugin_entry_fkey REFERENCES journal_entry (id) ON DELETE CASCADE,
    artifact text NOT NULL
);
COMMENT ON TABLE journal_plugin IS 'Owned by steward: the plugin an ADD_PLUGIN added to its service; a removal names it in its run.';

CREATE TABLE journal_backup
(
    entry      uuid   PRIMARY KEY CONSTRAINT journal_backup_entry_fkey REFERENCES journal_entry (id) ON DELETE CASCADE,
    archive    text   NOT NULL,
    size_bytes bigint CONSTRAINT journal_backup_size_bytes_check CHECK (size_bytes >= 0)
);
COMMENT ON TABLE journal_backup IS 'Owned by steward: the archive a DOWNLOAD_BACKUP took off the host, and its size when it was known.';

CREATE TABLE journal_language
(
    entry    uuid        NOT NULL CONSTRAINT journal_language_entry_fkey REFERENCES journal_entry (id) ON DELETE CASCADE,
    position smallint    NOT NULL CONSTRAINT journal_language_position_check CHECK (position >= 0),
    language varchar(16) NOT NULL,
    PRIMARY KEY (entry, position),
    CONSTRAINT journal_language_once UNIQUE (entry, language)
);
COMMENT ON TABLE journal_language IS 'Owned by steward: the languages an ANNOUNCE was written in, one row each, in the order given.';

CREATE TABLE journal_admin_below
(
    entry      uuid        NOT NULL CONSTRAINT journal_admin_below_entry_fkey REFERENCES journal_entry (id) ON DELETE CASCADE,
    position   smallint    NOT NULL CONSTRAINT journal_admin_below_position_check CHECK (position >= 0),
    discord_id varchar(32) NOT NULL CONSTRAINT journal_admin_below_discord_id_check CHECK (discord_id ~ '^[0-9]+$'),
    PRIMARY KEY (entry, position)
);
COMMENT ON TABLE journal_admin_below IS 'Owned by steward: the admins a REVOKE_ADMIN took away with the one it named, one row each.';

CREATE TABLE journal_objective
(
    entry     uuid PRIMARY KEY CONSTRAINT journal_objective_entry_fkey REFERENCES journal_entry (id) ON DELETE CASCADE,
    objective text NOT NULL
);
COMMENT ON TABLE journal_objective IS 'Owned by steward: the key of the objective a COMPLETE_OBJECTIVE completed.';

CREATE TABLE journal_milestone
(
    entry     uuid PRIMARY KEY CONSTRAINT journal_milestone_entry_fkey REFERENCES journal_entry (id) ON DELETE CASCADE,
    milestone text NOT NULL
);
COMMENT ON TABLE journal_milestone IS 'Owned by steward: the key of the milestone an UNLOCK_MILESTONE unlocked.';

CREATE TABLE journal_unanchored
(
    entry      uuid        PRIMARY KEY CONSTRAINT journal_unanchored_entry_fkey REFERENCES journal_entry (id) ON DELETE CASCADE,
    valid_from timestamptz NOT NULL
);
COMMENT ON TABLE journal_unanchored IS 'Owned by discord-bot: the date of the grant an UNANCHORED_GRANT anchored a person''s access to.';

-- steward and the bot write entries and read them back; nobody updates or deletes one.
GRANT SELECT, INSERT ON journal_entry, journal_grant, journal_revoke, journal_playtime, journal_unlink, journal_booking,
    journal_phase, journal_launch, journal_smp_start, journal_key, journal_factor, journal_push, journal_console,
    journal_setting, journal_bundle, journal_plugin, journal_backup, journal_language, journal_admin_below,
    journal_objective, journal_milestone, journal_unanchored TO ${role_steward_ui}, ${role_discord_bot};

DROP TABLE audit_log;
