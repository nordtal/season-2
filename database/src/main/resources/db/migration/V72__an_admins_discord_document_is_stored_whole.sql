-- An admin's version of a packaged document is one value, the whole document as its JSON file holds it. It is read
-- and written whole and never queried by part. Images an admin uploaded stay in layout_image. Every process re-reads
-- on nordtal_messages.
CREATE TABLE document_override
(
    namespace  varchar(32)  NOT NULL
        CONSTRAINT document_override_namespace_check CHECK (namespace ~ '^[a-z][a-z0-9]*(-[a-z0-9]+)*$'),
    name       varchar(64)  NOT NULL
        CONSTRAINT document_override_name_check CHECK (name ~ '^[a-z][a-z0-9]*(-[a-z0-9]+)*$'),
    document   jsonb        NOT NULL
        CONSTRAINT document_override_document_check CHECK (jsonb_typeof(document) = 'object'),
    -- The SHA-256 of the packaged declaration and document it was written over, in hex.
    replaced   char(64)     NOT NULL CONSTRAINT document_override_replaced_check CHECK (replaced ~ '^[0-9a-f]{64}$'),
    actor_kind varchar(16)  NOT NULL
        CONSTRAINT document_override_actor_kind_check CHECK (actor_kind IN ('PERSON', 'STEWARD', 'HOST')),
    actor_id   varchar(32),
    changed    timestamptz  NOT NULL,
    CONSTRAINT document_override_pkey PRIMARY KEY (namespace, name),
    CONSTRAINT document_override_actor_id_iff_person CHECK ((actor_kind = 'PERSON') = (actor_id IS NOT NULL))
);
COMMENT ON TABLE document_override IS 'Owned by steward, where an admin writes a document: one row per document an admin replaced. Every process reads the rows of the namespaces it loads.';

GRANT SELECT ON document_override TO ${role_read};
GRANT INSERT, UPDATE, DELETE ON document_override TO ${role_steward_ui};
