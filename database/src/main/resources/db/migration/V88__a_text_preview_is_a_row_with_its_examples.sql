-- A tried text is a row: its key, language, text and place, the service whose colours paint it and a row per example
-- value an admin gave. The recipient fills the rest from the key's schema and reads the colours itself. A drafted
-- document stays JSON as its own kind. A preview is shown once and gone within minutes, so an older one is dropped.
DELETE FROM bot_inbox WHERE kind = 'PREVIEW_MESSAGE';
DELETE FROM smp_inbox WHERE kind = 'PREVIEW_MESSAGE';
DELETE FROM hunger_games_inbox WHERE kind = 'PREVIEW_MESSAGE';

CREATE TABLE bot_preview_message
(
    request  bigint      PRIMARY KEY
        CONSTRAINT bot_preview_message_request_fkey REFERENCES bot_inbox (id) ON DELETE CASCADE,
    person   varchar(32) NOT NULL,
    key      text        NOT NULL,
    language varchar(16) NOT NULL CONSTRAINT bot_preview_message_language_check CHECK (language ~ '^[a-z]{2,8}$'),
    text     text        NOT NULL,
    shown    varchar(32) NOT NULL,
    service  varchar(64) NOT NULL
);
COMMENT ON TABLE bot_preview_message IS 'Owned by discord-bot, which claims its inbox: the text a PREVIEW_MESSAGE row of bot_inbox sends the admin, where it is shown and whose colours it takes.';

CREATE TABLE bot_preview_message_example
(
    request bigint NOT NULL
        CONSTRAINT bot_preview_message_example_request_fkey REFERENCES bot_preview_message (request) ON DELETE CASCADE,
    name    text   NOT NULL,
    value   text   NOT NULL,
    CONSTRAINT bot_preview_message_example_pkey PRIMARY KEY (request, name)
);
COMMENT ON TABLE bot_preview_message_example IS 'Owned by discord-bot, which claims its inbox: a value an admin gave a placeholder of a PREVIEW_MESSAGE row of bot_inbox.';

CREATE TABLE smp_preview_message
(
    request  bigint      PRIMARY KEY
        CONSTRAINT smp_preview_message_request_fkey REFERENCES smp_inbox (id) ON DELETE CASCADE,
    player   uuid        NOT NULL,
    key      text        NOT NULL,
    language varchar(16) NOT NULL CONSTRAINT smp_preview_message_language_check CHECK (language ~ '^[a-z]{2,8}$'),
    text     text        NOT NULL,
    shown    varchar(32) NOT NULL,
    service  varchar(64) NOT NULL
);
COMMENT ON TABLE smp_preview_message IS 'Owned by smp, which claims its inbox: the text a PREVIEW_MESSAGE row of smp_inbox shows the admin''s player, where it is shown and whose colours it takes.';

CREATE TABLE smp_preview_message_example
(
    request bigint NOT NULL
        CONSTRAINT smp_preview_message_example_request_fkey REFERENCES smp_preview_message (request) ON DELETE CASCADE,
    name    text   NOT NULL,
    value   text   NOT NULL,
    CONSTRAINT smp_preview_message_example_pkey PRIMARY KEY (request, name)
);
COMMENT ON TABLE smp_preview_message_example IS 'Owned by smp, which claims its inbox: a value an admin gave a placeholder of a PREVIEW_MESSAGE row of smp_inbox.';

CREATE TABLE hunger_games_preview_message
(
    request  bigint      PRIMARY KEY
        CONSTRAINT hunger_games_preview_message_request_fkey REFERENCES hunger_games_inbox (id) ON DELETE CASCADE,
    player   uuid        NOT NULL,
    key      text        NOT NULL,
    language varchar(16) NOT NULL
        CONSTRAINT hunger_games_preview_message_language_check CHECK (language ~ '^[a-z]{2,8}$'),
    text     text        NOT NULL,
    shown    varchar(32) NOT NULL,
    service  varchar(64) NOT NULL
);
COMMENT ON TABLE hunger_games_preview_message IS 'Owned by hunger-games, which claims its inbox: the text a PREVIEW_MESSAGE row of hunger_games_inbox shows the admin''s player, where it is shown and whose colours it takes.';

CREATE TABLE hunger_games_preview_message_example
(
    request bigint NOT NULL
        CONSTRAINT hunger_games_preview_message_example_request_fkey
            REFERENCES hunger_games_preview_message (request) ON DELETE CASCADE,
    name    text   NOT NULL,
    value   text   NOT NULL,
    CONSTRAINT hunger_games_preview_message_example_pkey PRIMARY KEY (request, name)
);
COMMENT ON TABLE hunger_games_preview_message_example IS 'Owned by hunger-games, which claims its inbox: a value an admin gave a placeholder of a PREVIEW_MESSAGE row of hunger_games_inbox.';

ALTER TABLE bot_inbox DROP CONSTRAINT bot_inbox_kind_check;
ALTER TABLE bot_inbox
    ADD CONSTRAINT bot_inbox_kind_check
        CHECK (kind IN ('GRANT', 'REVOKE', 'UNLINK', 'SET_PLAYTIME', 'ANNOUNCE', 'POST_ALERT', 'PAYMENT_BOOKED',
                        'PREVIEW_MESSAGE', 'PREVIEW_DOCUMENT'));
ALTER TABLE bot_inbox DROP CONSTRAINT bot_inbox_payload_iff_json_kind;
ALTER TABLE bot_inbox
    ADD CONSTRAINT bot_inbox_payload_iff_json_kind
        CHECK ((kind IN ('POST_ALERT', 'PREVIEW_DOCUMENT')) = (payload IS NOT NULL));
COMMENT ON TABLE bot_inbox IS 'Owned by discord-bot, which claims and carries out every row. steward asks for access changes, announcements, an alert in the admin channel, the word to a payer whose payment it booked and an admin''s preview of a text or a document, smp for its announcements.';

ALTER TABLE smp_inbox DROP CONSTRAINT smp_inbox_kind_check;
ALTER TABLE smp_inbox
    ADD CONSTRAINT smp_inbox_kind_check
        CHECK (kind IN ('COMPLETE_OBJECTIVE', 'UNLOCK_MILESTONE', 'PREVIEW_MESSAGE', 'PREVIEW_DOCUMENT',
                        'CHECK_SETTINGS', 'SET_AURA', 'SET_SPINS', 'STORE_MODE_STATE', 'REMOVE_STACK'));
ALTER TABLE smp_inbox DROP CONSTRAINT smp_inbox_payload_iff_json;
ALTER TABLE smp_inbox
    ADD CONSTRAINT smp_inbox_payload_iff_json
        CHECK ((kind IN ('PREVIEW_DOCUMENT', 'CHECK_SETTINGS')) = (payload IS NOT NULL));
COMMENT ON TABLE smp_inbox IS 'Owned by smp, which claims and carries out every row. steward asks for a track action, a person''s aura or spins, a player''s held state or the removal of a stack from it, an admin''s preview of a text or a document and a try of a settings change.';

ALTER TABLE hunger_games_inbox DROP CONSTRAINT hunger_games_inbox_kind_check;
ALTER TABLE hunger_games_inbox
    ADD CONSTRAINT hunger_games_inbox_kind_check
        CHECK (kind IN ('START_GAME', 'PREVIEW_MESSAGE', 'PREVIEW_DOCUMENT', 'CHECK_SETTINGS', 'STORE_MODE_STATE',
                        'REMOVE_STACK'));
ALTER TABLE hunger_games_inbox DROP CONSTRAINT hunger_games_inbox_payload_iff_json;
ALTER TABLE hunger_games_inbox
    ADD CONSTRAINT hunger_games_inbox_payload_iff_json
        CHECK ((kind IN ('PREVIEW_DOCUMENT', 'CHECK_SETTINGS')) = (payload IS NOT NULL));
COMMENT ON TABLE hunger_games_inbox IS 'Owned by hunger-games, which claims and carries out every row. steward asks for the start, a player''s held state or the removal of a stack from it, an admin''s preview of a text or a document and a try of a settings change.';

-- Steward asks for a preview, and its recipient reads it.
GRANT SELECT ON bot_preview_message, bot_preview_message_example, smp_preview_message, smp_preview_message_example,
    hunger_games_preview_message, hunger_games_preview_message_example TO ${role_read};
GRANT INSERT ON bot_preview_message, bot_preview_message_example, smp_preview_message, smp_preview_message_example,
    hunger_games_preview_message, hunger_games_preview_message_example TO ${role_steward_ui};
