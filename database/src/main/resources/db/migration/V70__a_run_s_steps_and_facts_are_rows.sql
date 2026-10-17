-- A run is rows: its stage on its own row, the services it acts on, one row per step with its outcome and reason
-- code, and each typed fact of a step in the table of its kind. steward-agent writes them as the run goes; the proxy,
-- which moves the players, writes how many it moved and how many came back. The report, the payload's services and
-- the moving array are still written beside them until every reader has moved, and the runs before are not
-- converted.
ALTER TABLE steward_inbox ADD COLUMN stage varchar(16)
    CONSTRAINT steward_inbox_stage_check
        CHECK (stage IN ('RESOLVING', 'PLANNED', 'COUNTDOWN', 'STOPPING', 'BACKING_UP', 'INSTALLING', 'STARTING',
                         'VERIFYING', 'DONE', 'NOTHING_TO_DO', 'FAILED', 'CANCELLED'));
COMMENT ON COLUMN steward_inbox.stage IS 'Where the run has got to, written by steward-agent with every step; null until it is claimed.';

CREATE TABLE run_service
(
    run     bigint  NOT NULL CONSTRAINT run_service_run_fkey REFERENCES steward_inbox (id) ON DELETE CASCADE,
    service text    NOT NULL CONSTRAINT run_service_service_check CHECK (service ~ '^[a-z0-9-]+$'),
    -- Whether the run stops it, so the proxy moves its players off it once the countdown runs out.
    moving  boolean NOT NULL,
    CONSTRAINT run_service_pkey PRIMARY KEY (run, service)
);
COMMENT ON TABLE run_service IS 'A service a run acts on: the ones its request names, written with the request, and the ones it stops, which steward-agent marks moving when the countdown starts.';

CREATE TABLE run_step
(
    id       bigserial   CONSTRAINT run_step_pkey PRIMARY KEY,
    run      bigint      NOT NULL CONSTRAINT run_step_run_fkey REFERENCES steward_inbox (id) ON DELETE CASCADE,
    -- The order the run took its steps in, from one.
    seq      integer     NOT NULL CONSTRAINT run_step_seq_check CHECK (seq > 0),
    step     varchar(16) NOT NULL
        CONSTRAINT run_step_step_check
            CHECK (step IN ('RUNTIME', 'SCOPE', 'RESOLVE', 'HAND_OVER', 'ROOM_CHECK', 'PRUNE', 'DUMP_DATABASE',
                            'STANDBY_UP', 'COUNTDOWN', 'PLAYERS_MOVED', 'STOP', 'SAVE_VOLUME', 'HOLD', 'UNHOLD',
                            'MIGRATE', 'INSTALL', 'RESTORE', 'START', 'VERIFY', 'PLAYERS_BACK', 'STANDBY_DOWN',
                            'CLEANUP', 'OFFSITE_COPY')),
    -- The service, volume or archive the step is about; null for a step about the whole run.
    service  text,
    started  timestamptz NOT NULL DEFAULT now(),
    finished timestamptz,
    outcome  varchar(8)
        CONSTRAINT run_step_outcome_check CHECK (outcome IN ('DONE', 'SKIPPED', 'WARNING', 'FAILED')),
    reason   varchar(24)
        CONSTRAINT run_step_reason_check
            CHECK (reason IN ('UNREACHABLE', 'UNEXPECTED', 'CANCELLED', 'INTERRUPTED', 'NO_CONTAINER', 'REFUSED',
                              'NOT_HEALTHY', 'UNVERIFIED_STOP', 'PLAYERS_STAYED', 'PLAYERS_UNKNOWN', 'NO_ROOM',
                              'TOOL_FAILED', 'NO_REPOSITORY', 'REFUSED_LOCAL_BUILD', 'RELEASED_MEANWHILE',
                              'OLDER_RELEASE', 'HELD', 'NOT_IN_SCOPE', 'NOTHING_HELD', 'UNNAMED', 'NEVER_DOWN',
                              'SELF', 'UNKNOWN', 'NOT_A_VOLUME', 'NO_VOLUMES', 'NOTHING_TO_REMOVE', 'NOT_SAVED',
                              'LEFT_DOWN', 'SOURCE_FAILED', 'NOT_IN_RELEASE', 'UNCLAIMED', 'UNVERIFIABLE',
                              'LOCAL_BUILD', 'FOREIGN_NEWER')),
    CONSTRAINT run_step_run_seq_key UNIQUE (run, seq),
    CONSTRAINT run_step_outcome_iff_finished CHECK ((finished IS NULL) = (outcome IS NULL)),
    CONSTRAINT run_step_done_has_no_reason CHECK (outcome <> 'DONE' OR reason IS NULL)
);
COMMENT ON TABLE run_step IS 'Owned by steward-agent: one step of a run, started when it began, with its outcome and reason code once it ended.';

CREATE TABLE run_change
(
    step         bigint     NOT NULL CONSTRAINT run_change_step_fkey REFERENCES run_step (id) ON DELETE CASCADE,
    artefact     text       NOT NULL,
    -- Null for a first install, or for an artefact no version names.
    version_from text,
    version_to   text,
    -- For a container made again: whether its image was pulled first or the one on the host was kept.
    image        varchar(8) CONSTRAINT run_change_image_check CHECK (image IN ('PULLED', 'KEPT')),
    CONSTRAINT run_change_pkey PRIMARY KEY (step, artefact)
);
COMMENT ON TABLE run_change IS 'Owned by steward-agent: an artefact a step resolves, installs or sees, with its versions.';

CREATE TABLE run_players
(
    step         bigint  NOT NULL CONSTRAINT run_players_step_fkey REFERENCES run_step (id) ON DELETE CASCADE,
    from_service text    NOT NULL,
    to_service   text    NOT NULL,
    moved        integer NOT NULL CONSTRAINT run_players_moved_check CHECK (moved >= 0),
    returned     integer NOT NULL DEFAULT 0,
    CONSTRAINT run_players_pkey PRIMARY KEY (step, from_service, to_service),
    CONSTRAINT run_players_returned_check CHECK (returned BETWEEN 0 AND moved)
);
COMMENT ON TABLE run_players IS 'Owned by the proxy, which makes the move: how many players a run moved from one service to another, and how many came back.';

CREATE TABLE run_image_removed
(
    step  bigint NOT NULL CONSTRAINT run_image_removed_step_fkey REFERENCES run_step (id) ON DELETE CASCADE,
    image text   NOT NULL,
    bytes bigint NOT NULL CONSTRAINT run_image_removed_bytes_check CHECK (bytes >= 0),
    CONSTRAINT run_image_removed_pkey PRIMARY KEY (step, image)
);
COMMENT ON TABLE run_image_removed IS 'Owned by steward-agent: an image the cleanup removed, by its id, and its size.';

CREATE TABLE run_room
(
    step         bigint NOT NULL CONSTRAINT run_room_pkey PRIMARY KEY
        CONSTRAINT run_room_step_fkey REFERENCES run_step (id) ON DELETE CASCADE,
    -- What the archives are expected to take, with the share of the disk kept free.
    bytes_needed bigint NOT NULL CONSTRAINT run_room_bytes_needed_check CHECK (bytes_needed >= 0),
    bytes_free   bigint NOT NULL CONSTRAINT run_room_bytes_free_check CHECK (bytes_free >= 0)
);
COMMENT ON TABLE run_room IS 'Owned by steward-agent: the room check before a backup or restore saves anything.';

CREATE TABLE run_offsite
(
    step        bigint NOT NULL CONSTRAINT run_offsite_pkey PRIMARY KEY
        CONSTRAINT run_offsite_step_fkey REFERENCES run_step (id) ON DELETE CASCADE,
    snapshot    text   NOT NULL,
    bytes_added bigint NOT NULL CONSTRAINT run_offsite_bytes_added_check CHECK (bytes_added >= 0)
);
COMMENT ON TABLE run_offsite IS 'Owned by steward-agent: the snapshot an offsite copy made and how many bytes it added to the repository.';

GRANT SELECT ON run_service, run_step, run_change, run_players, run_image_removed, run_room, run_offsite TO ${role_read};
-- Steward writes a request, and its services with it.
GRANT INSERT ON run_service TO ${role_steward_ui};
GRANT INSERT, UPDATE ON run_players TO ${role_proxy};
