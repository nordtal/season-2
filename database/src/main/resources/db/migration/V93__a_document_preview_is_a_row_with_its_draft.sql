-- A drafted document is a row: the namespace and name of the document, its language, its place and the draft as one
-- whole document, as a saved override is. The recipient reads the declaration from its own packaged documents. A
-- preview is shown once and gone within minutes, so an older one is dropped.
DELETE FROM bot_inbox WHERE kind = 'PREVIEW_DOCUMENT';
DELETE FROM smp_inbox WHERE kind = 'PREVIEW_DOCUMENT';
DELETE FROM hunger_games_inbox WHERE kind = 'PREVIEW_DOCUMENT';

CREATE TABLE bot_preview_document
(
    request   bigint      PRIMARY KEY
        CONSTRAINT bot_preview_document_request_fkey REFERENCES bot_inbox (id) ON DELETE CASCADE,
    person    varchar(32) NOT NULL,
    namespace varchar(32) NOT NULL
        CONSTRAINT bot_preview_document_namespace_check CHECK (namespace ~ '^[a-z][a-z0-9]*(-[a-z0-9]+)*$'),
    name      varchar(64) NOT NULL
        CONSTRAINT bot_preview_document_name_check CHECK (name ~ '^[a-z][a-z0-9]*(-[a-z0-9]+)*$'),
    language  varchar(16) NOT NULL
        CONSTRAINT bot_preview_document_language_check CHECK (language ~ '^[a-z]{2,8}$'),
    shown     varchar(32) NOT NULL,
    document  jsonb       NOT NULL
        CONSTRAINT bot_preview_document_document_check CHECK (jsonb_typeof(document) = 'object')
);
COMMENT ON TABLE bot_preview_document IS 'Owned by discord-bot, which claims its inbox: the drafted document a PREVIEW_DOCUMENT row of bot_inbox sends the admin, named by its namespace and name, its language, where it is shown and the draft whole.';

CREATE TABLE smp_preview_document
(
    request   bigint      PRIMARY KEY
        CONSTRAINT smp_preview_document_request_fkey REFERENCES smp_inbox (id) ON DELETE CASCADE,
    player    uuid        NOT NULL,
    namespace varchar(32) NOT NULL
        CONSTRAINT smp_preview_document_namespace_check CHECK (namespace ~ '^[a-z][a-z0-9]*(-[a-z0-9]+)*$'),
    name      varchar(64) NOT NULL
        CONSTRAINT smp_preview_document_name_check CHECK (name ~ '^[a-z][a-z0-9]*(-[a-z0-9]+)*$'),
    language  varchar(16) NOT NULL
        CONSTRAINT smp_preview_document_language_check CHECK (language ~ '^[a-z]{2,8}$'),
    shown     varchar(32) NOT NULL,
    document  jsonb       NOT NULL
        CONSTRAINT smp_preview_document_document_check CHECK (jsonb_typeof(document) = 'object')
);
COMMENT ON TABLE smp_preview_document IS 'Owned by smp, which claims its inbox: the drafted document a PREVIEW_DOCUMENT row of smp_inbox shows the admin''s player, named by its namespace and name, its language, where it is shown and the draft whole.';

CREATE TABLE hunger_games_preview_document
(
    request   bigint      PRIMARY KEY
        CONSTRAINT hunger_games_preview_document_request_fkey REFERENCES hunger_games_inbox (id) ON DELETE CASCADE,
    player    uuid        NOT NULL,
    namespace varchar(32) NOT NULL
        CONSTRAINT hunger_games_preview_document_namespace_check CHECK (namespace ~ '^[a-z][a-z0-9]*(-[a-z0-9]+)*$'),
    name      varchar(64) NOT NULL
        CONSTRAINT hunger_games_preview_document_name_check CHECK (name ~ '^[a-z][a-z0-9]*(-[a-z0-9]+)*$'),
    language  varchar(16) NOT NULL
        CONSTRAINT hunger_games_preview_document_language_check CHECK (language ~ '^[a-z]{2,8}$'),
    shown     varchar(32) NOT NULL,
    document  jsonb       NOT NULL
        CONSTRAINT hunger_games_preview_document_document_check CHECK (jsonb_typeof(document) = 'object')
);
COMMENT ON TABLE hunger_games_preview_document IS 'Owned by hunger-games, which claims its inbox: the drafted document a PREVIEW_DOCUMENT row of hunger_games_inbox shows the admin''s player, named by its namespace and name, its language, where it is shown and the draft whole.';

ALTER TABLE bot_inbox DROP CONSTRAINT bot_inbox_payload_iff_json_kind;
ALTER TABLE bot_inbox
    ADD CONSTRAINT bot_inbox_payload_iff_json_kind CHECK ((kind = 'POST_ALERT') = (payload IS NOT NULL));
ALTER TABLE smp_inbox DROP CONSTRAINT smp_inbox_payload_iff_json;
ALTER TABLE smp_inbox
    ADD CONSTRAINT smp_inbox_payload_iff_json CHECK ((kind = 'CHECK_SETTINGS') = (payload IS NOT NULL));
ALTER TABLE hunger_games_inbox DROP CONSTRAINT hunger_games_inbox_payload_iff_json;
ALTER TABLE hunger_games_inbox
    ADD CONSTRAINT hunger_games_inbox_payload_iff_json CHECK ((kind = 'CHECK_SETTINGS') = (payload IS NOT NULL));

-- Steward asks for a preview, and its recipient reads it.
GRANT SELECT ON bot_preview_document, smp_preview_document, hunger_games_preview_document TO ${role_read};
GRANT INSERT ON bot_preview_document, smp_preview_document, hunger_games_preview_document TO ${role_steward_ui};
