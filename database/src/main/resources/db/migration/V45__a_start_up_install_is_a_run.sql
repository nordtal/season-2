-- What steward-agent installs on its start, into volumes that hold nothing of it, is a run of its own kind.
-- steward-agent writes it once it is done, already settled, so it never waits in the inbox and is never claimed.
ALTER TABLE steward_inbox DROP CONSTRAINT steward_inbox_kind_check;
ALTER TABLE steward_inbox ADD CONSTRAINT steward_inbox_kind_check
    CHECK (kind IN ('UPDATE', 'RESTART', 'BACKUP', 'DOWN', 'START', 'RESTORE', 'RECREATE', 'DEPLOY', 'REMOVE_PLUGIN',
                    'INSTALL'));
