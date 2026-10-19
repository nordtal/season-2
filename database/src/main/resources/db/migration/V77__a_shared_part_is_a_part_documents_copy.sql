-- A shared part is a part an admin saved for reuse, kept whole as its JSON part. A document holds a copy of it,
-- marked with its name; Steward rewrites every copy in the transaction that changes the shared part.
CREATE TABLE shared_part
(
    name       varchar(64)  CONSTRAINT shared_part_pkey PRIMARY KEY
        CONSTRAINT shared_part_name_check CHECK (name ~ '^[a-z][a-z0-9]*(-[a-z0-9]+)*$'),
    part       jsonb        NOT NULL CONSTRAINT shared_part_part_check CHECK (jsonb_typeof(part) = 'object'),
    actor_kind varchar(16)  NOT NULL
        CONSTRAINT shared_part_actor_kind_check CHECK (actor_kind IN ('PERSON', 'STEWARD', 'HOST')),
    actor_id   varchar(32),
    changed    timestamptz  NOT NULL,
    CONSTRAINT shared_part_actor_id_iff_person CHECK ((actor_kind = 'PERSON') = (actor_id IS NOT NULL))
);
COMMENT ON TABLE shared_part IS 'Owned by steward, where an admin saves a shared part: one row per shared part. Only steward reads it.';

GRANT SELECT, INSERT, UPDATE, DELETE ON shared_part TO ${role_steward_ui};
