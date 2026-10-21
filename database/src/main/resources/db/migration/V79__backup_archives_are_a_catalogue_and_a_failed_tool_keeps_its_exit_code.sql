-- Every archive a backup writes is a row: the step that saved it, its size and time, whether the stop before it was
-- verified, and the step that pruned it with the rule that let it go. The backups page reads these rows instead of
-- matching file times against a run. A step that failed on a tool keeps the tool's exit code, and a cleanup the bytes
-- the build cache freed. The archives before are not catalogued.
CREATE TABLE backup_archive
(
    id            bigserial   CONSTRAINT backup_archive_pkey PRIMARY KEY,
    -- The volume it holds, or 'database' for a dump.
    volume        text        NOT NULL CONSTRAINT backup_archive_volume_check CHECK (volume ~ '^[A-Za-z0-9_.-]+$'),
    file          text        NOT NULL CONSTRAINT backup_archive_file_key UNIQUE,
    bytes         bigint      NOT NULL CONSTRAINT backup_archive_bytes_check CHECK (bytes >= 0),
    took          interval    NOT NULL,
    saved_by      bigint      NOT NULL CONSTRAINT backup_archive_saved_by_fkey REFERENCES run_step (id) ON DELETE CASCADE,
    -- False when a service it holds stopped and nobody could read how it ended.
    stop_verified boolean     NOT NULL,
    pruned_by     bigint      CONSTRAINT backup_archive_pruned_by_fkey REFERENCES run_step (id) ON DELETE CASCADE,
    prune_rule    varchar(12) CONSTRAINT backup_archive_prune_rule_check CHECK (prune_rule IN ('RETENTION', 'OVER_BUDGET')),
    CONSTRAINT backup_archive_pruned_with_rule CHECK ((pruned_by IS NULL) = (prune_rule IS NULL))
);
CREATE INDEX backup_archive_saved_by_idx ON backup_archive (saved_by);
CREATE INDEX backup_archive_pruned_by_idx ON backup_archive (pruned_by);
COMMENT ON TABLE backup_archive IS 'Owned by steward-agent: one archive a backup wrote, and the prune step that removed it.';

ALTER TABLE run_step ADD COLUMN exit_code integer;
ALTER TABLE run_step ADD CONSTRAINT run_step_exit_code_on_tool_failed CHECK (exit_code IS NULL OR reason IS NOT DISTINCT FROM 'TOOL_FAILED');
COMMENT ON COLUMN run_step.exit_code IS 'What the tool exited with, for a step that failed on it; null when it gave no code.';

CREATE TABLE run_cache_freed
(
    step  bigint NOT NULL CONSTRAINT run_cache_freed_pkey PRIMARY KEY
        CONSTRAINT run_cache_freed_step_fkey REFERENCES run_step (id) ON DELETE CASCADE,
    bytes bigint NOT NULL CONSTRAINT run_cache_freed_bytes_check CHECK (bytes >= 0)
);
COMMENT ON TABLE run_cache_freed IS 'Owned by steward-agent: the bytes a cleanup freed in the build cache.';

GRANT SELECT ON backup_archive, run_cache_freed TO ${role_read};
