-- A run of a newer release is carried out by a one-shot steward-agent at that release, which steward-agent starts
-- and hands the claimed row to. The row stays RUNNING throughout and is the lock: the container's name here tells a
-- starting steward-agent that the run is not an orphan while that container still runs.
ALTER TABLE steward_inbox ADD COLUMN runner text
    CONSTRAINT steward_inbox_runner_check CHECK (runner IS NULL OR length(runner) BETWEEN 1 AND 128);
