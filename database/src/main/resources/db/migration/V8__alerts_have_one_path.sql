-- Every admin alert is a row, and steward routes each one to the channels every admin chose for its type.

CREATE TABLE admin_alert
(
    id        bigserial   PRIMARY KEY,
    raised    timestamptz NOT NULL DEFAULT now(),
    -- The module that raised it: steward for what it measures and for a failed run, discord-bot for what it could
    -- not carry out.
    raised_by varchar(32) NOT NULL CONSTRAINT admin_alert_raised_by_check CHECK (raised_by ~ '^[a-z][a-z0-9-]*$'),
    type      varchar(16) NOT NULL
        CONSTRAINT admin_alert_type_check
            CHECK (type IN ('SERVICE', 'BACKUP', 'DISK', 'MEMORY', 'DRIFT', 'RUN', 'PAYMENT', 'BOT')),
    -- OK clears what an earlier row of the same type raised.
    level     varchar(8)  NOT NULL CONSTRAINT admin_alert_level_check CHECK (level IN ('OK', 'WARN', 'DOWN')),
    -- A word or a few, such as smp or disk.
    subject   text        NOT NULL,
    -- One line, which a lock screen shows.
    title     text        NOT NULL,
    -- The longer text the admin channel shows, Discord markdown allowed; may be empty.
    detail    text        NOT NULL,
    -- The page in Steward that can act on it.
    path      text        NOT NULL CONSTRAINT admin_alert_path_check CHECK (path LIKE '/%'),
    -- What raised it, such as run:42, so the same fact is raised once however often it is seen.
    source    varchar(64) CONSTRAINT admin_alert_source_key UNIQUE,
    -- When steward sent it to its channels; NULL until then.
    routed    timestamptz
);
COMMENT ON TABLE admin_alert IS 'Owned by steward, which raises what it measures and routes every row to the channels each admin chose for its type. discord-bot raises what it could not carry out and never reads a row.';
CREATE INDEX admin_alert_unrouted ON admin_alert (id) WHERE routed IS NULL;
CREATE INDEX admin_alert_raised ON admin_alert (raised);

GRANT SELECT, INSERT, UPDATE, DELETE ON admin_alert TO ${role_steward_ui};
GRANT INSERT ON admin_alert TO ${role_discord_bot};
GRANT USAGE ON SEQUENCE admin_alert_id_seq TO ${role_steward_ui}, ${role_discord_bot};

-- One switch per account, alert type and channel; every row already there is a push switch.
ALTER TABLE steward_push_preference RENAME TO steward_alert_preference;
ALTER TABLE steward_alert_preference
    ADD COLUMN channel varchar(16) NOT NULL DEFAULT 'PUSH'
        CONSTRAINT steward_alert_preference_channel_check CHECK (channel IN ('PUSH', 'DISCORD'));
ALTER TABLE steward_alert_preference ALTER COLUMN channel DROP DEFAULT;
ALTER TABLE steward_alert_preference DROP CONSTRAINT steward_push_preference_pkey;
ALTER TABLE steward_alert_preference
    ADD CONSTRAINT steward_alert_preference_pkey PRIMARY KEY (discord_id, alert_type, channel);
COMMENT ON TABLE steward_alert_preference IS 'Owned by steward: on which channels one account wants each alert type. A missing row is the type''s default for that channel, never "off".';

-- steward asks the bot to post an alert into the admin channel, naming the admins to mention.
ALTER TABLE bot_inbox DROP CONSTRAINT bot_inbox_kind_check;
ALTER TABLE bot_inbox
    ADD CONSTRAINT bot_inbox_kind_check
        CHECK (kind IN ('GRANT', 'REVOKE', 'SETTLE', 'UNLINK', 'SET_PLAYTIME', 'RELOAD_MESSAGES', 'ANNOUNCE',
                        'POST_ALERT'));
COMMENT ON TABLE bot_inbox IS 'Owned by discord-bot, which claims and carries out every row. steward asks for access changes, announcements, a reload of the messages and an alert in the admin channel, smp for its announcements.';
