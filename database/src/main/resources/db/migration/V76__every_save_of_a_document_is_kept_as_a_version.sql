-- Every save of an admin's document is kept whole, so Steward can restore it. A version without a document is a return
-- to the packaged one. The documents already saved start the history.
CREATE TABLE document_version
(
    id         bigserial    CONSTRAINT document_version_pkey PRIMARY KEY,
    namespace  varchar(32)  NOT NULL
        CONSTRAINT document_version_namespace_check CHECK (namespace ~ '^[a-z][a-z0-9]*(-[a-z0-9]+)*$'),
    name       varchar(64)  NOT NULL
        CONSTRAINT document_version_name_check CHECK (name ~ '^[a-z][a-z0-9]*(-[a-z0-9]+)*$'),
    document   jsonb
        CONSTRAINT document_version_document_check CHECK (jsonb_typeof(document) = 'object'),
    -- The SHA-256 of the packaged declaration and document it was written over, in hex.
    replaced   char(64)
        CONSTRAINT document_version_replaced_check CHECK (replaced ~ '^[0-9a-f]{64}$'),
    actor_kind varchar(16)  NOT NULL
        CONSTRAINT document_version_actor_kind_check CHECK (actor_kind IN ('PERSON', 'STEWARD', 'HOST')),
    actor_id   varchar(32),
    saved      timestamptz  NOT NULL,
    CONSTRAINT document_version_actor_id_iff_person CHECK ((actor_kind = 'PERSON') = (actor_id IS NOT NULL)),
    CONSTRAINT document_version_replaced_iff_document CHECK ((document IS NULL) = (replaced IS NULL))
);
CREATE INDEX document_version_document_idx ON document_version (namespace, name, id);
COMMENT ON TABLE document_version IS 'Owned by steward, where an admin saves a document: one row per save, never changed. Only steward reads it.';

INSERT INTO document_version (namespace, name, document, replaced, actor_kind, actor_id, saved)
SELECT namespace, name, document, replaced, actor_kind, actor_id, changed
FROM document_override
ORDER BY changed;

GRANT SELECT, INSERT ON document_version TO ${role_steward_ui};
GRANT USAGE ON SEQUENCE document_version_id_seq TO ${role_steward_ui};
