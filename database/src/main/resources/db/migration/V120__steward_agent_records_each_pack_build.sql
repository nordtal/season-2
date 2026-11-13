-- Each build of the resource pack that steward-agent ran: the prize list and the label it drew, and the zip it made.
-- The newest good build is the active pack, the good ones before it are superseded.
--
-- This only adds.

CREATE TABLE pack_build
(
    id          bigint       GENERATED ALWAYS AS IDENTITY CONSTRAINT pack_build_pkey PRIMARY KEY,
    state       varchar(16)  NOT NULL
        CONSTRAINT pack_build_state_check CHECK (state IN ('BUILDING', 'ACTIVE', 'SUPERSEDED', 'FAILED')),
    started     timestamptz  NOT NULL,
    -- Empty while the build runs.
    finished    timestamptz,
    -- The wheel's prize list as the build read it, a whole that only the next build replaces.
    prizes      jsonb        NOT NULL,
    -- The label on the wheel's spin button, by language.
    label       jsonb        NOT NULL,
    -- Empty unless the build made a zip.
    sha1        char(40)     CONSTRAINT pack_build_sha1_check CHECK (sha1 ~ '^[0-9a-f]{40}$'),
    size_bytes  bigint       CONSTRAINT pack_build_size_bytes_check CHECK (size_bytes >= 0),
    duration_ms bigint       CONSTRAINT pack_build_duration_ms_check CHECK (duration_ms >= 0),
    -- Empty unless the build failed.
    error       text,
    CONSTRAINT pack_build_finished_check CHECK ((state = 'BUILDING') = (finished IS NULL)),
    CONSTRAINT pack_build_made_check CHECK ((state IN ('ACTIVE', 'SUPERSEDED')) = (sha1 IS NOT NULL))
);
CREATE INDEX pack_build_started_idx ON pack_build (started DESC);
-- One pack is active at a time.
CREATE UNIQUE INDEX pack_build_active_key ON pack_build (state) WHERE state = 'ACTIVE';
COMMENT ON TABLE pack_build IS 'Owned by steward-agent: each build of the resource pack, its inputs, its zip and how it ended.';

GRANT SELECT ON pack_build TO ${role_steward_ui};
GRANT SELECT ON pack_build TO ${role_read};
